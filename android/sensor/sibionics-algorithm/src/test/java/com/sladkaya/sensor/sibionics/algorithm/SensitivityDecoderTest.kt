package com.sladkaya.sensor.sibionics.algorithm

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SensitivityDecoderTest {
    @Test
    fun normalValueIsAcceptedWithoutCallingFactionDecoder() {
        val native = FakeSensitivityApi(normal = 1.42f, faction = 1.99f)

        val result = SensitivityDecoder(native).decode(SensitivityToken.packageCode("aB12cd34"))

        val decoded = result as SensitivityDecodeResult.Success
        assertEquals(1.42f, decoded.value.coefficient, 0.0001f)
        assertEquals(SensitivityEncoding.NORMAL, decoded.value.encoding)
        assertEquals(listOf("normal:aB12cd34"), native.calls)
    }

    @Test
    fun factionDecoderIsCalledOnlyForExactMinusOneNormalResult() {
        val native = FakeSensitivityApi(normal = -1.0f, faction = 1.58f)

        val result = SensitivityDecoder(native).decode(SensitivityToken.packageCode("ABCDEFGH"))

        val decoded = result as SensitivityDecodeResult.Success
        assertEquals(1.58f, decoded.value.coefficient, 0.0001f)
        assertEquals(SensitivityEncoding.FACTION, decoded.value.encoding)
        assertEquals(listOf("normal:ABCDEFGH", "faction:ABCDEFGH"), native.calls)
    }

    @Test
    fun outOfRangeNormalValueDoesNotTriggerUnprovenFallback() {
        val native = FakeSensitivityApi(normal = 0.79f, faction = 1.4f)

        val result = SensitivityDecoder(native).decode(SensitivityToken.packageCode("ABCDEFGH"))

        assertEquals(
            SensitivityDecodeError.OUT_OF_RANGE,
            (result as SensitivityDecodeResult.Failure).error,
        )
        assertEquals(listOf("normal:ABCDEFGH"), native.calls)
    }

    @Test
    fun invalidFactionResultAndNativeExceptionFailClosed() {
        val invalid = SensitivityDecoder(FakeSensitivityApi(-1.0f, -1.0f))
            .decode(SensitivityToken.packageCode("ABCDEFGH"))
        val throwing = SensitivityDecoder(FakeSensitivityApi(1.0f, 1.0f, throws = true))
            .decode(SensitivityToken.packageCode("ABCDEFGH"))

        assertEquals(
            SensitivityDecodeError.DECODING_FAILED,
            (invalid as SensitivityDecodeResult.Failure).error,
        )
        assertTrue(throwing is SensitivityDecodeResult.Failure)
        assertEquals(
            SensitivityDecodeError.NATIVE_CALL_FAILED,
            (throwing as SensitivityDecodeResult.Failure).error,
        )
    }
}

private class FakeSensitivityApi(
    private val normal: Float,
    private val faction: Float,
    private val throws: Boolean = false,
) : NativeSensitivityApi {
    val calls = mutableListOf<String>()

    override fun decodeNormal(token: String): Float {
        calls += "normal:$token"
        if (throws) error("native failure")
        return normal
    }

    override fun decodeFaction(token: String): Float {
        calls += "faction:$token"
        if (throws) error("native failure")
        return faction
    }
}
