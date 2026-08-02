package com.sladkaya.sensor.sibionics

internal enum class Gs1V115DecodeError {
    INVALID_RECEIVE_TIME,
    PACKET_SIZE,
    HEADER,
    RECORD_COUNT,
    LENGTH,
    CHECKSUM,
    INDEX_SEQUENCE,
}

internal data class DecodedGs1V115Record(
    val sample: DecodedGs1RawSample,
    val electric: Int,
    val status: Int,
    val addTimeSeconds: Int,
    val sensorTimeWasClamped: Boolean,
)

internal sealed interface Gs1V115DecodeResult {
    data class Success(val records: List<DecodedGs1V115Record>) : Gs1V115DecodeResult
    data class Failure(val error: Gs1V115DecodeError) : Gs1V115DecodeResult
}

/** Bounded codec for the non-RC4 GS1 V115 request/response envelope. */
internal object Gs1V115WireCodec {
    fun request(index: Int, bluetoothAddress: String): ByteArray {
        require(index in MIN_INDEX..MAX_INDEX) { "GS1 V115 index is outside uint16" }
        require(CANONICAL_MAC.matches(bluetoothAddress)) { "Canonical Bluetooth address is required" }
        val mac = bluetoothAddress.split(':').map { it.toInt(16).toByte() }.reversed()
        val result = ByteArray(REQUEST_SIZE)
        result[0] = 0xaa.toByte()
        result[1] = 0x55
        result[2] = 0x07
        result[3] = index.toByte()
        result[4] = (index ushr 8).toByte()
        mac.forEachIndexed { position, byte -> result[5 + position] = byte }
        result[result.lastIndex] = checksum(result, result.lastIndex)
        return result
    }

    fun isV120Challenge(packet: ByteArray): Boolean = packet.contentEquals(V120_CHALLENGE)

    fun decode(packet: ByteArray, receivedAtEpochMs: Long): Gs1V115DecodeResult {
        if (receivedAtEpochMs <= 0L) return failure(Gs1V115DecodeError.INVALID_RECEIVE_TIME)
        if (packet.size !in MIN_RESPONSE_SIZE..MAX_PACKET_SIZE) {
            return failure(Gs1V115DecodeError.PACKET_SIZE)
        }
        if (packet[0] != 0xaa.toByte() || packet[1] != 0x55.toByte() || packet[2] != 0x09.toByte()) {
            return failure(Gs1V115DecodeError.HEADER)
        }
        val count = packet[3].u8()
        if (count > MAX_RECORDS) return failure(Gs1V115DecodeError.RECORD_COUNT)
        val expectedSize = RESPONSE_OVERHEAD + count * RECORD_SIZE
        if (packet.size != expectedSize) return failure(Gs1V115DecodeError.LENGTH)
        if (packet.sumOf { it.u8() } and 0xff != 0) return failure(Gs1V115DecodeError.CHECKSUM)

        val receivedAtEpochSeconds = receivedAtEpochMs / MILLIS_PER_SECOND
        val records = List(count) { position ->
            val offset = RESPONSE_HEADER_SIZE + position * RECORD_SIZE
            val index = packet.u16be(offset)
            val temperature10 = packet.u16be(offset + 2)
            val electric = packet.u16be(offset + 4)
            val signal10 = packet.u16be(offset + 6)
            val status = packet.u16be(offset + 8)
            val historyDistance = packet.u16be(offset + 10)
            val addTimeSeconds = packet.u16be(offset + 12)
            val reportedTime = receivedAtEpochSeconds + addTimeSeconds -
                historyDistance.toLong() * SAMPLE_PERIOD_SECONDS
            val clamped = reportedTime > receivedAtEpochSeconds
            DecodedGs1V115Record(
                sample = DecodedGs1RawSample(
                    index = index,
                    sensorTimeEpochSeconds = if (clamped) receivedAtEpochSeconds else reportedTime,
                    current = signal10,
                    temperature = temperature10,
                    reindex = historyDistance,
                    sensorTimeWasClamped = clamped,
                    addTimeSeconds = addTimeSeconds,
                ),
                electric = electric,
                status = status,
                addTimeSeconds = addTimeSeconds,
                sensorTimeWasClamped = clamped,
            )
        }
        if (!records.zipWithNext().all { (left, right) ->
                right.sample.index == left.sample.index + 1
            }
        ) {
            return failure(Gs1V115DecodeError.INDEX_SEQUENCE)
        }
        return Gs1V115DecodeResult.Success(records)
    }

    private fun checksum(bytes: ByteArray, length: Int): Byte =
        (-bytes.take(length).sumOf { it.u8() }).toByte()

    private fun failure(error: Gs1V115DecodeError) = Gs1V115DecodeResult.Failure(error)

    private fun ByteArray.u16be(offset: Int): Int =
        (this[offset].u8() shl 8) or this[offset + 1].u8()

    private fun Byte.u8(): Int = toInt() and 0xff

    private const val MIN_INDEX = 1
    private const val MAX_INDEX = 0xffff
    private const val REQUEST_SIZE = 20
    private const val RESPONSE_HEADER_SIZE = 4
    private const val RECORD_SIZE = 14
    private const val RESPONSE_OVERHEAD = RESPONSE_HEADER_SIZE + 1
    private const val MIN_RESPONSE_SIZE = RESPONSE_OVERHEAD
    private const val MAX_PACKET_SIZE = 250
    private const val MAX_RECORDS = (MAX_PACKET_SIZE - RESPONSE_OVERHEAD) / RECORD_SIZE
    private const val MILLIS_PER_SECOND = 1_000L
    private const val SAMPLE_PERIOD_SECONDS = 60L
    private val V120_CHALLENGE = byteArrayOf(
        0x23,
        0xf7.toByte(),
        0x6f,
        0xd9.toByte(),
        0xf4.toByte(),
    )
    private val CANONICAL_MAC = Regex("^(?:[0-9A-F]{2}:){5}[0-9A-F]{2}$")
}
