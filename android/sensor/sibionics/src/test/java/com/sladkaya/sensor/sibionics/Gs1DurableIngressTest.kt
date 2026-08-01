package com.sladkaya.sensor.sibionics

import com.sladkaya.core.data.SensorPacketIngressAppendResult
import com.sladkaya.core.data.SensorPacketIngressJournal
import com.sladkaya.core.data.SensorPacketIngressMarkHandledResult
import com.sladkaya.core.data.SensorPacketIngressOutcomeRecord
import com.sladkaya.core.data.SensorPacketIngressRecord
import com.sladkaya.core.model.SensorFamily
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class Gs1DurableIngressTest {
    @Test
    fun successfulAppendReturnsTypedPacketOnlyAfterExactEvidenceWasStored() = runBlocking {
        val journal = FakeIngressJournal()
        val ingress = Gs1DurableIngress(journal) { 1_700_000_000_000L }
        val source = byteArrayOf(1, 2, 3)

        val result = ingress.append(profile(), "attempt-7", 4, source)
            as Gs1DurableIngressResult.Stored
        source[0] = 99

        val record = journal.records.single()
        assertEquals("attempt-7:4", record.ingressId)
        assertEquals("sensor-a", record.sensorId)
        assertEquals("AA:BB:CC:DD:EE:FF", record.bluetoothAddress)
        assertEquals(4L, record.ordinal)
        assertArrayEquals(byteArrayOf(1, 2, 3), record.encryptedPacketCopy())
        assertArrayEquals(byteArrayOf(1, 2, 3), result.packet.encryptedPacketCopy())
    }

    @Test
    fun conflictAndStorageExceptionNeverProduceRuntimePacket() = runBlocking {
        val conflict = Gs1DurableIngress(
            journal = FakeIngressJournal(result = SensorPacketIngressAppendResult.Conflict("collision")),
            clock = { 1L },
        ).append(profile(), "attempt", 0, byteArrayOf(1)) as Gs1DurableIngressResult.Failed
        assertEquals("INGRESS_CONFLICT", conflict.code)
        assertTrue(!conflict.retryable)

        val unavailable = Gs1DurableIngress(
            journal = object : SensorPacketIngressJournal {
                override suspend fun append(
                    record: SensorPacketIngressRecord,
                ): SensorPacketIngressAppendResult = throw IllegalStateException("database offline")

                override suspend fun pending(
                    sensorId: String,
                    canonicalMac: String,
                ): List<SensorPacketIngressRecord> = emptyList()

                override suspend fun markHandled(
                    record: SensorPacketIngressOutcomeRecord,
                ): SensorPacketIngressMarkHandledResult =
                    SensorPacketIngressMarkHandledResult.MarkedHandled
            },
            clock = { 1L },
        ).append(profile(), "attempt", 0, byteArrayOf(1)) as Gs1DurableIngressResult.Failed
        assertEquals("INGRESS_STORAGE_UNAVAILABLE", unavailable.code)
        assertTrue(unavailable.retryable)
    }

    @Test
    fun uncertainAppendRetriesTheExactSameRecordWithoutChangingTimestampOrPacket() = runBlocking {
        val records = mutableListOf<SensorPacketIngressRecord>()
        var calls = 0
        var clockCalls = 0
        val journal = object : SensorPacketIngressJournal {
            override suspend fun append(
                record: SensorPacketIngressRecord,
            ): SensorPacketIngressAppendResult {
                records += record
                calls += 1
                if (calls == 1) throw IllegalStateException("commit outcome unknown")
                return SensorPacketIngressAppendResult.AlreadyAppended
            }

            override suspend fun pending(
                sensorId: String,
                canonicalMac: String,
            ): List<SensorPacketIngressRecord> = emptyList()

            override suspend fun markHandled(
                record: SensorPacketIngressOutcomeRecord,
            ): SensorPacketIngressMarkHandledResult =
                SensorPacketIngressMarkHandledResult.MarkedHandled
        }
        val ingress = Gs1DurableIngress(journal) {
            clockCalls += 1
            1_700_000_000_123L
        }
        val pending = (ingress.capture(
            profile(),
            attemptId = "attempt-retry",
            ordinal = 9,
            encryptedPacket = byteArrayOf(7, 8),
        ) as Gs1IngressCaptureResult.Ready).pending

        val uncertain = ingress.persist(pending) as Gs1DurableIngressResult.Failed
        val recovered = ingress.persist(pending) as Gs1DurableIngressResult.Stored

        assertEquals("INGRESS_STORAGE_UNAVAILABLE", uncertain.code)
        assertEquals(1, clockCalls)
        assertEquals(2, records.size)
        assertEquals(records[0].ingressId, records[1].ingressId)
        assertEquals(records[0].receivedAtEpochMs, records[1].receivedAtEpochMs)
        assertArrayEquals(records[0].encryptedPacketCopy(), records[1].encryptedPacketCopy())
        assertArrayEquals(byteArrayOf(7, 8), recovered.packet.encryptedPacketCopy())
    }

    private fun profile(): Gs1ActivationProfile =
        (Gs1ActivationProfile.validate(
            sensorId = "sensor-a",
            family = SensorFamily.SIBIONICS_GS1,
            bluetoothAddress = "AA:BB:CC:DD:EE:FF",
            transportVariant = 0,
            packageCode = "ABCDEFGH",
        ) as Gs1ActivationProfileValidation.Valid).profile
}

private class FakeIngressJournal(
    private val result: SensorPacketIngressAppendResult = SensorPacketIngressAppendResult.Appended,
) : SensorPacketIngressJournal {
    val records = mutableListOf<SensorPacketIngressRecord>()

    override suspend fun append(record: SensorPacketIngressRecord): SensorPacketIngressAppendResult {
        records += record
        return result
    }

    override suspend fun pending(
        sensorId: String,
        canonicalMac: String,
    ): List<SensorPacketIngressRecord> = emptyList()

    override suspend fun markHandled(
        record: SensorPacketIngressOutcomeRecord,
    ): SensorPacketIngressMarkHandledResult =
        SensorPacketIngressMarkHandledResult.MarkedHandled
}
