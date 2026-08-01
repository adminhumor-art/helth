package com.sladkaya.sensor.sibionics.datahandle

import java.lang.reflect.Modifier
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DataHandleJniAbiTest {
    @Test
    fun classMatchesRequiredJniAbi() {
        val type = Class.forName("com.no.sisense.enanddecryption.CGMDataHandle130")

        assertNativeStatic(type, "v120RegisterKey", Int::class.javaPrimitiveType, ByteArray::class.java, Int::class.javaPrimitiveType, ByteArray::class.java)
        assertNativeStatic(type, "V120ApplyAuthentication", Int::class.javaPrimitiveType, Int::class.javaPrimitiveType, Boolean::class.javaPrimitiveType, Int::class.javaPrimitiveType, ByteArray::class.java, ByteArray::class.java, Int::class.javaPrimitiveType)
        assertNativeStatic(type, "V120Activation", Int::class.javaPrimitiveType, Int::class.javaPrimitiveType, Boolean::class.javaPrimitiveType, ByteArray::class.java, Long::class.javaPrimitiveType, Int::class.javaPrimitiveType, ByteArray::class.java, Int::class.javaPrimitiveType)
        assertNativeStatic(type, "V120IsecUpdate", Int::class.javaPrimitiveType, Int::class.javaPrimitiveType, Boolean::class.javaPrimitiveType, ByteArray::class.java, Long::class.javaPrimitiveType, ByteArray::class.java, Int::class.javaPrimitiveType)
        assertNativeStatic(type, "V120RawData", Int::class.javaPrimitiveType, Int::class.javaPrimitiveType, Boolean::class.javaPrimitiveType, ByteArray::class.java, Long::class.javaPrimitiveType, Int::class.javaPrimitiveType, ByteArray::class.java, Int::class.javaPrimitiveType)
        assertNativeStatic(type, "V120Reset", Int::class.javaPrimitiveType, Int::class.javaPrimitiveType, Boolean::class.javaPrimitiveType, ByteArray::class.java, Int::class.javaPrimitiveType, ByteArray::class.java, Int::class.javaPrimitiveType)
        assertNativeStatic(type, "V120SpiltData", Int::class.javaPrimitiveType, Int::class.javaPrimitiveType, ByteArray::class.java, IntArray::class.java, ByteArray::class.java, Boolean::class.javaPrimitiveType, ByteArray::class.java, Int::class.javaPrimitiveType)
    }

    private fun assertNativeStatic(
        type: Class<*>,
        name: String,
        returnType: Class<*>?,
        vararg parameterTypes: Class<*>?,
    ) {
        val method = type.getDeclaredMethod(name, *parameterTypes)
        assertTrue("$name must be native", Modifier.isNative(method.modifiers))
        assertTrue("$name must be static", Modifier.isStatic(method.modifiers))
        assertEquals(returnType, method.returnType)
    }
}
