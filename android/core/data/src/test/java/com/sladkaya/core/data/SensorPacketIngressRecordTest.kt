package com.sladkaya.core.data

import com.sladkaya.core.model.SensorFamily
import java.security.MessageDigest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class SensorPacketIngressRecordTest {
    @Test
    fun packetIsDefensivelyCopiedOnInputAndOutput() {
        val source = byteArrayOf(1, 2, 3)
        val record = record(packet = source)

        source[0] = 99
        val firstRead = record.encryptedPacketCopy()
        firstRead[1] = 88

        assertArrayEquals(byteArrayOf(1, 2, 3), record.encryptedPacketCopy())
    }

    @Test
    fun packetHashMustMatchTheImmutablePacket() {
        assertThrows(IllegalArgumentException::class.java) {
            record(packetSha256 = "0".repeat(64))
        }
    }

    @Test
    fun bluetoothAddressMustAlreadyBeCanonical() {
        assertThrows(IllegalArgumentException::class.java) {
            record(bluetoothAddress = "aa:bb:cc:dd:ee:ff")
        }
        assertThrows(IllegalArgumentException::class.java) {
            record(bluetoothAddress = "AA-BB-CC-DD-EE-FF")
        }

        assertEquals("AA:BB:CC:DD:EE:FF", record().bluetoothAddress)
    }

    @Test
    fun onlyGs1FamiliesAreAccepted() {
        assertThrows(IllegalArgumentException::class.java) {
            record(sensorFamily = SensorFamily.SIMULATOR)
        }
        assertThrows(IllegalArgumentException::class.java) {
            record(sensorFamily = SensorFamily.SIBIONICS_GS3)
        }

        record(sensorFamily = SensorFamily.SIBIONICS_GS1)
        record(sensorFamily = SensorFamily.SIBIONICS_GS1SB)
    }

    @Test
    fun stableIngressIdentityAndBoundsAreRequired() {
        assertThrows(IllegalArgumentException::class.java) { record(ingressId = " ") }
        assertThrows(IllegalArgumentException::class.java) { record(attemptId = "") }
        assertThrows(IllegalArgumentException::class.java) { record(ordinal = -1) }
        assertThrows(IllegalArgumentException::class.java) { record(receivedAtEpochMs = 0) }
        assertThrows(IllegalArgumentException::class.java) { record(packet = byteArrayOf()) }
        assertThrows(IllegalArgumentException::class.java) {
            record(packet = ByteArray(4_097))
        }
    }

    @Test
    fun outcomeRequiresStableIdentityTimeAndBoundedDetail() {
        assertThrows(IllegalArgumentException::class.java) { outcome(ingressId = " ") }
        assertThrows(IllegalArgumentException::class.java) { outcome(handledAtEpochMs = 0) }
        assertThrows(IllegalArgumentException::class.java) { outcome(detail = "x".repeat(513)) }

        assertEquals(null, outcome(detail = null).detail)
        assertEquals(512, outcome(detail = "x".repeat(512)).detail?.length)
        assertEquals(4, SensorPacketIngressOutcomeStatus.entries.size)
    }

    private fun outcome(
        ingressId: String = "attempt-a:0",
        handledAtEpochMs: Long = 1_700_000_000_001L,
        detail: String? = null,
    ) = SensorPacketIngressOutcomeRecord(
        ingressId = ingressId,
        status = SensorPacketIngressOutcomeStatus.CORE_COMMITTED,
        handledAtEpochMs = handledAtEpochMs,
        detail = detail,
    )

    private fun record(
        ingressId: String = "attempt-a:0",
        sensorFamily: SensorFamily = SensorFamily.SIBIONICS_GS1,
        bluetoothAddress: String = "AA:BB:CC:DD:EE:FF",
        attemptId: String = "attempt-a",
        ordinal: Long = 0,
        receivedAtEpochMs: Long = 1_700_000_000_000L,
        packet: ByteArray = byteArrayOf(1, 2, 3),
        packetSha256: String = packet.sha256(),
    ) = SensorPacketIngressRecord(
        ingressId = ingressId,
        sensorId = "sensor-a",
        sensorFamily = sensorFamily,
        bluetoothAddress = bluetoothAddress,
        attemptId = attemptId,
        ordinal = ordinal,
        receivedAtEpochMs = receivedAtEpochMs,
        encryptedPacket = packet,
        packetSha256 = packetSha256,
    )

    private fun ByteArray.sha256(): String = MessageDigest.getInstance("SHA-256")
        .digest(this)
        .joinToString("") { "%02x".format(it) }
}
