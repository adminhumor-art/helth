package com.sladkaya.sensor.sibionics

import com.sladkaya.sensor.sibionics.algorithm.AlgorithmProfile
import org.junit.Assert.assertEquals
import org.junit.Test

class Gs1NativeArtifactIdentityProviderTest {
    @Test
    fun factoryAndPhysicalApprovalCanShareOneCanonicalInstalledIdentityProvider() {
        val identity = Gs1InstalledNativeArtifactIdentityProvider.resolve(
            profile = AlgorithmProfile.V116A,
            supportedAbis = listOf("arm64-v8a"),
        )

        assertEquals(
            "9f1f1d932d07bbb916f9a88e3a824ee7dabba99e98a141a18831cd0f2cf2eb61",
            identity.algorithmBinarySetSha256,
        )
        assertEquals(
            "13c2e96b3a590da34e85114ede0810279abb7142661bc8ccdad79f184663293b",
            identity.datahandleBinarySetSha256,
        )
    }
}
