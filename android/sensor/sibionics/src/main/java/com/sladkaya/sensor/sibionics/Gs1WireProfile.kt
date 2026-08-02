package com.sladkaya.sensor.sibionics

import com.sladkaya.sensor.sibionics.algorithm.AlgorithmProfile
import com.sladkaya.sensor.sibionics.datahandle.DataHandleBundles

/** Internal wire identity. It is never a user-facing setup choice. */
internal enum class Gs1WireProfile {
    UNRESOLVED,
    V115,
    V120,
}

internal data class Gs1WireProfileSpec(
    val wireProfile: Gs1WireProfile,
    val transportProtocol: String,
    val transportCodecId: String,
)

internal object Gs1WireProfiles {
    fun requireResolved(
        profile: Gs1WireProfile,
        transportVariant: Int,
    ): Gs1WireProfileSpec = when (profile) {
        Gs1WireProfile.V115 -> Gs1WireProfileSpec(
            wireProfile = Gs1WireProfile.V115,
            transportProtocol = "GS1_V115",
            transportCodecId = "GS1_V115_WIRE_V1",
        )
        Gs1WireProfile.V120 -> Gs1WireProfileSpec(
            wireProfile = Gs1WireProfile.V120,
            transportProtocol = "GS1_V120",
            transportCodecId = DataHandleBundles.require(transportVariant).binarySetId,
        )
        Gs1WireProfile.UNRESOLVED ->
            throw IllegalArgumentException("A resolved GS1 wire profile is required")
    }
}

/**
 * The host algorithm belongs to the verified official market bundle, not to the BLE wire format.
 * A Chinese sensor may speak either supported wire protocol while still using the CN V115G core.
 */
internal object Gs1AlgorithmProfiles {
    fun resolveForTransportVariant(transportVariant: Int): AlgorithmProfile? = when (
        transportVariant
    ) {
        GLOBAL_VARIANT -> AlgorithmProfile.V116A
        CHINESE_VARIANT -> AlgorithmProfile.V115G
        else -> null
    }

    fun requireForTransportVariant(transportVariant: Int): AlgorithmProfile =
        requireNotNull(resolveForTransportVariant(transportVariant)) {
            "No verified official algorithm bundle for transport variant $transportVariant"
        }

    private const val GLOBAL_VARIANT = 0
    private const val CHINESE_VARIANT = 2
}
