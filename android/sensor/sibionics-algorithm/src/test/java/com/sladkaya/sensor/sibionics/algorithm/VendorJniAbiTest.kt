package com.sladkaya.sensor.sibionics.algorithm

import java.lang.reflect.Modifier
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class VendorJniAbiTest {
    @Test
    fun v116aClassesMatchRequiredJniAbi() {
        assertContext("com.algorithm.v116a.AlgorithmContext")
        val library = assertLibrary(
            className = "com.algorithm.v116a.NativeAlgorithmLibraryV116A",
            contextName = "com.algorithm.v116a.AlgorithmContext",
        )
        assertSensitivityApi(library)
    }

    @Test
    fun v115gClassesMatchRequiredJniAbi() {
        assertContext("com.algorithm.v1_1_5_g.AlgorithmContext")
        val library = assertLibrary(
            className = "com.algorithm.v1_11_15_1g.NativeAlgorithmLibraryV1_11_15G",
            contextName = "com.algorithm.v1_1_5_g.AlgorithmContext",
        )
        val context = Class.forName("com.algorithm.v1_1_5_g.AlgorithmContext")
        assertNativeStatic(library.getDeclaredMethod("getJsonAlgorithmContext", context), String::class.java)
        assertNativeStatic(
            library.getDeclaredMethod("setJsonAlgorithmContext", context, String::class.java),
            Int::class.javaPrimitiveType,
        )
        assertNativeStatic(library.getDeclaredMethod("getSensitivityVersion"), String::class.java)
        assertNativeStatic(
            library.getDeclaredMethod("initAlgorithmContextFaction", context, Int::class.javaPrimitiveType, String::class.java),
            Int::class.javaPrimitiveType,
        )
        assertSensitivityApi(Class.forName("com.algorithm.v1_11_15.NativeAlgorithmLibraryv1_11_15"), includeInitialization = false)
    }

    private fun assertContext(className: String) {
        val type = Class.forName(className)
        val expectedFields = mapOf(
            "mNativeContext" to Long::class.javaPrimitiveType,
            "ig_data" to Double::class.javaPrimitiveType,
            "glucoseWarning" to Int::class.javaPrimitiveType,
            "currentWarning" to Int::class.javaPrimitiveType,
            "temperatureWarning" to Int::class.javaPrimitiveType,
            "ig_trend" to Int::class.javaPrimitiveType,
        )
        assertEquals(expectedFields, type.fields.associate { it.name to it.type })
        assertTrue(Modifier.isPublic(type.getConstructor().modifiers))
    }

    private fun assertLibrary(className: String, contextName: String): Class<*> {
        val type = Class.forName(className)
        val context = Class.forName(contextName)

        assertNativeStatic(type.getDeclaredMethod("getAlgorithmContextFromNative"), context)
        assertNativeStatic(
            type.getDeclaredMethod(
                "initAlgorithmContext",
                context,
                Int::class.javaPrimitiveType,
                String::class.java,
            ),
            Int::class.javaPrimitiveType,
        )
        assertNativeStatic(
            type.getDeclaredMethod(
                "processAlgorithmContext",
                context,
                Int::class.javaPrimitiveType,
                Double::class.javaPrimitiveType,
                Double::class.javaPrimitiveType,
                Double::class.javaPrimitiveType,
                Double::class.javaPrimitiveType,
                Double::class.javaPrimitiveType,
            ),
            Double::class.javaPrimitiveType,
        )
        assertNativeStatic(type.getDeclaredMethod("releaseAlgorithmContext", context), Int::class.javaPrimitiveType)
        assertNativeStatic(type.getDeclaredMethod("getBinaryStructAlgorithmContext", context), ByteArray::class.java)
        assertNativeStatic(
            type.getDeclaredMethod("setBinaryStructAlgorithmContext", context, ByteArray::class.java),
            Int::class.javaPrimitiveType,
        )
        assertNativeStatic(type.getDeclaredMethod("getAlgorithmVersion"), String::class.java)
        assertNativeStatic(type.getDeclaredMethod("getMyAlgorithmLibraryVersion"), String::class.java)
        return type
    }

    private fun assertSensitivityApi(type: Class<*>, includeInitialization: Boolean = true) {
        if (includeInitialization) {
            val context = Class.forName("com.algorithm.v116a.AlgorithmContext")
            assertNativeStatic(type.getDeclaredMethod("getSensitivityVersion"), String::class.java)
            assertNativeStatic(
                type.getDeclaredMethod("initAlgorithmContextFaction", context, Int::class.javaPrimitiveType, String::class.java),
                Int::class.javaPrimitiveType,
            )
        }
        assertNativeStatic(type.getDeclaredMethod("encryptSensitivity", String::class.java), String::class.java)
        assertNativeStatic(type.getDeclaredMethod("decryptSensitivity", String::class.java), Float::class.javaPrimitiveType)
        assertNativeStatic(type.getDeclaredMethod("encryptSensitivityFaction", String::class.java), String::class.java)
        assertNativeStatic(type.getDeclaredMethod("decryptSensitivityFaction", String::class.java), Float::class.javaPrimitiveType)
    }

    private fun assertNativeStatic(method: java.lang.reflect.Method, returnType: Class<*>?) {
        assertTrue("${method.name} must be native", Modifier.isNative(method.modifiers))
        assertTrue("${method.name} must be static", Modifier.isStatic(method.modifiers))
        assertEquals(returnType, method.returnType)
    }
}
