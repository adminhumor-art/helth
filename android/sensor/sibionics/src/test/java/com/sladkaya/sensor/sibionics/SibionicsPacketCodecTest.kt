package com.sladkaya.sensor.sibionics

import com.sladkaya.core.model.SensorFamily
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SibionicsPacketCodecTest {
    private val codec = SibionicsPacketCodec()

    @Test
    fun referenceV120SingleRecordNotificationFitsTheDefaultAttPayload() {
        val exactEnvelope = gs1Packet(count = 1)

        assertEquals(20, exactEnvelope.size)
        assertTrue(codec.decode(SensorFamily.SIBIONICS_GS1, exactEnvelope) is DecodedPacket.Gs1RawSamples)
    }

    @Test
    fun decryptsAndParsesSyntheticGs3Sample() {
        val count = 1
        val length = 9 + count * 8 + 2
        val plain = ByteArray(length + 1)
        plain[0] = length.toByte()
        plain[1] = 0x14
        plain[2] = count.toByte()
        plain[3] = 5
        plain[5] = 0x80.toByte()
        plain[6] = 0x5f
        plain[7] = 0x37
        plain[8] = 0x65
        val mmolTimesTen = 58
        plain[9 + 6] = ((mmolTimesTen and 0x03) shl 6).toByte()
        plain[9 + 7] = (mmolTimesTen ushr 2).toByte()
        plain[length] = SibionicsPacketCodec.checksum(plain, length)

        val decoded = codec.decode(SensorFamily.SIBIONICS_GS3, codec.encryptForTest(plain))
        assertTrue(decoded is DecodedPacket.Gs3GlucoseSamples)
        val sample = (decoded as DecodedPacket.Gs3GlucoseSamples).values.single()
        assertEquals(5, sample.index)
        assertEquals(104, sample.glucoseMgDl)
    }

    @Test
    fun gs1PacketExposesAlgorithmInputsInsteadOfGlucose() {
        val count = 1
        val recordsOffset = 9
        // Official V120 contract: L = 11 + 8*N, full packet = L + 1.
        val length = recordsOffset + count * 8 + 2
        val plain = ByteArray(length + 1)
        plain[0] = length.toByte()
        plain[1] = 0x08
        plain[2] = count.toByte()
        plain.putU16Le(3, 41)
        plain.putU32Le(5, 1_700_000_000L)
        plain.putU16Le(recordsOffset, 247)
        plain.putU16Le(recordsOffset + 2, 999)
        // 58 used to be misread as 5.8 mmol/L and exposed as 104 mg/dL.
        plain.putU16Le(recordsOffset + 4, 58)
        plain.putU16Le(recordsOffset + 6, 888)
        plain.putU16Le(recordsOffset + 8, 3)
        plain[length] = SibionicsPacketCodec.checksum(plain, length)

        val decoded = codec.decode(SensorFamily.SIBIONICS_GS1, codec.encryptForTest(plain))

        assertTrue(decoded is DecodedPacket.Gs1RawSamples)
        val sample = (decoded as DecodedPacket.Gs1RawSamples).values.single()
        assertEquals(41, sample.index)
        assertEquals(1_700_000_000L, sample.sensorTimeEpochSeconds)
        assertEquals(58, sample.current)
        assertEquals(247, sample.temperature)
        assertEquals(3, sample.reindex)
    }

    @Test
    fun rejectsPacketWithBadChecksum() {
        val plain = byteArrayOf(4, 1, 0, 0, 0)
        val decoded = codec.decode(SensorFamily.SIBIONICS_GS3, codec.encryptForTest(plain))
        assertTrue(decoded is DecodedPacket.Invalid)
    }

    @Test
    fun rejectsOtherwiseValidPacketWithBytesAfterDeclaredChecksum() {
        val exact = byteArrayOf(4, 0, 0, 0, 0)
        exact[4] = SibionicsPacketCodec.checksum(exact, 4)
        val padded = exact + 0

        val decoded = codec.decode(SensorFamily.SIBIONICS_GS1, codec.encryptForTest(padded))

        assertTrue(decoded is DecodedPacket.Invalid)
    }

    @Test
    fun rejectsGs1EnvelopeWithPayloadBeyondTheRecordCount() {
        val packet = gs1Packet(count = 0, bytesBeforeChecksum = 1)

        val decoded = codec.decode(SensorFamily.SIBIONICS_GS1, packet)

        assertTrue(decoded is DecodedPacket.Invalid)
    }

    @Test
    fun acceptsExactGs1RecordCountBoundaries() {
        val zero = codec.decode(SensorFamily.SIBIONICS_GS1, gs1Packet(count = 0))
        val maximum = codec.decode(SensorFamily.SIBIONICS_GS1, gs1Packet(count = 29))

        assertEquals(0, (zero as DecodedPacket.Gs1RawSamples).values.size)
        assertEquals(29, (maximum as DecodedPacket.Gs1RawSamples).values.size)
    }

    @Test
    fun rejectsGs1RecordCountThatCannotFitTheTransportEnvelope() {
        val decoded = codec.decode(SensorFamily.SIBIONICS_GS1, gs1Packet(count = 30))

        assertTrue(decoded is DecodedPacket.Invalid)
    }

    @Test
    fun acceptsMaximumStartIndexForOneRecordButRejectsIndexOverflow() {
        val maximum = codec.decode(
            SensorFamily.SIBIONICS_GS1,
            gs1Packet(count = 1, startIndex = 0xffff),
        )
        val overflow = codec.decode(
            SensorFamily.SIBIONICS_GS1,
            gs1Packet(count = 2, startIndex = 0xffff),
        )

        assertEquals(0xffff, (maximum as DecodedPacket.Gs1RawSamples).values.single().index)
        assertTrue(overflow is DecodedPacket.Invalid)
    }

    @Test
    fun rejectsDerivedGs1TimeAndReindexOverflow() {
        val maximum = codec.decode(
            SensorFamily.SIBIONICS_GS1,
            gs1Packet(count = 1, startTime = 0xffff_ffffL, finalReindex = 0xffff),
        )
        val timeOverflow = codec.decode(
            SensorFamily.SIBIONICS_GS1,
            gs1Packet(count = 2, startTime = 0xffff_ffffL),
        )
        val reindexOverflow = codec.decode(
            SensorFamily.SIBIONICS_GS1,
            gs1Packet(count = 2, finalReindex = 0xffff),
        )

        val maximumSample = (maximum as DecodedPacket.Gs1RawSamples).values.single()
        assertEquals(0xffff_ffffL, maximumSample.sensorTimeEpochSeconds)
        assertEquals(0xffff, maximumSample.reindex)
        assertTrue(timeOverflow is DecodedPacket.Invalid)
        assertTrue(reindexOverflow is DecodedPacket.Invalid)
    }


    private fun ByteArray.putU16Le(offset: Int, value: Int) {
        this[offset] = value.toByte()
        this[offset + 1] = (value ushr 8).toByte()
    }

    private fun ByteArray.putU32Le(offset: Int, value: Long) {
        repeat(4) { byte -> this[offset + byte] = (value ushr (byte * 8)).toByte() }
    }

    private fun gs1Packet(
        count: Int,
        startIndex: Int = 1,
        startTime: Long = 1_700_000_000L,
        finalReindex: Int = 0,
        bytesBeforeChecksum: Int = 0,
    ): ByteArray {
        val recordsOffset = 9
        val length = 11 + count * 8 + bytesBeforeChecksum
        val plain = ByteArray(length + 1)
        plain[0] = length.toByte()
        plain[1] = 0x08
        plain[2] = count.toByte()
        plain.putU16Le(3, startIndex)
        plain.putU32Le(5, startTime)
        repeat(count) { position ->
            val offset = recordsOffset + position * 8
            plain.putU16Le(offset, 247)
            plain.putU16Le(offset + 2, 9)
            plain.putU16Le(offset + 4, 58)
        }
        plain.putU16Le(recordsOffset + count * 8, finalReindex)
        plain[length] = SibionicsPacketCodec.checksum(plain, length)
        return codec.encryptForTest(plain)
    }
}
