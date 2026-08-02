package com.sladkaya.sensor.sibionics

import com.sladkaya.core.data.SensorPacketIngressRecord
import com.sladkaya.core.model.SensorFamily
import java.security.MessageDigest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class Gs1PendingIngressRecoveryPlannerTest {
    private val codec = SibionicsPacketCodec()

    @Test
    fun validNonDataAndEmptyRawEnvelopesAreNotReplayed() {
        val planner = planner()

        val decisions = planner.plan(
            currentCoreCursor = 10,
            orderedRecords = listOf(
                record(0, byteArrayOf(4, 0, 0, 0, 0xfc.toByte())),
                record(1, rawPacket(startIndex = 10, count = 0)),
            ),
        )

        assertEquals(
            listOf(
                Gs1PendingIngressRecoveryDisposition.NON_DATA,
                Gs1PendingIngressRecoveryDisposition.NON_DATA,
            ),
            decisions.map { it.disposition },
        )
    }

    @Test
    fun invalidWirePacketIsQuarantinedWithoutChangingFollowingCursor() {
        val exact = rawPacket(startIndex = 10, count = 1)
        val corrupted = exact.copyOf().also { it[it.lastIndex] = (it.last() + 1).toByte() }

        val decisions = planner().plan(
            currentCoreCursor = 10,
            orderedRecords = listOf(record(0, corrupted), record(1, exact)),
        )

        assertEquals(Gs1PendingIngressRecoveryDisposition.QUARANTINE_INVALID, decisions[0].disposition)
        assertTrue(!decisions[0].detail.isNullOrBlank())
        assertEquals(Gs1PendingIngressRecoveryDisposition.REPLAY_EXACT, decisions[1].disposition)
    }

    @Test
    fun packetsEntirelyBeforeCursorAreAlreadyCovered() {
        val decision = planner().plan(
            currentCoreCursor = 12,
            orderedRecords = listOf(record(0, rawPacket(startIndex = 10, count = 2))),
        ).single()

        assertEquals(Gs1PendingIngressRecoveryDisposition.ALREADY_COVERED, decision.disposition)
        assertEquals(10, decision.firstIndex)
        assertEquals(11, decision.lastIndex)
    }

    @Test
    fun exactContiguousPacketsAdvanceOnlyTheProjectedRecoveryCursor() {
        val firstPacket = rawPacket(startIndex = 10, count = 2)
        val secondPacket = rawPacket(startIndex = 12, count = 1)

        val decisions = planner().plan(
            currentCoreCursor = 10,
            orderedRecords = listOf(record(0, firstPacket), record(1, secondPacket)),
        )

        assertEquals(
            listOf(
                Gs1PendingIngressRecoveryDisposition.REPLAY_EXACT,
                Gs1PendingIngressRecoveryDisposition.REPLAY_EXACT,
            ),
            decisions.map { it.disposition },
        )
        assertArrayEquals(firstPacket, decisions[0].encryptedPacketCopy())
        assertArrayEquals(secondPacket, decisions[1].encryptedPacketCopy())
        assertEquals(12, decisions[0].projectedCursorAfter)
        assertEquals(13, decisions[1].projectedCursorAfter)
    }

    @Test
    fun duplicateAfterExactReplayIsAlreadyCovered() {
        val packet = rawPacket(startIndex = 10, count = 2)

        val decisions = planner().plan(
            currentCoreCursor = 10,
            orderedRecords = listOf(record(0, packet), record(1, packet)),
        )

        assertEquals(Gs1PendingIngressRecoveryDisposition.REPLAY_EXACT, decisions[0].disposition)
        assertEquals(Gs1PendingIngressRecoveryDisposition.ALREADY_COVERED, decisions[1].disposition)
        assertEquals(12, decisions[1].projectedCursorAfter)
    }

    @Test
    fun packetStartingAfterCursorIsBlockedByGap() {
        val decision = planner().plan(
            currentCoreCursor = 10,
            orderedRecords = listOf(record(0, rawPacket(startIndex = 11, count = 1))),
        ).single()

        assertEquals(Gs1PendingIngressRecoveryDisposition.BLOCKED_BY_GAP, decision.disposition)
        assertEquals(10, decision.projectedCursorBefore)
        assertEquals(10, decision.projectedCursorAfter)
    }

    @Test
    fun packetStraddlingCursorIsBlockedAsPartialOverlap() {
        val decision = planner().plan(
            currentCoreCursor = 11,
            orderedRecords = listOf(record(0, rawPacket(startIndex = 10, count = 2))),
        ).single()

        assertEquals(Gs1PendingIngressRecoveryDisposition.PARTIAL_OVERLAP, decision.disposition)
        assertEquals(10, decision.firstIndex)
        assertEquals(11, decision.lastIndex)
        assertEquals(11, decision.projectedCursorAfter)
    }

    @Test
    fun lastU16SampleCanBePlannedWithoutCursorOverflow() {
        val decision = planner().plan(
            currentCoreCursor = 0xffff,
            orderedRecords = listOf(record(0, rawPacket(startIndex = 0xffff, count = 1))),
        ).single()

        assertEquals(Gs1PendingIngressRecoveryDisposition.REPLAY_EXACT, decision.disposition)
        assertEquals(0x1_0000, decision.projectedCursorAfter)
    }

    @Test
    fun cursorBoundsIncludeOnePastTheFinalSensorIndexOnly() {
        val planner = planner()

        assertTrue(planner.plan(0x1_0000, emptyList()).isEmpty())
        assertThrows(IllegalArgumentException::class.java) {
            planner.plan(0, emptyList())
        }
        assertThrows(IllegalArgumentException::class.java) {
            planner.plan(0x1_0001, emptyList())
        }
    }

    @Test
    fun gs1SbUsesTheSameExactRecoveryRules() {
        val packet = rawPacket(startIndex = 1, count = 1)
        val decision = Gs1PendingIngressRecoveryPlanner(
            family = SensorFamily.SIBIONICS_GS1SB,
            codec = codec,
            wireProfile = Gs1WireProfile.V120,
        ).plan(
            currentCoreCursor = 1,
            orderedRecords = listOf(record(0, packet, SensorFamily.SIBIONICS_GS1SB)),
        ).single()

        assertEquals(Gs1PendingIngressRecoveryDisposition.REPLAY_EXACT, decision.disposition)
        assertArrayEquals(packet, decision.encryptedPacketCopy())
    }

    @Test
    fun recordFromAnotherFamilyIsQuarantinedBeforeDecode() {
        val decision = planner().plan(
            currentCoreCursor = 1,
            orderedRecords = listOf(
                record(
                    ordinal = 0,
                    packet = rawPacket(startIndex = 1, count = 1),
                    family = SensorFamily.SIBIONICS_GS1SB,
                ),
            ),
        ).single()

        assertEquals(Gs1PendingIngressRecoveryDisposition.QUARANTINE_INVALID, decision.disposition)
        assertTrue(decision.detail?.contains("family", ignoreCase = true) == true)
    }

    @Test
    fun validUnknownCommandRemainsPendingForProtocolReview() {
        val plain = byteArrayOf(5, 0x55, 0, 0, 0, 0xa6.toByte())
        val unknown = codec.encryptForTest(plain)

        val decision = planner().plan(
            currentCoreCursor = 10,
            orderedRecords = listOf(record(0, unknown)),
        ).single()

        assertEquals(
            Gs1PendingIngressRecoveryDisposition.UNSUPPORTED_PROTOCOL,
            decision.disposition,
        )
    }

    private fun planner() = Gs1PendingIngressRecoveryPlanner(
        family = SensorFamily.SIBIONICS_GS1,
        codec = codec,
        wireProfile = Gs1WireProfile.V120,
    )

    private fun record(
        ordinal: Long,
        packet: ByteArray,
        family: SensorFamily = SensorFamily.SIBIONICS_GS1,
    ) = SensorPacketIngressRecord(
        ingressId = "attempt-a:$ordinal",
        sensorId = "sensor-a",
        sensorFamily = family,
        bluetoothAddress = "AA:BB:CC:DD:EE:FF",
        attemptId = "attempt-a",
        ordinal = ordinal,
        receivedAtEpochMs = 1_700_000_000_000L + ordinal,
        encryptedPacket = packet,
        packetSha256 = packet.sha256ForTest(),
    )

    private fun rawPacket(startIndex: Int, count: Int): ByteArray {
        require(startIndex in 0..0xffff)
        require(count in 0..29)
        require(count == 0 || startIndex + count - 1 <= 0xffff)
        val length = 11 + count * 8
        val plain = ByteArray(length + 1)
        plain[0] = length.toByte()
        plain[1] = 0x08
        plain[2] = count.toByte()
        plain.putU16Le(3, startIndex)
        plain.putU32Le(5, 1_700_000_000L)
        repeat(count) { position ->
            val offset = 9 + position * 8
            plain.putU16Le(offset, 320 + position)
            plain.putU16Le(offset + 4, 50 + position)
        }
        plain.putU16Le(9 + count * 8, 0)
        plain[length] = SibionicsPacketCodec.checksum(plain, length)
        return codec.encryptForTest(plain)
    }

    private fun ByteArray.putU16Le(offset: Int, value: Int) {
        this[offset] = value.toByte()
        this[offset + 1] = (value ushr 8).toByte()
    }

    private fun ByteArray.putU32Le(offset: Int, value: Long) {
        repeat(4) { byteIndex ->
            this[offset + byteIndex] = (value ushr (byteIndex * 8)).toByte()
        }
    }
}

private fun ByteArray.sha256ForTest(): String = MessageDigest.getInstance("SHA-256")
    .digest(this)
    .joinToString(separator = "") { byte -> "%02x".format(byte) }
