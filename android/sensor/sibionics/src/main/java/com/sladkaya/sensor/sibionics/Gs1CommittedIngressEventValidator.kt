package com.sladkaya.sensor.sibionics

import com.sladkaya.core.data.CommittedSensorIngressReadResult
import com.sladkaya.core.data.CommittedSensorIngressReader
import com.sladkaya.core.data.CommittedSensorIngressSampleRecord
import com.sladkaya.core.data.RawSensorSampleRecord
import java.util.concurrent.CancellationException

internal sealed interface Gs1CommittedIngressEventValidation {
    data class Accepted(
        val event: Gs1DiagnosticRuntimeEvent.Committed,
    ) : Gs1CommittedIngressEventValidation

    data class Failed(
        val code: String,
        val detail: String? = null,
        val retryable: Boolean,
    ) : Gs1CommittedIngressEventValidation
}

/** Makes Room, not the in-memory native return value, the product output source. */
internal class Gs1CommittedIngressEventValidator(
    private val committedIngressReader: CommittedSensorIngressReader,
) {
    suspend fun validate(
        event: Gs1DiagnosticRuntimeEvent.Committed,
    ): Gs1CommittedIngressEventValidation {
        if (event.samples.isEmpty()) {
            return if (event.validatedTransportEnvelope &&
                event.diagnostics.isEmpty() && event.publications.isEmpty() && event.issues.isEmpty()
            ) {
                Gs1CommittedIngressEventValidation.Accepted(event)
            } else {
                failed(
                    "COMMITTED_EMPTY_EVENT_INVALID",
                    "Only a validated output-free transport envelope may omit Room rows",
                    retryable = false,
                )
            }
        }
        val read = try {
            committedIngressReader.read(event.ingress)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            return failed(
                "COMMITTED_ROOM_EVIDENCE_UNAVAILABLE",
                failure.message,
                retryable = true,
            )
        }
        val rows = when (read) {
            is CommittedSensorIngressReadResult.Exact -> read.samples
            is CommittedSensorIngressReadResult.HandledDuplicate -> return failed(
                "COMMITTED_ROOM_DUPLICATE_UNEXPECTED",
                "Live/replay commits must be linked to their current exact ingress",
                retryable = false,
            )
            is CommittedSensorIngressReadResult.Mismatch -> return failed(
                "COMMITTED_ROOM_EVIDENCE_MISMATCH",
                read.reason,
                retryable = false,
            )
        }
        val expectedIndices = event.samples.map(DecodedGs1RawSample::index)
        if (expectedIndices.size != expectedIndices.distinct().size) {
            return failed(
                "COMMITTED_ROOM_SAMPLE_MISMATCH",
                "Committed event contains duplicate sensor indexes",
                retryable = false,
            )
        }
        val byIndex = rows.associateBy { it.raw.sequence }
        val selected = expectedIndices.mapNotNull(byIndex::get)
        if (selected.size != event.samples.size ||
            !selected.zip(event.samples).all { (row, expected) ->
                row.raw.matches(event, expected)
            }
        ) {
            return failed(
                "COMMITTED_ROOM_SAMPLE_MISMATCH",
                "Room rows are not the exact committed event range",
                retryable = false,
            )
        }
        val committedIndices = expectedIndices.map(Int::toLong).toSet()
        val diagnosticIndices = event.diagnostics.map(Gs1DiagnosticReading::sequence)
        val issueIndices = event.issues.map { it.sequence.toLong() }
        if (diagnosticIndices.any { it !in committedIndices } ||
            issueIndices.any { it !in committedIndices } ||
            diagnosticIndices.size != diagnosticIndices.distinct().size ||
            issueIndices.size != issueIndices.distinct().size
        ) {
            return failed(
                "COMMITTED_ROOM_OUTPUT_RANGE_MISMATCH",
                "In-memory diagnostic output is outside the Room-proven prefix",
                retryable = false,
            )
        }
        val roomPublications = selected.mapNotNull { it.toProductPublication() }
        if (roomPublications.size != event.publications.size ||
            !roomPublications.zip(event.publications).all { (room, memory) ->
                room.matchesExactly(memory)
            }
        ) {
            return failed(
                "COMMITTED_ROOM_PUBLICATION_MISMATCH",
                "Native in-memory publications differ from exact Room publication lineage",
                retryable = false,
            )
        }
        return Gs1CommittedIngressEventValidation.Accepted(
            event.copy(
                samples = selected.map { it.raw.toDecodedSampleForSettlement() },
                publications = roomPublications,
            ),
        )
    }

    private fun failed(code: String, detail: String?, retryable: Boolean) =
        Gs1CommittedIngressEventValidation.Failed(code, detail, retryable)
}

private fun RawSensorSampleRecord.matches(
    event: Gs1DiagnosticRuntimeEvent.Committed,
    expected: DecodedGs1RawSample,
): Boolean = sourceIngressId == event.ingress.ingressId &&
    sensorId == event.ingress.sensorId &&
    sensorFamily == event.ingress.sensorFamily &&
    sequence == expected.index &&
    sensorTimeEpochMs == expected.sensorTimeEpochSeconds * 1_000L &&
    phoneTimeEpochMs == event.ingress.receivedAtEpochMs &&
    packetSha256 == event.ingress.packetSha256 &&
    packetCopy().contentEquals(event.ingress.encryptedPacketCopy()) &&
    currentRaw == expected.current &&
    temperatureRaw == expected.temperature &&
    historyDistance == expected.reindex &&
    sensorTimeWasClamped == expected.sensorTimeWasClamped &&
    addTimeSeconds == expected.addTimeSeconds

private fun CommittedSensorIngressSampleRecord.toProductPublication(): Gs1ProductPublication? =
    productPublication?.let { publication ->
        Gs1ProductPublication(
            reading = publication.reading,
            approvalId = publication.approvalId,
            publicationBindingId = publication.publicationBindingId,
        )
    }

private fun Gs1ProductPublication.matchesExactly(other: Gs1ProductPublication): Boolean =
    reading == other.reading && approvalId == other.approvalId &&
        publicationBindingId == other.publicationBindingId

private fun RawSensorSampleRecord.toDecodedSampleForSettlement() = DecodedGs1RawSample(
    index = sequence,
    sensorTimeEpochSeconds = sensorTimeEpochMs / 1_000L,
    current = currentRaw,
    temperature = temperatureRaw,
    reindex = historyDistance,
    sensorTimeWasClamped = sensorTimeWasClamped,
    addTimeSeconds = addTimeSeconds,
)
