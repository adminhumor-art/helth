package com.sladkaya.sensor.sibionics

import com.sladkaya.sensor.sibionics.algorithm.AlgorithmProfile
import com.sladkaya.sensor.sibionics.datahandle.SibionicsDataHandle

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
    val algorithmProfile: AlgorithmProfile,
)

internal object Gs1WireProfiles {
    private val resolved = mapOf(
        Gs1WireProfile.V115 to Gs1WireProfileSpec(
            wireProfile = Gs1WireProfile.V115,
            transportProtocol = "GS1_V115",
            transportCodecId = "GS1_V115_WIRE_V1",
            algorithmProfile = AlgorithmProfile.V115G,
        ),
        Gs1WireProfile.V120 to Gs1WireProfileSpec(
            wireProfile = Gs1WireProfile.V120,
            transportProtocol = "GS1_V120",
            transportCodecId = SibionicsDataHandle.BINARY_SET_ID,
            algorithmProfile = AlgorithmProfile.V116A,
        ),
    )

    fun requireResolved(profile: Gs1WireProfile): Gs1WireProfileSpec =
        requireNotNull(resolved[profile]) { "A resolved GS1 wire profile is required" }
}
