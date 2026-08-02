package com.sladkaya.sensor.sibionics

import com.sladkaya.sensor.sibionics.algorithm.AlgorithmCheckpoint
import com.sladkaya.sensor.sibionics.algorithm.AlgorithmCommitResult
import com.sladkaya.sensor.sibionics.algorithm.AlgorithmError
import com.sladkaya.sensor.sibionics.algorithm.AlgorithmErrorCode
import com.sladkaya.sensor.sibionics.algorithm.AlgorithmInitializationMode
import com.sladkaya.sensor.sibionics.algorithm.AlgorithmInput
import com.sladkaya.sensor.sibionics.algorithm.AlgorithmOutput
import com.sladkaya.sensor.sibionics.algorithm.AlgorithmStepResult
import com.sladkaya.sensor.sibionics.algorithm.DecodedSensitivity
import com.sladkaya.sensor.sibionics.algorithm.SibionicsAlgorithmSession
import java.security.MessageDigest
import java.util.concurrent.CancellationException

internal interface Gs1GoldenReplayAlgorithmSession : AutoCloseable {
    fun process(input: AlgorithmInput): AlgorithmStepResult
    fun confirmPersisted(checkpoint: AlgorithmCheckpoint): AlgorithmCommitResult
}

internal class Gs1GoldenReplaySessionAdapter(
    private val delegate: SibionicsAlgorithmSession,
) : Gs1GoldenReplayAlgorithmSession {
    override fun process(input: AlgorithmInput): AlgorithmStepResult = delegate.process(input)
    override fun confirmPersisted(checkpoint: AlgorithmCheckpoint): AlgorithmCommitResult =
        delegate.confirmPersisted(checkpoint)
    override fun close() = delegate.close()
}

internal sealed interface Gs1GoldenReplaySessionOpenResult {
    data class Success(
        val session: Gs1GoldenReplayAlgorithmSession,
        val sensitivity: DecodedSensitivity,
        val initializationMode: AlgorithmInitializationMode,
    ) : Gs1GoldenReplaySessionOpenResult

    data class Failure(val error: AlgorithmError) : Gs1GoldenReplaySessionOpenResult
}

/** Opens the same session and sensitivity configuration used by the diagnostic core. */
internal fun interface Gs1GoldenReplaySessionFactory {
    fun open(checkpoint: AlgorithmCheckpoint?): Gs1GoldenReplaySessionOpenResult
}

/** Key-bearing capability. The runner supplies the canonical message, never the key. */
internal fun interface Gs1GoldenSensitivityHmacCapability {
    fun hmacSha256(canonicalMessage: ByteArray): ByteArray
}

internal enum class Gs1GoldenReplayFailure {
    TRACE_INVALID,
    DECODER_FAILED,
    DECODE_OUTCOME_MISMATCH,
    DECODED_SAMPLE_MISMATCH,
    SESSION_OPEN_FAILED,
    ALGORITHM_STEP_FAILED,
    NATIVE_DIAGNOSTIC_MISMATCH,
    CHECKPOINT_INTEGRITY_MISMATCH,
    CHECKPOINT_CONFIRM_FAILED,
    SENSITIVITY_METADATA_MISMATCH,
    SENSITIVITY_BINDING_UNAVAILABLE,
    SENSITIVITY_BINDING_MISMATCH,
}

internal data class Gs1GoldenReplayReport(
    val traceId: String,
    val matchedNotifications: Int,
    val matchedSamples: Int,
    val contextsOpened: Int,
    val syntheticOnly: Boolean,
    /** A replay match alone never opens the physical product release gate. */
    val releaseEvidence: Boolean = false,
)

internal sealed interface Gs1GoldenReplayResult {
    data class Matched(val report: Gs1GoldenReplayReport) : Gs1GoldenReplayResult
    data class Failed(
        val failure: Gs1GoldenReplayFailure,
        val detail: String,
        val attemptOrdinal: Int? = null,
        val ingressOrdinal: Long? = null,
        val sampleIndex: Int? = null,
    ) : Gs1GoldenReplayResult
}

/**
 * Deterministically compares transport decode, native diagnostic values and
 * state hashes. It never constructs a product measurement.
 */
internal class Gs1GoldenReplayRunner(
    private val decoder: Gs1PacketVerifier,
    private val sessionFactory: Gs1GoldenReplaySessionFactory,
    private val sensitivityHmac: Gs1GoldenSensitivityHmacCapability?,
) {
    fun run(plan: Gs1GoldenReplayPlan): Gs1GoldenReplayResult {
        val checked = Gs1GoldenReplayPlanner().plan(plan.trace)
        if (checked is Gs1GoldenReplayPlanResult.Invalid) {
            return failed(Gs1GoldenReplayFailure.TRACE_INVALID, checked.detail)
        }
        if (sensitivityHmac == null) {
            return failed(
                Gs1GoldenReplayFailure.SENSITIVITY_BINDING_UNAVAILABLE,
                "private sensitivity HMAC capability is required",
            )
        }

        var session: Gs1GoldenReplayAlgorithmSession? = null
        var activeSensitivity: DecodedSensitivity? = null
        var activeAttempt: Int? = null
        var checkpoint: AlgorithmCheckpoint? = null
        var matchedNotifications = 0
        var matchedSamples = 0
        var contextsOpened = 0
        try {
            plan.notifications.forEach { notification ->
                if (activeAttempt != notification.attemptOrdinal) {
                    session?.close()
                    session = null
                    activeSensitivity = null
                    activeAttempt = notification.attemptOrdinal
                }
                val decoded = try {
                    decoder.decode(
                        notification.encryptedPacketCopy(),
                        notification.receivedAtEpochMs,
                    )
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (failure: LinkageError) {
                    return failedAt(
                        Gs1GoldenReplayFailure.DECODER_FAILED,
                        failure.message ?: "decoder linkage failed",
                        notification,
                    )
                } catch (failure: Exception) {
                    return failedAt(
                        Gs1GoldenReplayFailure.DECODER_FAILED,
                        failure.message ?: "decoder failed",
                        notification,
                    )
                }

                when (notification.expectedDecode) {
                    Gs1GoldenDecodeExpectation.NON_DATA -> {
                        if (decoded !is Gs1VerifiedPacketResult.Failure ||
                            decoded.error != Gs1VerifiedPacketError.NOT_GS1_DATA
                        ) {
                            return failedAt(
                                Gs1GoldenReplayFailure.DECODE_OUTCOME_MISMATCH,
                                "expected an unambiguous non-data notification",
                                notification,
                            )
                        }
                    }

                    Gs1GoldenDecodeExpectation.REJECTED -> {
                        if (decoded !is Gs1VerifiedPacketResult.Failure ||
                            decoded.error != notification.expectedDecodeError
                        ) {
                            return failedAt(
                                Gs1GoldenReplayFailure.DECODE_OUTCOME_MISMATCH,
                                "packet rejection differs from the trace",
                                notification,
                            )
                        }
                    }

                    Gs1GoldenDecodeExpectation.GS1_DATA -> {
                        if (decoded !is Gs1VerifiedPacketResult.Success) {
                            return failedAt(
                                Gs1GoldenReplayFailure.DECODE_OUTCOME_MISMATCH,
                                "expected GS1 data but decoder rejected the packet",
                                notification,
                            )
                        }
                        if (decoded.decrypted != notification.expectedDecrypted) {
                            return failedAt(
                                Gs1GoldenReplayFailure.DECODE_OUTCOME_MISMATCH,
                                "decryption marker differs from the trace",
                                notification,
                            )
                        }
                        val expectedDecoded = notification.samples.map { it.decoded }
                        if (decoded.samples != expectedDecoded) {
                            return failedAt(
                                Gs1GoldenReplayFailure.DECODED_SAMPLE_MISMATCH,
                                "decoded raw samples differ from the trace",
                                notification,
                            )
                        }
                        if (session == null) {
                            val opened = when (val result = safeOpen(checkpoint)) {
                                is Gs1GoldenReplaySessionOpenResult.Success -> result
                                is Gs1GoldenReplaySessionOpenResult.Failure -> {
                                    return failedAt(
                                        Gs1GoldenReplayFailure.SESSION_OPEN_FAILED,
                                        "${result.error.code}: session open failed",
                                        notification,
                                    )
                                }
                            }
                            sensitivityMetadataMismatch(plan.trace, opened)?.let { detail ->
                                opened.session.close()
                                return failedAt(
                                    Gs1GoldenReplayFailure.SENSITIVITY_METADATA_MISMATCH,
                                    detail,
                                    notification,
                                )
                            }
                            when (val binding = verifySensitivityBinding(plan.trace, opened.sensitivity)) {
                                SensitivityBindingResult.Matched -> Unit
                                is SensitivityBindingResult.Unavailable -> {
                                    opened.session.close()
                                    return failedAt(
                                        Gs1GoldenReplayFailure.SENSITIVITY_BINDING_UNAVAILABLE,
                                        binding.detail,
                                        notification,
                                    )
                                }
                                is SensitivityBindingResult.Mismatch -> {
                                    opened.session.close()
                                    return failedAt(
                                        Gs1GoldenReplayFailure.SENSITIVITY_BINDING_MISMATCH,
                                        binding.detail,
                                        notification,
                                    )
                                }
                            }
                            session = opened.session
                            activeSensitivity = opened.sensitivity
                            contextsOpened += 1
                        }
                        notification.samples.forEach { expected ->
                            val step = try {
                                checkNotNull(session).process(expected.decoded.toAlgorithmInput())
                            } catch (cancelled: CancellationException) {
                                throw cancelled
                            } catch (failure: LinkageError) {
                                return failedAt(
                                    Gs1GoldenReplayFailure.ALGORITHM_STEP_FAILED,
                                    failure.message ?: "native linkage failed",
                                    notification,
                                    expected.decoded.index,
                                )
                            } catch (failure: Exception) {
                                return failedAt(
                                    Gs1GoldenReplayFailure.ALGORITHM_STEP_FAILED,
                                    failure.message ?: "algorithm step failed",
                                    notification,
                                    expected.decoded.index,
                                )
                            }
                            val observed = when (step) {
                                is AlgorithmStepResult.Success -> ObservedStep(
                                    output = step.output,
                                    checkpoint = step.checkpoint,
                                    errorCode = null,
                                )

                                is AlgorithmStepResult.Failure -> {
                                    val diagnostic = step.diagnosticOutput
                                    val diagnosticCheckpoint = step.checkpoint
                                    if (diagnostic == null || diagnosticCheckpoint == null) {
                                        return failedAt(
                                            Gs1GoldenReplayFailure.ALGORITHM_STEP_FAILED,
                                            "${step.error.code}: ${step.error.message}",
                                            notification,
                                            expected.decoded.index,
                                        )
                                    }
                                    ObservedStep(diagnostic, diagnosticCheckpoint, step.error.code)
                                }
                            }
                            diagnosticMismatch(plan.trace, expected, observed)?.let { detail ->
                                return failedAt(
                                    Gs1GoldenReplayFailure.NATIVE_DIAGNOSTIC_MISMATCH,
                                    detail,
                                    notification,
                                    expected.decoded.index,
                                )
                            }
                            checkpointMismatch(
                                plan.trace,
                                checkNotNull(activeSensitivity),
                                expected,
                                observed.checkpoint,
                            )?.let { detail ->
                                return failedAt(
                                    Gs1GoldenReplayFailure.CHECKPOINT_INTEGRITY_MISMATCH,
                                    detail,
                                    notification,
                                    expected.decoded.index,
                                )
                            }
                            val confirmed = try {
                                checkNotNull(session).confirmPersisted(observed.checkpoint)
                            } catch (cancelled: CancellationException) {
                                throw cancelled
                            } catch (failure: Exception) {
                                return failedAt(
                                    Gs1GoldenReplayFailure.CHECKPOINT_CONFIRM_FAILED,
                                    failure.message ?: "checkpoint confirmation failed",
                                    notification,
                                    expected.decoded.index,
                                )
                            }
                            if (confirmed is AlgorithmCommitResult.Failure) {
                                return failedAt(
                                    Gs1GoldenReplayFailure.CHECKPOINT_CONFIRM_FAILED,
                                    "${confirmed.error.code}: ${confirmed.error.message}",
                                    notification,
                                    expected.decoded.index,
                                )
                            }
                            checkpoint = observed.checkpoint
                            matchedSamples += 1
                        }
                    }
                }
                matchedNotifications += 1
            }
            return Gs1GoldenReplayResult.Matched(
                Gs1GoldenReplayReport(
                    traceId = plan.trace.traceId,
                    matchedNotifications = matchedNotifications,
                    matchedSamples = matchedSamples,
                    contextsOpened = contextsOpened,
                    syntheticOnly = plan.trace.provenance == Gs1GoldenTraceProvenance.SYNTHETIC_TEST_ONLY,
                ),
            )
        } finally {
            session?.close()
        }
    }

    private fun safeOpen(checkpoint: AlgorithmCheckpoint?): Gs1GoldenReplaySessionOpenResult = try {
        sessionFactory.open(checkpoint)
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: LinkageError) {
        Gs1GoldenReplaySessionOpenResult.Failure(
            AlgorithmError(
                AlgorithmErrorCode.NATIVE_CREATE_FAILED,
                "session factory linkage failed",
            ),
        )
    } catch (_: Exception) {
        Gs1GoldenReplaySessionOpenResult.Failure(
            AlgorithmError(
                AlgorithmErrorCode.NATIVE_CREATE_FAILED,
                "session factory failed",
            ),
        )
    }

    private fun diagnosticMismatch(
        trace: Gs1GoldenTrace,
        expected: Gs1GoldenExpectedSample,
        observed: ObservedStep,
    ): String? {
        val diagnostic = expected.diagnostic
        val output = observed.output
        return when {
            output.index != expected.decoded.index -> "algorithm output index differs"
            output.sensorTimeEpochSeconds != expected.decoded.sensorTimeEpochSeconds -> "algorithm output time differs"
            output.algorithmProfile != trace.algorithmProfile -> "algorithm profile differs"
            output.algorithmVersion != trace.algorithmVersion -> "algorithm version differs"
            output.tokenSource != trace.sensitivityEvidence.tokenSource -> "sensitivity token source differs"
            output.initializationMode != trace.sensitivityEvidence.initializationMode -> "initialization mode differs"
            output.nativeGlucoseMmolL.toBits() != diagnostic.nativeGlucoseMmolLBits -> "native output bits differ"
            output.glucoseMmolL.toBits() != diagnostic.displayedGlucoseMmolLBits -> "diagnostic output bits differ"
            output.trend != diagnostic.trend -> "native trend differs"
            output.warnings.glucose != diagnostic.glucoseWarning -> "glucose warning differs"
            output.warnings.current != diagnostic.currentWarning -> "current warning differs"
            output.warnings.temperature != diagnostic.temperatureWarning -> "temperature warning differs"
            observed.errorCode != diagnostic.algorithmErrorCode -> "algorithm diagnostic error differs"
            else -> null
        }
    }

    private fun checkpointMismatch(
        trace: Gs1GoldenTrace,
        sensitivity: DecodedSensitivity,
        expected: Gs1GoldenExpectedSample,
        checkpoint: AlgorithmCheckpoint,
    ): String? {
        val actualHash = checkpoint.nativeState.gs1GoldenSha256()
        return when {
            checkpoint.profile != trace.algorithmProfile -> "checkpoint profile differs"
            checkpoint.algorithmVersion != trace.algorithmVersion -> "checkpoint algorithm version differs"
            checkpoint.binarySetId != trace.algorithmBinarySetId -> "checkpoint binary set differs"
            checkpoint.sensitivityToken != sensitivity.token -> "checkpoint sensitivity input differs"
            checkpoint.sensitivityToken.source != trace.sensitivityEvidence.tokenSource ->
                "checkpoint sensitivity token source differs"
            checkpoint.initializationMode != trace.sensitivityEvidence.initializationMode ->
                "checkpoint initialization mode differs"
            checkpoint.lastProcessedIndex != expected.decoded.index -> "checkpoint sequence differs"
            checkpoint.lastSensorTimeEpochSeconds != expected.decoded.sensorTimeEpochSeconds -> "checkpoint sensor time differs"
            checkpoint.nativeState.size != trace.algorithmProfile.stateSize -> "checkpoint state size differs"
            checkpoint.nativeStateSha256.lowercase() != actualHash -> "checkpoint self-hash is invalid"
            actualHash != expected.diagnostic.stateSha256 -> "checkpoint state hash differs from trace"
            else -> null
        }
    }

    private fun sensitivityMetadataMismatch(
        trace: Gs1GoldenTrace,
        opened: Gs1GoldenReplaySessionOpenResult.Success,
    ): String? {
        val expected = trace.sensitivityEvidence
        val sensitivity = opened.sensitivity
        return when {
            opened.initializationMode != expected.initializationMode ->
                "session initialization mode differs from trace"
            !sensitivity.token.isValid() -> "session sensitivity input is invalid"
            sensitivity.token.source != expected.tokenSource -> "session sensitivity token source differs"
            sensitivity.encoding != expected.encoding -> "session sensitivity encoding differs"
            sensitivity.coefficient.toBits() != expected.coefficientBits ->
                "session sensitivity coefficient bits differ"
            else -> null
        }
    }

    private fun verifySensitivityBinding(
        trace: Gs1GoldenTrace,
        sensitivity: DecodedSensitivity,
    ): SensitivityBindingResult {
        val capability = sensitivityHmac
            ?: return SensitivityBindingResult.Unavailable("private sensitivity HMAC capability is missing")
        val observed = try {
            capability.hmacSha256(
                gs1GoldenSensitivityBindingMessage(
                    traceId = trace.traceId,
                    tokenSource = sensitivity.token.source,
                    exactInput = sensitivity.token.value,
                ),
            )
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: LinkageError) {
            return SensitivityBindingResult.Unavailable("sensitivity HMAC linkage failed")
        } catch (_: Exception) {
            return SensitivityBindingResult.Unavailable("sensitivity HMAC failed")
        }
        if (observed.size != SHA256_BYTES) {
            return SensitivityBindingResult.Unavailable("sensitivity HMAC capability returned an invalid size")
        }
        val expected = trace.sensitivityEvidence.inputHmacSha256.lowerHexBytes()
            ?: return SensitivityBindingResult.Mismatch("trace sensitivity HMAC is invalid")
        return if (MessageDigest.isEqual(expected, observed)) {
            SensitivityBindingResult.Matched
        } else {
            SensitivityBindingResult.Mismatch("exact sensitivity input does not match private trace evidence")
        }
    }

    private fun failedAt(
        failure: Gs1GoldenReplayFailure,
        detail: String,
        notification: Gs1GoldenNotification,
        sampleIndex: Int? = null,
    ) = Gs1GoldenReplayResult.Failed(
        failure = failure,
        detail = detail,
        attemptOrdinal = notification.attemptOrdinal,
        ingressOrdinal = notification.ordinal,
        sampleIndex = sampleIndex,
    )

    private fun failed(failure: Gs1GoldenReplayFailure, detail: String) =
        Gs1GoldenReplayResult.Failed(failure, detail)

    private data class ObservedStep(
        val output: AlgorithmOutput,
        val checkpoint: AlgorithmCheckpoint,
        val errorCode: com.sladkaya.sensor.sibionics.algorithm.AlgorithmErrorCode?,
    )

    private sealed interface SensitivityBindingResult {
        data object Matched : SensitivityBindingResult
        data class Unavailable(val detail: String) : SensitivityBindingResult
        data class Mismatch(val detail: String) : SensitivityBindingResult
    }

    private companion object {
        const val SHA256_BYTES = 32
    }
}

private fun String.lowerHexBytes(): ByteArray? {
    if (length != 64 || !all { it in '0'..'9' || it in 'a'..'f' }) return null
    return ByteArray(length / 2) { index ->
        substring(index * 2, index * 2 + 2).toInt(16).toByte()
    }
}
