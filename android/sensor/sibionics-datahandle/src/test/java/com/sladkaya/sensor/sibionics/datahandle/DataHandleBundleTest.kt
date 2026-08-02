package com.sladkaya.sensor.sibionics.datahandle

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DataHandleBundleTest {
    @Test
    fun transportVariantSelectsOneExactOfficialBundleWithoutFallback() {
        assertEquals(DataHandleBundle.GLOBAL, DataHandleBundles.resolve(0))
        assertEquals(DataHandleBundle.CHINESE, DataHandleBundles.resolve(2))
        assertNull(DataHandleBundles.resolve(1))
        assertNull(DataHandleBundles.resolve(3))
        assertNull(DataHandleBundles.resolve(99))
    }

    @Test
    fun runtimeBundleIdentitiesAreDerivedFromTheGeneratedPinnedManifest() {
        assertEquals(
            DataHandleBundleBinaryIdentity.calculate(
                GeneratedDataHandleBinaryManifest.global,
            ),
            DataHandleBundle.GLOBAL.binarySetId,
        )
        assertEquals(
            DataHandleBundleBinaryIdentity.calculate(
                GeneratedDataHandleBinaryManifest.chinese,
            ),
            DataHandleBundle.CHINESE.binarySetId,
        )
        assertEquals(
            setOf(
                "arm64-v8a/libsladkaya-datahandle-global.so",
                "armeabi-v7a/libsladkaya-datahandle-global.so",
            ),
            GeneratedDataHandleBinaryManifest.global.keys,
        )
        assertEquals(
            setOf(
                "arm64-v8a/libsladkaya-datahandle-cn.so",
                "armeabi-v7a/libsladkaya-datahandle-cn.so",
            ),
            GeneratedDataHandleBinaryManifest.chinese.keys,
        )
        assertEquals("sladkaya-datahandle-global", DataHandleBundle.GLOBAL.libraryName)
        assertEquals("sladkaya-datahandle-cn", DataHandleBundle.CHINESE.libraryName)
    }

    @Test
    fun changingAnyPinnedBinaryHashChangesTheBundleIdentity() {
        val original = GeneratedDataHandleBinaryManifest.chinese
        val firstPath = original.keys.sorted().first()
        val altered = original + (firstPath to "00".repeat(32))

        assertNotEquals(
            DataHandleBundleBinaryIdentity.calculate(original),
            DataHandleBundleBinaryIdentity.calculate(altered),
        )
    }
}
