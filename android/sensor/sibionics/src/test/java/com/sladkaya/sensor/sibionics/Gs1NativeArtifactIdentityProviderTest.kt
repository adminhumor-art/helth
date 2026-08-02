package com.sladkaya.sensor.sibionics

import com.sladkaya.sensor.sibionics.algorithm.AlgorithmProfile
import org.junit.Assert.assertEquals
import org.junit.Test

class Gs1NativeArtifactIdentityProviderTest {
    @Test
    fun factoryAndPhysicalApprovalCanShareOneCanonicalInstalledIdentityProvider() {
        val identity = Gs1InstalledNativeArtifactIdentityProvider.resolve(
            profile = AlgorithmProfile.V116A,
            transportVariant = 0,
            supportedAbis = listOf("arm64-v8a"),
        )

        assertEquals(
            "9f1f1d932d07bbb916f9a88e3a824ee7dabba99e98a141a18831cd0f2cf2eb61",
            identity.algorithmBinarySetSha256,
        )
        assertEquals(
            "4267da74d2889a6f0179214c3f352c28c2741768a902720b67997899b26f5203",
            identity.datahandleBinarySetSha256,
        )
    }

    @Test
    fun chineseApprovalPinsTheChineseDataHandleBundle() {
        val identity = Gs1InstalledNativeArtifactIdentityProvider.resolve(
            profile = AlgorithmProfile.V115G,
            transportVariant = 2,
            supportedAbis = listOf("arm64-v8a"),
        )

        assertEquals(
            "31a2f31c237cdcf6e0b1e228b7015ad8b46f3e56d4207ecc1ff552b8db8defd4",
            identity.datahandleBinarySetSha256,
        )
    }
}
