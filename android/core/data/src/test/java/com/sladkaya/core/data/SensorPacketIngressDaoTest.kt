package com.sladkaya.core.data

import com.sladkaya.core.model.SensorFamily
import java.security.MessageDigest
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SensorPacketIngressDaoTest {
    @Test
    fun firstAppendIsCommittedAndExactRetryIsIdempotent() = runBlocking {
        val dao = RecordingIngressDao()
        val entity = record().toEntity()

        assertEquals(SensorPacketIngressDisposition.APPENDED, dao.append(entity))
        assertEquals(SensorPacketIngressDisposition.ALREADY_APPENDED, dao.append(entity.copy()))
        assertEquals(1, dao.values.size)
    }

    @Test
    fun sameIngressIdWithDifferentContentsIsConflict() = runBlocking {
        val journal = RoomSensorPacketIngressJournal(RecordingIngressDao())

        assertEquals(SensorPacketIngressAppendResult.Appended, journal.append(record()))
        val conflict = journal.append(record(packet = byteArrayOf(9, 8, 7)))

        assertEquals(
            SensorPacketIngressAppendResult.Conflict("Ingress identity conflicts with different contents"),
            conflict,
        )
    }

    @Test
    fun sameAttemptOrdinalWithDifferentIngressIdIsConflict() = runBlocking {
        val journal = RoomSensorPacketIngressJournal(RecordingIngressDao())

        journal.append(record(ingressId = "ingress-a"))
        val conflict = journal.append(record(ingressId = "ingress-b"))

        assertEquals(
            SensorPacketIngressAppendResult.Conflict("Ingress identity conflicts with different contents"),
            conflict,
        )
    }

    @Test
    fun repositoryReturnsAlreadyAppendedForExactRetry() = runBlocking {
        val journal = RoomSensorPacketIngressJournal(RecordingIngressDao())

        journal.append(record())

        assertEquals(SensorPacketIngressAppendResult.AlreadyAppended, journal.append(record()))
    }

    @Test
    fun appendedIngressIsPendingUntilOutcomeIsAppended() = runBlocking {
        val journal = RoomSensorPacketIngressJournal(RecordingIngressDao())
        val ingress = record()

        journal.append(ingress)
        assertEquals(listOf(ingress.ingressId), journal.pending(ingress.sensorId, ingress.bluetoothAddress).map { it.ingressId })

        assertEquals(
            SensorPacketIngressMarkHandledResult.MarkedHandled,
            journal.markHandled(outcome(ingress.ingressId)),
        )
        assertTrue(journal.pending(ingress.sensorId, ingress.bluetoothAddress).isEmpty())
    }

    @Test
    fun exactOutcomeRetryIsIdempotentButDifferentOutcomeConflicts() = runBlocking {
        val journal = RoomSensorPacketIngressJournal(RecordingIngressDao())
        journal.append(record())
        val outcome = outcome("ingress-a")

        assertEquals(SensorPacketIngressMarkHandledResult.MarkedHandled, journal.markHandled(outcome))
        assertEquals(SensorPacketIngressMarkHandledResult.AlreadyHandled, journal.markHandled(outcome))
        assertEquals(
            SensorPacketIngressMarkHandledResult.Conflict("Ingress outcome conflicts with existing outcome"),
            journal.markHandled(outcome.copy(status = SensorPacketIngressOutcomeStatus.QUARANTINED)),
        )
    }

    @Test
    fun outcomeForUnknownIngressIsRejected() = runBlocking {
        val journal = RoomSensorPacketIngressJournal(RecordingIngressDao())

        assertEquals(
            SensorPacketIngressMarkHandledResult.Conflict("Ingress does not exist"),
            journal.markHandled(outcome("missing")),
        )
    }

    @Test
    fun pendingIsOrderedAndIsolatedBySensorAndCanonicalMac() = runBlocking {
        val journal = RoomSensorPacketIngressJournal(RecordingIngressDao())
        listOf(
            record(ingressId = "late", attemptId = "attempt-b", ordinal = 0, receivedAtEpochMs = 20),
            record(ingressId = "ordinal-2", attemptId = "attempt-a", ordinal = 2, receivedAtEpochMs = 10),
            record(ingressId = "ordinal-1", attemptId = "attempt-a", ordinal = 1, receivedAtEpochMs = 10),
            record(ingressId = "other-sensor", sensorId = "sensor-b", attemptId = "attempt-c", receivedAtEpochMs = 1),
            record(
                ingressId = "other-mac",
                bluetoothAddress = "11:22:33:44:55:66",
                attemptId = "attempt-d",
                receivedAtEpochMs = 1,
            ),
        ).forEach { journal.append(it) }

        assertEquals(
            listOf("ordinal-1", "ordinal-2", "late"),
            journal.pending("sensor-a", "AA:BB:CC:DD:EE:FF").map { it.ingressId },
        )
    }

    private fun record(
        ingressId: String = "ingress-a",
        sensorId: String = "sensor-a",
        bluetoothAddress: String = "AA:BB:CC:DD:EE:FF",
        attemptId: String = "attempt-a",
        ordinal: Long = 0,
        receivedAtEpochMs: Long = 1_700_000_000_000L,
        packet: ByteArray = byteArrayOf(1, 2, 3),
    ) = SensorPacketIngressRecord(
        ingressId = ingressId,
        sensorId = sensorId,
        sensorFamily = SensorFamily.SIBIONICS_GS1,
        bluetoothAddress = bluetoothAddress,
        attemptId = attemptId,
        ordinal = ordinal,
        receivedAtEpochMs = receivedAtEpochMs,
        encryptedPacket = packet,
        packetSha256 = packet.sha256(),
    )

    private fun outcome(ingressId: String) = SensorPacketIngressOutcomeRecord(
        ingressId = ingressId,
        status = SensorPacketIngressOutcomeStatus.CORE_COMMITTED,
        handledAtEpochMs = 1_700_000_001_000L,
        detail = "durably committed",
    )

    private fun ByteArray.sha256(): String = MessageDigest.getInstance("SHA-256")
        .digest(this)
        .joinToString("") { "%02x".format(it) }
}

private class RecordingIngressDao : SensorPacketIngressDao() {
    val values = mutableListOf<SensorPacketIngressEntity>()
    private val outcomes = mutableListOf<SensorPacketIngressOutcomeEntity>()

    override suspend fun insert(value: SensorPacketIngressEntity): Long {
        if (values.any { it.ingressId == value.ingressId ||
                it.attemptId == value.attemptId && it.ordinal == value.ordinal
            }
        ) return -1L
        values += value.copy(encryptedPacket = value.encryptedPacket.copyOf())
        return values.size.toLong()
    }

    override suspend fun byIngressId(ingressId: String): SensorPacketIngressEntity? =
        values.firstOrNull { it.ingressId == ingressId }

    override suspend fun byAttemptOrdinal(attemptId: String, ordinal: Long): SensorPacketIngressEntity? =
        values.firstOrNull { it.attemptId == attemptId && it.ordinal == ordinal }

    override suspend fun insertOutcome(value: SensorPacketIngressOutcomeEntity): Long {
        if (outcomes.any { it.ingressId == value.ingressId }) return -1L
        outcomes += value
        return outcomes.size.toLong()
    }

    override suspend fun outcomeByIngressId(ingressId: String): SensorPacketIngressOutcomeEntity? =
        outcomes.firstOrNull { it.ingressId == ingressId }

    override suspend fun pending(sensorId: String, bluetoothAddress: String): List<SensorPacketIngressEntity> =
        values.filter { ingress ->
            ingress.sensorId == sensorId &&
                ingress.bluetoothAddress == bluetoothAddress &&
                outcomes.none { it.ingressId == ingress.ingressId }
        }.sortedWith(compareBy({ it.receivedAtEpochMs }, { it.attemptId }, { it.ordinal }))
}
