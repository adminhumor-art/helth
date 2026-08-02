package com.sladkaya.sensor.sibionics

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class Gs1V115WireCodecTest {
    @Test
    fun referenceV115SingleRecordNotificationFitsTheDefaultAttPayload() {
        val exactEnvelope = response(record(1, 300, 20, 1_000, 0, 0, 0))

        assertEquals(19, exactEnvelope.size)
        assertTrue(
            Gs1V115WireCodec.decode(exactEnvelope, 1_700_000_000_000L) is
                Gs1V115DecodeResult.Success,
        )
    }

    @Test
    fun requestMatchesThePinnedV115WireVector() {
        val request = Gs1V115WireCodec.request(
            index = 34_304,
            bluetoothAddress = "E1:54:53:09:27:43",
        )

        assertArrayEquals(
            hex("AA 55 07 00 86 43 27 09 53 54 E1 00 00 00 00 00 00 00 00 79"),
            request,
        )
        assertEquals(0, request.sumOf { it.toInt() and 0xff } and 0xff)
    }

    @Test
    fun requestKeepsLittleEndianIndexAndReversesTheCanonicalMac() {
        assertArrayEquals(
            hex("AA 55 07 01 00 FF EE DD CC BB AA 00 00 00 00 00 00 00 00 FE"),
            Gs1V115WireCodec.request(1, "AA:BB:CC:DD:EE:FF"),
        )
        assertArrayEquals(
            hex("AA 55 07 00 01 FF EE DD CC BB AA 00 00 00 00 00 00 00 00 FE"),
            Gs1V115WireCodec.request(256, "AA:BB:CC:DD:EE:FF"),
        )
    }

    @Test
    fun requestRejectsAnInvalidCursorOrMacInsteadOfTruncating() {
        assertThrows(IllegalArgumentException::class.java) {
            Gs1V115WireCodec.request(0, "AA:BB:CC:DD:EE:FF")
        }
        assertThrows(IllegalArgumentException::class.java) {
            Gs1V115WireCodec.request(65_536, "AA:BB:CC:DD:EE:FF")
        }
        assertThrows(IllegalArgumentException::class.java) {
            Gs1V115WireCodec.request(1, "AA:BB:CC:DD:EE")
        }
    }

    @Test
    fun exactFiveByteChallengeIsTheOnlyV120SwitchMarker() {
        assertTrue(Gs1V115WireCodec.isV120Challenge(hex("23 F7 6F D9 F4")))
        assertFalse(Gs1V115WireCodec.isV120Challenge(hex("04 00 00 00 FC")))
        assertFalse(Gs1V115WireCodec.isV120Challenge(hex("23 F7 6F D9 F5")))
        assertFalse(Gs1V115WireCodec.isV120Challenge(hex("23 F7 6F D9 F4 00")))
    }

    @Test
    fun responseUsesBigEndianFieldsAndTheDurableReceiveTimestamp() {
        val packet = response(
            record(
                index = 0x1234,
                temperature10 = 321,
                electric = 0x4567,
                signal10 = 987,
                status = 3,
                historyDistance = 2,
                addTimeSeconds = 30,
            ),
        )

        val result = Gs1V115WireCodec.decode(packet, receivedAtEpochMs = 1_700_000_000_999L)

        assertTrue(result is Gs1V115DecodeResult.Success)
        val decoded = (result as Gs1V115DecodeResult.Success).records.single()
        assertEquals(0x1234, decoded.sample.index)
        assertEquals(321, decoded.sample.temperature)
        assertEquals(987, decoded.sample.current)
        assertEquals(2, decoded.sample.reindex)
        assertEquals(1_699_999_910L, decoded.sample.sensorTimeEpochSeconds)
        assertEquals(0x4567, decoded.electric)
        assertEquals(3, decoded.status)
        assertEquals(30, decoded.addTimeSeconds)
    }

    @Test
    fun responseClampsAReportedFutureTimeToTheCapturedReceiveSecond() {
        val packet = response(
            record(
                index = 1,
                temperature10 = 300,
                electric = 20,
                signal10 = 1_000,
                status = 0,
                historyDistance = 0,
                addTimeSeconds = 25,
            ),
        )

        val result = Gs1V115WireCodec.decode(packet, receivedAtEpochMs = 1_700_000_000_999L)

        val decoded = (result as Gs1V115DecodeResult.Success).records.single()
        assertEquals(1_700_000_000L, decoded.sample.sensorTimeEpochSeconds)
        assertTrue(decoded.sensorTimeWasClamped)
    }

    @Test
    fun emptyResponseIsValidTransportProgressWithoutSamples() {
        val result = Gs1V115WireCodec.decode(response(), receivedAtEpochMs = 1_700_000_000_000L)

        assertTrue(result is Gs1V115DecodeResult.Success)
        assertTrue((result as Gs1V115DecodeResult.Success).records.isEmpty())
    }

    @Test
    fun malformedEnvelopeFailsClosed() {
        val valid = response(
            record(1, 300, 20, 1_000, 0, 0, 0),
        )
        val wrongChecksum = valid.copyOf().also { it[it.lastIndex] = (it.last() + 1).toByte() }
        val trailing = valid + 0
        val tooMany = byteArrayOf(0xaa.toByte(), 0x55, 0x09, 18) +
            ByteArray(18 * 14) + byteArrayOf(0)

        assertEquals(Gs1V115DecodeError.CHECKSUM, failure(wrongChecksum))
        assertEquals(Gs1V115DecodeError.LENGTH, failure(valid.copyOf(valid.size - 1)))
        assertEquals(Gs1V115DecodeError.LENGTH, failure(trailing))
        assertEquals(Gs1V115DecodeError.PACKET_SIZE, failure(tooMany))
        assertEquals(
            Gs1V115DecodeError.HEADER,
            failure(valid.copyOf().also { it[2] = 8 }),
        )
    }

    private fun failure(packet: ByteArray): Gs1V115DecodeError {
        val result = Gs1V115WireCodec.decode(packet, 1_700_000_000_000L)
        return (result as Gs1V115DecodeResult.Failure).error
    }

    private fun response(vararg records: ByteArray): ByteArray {
        val withoutChecksum = byteArrayOf(0xaa.toByte(), 0x55, 0x09, records.size.toByte()) +
            records.fold(ByteArray(0)) { accumulator, record -> accumulator + record }
        return withoutChecksum + (-withoutChecksum.sum()).toByte()
    }

    private fun record(
        index: Int,
        temperature10: Int,
        electric: Int,
        signal10: Int,
        status: Int,
        historyDistance: Int,
        addTimeSeconds: Int,
    ): ByteArray = listOf(
        index,
        temperature10,
        electric,
        signal10,
        status,
        historyDistance,
        addTimeSeconds,
    ).flatMap { value -> listOf((value ushr 8).toByte(), value.toByte()) }.toByteArray()

    private fun hex(value: String): ByteArray = value.split(' ').map { it.toInt(16).toByte() }.toByteArray()
}
