package com.sladkaya.sensor.sibionics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class Gs1StreamFreshnessPolicyTest {
    @Test
    fun firstDurableCommitArmsBoundedSilenceDeadline() {
        val armed = Gs1StreamFreshnessPolicy.begin(generation = 8L)
            .arm(nowElapsedMillis = 1_000L)

        assertEquals(Gs1StreamFreshnessPolicy.SILENCE_TIMEOUT_MILLIS, armed.deadline.delayMillis)
        assertEquals(
            1_000L + Gs1StreamFreshnessPolicy.SILENCE_TIMEOUT_MILLIS,
            armed.deadline.dueAtElapsedMillis,
        )
        assertEquals(
            Gs1StreamFreshnessDecision.Silent,
            armed.policy.onTimerFired(armed.deadline.token),
        )
    }

    @Test
    fun laterDurableCommitMakesEarlierTimerStale() {
        val first = Gs1StreamFreshnessPolicy.begin(generation = 9L)
            .arm(nowElapsedMillis = 5_000L)
        val second = first.policy.arm(nowElapsedMillis = 15_000L)

        assertEquals(
            Gs1StreamFreshnessDecision.Stale,
            second.policy.onTimerFired(first.deadline.token),
        )
        assertEquals(
            Gs1StreamFreshnessDecision.Silent,
            second.policy.onTimerFired(second.deadline.token),
        )
    }

    @Test
    fun timerFromAnotherGenerationIsAlwaysStale() {
        val old = Gs1StreamFreshnessPolicy.begin(generation = 10L)
            .arm(nowElapsedMillis = 0L)
        val current = Gs1StreamFreshnessPolicy.begin(generation = 11L)
            .arm(nowElapsedMillis = 0L)

        assertEquals(
            Gs1StreamFreshnessDecision.Stale,
            current.policy.onTimerFired(old.deadline.token),
        )
    }

    @Test
    fun elapsedArithmeticSaturatesInsteadOfWrapping() {
        val armed = Gs1StreamFreshnessPolicy.begin(generation = 12L)
            .arm(nowElapsedMillis = Long.MAX_VALUE - 10L)

        assertEquals(Long.MAX_VALUE, armed.deadline.dueAtElapsedMillis)
        assertTrue(armed.deadline.delayMillis > 0L)
    }
}
