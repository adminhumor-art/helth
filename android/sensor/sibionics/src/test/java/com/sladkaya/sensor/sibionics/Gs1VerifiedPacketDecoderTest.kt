package com.sladkaya.sensor.sibionics

import com.sladkaya.core.model.SensorFamily
import com.sladkaya.sensor.sibionics.datahandle.DataHandleError
import com.sladkaya.sensor.sibionics.datahandle.Gs1DataSplitResult
import com.sladkaya.sensor.sibionics.datahandle.Gs1NativeRecord
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class Gs1VerifiedPacketDecoderTest {
    private val codec = SibionicsPacketCodec()

    @Test
    fun acceptsOnlyWhenIndependentWireAndNativeParsersAgree() {
        val packet = packet(index = 41, current = 58, temperature = 247, reindex = 3)
        val native = FakeGs1Splitter(
            Gs1DataSplitResult.Success(
                records = listOf(record(index = 41, current = 58, temperature = 247, reindex = 3)),
                decrypted = true,
            ),
        )

        val result = Gs1VerifiedPacketDecoder(codec, native).decode(packet)

        val verified = result as Gs1VerifiedPacketResult.Success
        assertEquals(DecodedGs1RawSample(41, 1_700_000_000L, 58, 247, 3), verified.samples.single())
        assertEquals(1, native.calls)
    }

    @Test
    fun anyNativeParityMismatchBlocksThePacket() {
        val packet = packet(index = 41, current = 58, temperature = 247, reindex = 3)
        val native = FakeGs1Splitter(
            Gs1DataSplitResult.Success(
                records = listOf(record(index = 41, current = 59, temperature = 247, reindex = 3)),
                decrypted = true,
            ),
        )

        val result = Gs1VerifiedPacketDecoder(codec, native).decode(packet)

        assertEquals(
            Gs1VerifiedPacketError.PARSER_PARITY_MISMATCH,
            (result as Gs1VerifiedPacketResult.Failure).error,
        )
    }

    @Test
    fun invalidWirePacketFailsBeforeCallingNativeCode() {
        val native = FakeGs1Splitter(Gs1DataSplitResult.Failure(DataHandleError.SPLIT_FAILED))

        val result = Gs1VerifiedPacketDecoder(codec, native).decode(byteArrayOf(1, 2, 3))

        assertTrue(result is Gs1VerifiedPacketResult.Failure)
        assertEquals(0, native.calls)
    }

    @Test
    fun nativeParserFailureIsNotReplacedByTheLocalParser() {
        val native = FakeGs1Splitter(
            Gs1DataSplitResult.Failure(DataHandleError.MALFORMED_NATIVE_PAYLOAD),
        )

        val result = Gs1VerifiedPacketDecoder(codec, native).decode(packet())

        assertEquals(
            Gs1VerifiedPacketError.NATIVE_SPLIT_FAILED,
            (result as Gs1VerifiedPacketResult.Failure).error,
        )
    }

    @Test
    fun zeroRecordDataResponseIsAValidNoDataEvent() {
        val length = 11
        val plain = ByteArray(length + 1)
        plain[0] = length.toByte()
        plain[1] = 0x08
        plain[2] = 0
        plain.putU16Le(3, 41)
        plain.putU32Le(5, 1_700_000_000L)
        plain.putU16Le(9, 0)
        plain[length] = SibionicsPacketCodec.checksum(plain, length)
        val packet = codec.encryptForTest(plain)
        val native = FakeGs1Splitter(
            Gs1DataSplitResult.Success(records = emptyList(), decrypted = true),
        )

        val result = Gs1VerifiedPacketDecoder(codec, native).decode(packet)

        assertTrue(result is Gs1VerifiedPacketResult.Success)
        assertTrue((result as Gs1VerifiedPacketResult.Success).samples.isEmpty())
        assertEquals(1, native.calls)
    }

    @Test
    fun wireBoundaryViolationsNeverReachTheNativeParser() {
        val packets = listOf(
            packet(count = 0, bytesBeforeChecksum = 1),
            packet(count = 30),
            packet(count = 2, index = 0xffff),
            packet(count = 2, sensorTime = 0xffff_ffffL),
            packet(count = 2, reindex = 0xffff),
        )

        packets.forEach { malformed ->
            val native = FakeGs1Splitter(
                Gs1DataSplitResult.Failure(DataHandleError.SPLIT_FAILED),
            )

            val result = Gs1VerifiedPacketDecoder(codec, native).decode(malformed)

            assertEquals(
                Gs1VerifiedPacketError.WIRE_PACKET_INVALID,
                (result as Gs1VerifiedPacketResult.Failure).error,
            )
            assertEquals(0, native.calls)
        }
    }

    private fun packet(
        index: Int = 41,
        current: Int = 58,
        temperature: Int = 247,
        reindex: Int = 3,
        count: Int = 1,
        sensorTime: Long = 1_700_000_000L,
        bytesBeforeChecksum: Int = 0,
    ): ByteArray {
        val recordsOffset = 9
        val length = recordsOffset + count * 8 + 2 + bytesBeforeChecksum
        val plain = ByteArray(length + 1)
        plain[0] = length.toByte()
        plain[1] = 0x08
        plain[2] = count.toByte()
        plain.putU16Le(3, index)
        plain.putU32Le(5, sensorTime)
        repeat(count) { position ->
            val offset = recordsOffset + position * 8
            plain.putU16Le(offset, temperature)
            plain.putU16Le(offset + 2, 9)
            plain.putU16Le(offset + 4, current)
        }
        plain.putU16Le(recordsOffset + count * 8, reindex)
        plain[length] = SibionicsPacketCodec.checksum(plain, length)
        return codec.encryptForTest(plain)
    }

    private fun record(
        index: Int,
        current: Int,
        temperature: Int,
        reindex: Int,
    ) = Gs1NativeRecord(
        index = index,
        temperature10 = temperature,
        current10 = current,
        dump = 9,
        reindex = reindex,
        embeddedGlucose = 0,
        trend = 0,
        glucoseWarning = 0,
        temperatureWarning = 0,
        currentWarning = 0,
        sensorTimeEpochSeconds = 1_700_000_000L,
    )

    private fun ByteArray.putU16Le(offset: Int, value: Int) {
        this[offset] = value.toByte()
        this[offset + 1] = (value ushr 8).toByte()
    }

    private fun ByteArray.putU32Le(offset: Int, value: Long) {
        repeat(4) { byte -> this[offset + byte] = (value ushr (byte * 8)).toByte() }
    }
}

private class FakeGs1Splitter(
    private val result: Gs1DataSplitResult,
) : Gs1NativeSplitter {
    var calls = 0

    override fun split(packet: ByteArray): Gs1DataSplitResult {
        calls += 1
        return result
    }
}
