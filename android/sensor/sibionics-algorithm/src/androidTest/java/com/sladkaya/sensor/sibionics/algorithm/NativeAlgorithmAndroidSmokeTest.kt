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
    fun v116aProductContractExposesOnlyTheOfficialStandardInitialization() {
        requireSupportedArmRuntime()

        assertEquals(
            setOf(AlgorithmInitializationMode.STANDARD),
            V116ANativeAlgorithmApi().supportedInitializationModes,
        )
    }

    @Test
    fun v115gProductContractExposesOnlyTheProvenStandardInitialization() {
        requireSupportedArmRuntime()

        assertEquals(
            setOf(AlgorithmInitializationMode.STANDARD),
            V115GNativeAlgorithmApi().supportedInitializationModes,
        )
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
