package com.sladkaya.sensor.sibionics

import com.sladkaya.core.model.SensorFamily
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.UUID
import kotlin.math.roundToInt

object SibionicsUuids {
    val SERVICE: UUID = UUID.fromString("0000ff30-0000-1000-8000-00805f9b34fb")
    val NOTIFY: UUID = UUID.fromString("0000ff31-0000-1000-8000-00805f9b34fb")
    val WRITE: UUID = UUID.fromString("0000ff32-0000-1000-8000-00805f9b34fb")
    val CLIENT_CHARACTERISTIC_CONFIGURATION: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
}

data class DecodedGs1RawSample(
    val index: Int,
    val sensorTimeEpochSeconds: Long,
    val current: Int,
    val temperature: Int,
    val reindex: Int,
)

data class DecodedGs3GlucoseSample(
    val index: Int,
    val sensorTimeEpochSeconds: Long,
    val glucoseMgDl: Int,
)

sealed interface DecodedPacket {
    data class Gs1RawSamples(val values: List<DecodedGs1RawSample>) : DecodedPacket
    data class Gs3GlucoseSamples(val values: List<DecodedGs3GlucoseSample>) : DecodedPacket
    data class Acknowledgement(val command: Int, val status: Int, val detail: Int) : DecodedPacket
    data class DeviceInformation(val subcommand: Int) : DecodedPacket
    data class Unsupported(val command: Int) : DecodedPacket
    data class Invalid(val reason: String) : DecodedPacket
}

/**
 * Parser for the shared FF30/FF31/FF32 SiBionics transport.
 *
 * Parsed samples remain diagnostic until their values are validated against a
 * physical sensor and the manufacturer's reference application.
 */
class SibionicsPacketCodec {
    fun decode(family: SensorFamily, encrypted: ByteArray): DecodedPacket {
        if (encrypted.isEmpty() || encrypted.size > 250) return DecodedPacket.Invalid("packet size")
        val plain = if (encrypted.contentEquals(byteArrayOf(4, 0, 0, 0, 0xfc.toByte()))) {
            encrypted.copyOf()
        } else {
            Rc4.xor(encrypted)
        }
        if (plain.size < 2) return DecodedPacket.Invalid("short header")
        val length = plain[0].u8()
        if (length == 0 || length + 1 != plain.size) {
            return DecodedPacket.Invalid("invalid length")
        }
        if (!checksumValid(plain, length)) return DecodedPacket.Invalid("checksum")
        val command = plain[1].u8()
        if (length == 4) {
            return DecodedPacket.Acknowledgement(command, plain[2].u8(), plain[3].u8())
        }
        return when {
            family == SensorFamily.SIBIONICS_GS3 && command == 0x14 -> decodeGs3(plain, length)
            family in setOf(SensorFamily.SIBIONICS_GS1, SensorFamily.SIBIONICS_GS1SB) && command == 0x08 -> decodeGs1(plain, length)
            command == 0xf0 && length >= 3 -> DecodedPacket.DeviceInformation(plain[2].u8())
            else -> DecodedPacket.Unsupported(command)
        }
    }

    private fun decodeGs3(packet: ByteArray, length: Int): DecodedPacket {
        if (length < 12) return DecodedPacket.Invalid("GS3 data packet is too short")
        val count = packet[2].u8()
        val expectedMinimum = 9 + count * 8 + 2 + 1
        if (length + 1 < expectedMinimum) return DecodedPacket.Invalid("GS3 record count exceeds packet")
        val startIndex = packet.u16le(3)
        val startTime = packet.u32le(5)
        val samples = buildList {
            repeat(count) { position ->
                val index = startIndex + position
                if (index % 5 != 0) return@repeat
                val offset = 9 + position * 8
                val high = packet[offset + 7].u8()
                val low = packet[offset + 6].u8()
                val mmolTimesTen = (high shl 2) or (low ushr 6)
                val mgDl = (mmolTimesTen * 1.8).roundToInt()
                if (mgDl in 20..600) {
                    add(DecodedGs3GlucoseSample(index, startTime + position * 60L, mgDl))
                }
            }
        }
        return if (samples.isEmpty()) {
            DecodedPacket.Invalid("GS3 packet contains no usable sample")
        } else {
            DecodedPacket.Gs3GlucoseSamples(samples)
        }
    }

    private fun decodeGs1(packet: ByteArray, length: Int): DecodedPacket {
        val count = packet[2].u8()
        if (count > GS1_MAX_RECORDS) {
            return DecodedPacket.Invalid("GS1 record count exceeds transport envelope")
        }
        val headerOffset = 3
        val recordsOffset = headerOffset + 6
        val expectedLength = GS1_BASE_LENGTH + count * GS1_RECORD_LENGTH
        if (length != expectedLength) {
            return DecodedPacket.Invalid("GS1 length does not match record count")
        }
        val startIndex = packet.u16le(headerOffset)
        val startTime = packet.u32le(headerOffset + 2)
        val finalReindex = packet.u16le(recordsOffset + count * GS1_RECORD_LENGTH)
        if (count > 0) {
            val positionOfFirstRecord = count - 1L
            if (startIndex.toLong() + positionOfFirstRecord > U16_MAX) {
                return DecodedPacket.Invalid("GS1 sample index overflow")
            }
            if (startTime + positionOfFirstRecord * GS1_SAMPLE_PERIOD_SECONDS > U32_MAX) {
                return DecodedPacket.Invalid("GS1 sample time overflow")
            }
            if (finalReindex.toLong() + positionOfFirstRecord > U16_MAX) {
                return DecodedPacket.Invalid("GS1 reindex overflow")
            }
        }
        val samples = List(count) { position ->
            val offset = recordsOffset + position * GS1_RECORD_LENGTH
            DecodedGs1RawSample(
                index = startIndex + position,
                sensorTimeEpochSeconds = startTime + position * 60L,
                current = packet.u16le(offset + 4),
                temperature = packet.u16le(offset),
                reindex = finalReindex + count - 1 - position,
            )
        }
        return DecodedPacket.Gs1RawSamples(samples)
    }

    internal fun encryptForTest(plain: ByteArray): ByteArray = Rc4.xor(plain)

    companion object {
        private const val GS1_BASE_LENGTH = 11
        private const val GS1_RECORD_LENGTH = 8
        private const val GS1_MAX_RECORDS = 29
        private const val GS1_SAMPLE_PERIOD_SECONDS = 60L
        private const val U16_MAX = 0xffffL
        private const val U32_MAX = 0xffff_ffffL

        fun checksum(data: ByteArray, length: Int): Byte = (-data.take(length).sum()).toByte()
        fun checksumValid(data: ByteArray, length: Int): Boolean = data[length] == checksum(data, length)
    }
}

internal object Rc4 {
    private val key = byteArrayOf(
        0x01, 0x38, 0x0b, 0x9a.toByte(), 0x00, 0x5b, 0x02, 0x5d,
        0xcd.toByte(), 0x9e.toByte(), 0xc3.toByte(), 0x99.toByte(),
        0x09, 0x37, 0xaa.toByte(), 0xe8.toByte(),
    )

    fun xor(input: ByteArray): ByteArray {
        val state = IntArray(256) { it }
        var j = 0
        for (i in state.indices) {
            j = (j + state[i] + key[i % key.size].u8()) and 0xff
            val temporary = state[i]
            state[i] = state[j]
            state[j] = temporary
        }
        val result = ByteArray(input.size)
        var i = 0
        j = 0
        input.indices.forEach { position ->
            i = (i + 1) and 0xff
            j = (j + state[i]) and 0xff
            val temporary = state[i]
            state[i] = state[j]
            state[j] = temporary
            val keyByte = state[(state[i] + state[j]) and 0xff]
            result[position] = (input[position].u8() xor keyByte).toByte()
        }
        return result
    }
}

private fun Byte.u8(): Int = toInt() and 0xff

private fun ByteArray.u16le(offset: Int): Int = ByteBuffer.wrap(this, offset, 2).order(ByteOrder.LITTLE_ENDIAN).short.toInt() and 0xffff

private fun ByteArray.u32le(offset: Int): Long = ByteBuffer.wrap(this, offset, 4).order(ByteOrder.LITTLE_ENDIAN).int.toLong() and 0xffffffffL
