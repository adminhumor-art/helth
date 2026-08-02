package com.sladkaya.sensor.sibionics

import org.junit.Assert.assertEquals
import org.junit.Test

class Gs1CoreEventDeliveryTest {
    @Test
    fun committedDeliveryFailureRetainsMachineCodeDetailAndRetryability() {
        val failure = Gs1CommittedDeliveryUnavailableException(
            code = "LOCAL_EFFECTS_TIMEOUT",
            detail = "local effects timed out",
            retryable = true,
        )

        assertEquals("LOCAL_EFFECTS_TIMEOUT", failure.code)
        assertEquals("local effects timed out", failure.message)
        assertEquals(true, failure.retryable)
    }
}
