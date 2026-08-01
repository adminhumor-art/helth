package com.sladkaya.sensor.sibionics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class Gs1ReconnectGateTest {
    @Test
    fun retryableFailuresUseBoundedExponentialBackoff() {
        val gate = Gs1ReconnectGate()
        val token = gate.begin()

        val delays = (1..10).map { gate.onRetryableFailure(token)!!.delayMillis }

        assertEquals(
            listOf(1_000L, 2_000L, 4_000L, 8_000L, 16_000L, 32_000L, 60_000L, 60_000L, 60_000L, 60_000L),
            delays,
        )
    }

    @Test
    fun stopInvalidatesScheduledReconnectAndPlanCanOnlyBeConsumedOnce() {
        val gate = Gs1ReconnectGate()
        val token = gate.begin()
        val first = gate.onRetryableFailure(token)!!

        assertTrue(gate.consumeIfCurrent(first))
        assertFalse(gate.consumeIfCurrent(first))

        val second = gate.onRetryableFailure(token)!!
        gate.stop(token)
        assertFalse(gate.consumeIfCurrent(second))
    }

    @Test
    fun stableStreamResetsBackoffAndInvalidatesOlderTimer() {
        val gate = Gs1ReconnectGate()
        val token = gate.begin()
        val stale = gate.onRetryableFailure(token)!!
        gate.markStable(token)

        assertFalse(gate.consumeIfCurrent(stale))
        val afterStable = gate.onRetryableFailure(token)!!
        assertEquals(1, afterStable.attempt)
        assertEquals(1_000L, afterStable.delayMillis)
    }

    @Test
    fun newManualSessionMakesEveryOldPlanStale() {
        val gate = Gs1ReconnectGate()
        val old = gate.begin()
        val oldPlan = gate.onRetryableFailure(old)!!
        val fresh = gate.begin()

        assertFalse(gate.consumeIfCurrent(oldPlan))
        assertEquals(1, gate.onRetryableFailure(fresh)!!.attempt)
    }
}
