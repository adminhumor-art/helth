package com.sladkaya.app.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SignalLossWatchdogCodecTest {
    @Test
    fun roundTripPreservesStateAndTamperingFailsClosed() {
        val codec = SignalLossWatchdogCodec()
        val state = SignalLossWatchdogState(
            generation = 7L,
            readingIdentity = "c".repeat(64),
            sensorTimeEpochMs = 1_800_000_000_000L,
            phoneTimeEpochMs = 1_800_000_001_000L,
            staleAfterMs = 600_000L,
            demo = false,
        )
        val encoded = codec.encode(state)

        assertEquals(SignalLossWatchdogDecodeResult.Success(state), codec.decode(encoded))
        assertTrue(
            codec.decode(encoded.replace("|7|", "|8|")) ===
                SignalLossWatchdogDecodeResult.Failure,
        )
    }
}
