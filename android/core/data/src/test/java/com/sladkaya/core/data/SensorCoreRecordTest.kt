package com.sladkaya.core.data

import com.sladkaya.core.model.GlucoseReading
import com.sladkaya.core.model.ReadingQuality
import com.sladkaya.core.model.SensorFamily
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import java.security.MessageDigest

class SensorCoreRecordTest {
    @Test
    fun rawPacketIsCopiedAtThePublicBoundary() {
        val packet = byteArrayOf(1, 2, 3, 4)
        val raw = raw(packet = packet)

        packet[0] = 99
        val firstRead = raw.packetCopy()
        firstRead[1] = 88

        assertArrayEquals(byteArrayOf(1, 2, 3, 4), raw.packetCopy())
    }

    @Test
    fun atomicRecordRejectsAnyCrossComponentIdentityMismatch() {
        val raw = raw()
        val result = result()
        val checkpoint = checkpoint()

        assertThrows(IllegalArgumentException::class.java) {
            AtomicSensorCoreRecord(raw, result.copy(sequence = result.sequence + 1), checkpoint, reading())
        }
        assertThrows(IllegalArgumentException::class.java) {
            AtomicSensorCoreRecord(raw, result, checkpoint.copy(sensorId = "another"), reading())
        }
        assertThrows(IllegalArgumentException::class.java) {
            AtomicSensorCoreRecord(raw, result, checkpoint, reading().copy(eventId = "another"))
        }
    }

    @Test
    fun entityBundlePreservesRawAndAlgorithmProvenance() {
        val record = AtomicSensorCoreRecord(raw(), result(), checkpoint(), reading())

        val bundle = record.toEntityBundle()

        assertArrayEquals(byteArrayOf(1, 2, 3, 4), bundle.raw.packet)
        assertEquals("V116A", bundle.result.algorithmProfile)
        assertEquals("PACKAGE_CODE", bundle.result.sensitivityTokenSource)
        assertEquals(1.42, bundle.result.sensitivityCoefficient, 0.0001)
        assertEquals("NORMAL", bundle.result.sensitivityEncoding)
        assertEquals("STANDARD", bundle.result.initializationMode)
        assertEquals(true, bundle.result.alarmEligible)
        assertEquals(null, bundle.result.algorithmErrorCode)
        assertEquals(1, bundle.checkpoint.schemaVersion)
        assertEquals("AA:BB:CC:DD:EE:FF", bundle.checkpoint.bluetoothAddress)
        assertEquals(SensorFamily.SIBIONICS_GS1.wireName, bundle.checkpoint.sensorFamily)
        assertEquals("GS1_V120", bundle.checkpoint.transportProtocol)
        assertEquals(101, bundle.measurement?.glucoseMgDl)
    }

    @Test
    fun checkpointRejectsAnythingTheAlgorithmCannotRestore() {
        val valid = checkpoint()

        assertThrows(IllegalArgumentException::class.java) {
            valid.copy(sensitivityToken = "!!!!!!!!")
        }
        assertThrows(IllegalArgumentException::class.java) {
            valid.copy(schemaVersion = 2)
        }
        assertThrows(IllegalArgumentException::class.java) {
            valid.copy(bluetoothAddress = "aa:bb:cc:dd:ee:ff")
        }
        assertThrows(IllegalArgumentException::class.java) {
            valid.copy(state = ByteArray(2_479), stateSha256 = ByteArray(2_479).sha256())
        }
        assertThrows(IllegalArgumentException::class.java) {
            valid.copy(
                algorithmProfile = "V115G",
                state = ByteArray(2_480),
                stateSha256 = ByteArray(2_480).sha256(),
            )
        }
    }

    private fun raw(packet: ByteArray = byteArrayOf(1, 2, 3, 4)) = RawSensorSampleRecord(
        eventId = "event-10",
        sensorId = "sensor-a",
        sensorFamily = SensorFamily.SIBIONICS_GS1,
        sequence = 10,
        sensorTimeEpochMs = 1_700_000_600_000L,
        phoneTimeEpochMs = 1_700_000_601_000L,
        packet = packet,
        packetSha256 = packet.sha256(),
        currentRaw = 52,
        temperatureRaw = 321,
        historyDistance = 0,
        transportVariant = 0,
    )

    private fun result() = SensorAlgorithmResultRecord(
        eventId = "event-10",
        sensorId = "sensor-a",
        sequence = 10,
        sensorTimeEpochMs = 1_700_000_600_000L,
        nativeGlucoseMmolL = 5.61,
        displayedGlucoseMmolL = 5.61,
        nativeTrend = 2,
        glucoseWarning = 0,
        currentWarning = 0,
        temperatureWarning = 0,
        algorithmProfile = "V116A",
        algorithmVersion = "1.1.6A",
        binarySetId = "v116a-arm64-test",
        sensitivityToken = "ABCDEFGH",
        sensitivityTokenSource = "PACKAGE_CODE",
        sensitivityCoefficient = 1.42,
        sensitivityEncoding = "NORMAL",
        initializationMode = "STANDARD",
        publishable = true,
    )

    private fun checkpoint() = SensorAlgorithmCheckpointRecord(
        sensorId = "sensor-a",
        bluetoothAddress = "AA:BB:CC:DD:EE:FF",
        sensorFamily = SensorFamily.SIBIONICS_GS1,
        transportVariant = 0,
        transportProtocol = "GS1_V120",
        dataHandleBinarySetId = "datahandle-test",
        sequence = 10,
        sensorTimeEpochMs = 1_700_000_600_000L,
        algorithmProfile = "V116A",
        algorithmVersion = "1.1.6A",
        binarySetId = "v116a-arm64-test",
        sensitivityToken = "ABCDEFGH",
        sensitivityTokenSource = "PACKAGE_CODE",
        sensitivityCoefficient = 1.42,
        sensitivityEncoding = "NORMAL",
        initializationMode = "STANDARD",
        state = ByteArray(2_480) { it.toByte() },
        stateSha256 = ByteArray(2_480) { it.toByte() }.sha256(),
        displayOffsetMmolL = 0.4,
        schemaVersion = 1,
    )

    private fun reading() = GlucoseReading(
        eventId = "event-10",
        sensorId = "sensor-a",
        sensorFamily = SensorFamily.SIBIONICS_GS1,
        sensorTimeEpochMs = 1_700_000_600_000L,
        phoneTimeEpochMs = 1_700_000_601_000L,
        glucoseMgDl = 101,
        trendMgDlPerMinute = -1.0,
        quality = ReadingQuality.VALID,
        sequence = 10,
    )

    private fun ByteArray.sha256(): String = MessageDigest.getInstance("SHA-256")
        .digest(this)
        .joinToString("") { "%02x".format(it) }
}
