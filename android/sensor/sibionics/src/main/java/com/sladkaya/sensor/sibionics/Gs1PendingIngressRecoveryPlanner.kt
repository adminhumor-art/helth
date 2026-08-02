package com.sladkaya.sensor.sibionics

import com.sladkaya.core.data.SensorPacketIngressRecord
import com.sladkaya.core.model.SensorFamily

internal enum class Gs1PendingIngressRecoveryDisposition {
    NON_DATA,
    QUARANTINE_INVALID,
    ALREADY_COVERED,
    RESOLVE_EXACT,
    REPLAY_EXACT,
    BLOCKED_BY_GAP,
    PARTIAL_OVERLAP,
    UNSUPPORTED_PROTOCOL,
}

/**
 * One immutable decision for one durable ingress record. The original record is
 * retained so a replay executor can submit its exact encrypted bytes without
 * rebuilding a transport envelope.
 */
internal data class Gs1PendingIngressRecoveryEntry(
    val record: SensorPacketIngressRecord,
    val disposition: Gs1PendingIngressRecoveryDisposition,
    val projectedCursorBefore: Int,
    val projectedCursorAfter: Int,
    val firstIndex: Int? = null,
    val lastIndex: Int? = null,
    val expectedSamples: List<DecodedGs1RawSample> = emptyList(),
    val detail: String? = null,
) {
    fun encryptedPacketCopy(): ByteArray = record.encryptedPacketCopy()
}

/**
 * Pure recovery planner for an already ordered append-only ingress stream.
 *
 * The cursor is projected forward only for exact, non-overlapping raw batches.
 * No packet is decrypted and re-encrypted for output: every decision keeps the
 * original [SensorPacketIngressRecord]. Execution and processed marking remain
 * outside this planner.
 */
internal class Gs1PendingIngressRecoveryPlanner(
    private val family: SensorFamily,
    private val codec: SibionicsPacketCodec,
    private val wireProfile: Gs1WireProfile,
) {
    init {
        require(family == SensorFamily.SIBIONICS_GS1 || family == SensorFamily.SIBIONICS_GS1SB)
    }

    fun plan(
        currentCoreCursor: Int,
        orderedRecords: List<SensorPacketIngressRecord>,
    ): List<Gs1PendingIngressRecoveryEntry> {
        require(currentCoreCursor in FIRST_SENSOR_INDEX..CURSOR_AFTER_LAST_SENSOR_INDEX)
        var projectedCursor = currentCoreCursor

        return orderedRecords.map { record ->
            val cursorBefore = projectedCursor
            val decision = classify(record, cursorBefore)
            if (decision.disposition == Gs1PendingIngressRecoveryDisposition.REPLAY_EXACT) {
                projectedCursor = checkNotNull(decision.lastIndex) + 1
            }
            decision.copy(projectedCursorAfter = projectedCursor)
        }
    }

    private fun classify(
        record: SensorPacketIngressRecord,
        projectedCursor: Int,
    ): Gs1PendingIngressRecoveryEntry {
        if (record.sensorFamily != family) {
            return entry(
                record = record,
                disposition = Gs1PendingIngressRecoveryDisposition.QUARANTINE_INVALID,
                cursor = projectedCursor,
                detail = "Ingress sensor family does not match the recovery family",
            )
        }

        val bytes = record.encryptedPacketCopy()
        if (wireProfile == Gs1WireProfile.UNRESOLVED) {
            if (Gs1V115WireCodec.isV120Challenge(bytes)) {
                return entry(
                    record,
                    Gs1PendingIngressRecoveryDisposition.RESOLVE_EXACT,
                    projectedCursor,
                    detail = "EXACT_V120_CHALLENGE",
                )
            }
            return when (
                val decoded = Gs1V115WireCodec.decode(bytes, record.receivedAtEpochMs)
            ) {
                is Gs1V115DecodeResult.Success -> if (decoded.records.isEmpty()) {
                    entry(
                        record,
                        Gs1PendingIngressRecoveryDisposition.RESOLVE_EXACT,
                        projectedCursor,
                        detail = "VALIDATED_V115_ENVELOPE",
                    )
                } else {
                    classifyRaw(record, decoded.records.map { it.sample }, projectedCursor)
                }
                is Gs1V115DecodeResult.Failure -> entry(
                    record,
                    Gs1PendingIngressRecoveryDisposition.UNSUPPORTED_PROTOCOL,
                    projectedCursor,
                    detail = "Unresolved packet is neither an exact challenge nor a valid V115 envelope",
                )
            }
        }
        if (wireProfile == Gs1WireProfile.V115) {
            if (Gs1V115WireCodec.isV120Challenge(bytes)) {
                return entry(
                    record,
                    Gs1PendingIngressRecoveryDisposition.UNSUPPORTED_PROTOCOL,
                    projectedCursor,
                    detail = "V120 evidence conflicts with durable V115 binding",
                )
            }
            return when (
                val decoded = Gs1V115WireCodec.decode(bytes, record.receivedAtEpochMs)
            ) {
                is Gs1V115DecodeResult.Success -> classifyRaw(
                    record,
                    decoded.records.map { it.sample },
                    projectedCursor,
                )
                is Gs1V115DecodeResult.Failure -> entry(
                    record,
                    Gs1PendingIngressRecoveryDisposition.QUARANTINE_INVALID,
                    projectedCursor,
                    detail = "V115_${decoded.error.name}",
                )
            }
        }
        if (Gs1V115WireCodec.isV120Challenge(bytes)) {
            return entry(
                record,
                Gs1PendingIngressRecoveryDisposition.NON_DATA,
                projectedCursor,
                detail = "V120 binding already covers the exact protocol challenge",
            )
        }
        val oppositeV115 = Gs1V115WireCodec.decode(bytes, record.receivedAtEpochMs)
        if (oppositeV115 is Gs1V115DecodeResult.Success) {
            return entry(
                record,
                Gs1PendingIngressRecoveryDisposition.UNSUPPORTED_PROTOCOL,
                projectedCursor,
                detail = "V115 evidence conflicts with durable V120 binding",
            )
        }
        return when (val decoded = codec.decode(family, bytes)) {
            is DecodedPacket.Invalid -> entry(
                record = record,
                disposition = Gs1PendingIngressRecoveryDisposition.QUARANTINE_INVALID,
                cursor = projectedCursor,
                detail = decoded.reason,
            )

            is DecodedPacket.Gs1RawSamples -> classifyRaw(record, decoded.values, projectedCursor)

            is DecodedPacket.Acknowledgement,
            is DecodedPacket.DeviceInformation,
            -> entry(
                record = record,
                disposition = Gs1PendingIngressRecoveryDisposition.NON_DATA,
                cursor = projectedCursor,
            )

            is DecodedPacket.Gs3GlucoseSamples -> entry(
                record = record,
                disposition = Gs1PendingIngressRecoveryDisposition.UNSUPPORTED_PROTOCOL,
                cursor = projectedCursor,
                detail = "Unexpected GS3 data in a GS1 recovery stream",
            )

            is DecodedPacket.Unsupported -> entry(
                record = record,
                disposition = Gs1PendingIngressRecoveryDisposition.UNSUPPORTED_PROTOCOL,
                cursor = projectedCursor,
                detail = "Unsupported protocol command ${decoded.command}",
            )
        }
    }

    private fun classifyRaw(
        record: SensorPacketIngressRecord,
        samples: List<DecodedGs1RawSample>,
        projectedCursor: Int,
    ): Gs1PendingIngressRecoveryEntry {
        if (samples.isEmpty()) {
            return entry(
                record = record,
                disposition = Gs1PendingIngressRecoveryDisposition.NON_DATA,
                cursor = projectedCursor,
                detail = "GS1 data envelope contains no samples",
            )
        }

        val first = samples.first().index
        val last = samples.last().index
        val disposition = when {
            last < projectedCursor -> Gs1PendingIngressRecoveryDisposition.ALREADY_COVERED
            first == projectedCursor -> Gs1PendingIngressRecoveryDisposition.REPLAY_EXACT
            first > projectedCursor -> Gs1PendingIngressRecoveryDisposition.BLOCKED_BY_GAP
            else -> Gs1PendingIngressRecoveryDisposition.PARTIAL_OVERLAP
        }
        return entry(
            record = record,
            disposition = disposition,
            cursor = projectedCursor,
            firstIndex = first,
            lastIndex = last,
            expectedSamples = samples,
        )
    }

    private fun entry(
        record: SensorPacketIngressRecord,
        disposition: Gs1PendingIngressRecoveryDisposition,
        cursor: Int,
        firstIndex: Int? = null,
        lastIndex: Int? = null,
        expectedSamples: List<DecodedGs1RawSample> = emptyList(),
        detail: String? = null,
    ) = Gs1PendingIngressRecoveryEntry(
        record = record,
        disposition = disposition,
        projectedCursorBefore = cursor,
        projectedCursorAfter = cursor,
        firstIndex = firstIndex,
        lastIndex = lastIndex,
        expectedSamples = expectedSamples.toList(),
        detail = detail,
    )

    private companion object {
        const val FIRST_SENSOR_INDEX = 1
        const val CURSOR_AFTER_LAST_SENSOR_INDEX = 0x1_0000
    }
}
