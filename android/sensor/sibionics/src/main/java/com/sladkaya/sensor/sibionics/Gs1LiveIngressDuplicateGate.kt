package com.sladkaya.sensor.sibionics

import com.sladkaya.core.data.CommittedSensorIngressReadResult
import com.sladkaya.core.data.CommittedSensorIngressReader
import com.sladkaya.core.data.CommittedSensorIngressSampleRecord
import com.sladkaya.core.data.CommittedSensorCoverageReadResult
import com.sladkaya.core.data.CommittedSensorCoverageRequest
import com.sladkaya.core.data.SensorPacketIngressJournal
import com.sladkaya.core.data.SensorPacketIngressMarkHandledResult
import com.sladkaya.core.data.SensorPacketIngressOutcomeRecord
import com.sladkaya.core.data.SensorPacketIngressOutcomeStatus
import com.sladkaya.core.data.SensorPacketIngressRecord
import java.util.concurrent.CancellationException

internal sealed interface Gs1LiveIngressDuplicateResult {
    data object NotDuplicate : Gs1LiveIngressDuplicateResult
    data object Handled : Gs1LiveIngressDuplicateResult
    data class VerifiedSuffix(
        val committedPrefixSampleCount: Int,
    ) : Gs1LiveIngressDuplicateResult {
        init {
            require(committedPrefixSampleCount > 0)
        }
    }
    data class Failed(
        val code: String,
        val detail: String? = null,
        val retryable: Boolean,
    ) : Gs1LiveIngressDuplicateResult
}

/** Carries the exact Room-proven overlap into the one packet submitted to the core. */
internal fun DurablyJournaledGs1Packet.withVerifiedCommittedPrefix(
    result: Gs1LiveIngressDuplicateResult.VerifiedSuffix,
): DurablyJournaledGs1Packet = DurablyJournaledGs1Packet(
    ingress = ingress,
    verifiedCommittedPrefixSampleCount = result.committedPrefixSampleCount,
)

/**
 * Resolves a whole live packet that is already behind the durable core cursor.
 * Only an exact, previously handled Room lineage can suppress stateful ingest.
 */
internal class Gs1LiveIngressDuplicateGate(
    private val journal: SensorPacketIngressJournal,
    private val committedIngressReader: CommittedSensorIngressReader,
    private val codec: SibionicsPacketCodec,
) {
    suspend fun resolve(
        profile: Gs1DiagnosticActivationProfile,
        currentCoreCursor: Int,
        wireProfile: Gs1WireProfile,
        currentIngress: SensorPacketIngressRecord,
    ): Gs1LiveIngressDuplicateResult {
        if (currentIngress.sensorId != profile.sensorId ||
            currentIngress.sensorFamily != profile.family ||
            currentIngress.bluetoothAddress != profile.bluetoothAddress
        ) {
            return failed(
                "LIVE_DUPLICATE_INGRESS_IDENTITY_MISMATCH",
                currentIngress.ingressId,
                retryable = false,
            )
        }
        val entry = try {
            Gs1PendingIngressRecoveryPlanner(
                family = profile.family,
                codec = codec,
                wireProfile = wireProfile,
            ).plan(currentCoreCursor, listOf(currentIngress)).single()
        } catch (failure: Exception) {
            return failed(
                "LIVE_DUPLICATE_CLASSIFICATION_FAILED",
                failure.message,
                retryable = false,
            )
        }
        if (entry.disposition == Gs1PendingIngressRecoveryDisposition.PARTIAL_OVERLAP) {
            return resolvePartialOverlap(profile, wireProfile, entry)
        }
        if (entry.disposition != Gs1PendingIngressRecoveryDisposition.ALREADY_COVERED) {
            return Gs1LiveIngressDuplicateResult.NotDuplicate
        }
        val read = try {
            committedIngressReader.read(currentIngress)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            return failed(
                "LIVE_DUPLICATE_COMMITTED_EVIDENCE_UNAVAILABLE",
                failure.message,
                retryable = true,
            )
        }
        val duplicate = when (read) {
            is CommittedSensorIngressReadResult.HandledDuplicate -> read
            is CommittedSensorIngressReadResult.Exact -> return failed(
                "LIVE_DUPLICATE_CURRENT_INGRESS_ALREADY_MUTATED",
                currentIngress.ingressId,
                retryable = false,
            )
            is CommittedSensorIngressReadResult.Mismatch -> return failed(
                "LIVE_DUPLICATE_COMMITTED_EVIDENCE_MISMATCH",
                read.reason,
                retryable = false,
            )
        }
        if (!duplicate.sourceIngress.isExactDuplicateSourceForLive(currentIngress)) {
            return failed(
                "LIVE_DUPLICATE_COMMITTED_EVIDENCE_MISMATCH",
                "Handled origin differs from the current encrypted ingress",
                retryable = false,
            )
        }
        val expected = entry.expectedSamples
        val exactRange = duplicate.samples.size == expected.size &&
            duplicate.samples.zip(expected).all { (committed, decoded) ->
                committed.matchesDuplicateExpected(
                    sourceIngress = duplicate.sourceIngress,
                    profile = profile,
                    expected = decoded,
                    wireProfile = wireProfile,
                )
            }
        if (!exactRange) {
            return failed(
                "LIVE_DUPLICATE_COMMITTED_PREFIX_MISMATCH",
                "${currentIngress.ingressId}: handled origin is not the exact decoded range",
                retryable = false,
            )
        }
        val outcome = SensorPacketIngressOutcomeRecord(
            ingressId = currentIngress.ingressId,
            status = SensorPacketIngressOutcomeStatus.ALREADY_COVERED,
            handledAtEpochMs = currentIngress.receivedAtEpochMs,
            detail = null,
        )
        return try {
            when (val marked = journal.markHandled(outcome)) {
                SensorPacketIngressMarkHandledResult.MarkedHandled,
                SensorPacketIngressMarkHandledResult.AlreadyHandled,
                -> Gs1LiveIngressDuplicateResult.Handled

                is SensorPacketIngressMarkHandledResult.Conflict -> failed(
                    "LIVE_DUPLICATE_OUTCOME_CONFLICT",
                    marked.reason,
                    retryable = false,
                )
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            failed(
                "LIVE_DUPLICATE_OUTCOME_STORAGE_UNAVAILABLE",
                failure.message,
                retryable = true,
            )
        }
    }

    private suspend fun resolvePartialOverlap(
        profile: Gs1DiagnosticActivationProfile,
        wireProfile: Gs1WireProfile,
        entry: Gs1PendingIngressRecoveryEntry,
    ): Gs1LiveIngressDuplicateResult {
        val first = checkNotNull(entry.firstIndex)
        val lastCovered = entry.projectedCursorBefore - 1
        val committedPrefixCount = entry.projectedCursorBefore - first
        if (committedPrefixCount !in 1 until entry.expectedSamples.size) {
            return failed(
                "LIVE_PARTIAL_COMMITTED_PREFIX_MISMATCH",
                "Partial overlap has no exact committed prefix and replayable suffix",
                retryable = false,
            )
        }
        val read = try {
            committedIngressReader.readHandledCoverage(
                CommittedSensorCoverageRequest(
                    sensorId = profile.sensorId,
                    sensorFamily = profile.family,
                    bluetoothAddress = profile.bluetoothAddress,
                    firstSequence = first,
                    lastSequence = lastCovered,
                ),
            )
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            return failed(
                "LIVE_PARTIAL_COMMITTED_EVIDENCE_UNAVAILABLE",
                failure.message,
                retryable = true,
            )
        }
        val rows = when (read) {
            is CommittedSensorCoverageReadResult.Exact -> read.samples
            is CommittedSensorCoverageReadResult.Mismatch -> return failed(
                "LIVE_PARTIAL_COMMITTED_PREFIX_MISMATCH",
                read.reason,
                retryable = false,
            )
        }
        val expectedPrefix = entry.expectedSamples.take(committedPrefixCount)
        val exactPrefix = rows.size == expectedPrefix.size &&
            rows.zip(expectedPrefix).all { (committed, expected) ->
                committed.matchesCoverageExpected(profile, expected, wireProfile)
            }
        if (!exactPrefix) {
            return failed(
                "LIVE_PARTIAL_COMMITTED_PREFIX_MISMATCH",
                "${entry.record.ingressId}: Room coverage differs from the live packet prefix",
                retryable = false,
            )
        }
        return Gs1LiveIngressDuplicateResult.VerifiedSuffix(committedPrefixCount)
    }

    private fun failed(code: String, detail: String?, retryable: Boolean) =
        Gs1LiveIngressDuplicateResult.Failed(code, detail, retryable)
}

private fun SensorPacketIngressRecord.isExactDuplicateSourceForLive(
    current: SensorPacketIngressRecord,
): Boolean = ingressId != current.ingressId &&
    sensorId == current.sensorId &&
    sensorFamily == current.sensorFamily &&
    bluetoothAddress == current.bluetoothAddress &&
    packetSha256 == current.packetSha256 &&
    encryptedPacketCopy().contentEquals(current.encryptedPacketCopy())

private fun CommittedSensorIngressSampleRecord.matchesDuplicateExpected(
    sourceIngress: SensorPacketIngressRecord,
    profile: Gs1DiagnosticActivationProfile,
    expected: DecodedGs1RawSample,
    wireProfile: Gs1WireProfile,
): Boolean = raw.sourceIngressId == sourceIngress.ingressId &&
    raw.sensorId == profile.sensorId &&
    raw.sensorFamily == profile.family &&
    raw.sequence == expected.index &&
    (wireProfile == Gs1WireProfile.V115 ||
        raw.sensorTimeEpochMs == expected.sensorTimeEpochSeconds * 1_000L) &&
    raw.phoneTimeEpochMs == sourceIngress.receivedAtEpochMs &&
    raw.packetSha256 == sourceIngress.packetSha256 &&
    raw.packetCopy().contentEquals(sourceIngress.encryptedPacketCopy()) &&
    raw.currentRaw == expected.current &&
    raw.temperatureRaw == expected.temperature &&
    raw.historyDistance == expected.reindex &&
    raw.transportVariant == profile.transportVariant &&
    // V115 clamp is not receive-time provenance: algebraically it is exactly
    // addTime > historyDistance * 60, so it remains an exact packet-derived check.
    raw.sensorTimeWasClamped == expected.sensorTimeWasClamped &&
    raw.addTimeSeconds == expected.addTimeSeconds

private fun CommittedSensorIngressSampleRecord.matchesCoverageExpected(
    profile: Gs1DiagnosticActivationProfile,
    expected: DecodedGs1RawSample,
    wireProfile: Gs1WireProfile,
): Boolean = raw.sensorId == profile.sensorId &&
    raw.sensorFamily == profile.family &&
    raw.sequence == expected.index &&
    (wireProfile == Gs1WireProfile.V115 ||
        raw.sensorTimeEpochMs == expected.sensorTimeEpochSeconds * 1_000L) &&
    raw.currentRaw == expected.current &&
    raw.temperatureRaw == expected.temperature &&
    raw.historyDistance == expected.reindex &&
    raw.transportVariant == profile.transportVariant &&
    raw.sensorTimeWasClamped == expected.sensorTimeWasClamped &&
    raw.addTimeSeconds == expected.addTimeSeconds
