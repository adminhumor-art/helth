package com.sladkaya.sensor.sibionics

import com.sladkaya.core.data.SensorPacketIngressAppendResult
import com.sladkaya.core.data.SensorPacketIngressJournal
import com.sladkaya.core.data.SensorPacketIngressMarkHandledResult
import com.sladkaya.core.data.SensorPacketIngressOutcomeRecord
import com.sladkaya.core.data.SensorPacketIngressOutcomeStatus
import com.sladkaya.core.data.SensorPacketIngressRecord
import com.sladkaya.core.model.SensorFamily
import java.security.MessageDigest
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class Gs1PendingIngressRecoveryTest {
    private val codec = SibionicsPacketCodec()

    @Test
    fun orderedPendingEvidenceIsClassifiedReplayedExactlyAndMarked() = runBlocking {
        val acknowledgement = byteArrayOf(4, 0, 0, 0, 0xfc.toByte())
        val firstRaw = rawPacket(startIndex = 10, count = 1)
        val secondRaw = rawPacket(startIndex = 11, count = 1)
        val journal = FakeRecoveryJournal(
            listOf(
                record(0, acknowledgement),
                record(1, firstRaw),
                record(2, secondRaw),
            ),
        )
        val replayed = mutableListOf<ByteArray>()
        val recovery = recovery(journal) { packet ->
            val bytes = packet.encryptedPacketCopy()
            replayed += bytes
            val decoded = codec.decode(SensorFamily.SIBIONICS_GS1, bytes)
                as DecodedPacket.Gs1RawSamples
            Gs1RuntimeAwaitResult.Processed(
                Gs1PacketProcessingResult.Completed(
                    readings = emptyList(),
                    committedSamples = decoded.values,
                ),
            )
        }

        val result = recovery.recover(profile(), generation = 7L, initialCoreCursor = 10)
            as Gs1PendingIngressRecoveryResult.Completed

        assertEquals(12, result.finalCoreCursor)
        assertEquals(3, result.handledRecords)
        assertEquals(null, result.blocked)
        assertEquals(
            listOf(
                SensorPacketIngressOutcomeStatus.NON_DATA,
                SensorPacketIngressOutcomeStatus.CORE_COMMITTED,
                SensorPacketIngressOutcomeStatus.CORE_COMMITTED,
            ),
            journal.outcomes.map { it.status },
        )
        assertArrayEquals(firstRaw, replayed[0])
        assertArrayEquals(secondRaw, replayed[1])
    }

    @Test
    fun laterExactPacketClosesEarlierGapBeforeLiveConnection() = runBlocking {
        val journal = FakeRecoveryJournal(
            listOf(
                record(0, rawPacket(startIndex = 11, count = 1)),
                record(1, rawPacket(startIndex = 10, count = 1)),
            ),
        )
        val replayedIndices = mutableListOf<Int>()
        val result = recovery(journal) { packet ->
            val decoded = codec.decode(
                SensorFamily.SIBIONICS_GS1,
                packet.encryptedPacketCopy(),
            ) as DecodedPacket.Gs1RawSamples
            replayedIndices += decoded.values.single().index
            Gs1RuntimeAwaitResult.Processed(
                Gs1PacketProcessingResult.Completed(
                    readings = emptyList(),
                    committedSamples = decoded.values,
                ),
            )
        }.recover(profile(), generation = 1L, initialCoreCursor = 10)
            as Gs1PendingIngressRecoveryResult.Completed

        assertEquals(12, result.finalCoreCursor)
        assertEquals(null, result.blocked)
        assertEquals(listOf(10, 11), replayedIndices)
        assertEquals(2, journal.outcomes.size)
    }

    @Test
    fun unfillableGapRemainsPendingForLiveHistoryBackfill() = runBlocking {
        val journal = FakeRecoveryJournal(
            listOf(record(0, rawPacket(startIndex = 11, count = 1))),
        )
        val result = recovery(journal) { error("gap must not reach the core") }
            .recover(profile(), generation = 1L, initialCoreCursor = 10)
            as Gs1PendingIngressRecoveryResult.Completed

        assertEquals(10, result.finalCoreCursor)
        assertEquals(Gs1PendingIngressRecoveryDisposition.BLOCKED_BY_GAP, result.blocked?.disposition)
        assertTrue(journal.outcomes.isEmpty())
    }

    @Test
    fun unsupportedProtocolStopsRecoveryBeforeAnyLaterRawPacket() = runBlocking {
        val plainUnsupported = byteArrayOf(5, 0x55, 0, 0, 0, 0xa6.toByte())
        val journal = FakeRecoveryJournal(
            listOf(
                record(0, codec.encryptForTest(plainUnsupported)),
                record(1, rawPacket(startIndex = 10, count = 1)),
            ),
        )
        var replayCalls = 0

        val result = recovery(journal) {
            replayCalls += 1
            error("unsupported protocol must stop recovery before replay")
        }.recover(profile(), generation = 1L, initialCoreCursor = 10)
            as Gs1PendingIngressRecoveryResult.Completed

        assertEquals(10, result.finalCoreCursor)
        assertEquals(
            Gs1PendingIngressRecoveryDisposition.UNSUPPORTED_PROTOCOL,
            result.blocked?.disposition,
        )
        assertEquals(0, replayCalls)
        assertTrue(journal.outcomes.isEmpty())
    }

    @Test
    fun invalidAndAlreadyCoveredRecordsGetDurableTerminalOutcomesWithoutReplay() = runBlocking {
        val covered = rawPacket(startIndex = 8, count = 2)
        val invalid = rawPacket(startIndex = 10, count = 1).also {
            it[it.lastIndex] = (it.last() + 1).toByte()
        }
        val journal = FakeRecoveryJournal(listOf(record(0, covered), record(1, invalid)))

        val result = recovery(journal) { error("neither record is replayable") }
            .recover(profile(), generation = 1L, initialCoreCursor = 10)
            as Gs1PendingIngressRecoveryResult.Completed

        assertEquals(10, result.finalCoreCursor)
        assertEquals(
            listOf(
                SensorPacketIngressOutcomeStatus.ALREADY_COVERED,
                SensorPacketIngressOutcomeStatus.QUARANTINED,
            ),
            journal.outcomes.map { it.status },
        )
        assertEquals(journal.records[0].receivedAtEpochMs, journal.outcomes[0].handledAtEpochMs)
        assertEquals(journal.records[1].receivedAtEpochMs, journal.outcomes[1].handledAtEpochMs)
    }

    @Test
    fun partialOverlapRemainsPendingForLiveHistoryBackfill() = runBlocking {
        val journal = FakeRecoveryJournal(listOf(record(0, rawPacket(startIndex = 9, count = 2))))

        val result = recovery(journal) { error("partial overlap must not be replayed") }
            .recover(profile(), generation = 1L, initialCoreCursor = 10)
            as Gs1PendingIngressRecoveryResult.Completed

        assertEquals(Gs1PendingIngressRecoveryDisposition.PARTIAL_OVERLAP, result.blocked?.disposition)
        assertTrue(journal.outcomes.isEmpty())
    }

    @Test
    fun coreRejectionLeavesEvidencePendingAndFailsClosed() = runBlocking {
        val journal = FakeRecoveryJournal(listOf(record(0, rawPacket(startIndex = 10, count = 1))))
        val result = recovery(journal) {
            Gs1RuntimeAwaitResult.Processed(
                Gs1PacketProcessingResult.Rejected(
                    code = "NATIVE_REJECTED",
                    message = "state mismatch",
                ),
            )
        }.recover(profile(), generation = 1L, initialCoreCursor = 10)
            as Gs1PendingIngressRecoveryResult.Failed

        assertEquals("RECOVERY_CORE_REJECTED", result.code)
        assertTrue(!result.retryable)
        assertTrue(journal.outcomes.isEmpty())
    }

    @Test
    fun coreInvalidPacketIsDurablyQuarantinedInsteadOfBlockingEveryRestart() = runBlocking {
        val journal = FakeRecoveryJournal(listOf(record(0, rawPacket(startIndex = 10, count = 1))))
        val result = recovery(journal) {
            Gs1RuntimeAwaitResult.Processed(
                Gs1PacketProcessingResult.InvalidPacket(
                    error = Gs1VerifiedPacketError.WIRE_PACKET_INVALID,
                    detail = "strict verifier rejected compatibility decode",
                ),
            )
        }.recover(profile(), generation = 1L, initialCoreCursor = 10)
            as Gs1PendingIngressRecoveryResult.Completed

        assertEquals(10, result.finalCoreCursor)
        assertEquals(1, result.handledRecords)
        assertEquals(SensorPacketIngressOutcomeStatus.QUARANTINED, journal.outcomes.single().status)
    }

    @Test
    fun uncertainOutcomeWriteCanBeRetriedWithByteIdenticalOutcomeRecord() = runBlocking {
        val journal = FakeRecoveryJournal(
            records = listOf(record(0, byteArrayOf(4, 0, 0, 0, 0xfc.toByte()))),
            mark = { outcome ->
                if (outcomes.isEmpty()) {
                    outcomes += outcome
                    throw IllegalStateException("commit response lost")
                }
                assertEquals(outcomes.single(), outcome)
                SensorPacketIngressMarkHandledResult.AlreadyHandled
            },
        )
        val first = recovery(journal) { error("ack is non-data") }
            .recover(profile(), generation = 1L, initialCoreCursor = 10)
        val second = recovery(journal) { error("ack is non-data") }
            .recover(profile(), generation = 2L, initialCoreCursor = 10)

        assertEquals("RECOVERY_OUTCOME_STORAGE_UNAVAILABLE", (first as Gs1PendingIngressRecoveryResult.Failed).code)
        assertTrue(second is Gs1PendingIngressRecoveryResult.Completed)
        assertEquals(1, journal.outcomes.size)
    }

    private fun recovery(
        journal: SensorPacketIngressJournal,
        replay: suspend (DurablyJournaledGs1Packet) -> Gs1RuntimeAwaitResult,
    ) = Gs1PendingIngressRecovery(
        journal = journal,
        codec = codec,
        replay = { _, packet -> replay(packet) },
    )

    private fun profile(): Gs1ActivationProfile =
        (Gs1ActivationProfile.validate(
            sensorId = "sensor-a",
            family = SensorFamily.SIBIONICS_GS1,
            bluetoothAddress = "AA:BB:CC:DD:EE:FF",
            transportVariant = 0,
            packageCode = "ABCDEFGH",
        ) as Gs1ActivationProfileValidation.Valid).profile

    private fun record(ordinal: Long, packet: ByteArray) = SensorPacketIngressRecord(
        ingressId = "attempt-a:$ordinal",
        sensorId = "sensor-a",
        sensorFamily = SensorFamily.SIBIONICS_GS1,
        bluetoothAddress = "AA:BB:CC:DD:EE:FF",
        attemptId = "attempt-a",
        ordinal = ordinal,
        receivedAtEpochMs = 1_700_000_000_000L + ordinal,
        encryptedPacket = packet,
        packetSha256 = packet.sha256ForRecoveryTest(),
    )

    private fun rawPacket(startIndex: Int, count: Int): ByteArray {
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

private class FakeRecoveryJournal(
    val records: List<SensorPacketIngressRecord>,
    private val mark: suspend FakeRecoveryJournal.(SensorPacketIngressOutcomeRecord) ->
        SensorPacketIngressMarkHandledResult = { outcome ->
            outcomes += outcome
            SensorPacketIngressMarkHandledResult.MarkedHandled
        },
) : SensorPacketIngressJournal {
    val outcomes = mutableListOf<SensorPacketIngressOutcomeRecord>()

    override suspend fun append(record: SensorPacketIngressRecord) =
        SensorPacketIngressAppendResult.Appended

    override suspend fun pending(sensorId: String, canonicalMac: String) = records

    override suspend fun markHandled(record: SensorPacketIngressOutcomeRecord) = mark(record)
}

private fun ByteArray.sha256ForRecoveryTest(): String = MessageDigest.getInstance("SHA-256")
    .digest(this)
    .joinToString(separator = "") { byte -> "%02x".format(byte) }
