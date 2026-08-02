package com.sladkaya.sensor.sibionics

import com.sladkaya.core.model.SensorFamily
import com.sladkaya.sensor.sibionics.datahandle.Gs1DataSplitResult
import com.sladkaya.sensor.sibionics.datahandle.Gs1NativeRecord
import com.sladkaya.sensor.sibionics.datahandle.SibionicsDataHandle

enum class Gs1VerifiedPacketError {
    WIRE_PACKET_INVALID,
    NOT_GS1_DATA,
    NATIVE_SPLIT_FAILED,
    PARSER_PARITY_MISMATCH,
}

sealed interface Gs1VerifiedPacketResult {
    data class Success(
        val samples: List<DecodedGs1RawSample>,
        val nativeRecords: List<Gs1NativeRecord>,
        val decrypted: Boolean,
    ) : Gs1VerifiedPacketResult

    data class Failure(
        val error: Gs1VerifiedPacketError,
        val detail: String? = null,
    ) : Gs1VerifiedPacketResult
}

internal fun interface Gs1NativeSplitter {
    fun split(packet: ByteArray): Gs1DataSplitResult
}

internal fun interface Gs1PacketVerifier {
    fun decode(encryptedPacket: ByteArray, receivedAtEpochMs: Long): Gs1VerifiedPacketResult
}

/**
 * Accepts a GS1 packet only when the bounded native parser and an independent
 * wire-format parser produce identical algorithm inputs.
 */
internal class Gs1VerifiedPacketDecoder(
    private val wireCodec: SibionicsPacketCodec = SibionicsPacketCodec(),
    private val nativeSplitter: Gs1NativeSplitter = Gs1NativeSplitter { packet ->
        SibionicsDataHandle().splitGs1Data(packet)
    },
) : Gs1PacketVerifier {
    override fun decode(
        encryptedPacket: ByteArray,
        receivedAtEpochMs: Long,
    ): Gs1VerifiedPacketResult {
        val local = when (val decoded = wireCodec.decode(SensorFamily.SIBIONICS_GS1, encryptedPacket)) {
            is DecodedPacket.Gs1RawSamples -> decoded.values
            is DecodedPacket.Invalid -> {
                return Gs1VerifiedPacketResult.Failure(
                    Gs1VerifiedPacketError.WIRE_PACKET_INVALID,
                    decoded.reason,
                )
            }
            else -> return Gs1VerifiedPacketResult.Failure(Gs1VerifiedPacketError.NOT_GS1_DATA)
        }

        val native = when (val result = nativeSplitter.split(encryptedPacket.copyOf())) {
            is Gs1DataSplitResult.Success -> result
            is Gs1DataSplitResult.Failure -> {
                return Gs1VerifiedPacketResult.Failure(
                    Gs1VerifiedPacketError.NATIVE_SPLIT_FAILED,
                    result.error.name,
                )
            }
        }
        val nativeSamples = native.records.map { record ->
            DecodedGs1RawSample(
                index = record.index,
                sensorTimeEpochSeconds = record.sensorTimeEpochSeconds,
                current = record.current10,
                temperature = record.temperature10,
                reindex = record.reindex,
            )
        }
        if (nativeSamples != local) {
            return Gs1VerifiedPacketResult.Failure(Gs1VerifiedPacketError.PARSER_PARITY_MISMATCH)
        }
        return Gs1VerifiedPacketResult.Success(
            samples = nativeSamples,
            nativeRecords = native.records,
            decrypted = native.decrypted,
        )
    }
}
