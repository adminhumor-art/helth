package com.sladkaya.sensor.sibionics

import com.sladkaya.core.model.SensorFamily
import com.sladkaya.sensor.sibionics.algorithm.AlgorithmProfile
import com.sladkaya.sensor.sibionics.algorithm.AlgorithmErrorCode
import com.sladkaya.sensor.sibionics.algorithm.AlgorithmInitializationMode
import com.sladkaya.sensor.sibionics.algorithm.SensitivityEncoding
import com.sladkaya.sensor.sibionics.algorithm.SensitivityTokenSource

internal class Gs1GoldenReplayPlan internal constructor(
    val trace: Gs1GoldenTrace,
) {
    val notifications: List<Gs1GoldenNotification> = trace.notifications.toList()
}

internal sealed interface Gs1GoldenReplayPlanResult {
    data class Ready(val plan: Gs1GoldenReplayPlan) : Gs1GoldenReplayPlanResult
    data class Invalid(
        val error: Gs1GoldenTraceError,
        val detail: String,
    ) : Gs1GoldenReplayPlanResult
}

/**
 * Validates both parsed and programmatically constructed traces before any
 * decoder or native context can be opened.
 */
internal class Gs1GoldenReplayPlanner {
    fun plan(trace: Gs1GoldenTrace): Gs1GoldenReplayPlanResult {
        if (trace.formatVersion != FORMAT_VERSION) return invalid(
            Gs1GoldenTraceError.UNKNOWN_VERSION,
            "unsupported trace version ${trace.formatVersion}",
        )
        if (!TRACE_ID.matches(trace.traceId)) return invalidField("trace id is not a bounded pseudonym")
        if (trace.family != SensorFamily.SIBIONICS_GS1 && trace.family != SensorFamily.SIBIONICS_GS1SB) {
            return invalidField("golden trace supports only GS1/GS1Sb")
        }
        if (!boundedAscii(trace.algorithmVersion, MAX_METADATA_CHARS)) return invalidField("algorithm version is invalid")
        if (!boundedAscii(trace.algorithmBinarySetId, MAX_METADATA_CHARS)) return invalidField("binary set id is invalid")
        val wireProfile = when (trace.transportProtocol) {
            V120_TRANSPORT_PROTOCOL -> if (trace.algorithmProfile == V120_ALGORITHM_PROFILE) {
                GoldenWireProfile.V120
            } else {
                return invalidField("GS1 V120 trace requires the V116A algorithm profile")
            }
            V115_TRANSPORT_PROTOCOL -> if (trace.algorithmProfile == V115_ALGORITHM_PROFILE) {
                GoldenWireProfile.V115
            } else {
                return invalidField("GS1 V115 trace requires the V115G algorithm profile")
            }
            else -> return invalidField("transport protocol is unsupported")
        }
        if (trace.sensitivityEvidence.tokenSource != SensitivityTokenSource.PACKAGE_CODE) {
            return invalidField("sensitivity token source is not the package code")
        }
        if (!trace.sensitivityEvidence.hasConsistentInitialization()) {
            return invalidField("sensitivity encoding does not match initialization mode")
        }
        val coefficient = Float.fromBits(trace.sensitivityEvidence.coefficientBits)
        if (!coefficient.isFinite() || coefficient !in MIN_SENSITIVITY..MAX_SENSITIVITY) {
            return invalidField("sensitivity coefficient is outside verified bounds")
        }
        if (!trace.sensitivityEvidence.inputHmacSha256.isSha256()) {
            return invalidField("sensitivity input HMAC is invalid")
        }
        if (!trace.macIdentity.macPseudonym.isSha256()) return invalidField("MAC pseudonym is not lower-case SHA-256")
        if (!trace.macIdentity.evidenceHmacSha256.isSha256()) return invalidField("identity evidence HMAC is invalid")
        if (trace.provenance == Gs1GoldenTraceProvenance.SYNTHETIC_TEST_ONLY &&
            (trace.privacyClassification != Gs1GoldenPrivacyClassification.SYNTHETIC_PUBLIC_FIXTURE ||
                trace.macIdentity.evidenceKind != Gs1GoldenIdentityEvidenceKind.SYNTHETIC)
        ) {
            return invalidField("synthetic trace must be an explicitly synthetic public fixture")
        }
        if (trace.provenance == Gs1GoldenTraceProvenance.PRIVATE_REFERENCE_CAPTURE &&
            (trace.privacyClassification != Gs1GoldenPrivacyClassification.PRIVATE_SENSITIVE_EVIDENCE ||
                trace.macIdentity.evidenceKind == Gs1GoldenIdentityEvidenceKind.SYNTHETIC)
        ) {
            return invalidField("reference trace must remain private sensitive evidence")
        }
        if (trace.notifications.isEmpty() || trace.notifications.size > MAX_NOTIFICATIONS) {
            return invalidField("notification count is outside bounds")
        }

        var previousAttempt = -1
        var previousAttemptPseudonym: String? = null
        var previousOrdinal = -1L
        var previousReceivedAt = -1L
        val closedAttemptPseudonyms = mutableSetOf<String>()
        var expectedSampleIndex = FIRST_SAMPLE_INDEX
        var previousSensorTime: Long? = null

        trace.notifications.forEachIndexed { position, notification ->
            if (notification.attemptOrdinal < 0 || notification.ordinal < 0L) {
                return conflict("negative attempt/ordinal at notification $position")
            }
            if (!ATTEMPT_PSEUDONYM.matches(notification.attemptPseudonym)) {
                return invalidField("attempt pseudonym is invalid at notification $position")
            }
            when {
                position == 0 -> {
                    if (notification.attemptOrdinal != 0 || notification.ordinal != 0L) {
                        return conflict("first ingress must be attempt 0 ordinal 0")
                    }
                }

                notification.attemptOrdinal == previousAttempt -> {
                    if (notification.attemptPseudonym != previousAttemptPseudonym ||
                        notification.ordinal != previousOrdinal + 1L
                    ) {
                        return conflict("ingress ordinal or attempt identity conflicts at notification $position")
                    }
                }

                notification.attemptOrdinal == previousAttempt + 1 -> {
                    previousAttemptPseudonym?.let(closedAttemptPseudonyms::add)
                    if (notification.ordinal != 0L || notification.attemptPseudonym in closedAttemptPseudonyms) {
                        return conflict("new attempt must start at ordinal 0 with a fresh pseudonym")
                    }
                }

                else -> return conflict("attempt ordinal is not contiguous at notification $position")
            }
            if (notification.receivedAtEpochMs <= 0L || notification.receivedAtEpochMs < previousReceivedAt) {
                return conflict("ingress timestamps regress at notification $position")
            }
            val packet = notification.encryptedPacketCopy()
            if (packet.isEmpty() || packet.size > MAX_PACKET_BYTES) {
                return invalidField("encrypted packet size is outside bounds at notification $position")
            }
            if (!notification.packetSha256.isSha256() || packet.gs1GoldenSha256() != notification.packetSha256) {
                return invalidField("encrypted packet hash conflicts at notification $position")
            }

            when (notification.expectedDecode) {
                Gs1GoldenDecodeExpectation.GS1_DATA -> {
                    val expectedDecrypted = wireProfile == GoldenWireProfile.V120
                    if (notification.expectedDecodeError != null ||
                        notification.expectedDecrypted != expectedDecrypted ||
                        notification.samples.isEmpty()
                    ) {
                        return invalidField("GS1 data expectation is incomplete at notification $position")
                    }
                }

                Gs1GoldenDecodeExpectation.NON_DATA -> {
                    if (notification.expectedDecodeError != null || notification.expectedDecrypted || notification.samples.isNotEmpty()) {
                        return invalidField("non-data expectation contains data fields at notification $position")
                    }
                }

                Gs1GoldenDecodeExpectation.REJECTED -> {
                    if (notification.expectedDecodeError == null ||
                        notification.expectedDecodeError == Gs1VerifiedPacketError.NOT_GS1_DATA ||
                        notification.expectedDecrypted ||
                        notification.samples.isNotEmpty()
                    ) {
                        return invalidField("rejected expectation is incomplete at notification $position")
                    }
                }
            }
            val maxSamples = when (wireProfile) {
                GoldenWireProfile.V115 -> MAX_V115_SAMPLES_PER_NOTIFICATION
                GoldenWireProfile.V120 -> MAX_V120_SAMPLES_PER_NOTIFICATION
            }
            if (notification.samples.size > maxSamples) {
                return invalidField("sample count exceeds transport bounds at notification $position")
            }
            notification.samples.forEach { expected ->
                val sample = expected.decoded
                if (sample.index != expectedSampleIndex) {
                    return conflict("decoded sample index ${sample.index} conflicts with expected $expectedSampleIndex")
                }
                if (sample.sensorTimeEpochSeconds <= 0L ||
                    sample.current !in U16_RANGE ||
                    sample.temperature !in U16_RANGE ||
                    sample.reindex !in U16_RANGE
                ) {
                    return invalidField("decoded sample is outside transport bounds at index ${sample.index}")
                }
                when (wireProfile) {
                    GoldenWireProfile.V120 -> {
                        if (sample.sensorTimeEpochSeconds > U32_MAX ||
                            sample.addTimeSeconds != null ||
                            sample.sensorTimeWasClamped
                        ) {
                            return invalidField("V120 sample contains invalid time provenance at index ${sample.index}")
                        }
                        if (previousSensorTime != null &&
                            sample.sensorTimeEpochSeconds != previousSensorTime!! + SAMPLE_SECONDS
                        ) {
                            return conflict("V120 sample time is not exactly sequential at index ${sample.index}")
                        }
                    }

                    GoldenWireProfile.V115 -> {
                        val addTime = sample.addTimeSeconds
                            ?: return invalidField("V115 sample lacks add-time provenance at index ${sample.index}")
                        if (addTime !in U16_RANGE) {
                            return invalidField("V115 add-time is outside uint16 at index ${sample.index}")
                        }
                        val receivedAtSeconds = notification.receivedAtEpochMs / MILLIS_PER_SECOND
                        val reportedTime = receivedAtSeconds + addTime -
                            sample.reindex.toLong() * SAMPLE_SECONDS
                        val clamped = reportedTime > receivedAtSeconds
                        val expectedTime = if (clamped) receivedAtSeconds else reportedTime
                        if (sample.sensorTimeEpochSeconds != expectedTime ||
                            sample.sensorTimeWasClamped != clamped
                        ) {
                            return invalidField("V115 sample time conflicts with receive-time provenance at index ${sample.index}")
                        }
                        if (previousSensorTime != null &&
                            sample.sensorTimeEpochSeconds < previousSensorTime!!
                        ) {
                            return conflict("V115 sample time regresses at index ${sample.index}")
                        }
                    }
                }
                if (!sample.toAlgorithmInput().isValid()) {
                    return invalidField("decoded sample is outside algorithm input bounds at index ${sample.index}")
                }
                val diagnostic = expected.diagnostic
                if (!Double.fromBits(diagnostic.nativeGlucoseMmolLBits).isFinite() ||
                    !Double.fromBits(diagnostic.displayedGlucoseMmolLBits).isFinite()
                ) {
                    return invalidField("diagnostic contains a non-finite output at index ${sample.index}")
                }
                if (!diagnostic.stateSha256.isSha256()) {
                    return invalidField("state hash is invalid at index ${sample.index}")
                }
                if (diagnostic.algorithmErrorCode != null &&
                    diagnostic.algorithmErrorCode != AlgorithmErrorCode.INVALID_GLUCOSE
                ) {
                    return invalidField("only a checkpointed diagnostic error may appear at index ${sample.index}")
                }
                expectedSampleIndex += 1
                previousSensorTime = sample.sensorTimeEpochSeconds
            }

            previousAttempt = notification.attemptOrdinal
            previousAttemptPseudonym = notification.attemptPseudonym
            previousOrdinal = notification.ordinal
            previousReceivedAt = notification.receivedAtEpochMs
        }
        if (expectedSampleIndex == FIRST_SAMPLE_INDEX) return invalidField("trace contains no decoded GS1 samples")
        return Gs1GoldenReplayPlanResult.Ready(Gs1GoldenReplayPlan(trace))
    }

    private fun invalidField(detail: String) = invalid(Gs1GoldenTraceError.INVALID_FIELD, detail)

    private fun conflict(detail: String) = invalid(Gs1GoldenTraceError.CONFLICTING_SEQUENCE, detail)

    private fun invalid(error: Gs1GoldenTraceError, detail: String) =
        Gs1GoldenReplayPlanResult.Invalid(error, detail)

    private fun boundedAscii(value: String, maxChars: Int): Boolean =
        value.isNotEmpty() && value.length <= maxChars && value.all { it in '!'..'~' }

    private fun String.isSha256(): Boolean =
        length == SHA256_CHARS && all { it in '0'..'9' || it in 'a'..'f' }

    private fun Gs1GoldenSensitivityEvidence.hasConsistentInitialization(): Boolean =
        when (initializationMode) {
            AlgorithmInitializationMode.STANDARD -> encoding == SensitivityEncoding.NORMAL
            AlgorithmInitializationMode.FACTION -> encoding == SensitivityEncoding.FACTION
        }

    private companion object {
        const val FORMAT_VERSION = 1
        const val V115_TRANSPORT_PROTOCOL = "GS1_V115"
        const val V120_TRANSPORT_PROTOCOL = "GS1_V120"
        const val FIRST_SAMPLE_INDEX = 1
        const val SAMPLE_SECONDS = 60L
        const val MILLIS_PER_SECOND = 1_000L
        const val MAX_PACKET_BYTES = 250
        const val MAX_NOTIFICATIONS = 10_000
        const val MAX_V115_SAMPLES_PER_NOTIFICATION = 17
        const val MAX_V120_SAMPLES_PER_NOTIFICATION = 29
        const val MAX_METADATA_CHARS = 128
        const val SHA256_CHARS = 64
        const val U32_MAX = 0xffff_ffffL
        const val MIN_SENSITIVITY = 0.8f
        const val MAX_SENSITIVITY = 2.5f
        val V115_ALGORITHM_PROFILE = AlgorithmProfile.V115G
        val V120_ALGORITHM_PROFILE = AlgorithmProfile.V116A
        val U16_RANGE = 0..0xffff
        val TRACE_ID = Regex("^[A-Za-z0-9][A-Za-z0-9._-]{0,127}$")
        val ATTEMPT_PSEUDONYM = Regex("^[A-Za-z0-9][A-Za-z0-9._-]{0,127}$")
    }

    private enum class GoldenWireProfile {
        V115,
        V120,
    }
}
