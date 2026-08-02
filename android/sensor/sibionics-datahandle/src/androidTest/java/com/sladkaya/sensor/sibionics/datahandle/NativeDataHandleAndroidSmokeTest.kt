package com.sladkaya.sensor.sibionics.datahandle

import android.os.Build
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class NativeDataHandleAndroidSmokeTest {
    @Test
    fun pinnedLibraryLoadsAndBuildsResetCommandThroughRealJni() {
        assertTrue(
            "Pinned native library requires an ARM Android runtime; device ABIs=" +
                Build.SUPPORTED_ABIS.joinToString(),
            Build.SUPPORTED_ABIS.any(SUPPORTED_ABIS::contains),
        )

        val result = SibionicsDataHandle().reset()

        assertTrue(result is DataHandleCommandResult.Success)
        result as DataHandleCommandResult.Success
        assertTrue(result.bytes.isNotEmpty())
    }

    private companion object {
        val SUPPORTED_ABIS = setOf("arm64-v8a", "armeabi-v7a")
    }
}
