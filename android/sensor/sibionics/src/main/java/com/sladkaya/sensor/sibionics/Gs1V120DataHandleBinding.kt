package com.sladkaya.sensor.sibionics

import com.sladkaya.sensor.sibionics.datahandle.DataHandleBundle
import com.sladkaya.sensor.sibionics.datahandle.DataHandleBundles
import com.sladkaya.sensor.sibionics.datahandle.DataHandleGateway

internal class Gs1V120DataHandleBinding private constructor(
    val bundle: DataHandleBundle,
    val dataHandle: DataHandleGateway,
    val commandCodec: Gs1CommandCodec,
    val packetVerifier: Gs1PacketVerifier,
) {
    companion object {
        fun bind(
            transportVariant: Int,
            handle: DataHandleGateway,
        ): Gs1V120DataHandleBinding {
            val expected = DataHandleBundles.require(transportVariant)
            require(handle.bundle == expected) {
                "The V120 command and packet paths require the same native bundle"
            }
            return Gs1V120DataHandleBinding(
                bundle = expected,
                dataHandle = handle,
                commandCodec = OfficialGs1CommandCodec(handle),
                packetVerifier = Gs1VerifiedPacketDecoder(
                    nativeSplitter = Gs1NativeSplitter(handle::splitGs1Data),
                ),
            )
        }
    }
}
