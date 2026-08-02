package com.sladkaya.sensor.sibionics

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

internal interface Gs1SampleProcessor {
    suspend fun process(
        sourceIngressId: String,
        encryptedPacket: ByteArray,
        sample: DecodedGs1RawSample,
        receivedAtEpochMs: Long,
    ): Gs1ProcessingResult

    suspend fun retryPendingCommit(): Gs1ProcessingResult
}

internal sealed interface Gs1PacketProcessingResult {
    data class CommittedIssue(
        val sequence: Int,
        val code: String,
        val message: String,
    ) {
        init {
            require(sequence >= 0)
            require(code.isNotBlank())
            require(message.isNotBlank())
        }
    }

    data class Completed(
        val committedSamples: List<DecodedGs1RawSample>,
        val diagnostics: List<Gs1DiagnosticReading> = emptyList(),
        val publications: List<Gs1ProductPublication> = emptyList(),
        val committedIssues: List<CommittedIssue> = emptyList(),
        val resolvedWireProfile: Gs1WireProfile? = null,
        val validatedTransportEnvelope: Boolean = false,
    ) : Gs1PacketProcessingResult

    data class InvalidPacket(
        val error: Gs1VerifiedPacketError,
        val detail: String?,
    ) : Gs1PacketProcessingResult

    data class Rejected(
        val code: String,
        val message: String,
        val committedSamples: List<DecodedGs1RawSample> = emptyList(),
        val diagnostics: List<Gs1DiagnosticReading> = emptyList(),
        val publications: List<Gs1ProductPublication> = emptyList(),
        val committedIssues: List<CommittedIssue> = emptyList(),
    ) : Gs1PacketProcessingResult

    data class PersistenceUnavailable(val message: String) : Gs1PacketProcessingResult

    data class StorageConflict(
        val reason: String,
        val committedSamples: List<DecodedGs1RawSample> = emptyList(),
        val diagnostics: List<Gs1DiagnosticReading> = emptyList(),
        val publications: List<Gs1ProductPublication> = emptyList(),
        val committedIssues: List<CommittedIssue> = emptyList(),
    ) : Gs1PacketProcessingResult

    data class Closed(
        val reason: String,
        val committedSamples: List<DecodedGs1RawSample> = emptyList(),
        val diagnostics: List<Gs1DiagnosticReading> = emptyList(),
        val publications: List<Gs1ProductPublication> = emptyList(),
        val committedIssues: List<CommittedIssue> = emptyList(),
    ) : Gs1PacketProcessingResult

    data object NoPendingCommit : Gs1PacketProcessingResult
}

/**
 * Serial, lossless bridge between one verified transport packet and the
 * stateful algorithm/storage boundary. A batch is never advanced past an
 * uncertain database outcome; callers must retry that exact pending commit.
 */
internal class Gs1PacketProcessor(
    private val core: Gs1SampleProcessor,
    private val decoder: Gs1PacketVerifier = Gs1VerifiedPacketDecoder(),
    initialExpectedIndex: Int = FIRST_SENSOR_INDEX,
    private val wireProfile: Gs1WireProfile,
) {
    private val mutex = Mutex()
    private var pending: PendingBatch? = null
    private var expectedNextIndex = initialExpectedIndex

    init {
        require(initialExpectedIndex in FIRST_SENSOR_INDEX..MAX_SENSOR_INDEX)
    }

    suspend fun ingest(
        sourceIngressId: String,
        encryptedPacket: ByteArray,
        receivedAtEpochMs: Long,
        verifiedCommittedPrefixSampleCount: Int = 0,
    ): Gs1PacketProcessingResult = mutex.withLock {
        require(sourceIngressId.isNotBlank() && sourceIngressId.length <= MAX_INGRESS_ID_CHARS)
        require(receivedAtEpochMs > 0L)
        if (pending != null) {
            return@withLock Gs1PacketProcessingResult.PersistenceUnavailable(
                "The previous GS1 batch still requires a durable commit",
            )
        }
        val verified = when (
            val decoded = decoder.decode(encryptedPacket.copyOf(), receivedAtEpochMs)
        ) {
            is Gs1VerifiedPacketResult.Success -> decoded
            is Gs1VerifiedPacketResult.Failure -> {
                return@withLock Gs1PacketProcessingResult.InvalidPacket(decoded.error, decoded.detail)
            }
        }
        if (verified.samples.isEmpty()) {
            return@withLock Gs1PacketProcessingResult.Completed(
                committedSamples = emptyList(),
                validatedTransportEnvelope = true,
            )
        }
        val batchError = validateWholeBatch(
            samples = verified.samples,
            receivedAtEpochMs = receivedAtEpochMs,
            verifiedCommittedPrefixSampleCount = verifiedCommittedPrefixSampleCount,
        )
        if (batchError != null) {
            return@withLock Gs1PacketProcessingResult.Rejected(
                code = "BATCH_SEQUENCE_INVALID",
                message = batchError,
            )
        }
        pending = PendingBatch(
            sourceIngressId = sourceIngressId,
            encryptedPacket = encryptedPacket.copyOf(),
            receivedAtEpochMs = receivedAtEpochMs,
            samples = verified.samples.drop(verifiedCommittedPrefixSampleCount),
        )
        drainPendingLocked(retryCurrent = false)
    }

    suspend fun retryPending(): Gs1PacketProcessingResult = mutex.withLock {
        if (pending == null) return@withLock Gs1PacketProcessingResult.NoPendingCommit
        drainPendingLocked(retryCurrent = true)
    }

    private suspend fun drainPendingLocked(retryCurrent: Boolean): Gs1PacketProcessingResult {
        val batch = pending ?: return Gs1PacketProcessingResult.NoPendingCommit
        var retry = retryCurrent
        while (batch.position < batch.samples.size) {
            val sample = batch.samples[batch.position]
            val result = if (retry) {
                retry = false
                core.retryPendingCommit()
            } else {
                core.process(
                    batch.sourceIngressId,
                    batch.encryptedPacket,
                    sample,
                    batch.receivedAtEpochMs,
                )
            }
            when (result) {
                is Gs1ProcessingResult.Diagnostic -> {
                    batch.diagnostics += result.candidate
                    advanceCommittedSample(batch, sample)
                }

                is Gs1ProcessingResult.ProductPublicationReady -> {
                    batch.publications += result.publication
                    advanceCommittedSample(batch, sample)
                }

                is Gs1ProcessingResult.ApprovedCheckpointOnly -> {
                    advanceCommittedSample(batch, sample)
                }

                is Gs1ProcessingResult.Rejected -> {
                    if (result.checkpointCommitted) {
                        batch.committedIssues += Gs1PacketProcessingResult.CommittedIssue(
                            sequence = sample.index,
                            code = result.code,
                            message = result.message,
                        )
                        advanceCommittedSample(batch, sample)
                        if (result.terminalAfterCommit) {
                            pending = null
                            return Gs1PacketProcessingResult.Rejected(
                                code = result.code,
                                message = result.message,
                                committedSamples = batch.committedSamples.toList(),
                                diagnostics = batch.diagnostics.toList(),
                                publications = batch.publications.toList(),
                                committedIssues = batch.committedIssues.toList(),
                            )
                        }
                    } else {
                        pending = null
                        return Gs1PacketProcessingResult.Rejected(
                            code = result.code,
                            message = result.message,
                            committedSamples = batch.committedSamples.toList(),
                            diagnostics = batch.diagnostics.toList(),
                            publications = batch.publications.toList(),
                            committedIssues = batch.committedIssues.toList(),
                        )
                    }
                }

                is Gs1ProcessingResult.PersistenceUnavailable -> {
                    return Gs1PacketProcessingResult.PersistenceUnavailable(result.message)
                }

                is Gs1ProcessingResult.StorageConflict -> {
                    pending = null
                    return Gs1PacketProcessingResult.StorageConflict(
                        reason = result.reason,
                        committedSamples = batch.committedSamples.toList(),
                        diagnostics = batch.diagnostics.toList(),
                        publications = batch.publications.toList(),
                        committedIssues = batch.committedIssues.toList(),
                    )
                }

                is Gs1ProcessingResult.Closed -> {
                    pending = null
                    return Gs1PacketProcessingResult.Closed(
                        reason = result.reason,
                        committedSamples = batch.committedSamples.toList(),
                        diagnostics = batch.diagnostics.toList(),
                        publications = batch.publications.toList(),
                        committedIssues = batch.committedIssues.toList(),
                    )
                }

                Gs1ProcessingResult.NoPendingCommit -> {
                    pending = null
                    return Gs1PacketProcessingResult.Closed(
                        reason = "GS1 core lost an expected pending commit",
                        committedSamples = batch.committedSamples.toList(),
                        diagnostics = batch.diagnostics.toList(),
                        publications = batch.publications.toList(),
                        committedIssues = batch.committedIssues.toList(),
                    )
                }
            }
        }
        pending = null
        return Gs1PacketProcessingResult.Completed(
            committedSamples = batch.committedSamples.toList(),
            diagnostics = batch.diagnostics.toList(),
            publications = batch.publications.toList(),
            committedIssues = batch.committedIssues.toList(),
        )
    }

    private fun validateWholeBatch(
        samples: List<DecodedGs1RawSample>,
        receivedAtEpochMs: Long,
        verifiedCommittedPrefixSampleCount: Int,
    ): String? {
        if (expectedNextIndex > MAX_SENSOR_INDEX) {
            return "The sensor sequence has already reached its maximum value"
        }
        if (verifiedCommittedPrefixSampleCount !in 0 until samples.size) {
            return "Verified committed prefix must leave a non-empty suffix for the core"
        }
        samples.forEachIndexed { position, sample ->
            val expectedIndex = samples.first().index + position
            if (expectedIndex > MAX_SENSOR_INDEX || sample.index != expectedIndex) {
                return "Sensor indexes in one batch must be consecutive"
            }
            if (!sample.toAlgorithmInput().isValid()) {
                return "A decoded sample is outside the algorithm input contract"
            }
            if (wireProfile == Gs1WireProfile.V115) {
                val addTime = sample.addTimeSeconds
                    ?: return "V115 sample is missing reference add-time provenance"
                val receivedAtEpochSeconds = receivedAtEpochMs / MILLIS_PER_SECOND
                val reported = receivedAtEpochSeconds + addTime -
                    sample.reindex.toLong() * SECONDS_PER_SAMPLE
                val clamped = reported > receivedAtEpochSeconds
                val expectedTime = if (clamped) receivedAtEpochSeconds else reported
                if (sample.sensorTimeEpochSeconds != expectedTime ||
                    sample.sensorTimeWasClamped != clamped
                ) {
                    return "V115 sample time does not match the durable receive-time formula"
                }
            } else if (sample.addTimeSeconds != null || sample.sensorTimeWasClamped) {
                return "V120 sample unexpectedly contains V115 time provenance"
            }
            if (position > 0) {
                val previous = samples[position - 1]
                val validTime = if (wireProfile == Gs1WireProfile.V115) {
                    sample.sensorTimeEpochSeconds >= previous.sensorTimeEpochSeconds
                } else {
                    sample.sensorTimeEpochSeconds == previous.sensorTimeEpochSeconds + SECONDS_PER_SAMPLE
                }
                if (!validTime) {
                    return if (wireProfile == Gs1WireProfile.V115) {
                        "V115 sensor timestamps in one batch must be nondecreasing"
                    } else {
                        "V120 sensor timestamps in one batch must advance by exactly 60 seconds"
                    }
                }
            }
        }
        val firstUncommitted = samples[verifiedCommittedPrefixSampleCount]
        if (firstUncommitted.index != expectedNextIndex) {
            return "Expected sensor index $expectedNextIndex but received ${firstUncommitted.index}"
        }
        return null
    }

    private fun advanceCommittedSample(
        batch: PendingBatch,
        sample: DecodedGs1RawSample,
    ) {
        check(sample.index == expectedNextIndex) {
            "Committed GS1 sample does not match the expected sequence"
        }
        batch.committedSamples += sample
        batch.position += 1
        expectedNextIndex = sample.index + 1
    }

    private data class PendingBatch(
        val sourceIngressId: String,
        val encryptedPacket: ByteArray,
        val receivedAtEpochMs: Long,
        val samples: List<DecodedGs1RawSample>,
        var position: Int = 0,
        val committedSamples: MutableList<DecodedGs1RawSample> = mutableListOf(),
        val diagnostics: MutableList<Gs1DiagnosticReading> = mutableListOf(),
        val publications: MutableList<Gs1ProductPublication> = mutableListOf(),
        val committedIssues: MutableList<Gs1PacketProcessingResult.CommittedIssue> = mutableListOf(),
    )

    private companion object {
        const val FIRST_SENSOR_INDEX = 1
        const val MAX_SENSOR_INDEX = 0xffff
        const val SECONDS_PER_SAMPLE = 60L
        const val MILLIS_PER_SECOND = 1_000L
        const val MAX_INGRESS_ID_CHARS = 128
    }
}
