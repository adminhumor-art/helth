package com.sladkaya.sensor.sibionics.datahandle

import android.os.Build
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class NativeDataHandleAndroidSmokeTest {
    @Test
    fun eachPinnedLibraryLoadsInItsOwnProcessAndBuildsAResetCommand() = runBlocking {
        assertTrue(
            "Pinned native library requires an ARM Android runtime; device ABIs=" +
                Build.SUPPORTED_ABIS.joinToString(),
            Build.SUPPORTED_ABIS.any(SUPPORTED_ABIS::contains),
        )

        val context = InstrumentationRegistry.getInstrumentation().targetContext
        listOf(0 to DataHandleBundle.GLOBAL, 2 to DataHandleBundle.CHINESE).forEach {
                (transportVariant, expectedBundle) ->
            val opened = RemoteDataHandleGatewayConnector.connect(context, transportVariant)
            assertTrue(opened.toString(), opened is DataHandleGatewayOpenResult.Success)
            opened as DataHandleGatewayOpenResult.Success
            opened.gateway.use { gateway ->
                assertEquals(expectedBundle, gateway.bundle)
                val result = gateway.reset()
                assertTrue(result.toString(), result is DataHandleCommandResult.Success)
                result as DataHandleCommandResult.Success
                assertTrue(result.bytes.isNotEmpty())
            }
        }
    }

    private companion object {
        val SUPPORTED_ABIS = setOf("arm64-v8a", "armeabi-v7a")
    }
}
