package com.sladkaya.sensor.sibionics

/** Strict V115 verifier. Unlike V120, this wire format is not RC4/datahandle. */
internal class Gs1V115VerifiedPacketDecoder : Gs1PacketVerifier {
    override fun decode(
        encryptedPacket: ByteArray,
        receivedAtEpochMs: Long,
    ): Gs1VerifiedPacketResult = when (
        val decoded = Gs1V115WireCodec.decode(encryptedPacket, receivedAtEpochMs)
    ) {
        is Gs1V115DecodeResult.Success -> Gs1VerifiedPacketResult.Success(
            samples = decoded.records.map { it.sample },
            nativeRecords = emptyList(),
            decrypted = false,
        )
        is Gs1V115DecodeResult.Failure -> Gs1VerifiedPacketResult.Failure(
            error = Gs1VerifiedPacketError.WIRE_PACKET_INVALID,
            detail = "V115_${decoded.error.name}",
        )
    }
}
