package com.sladkaya.sensor.sibionics.algorithm

import android.os.Build
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class NativeAlgorithmAndroidSmokeTest {
    @Test
    fun v116aLoadsRealJniAndCreatesThenReleasesContext() {
        requireSupportedArmRuntime()

        smoke(
            api = V116ANativeAlgorithmApi(),
            expectedProfile = AlgorithmProfile.V116A,
        )
    }

    @Test
    fun v115gLoadsRealJniAndCreatesThenReleasesContext() {
        requireSupportedArmRuntime()

        smoke(
            api = V115GNativeAlgorithmApi(),
            expectedProfile = AlgorithmProfile.V115G,
        )
    }

    @Test
    fun v116aFactionBranchUsesRealJniAndReleasesContext() {
        requireSupportedArmRuntime()

        factionSmoke(V116ANativeAlgorithmApi())
    }

    @Test
    fun v115gFactionBranchUsesRealJniAndReleasesContext() {
        requireSupportedArmRuntime()

        factionSmoke(V115GNativeAlgorithmApi())
    }

    private fun smoke(
        api: NativeAlgorithmApi,
        expectedProfile: AlgorithmProfile,
    ) {
        assertEquals(expectedProfile, api.profile)
        assertEquals(NativeBinarySets.resolve(expectedProfile).id, api.binarySetId)
        assertTrue(
            "Native algorithm metadata must expose a concrete version",
            api.algorithmVersion.isNotBlank() && api.algorithmVersion != "unknown",
        )

        val context = api.createContext()
        assertEquals(1, api.release(context))
    }

    private fun factionSmoke(api: NativeAlgorithmApi) {
        assertTrue(AlgorithmInitializationMode.FACTION in api.supportedInitializationModes)
        val context = api.createContext()
        try {
            assertEquals(
                "The pinned library must accept the exact faction entry point",
                1,
                api.initialize(
                    context = context,
                    sensitivityToken = "ABCDEFGH",
                    mode = AlgorithmInitializationMode.FACTION,
                ),
            )
        } finally {
            assertEquals(1, api.release(context))
        }
    }

    private fun requireSupportedArmRuntime() {
        assertTrue(
            "Pinned native libraries require an ARM Android runtime; device ABIs=" +
                Build.SUPPORTED_ABIS.joinToString(),
            Build.SUPPORTED_ABIS.any(SUPPORTED_ABIS::contains),
        )
    }

    private companion object {
        val SUPPORTED_ABIS = setOf("arm64-v8a", "armeabi-v7a")
    }
}
