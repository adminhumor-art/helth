package com.sladkaya.sensor.sibionics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class Gs1GattDeadlinePolicyTest {
    @Test
    fun everyPhaseHasPositiveBoundedDeadline() {
        Gs1GattDeadlinePhase.entries.forEach { phase ->
            val transition = Gs1GattDeadlinePolicy.begin(
                generation = 7L,
                nowElapsedMillis = 1_000L,
                phase = phase,
            )

            assertTrue("$phase must be positive", transition.deadline.delayMillis > 0L)
            assertTrue(
                "$phase must be bounded by the total handshake budget",
                transition.deadline.delayMillis <= Gs1GattDeadlinePolicy.HANDSHAKE_BUDGET_MILLIS,
            )
        }
    }

    @Test
    fun oldTokenIsStaleImmediatelyAfterPhaseTransition() {
        val connecting = Gs1GattDeadlinePolicy.begin(
            generation = 9L,
            nowElapsedMillis = 10L,
            phase = Gs1GattDeadlinePhase.CONNECTING,
        )
        val discovering = connecting.policy.enter(
            phase = Gs1GattDeadlinePhase.DISCOVERING,
            nowElapsedMillis = 100L,
        )

        assertFalse(discovering.policy.accepts(connecting.deadline.token))
        assertTrue(discovering.policy.accepts(discovering.deadline.token))
        assertEquals(
            Gs1GattTimerDecision.Stale,
            discovering.policy.onTimerFired(connecting.deadline.token),
        )
        assertEquals(
            Gs1GattTimerDecision.TerminalTimeout(Gs1GattDeadlinePhase.DISCOVERING),
            discovering.policy.onTimerFired(discovering.deadline.token),
        )
    }

    @Test
    fun authRetryIsOneSecondAndBecomesTerminalAtLimit() {
        var policy = Gs1GattDeadlinePolicy.begin(
            generation = 11L,
            nowElapsedMillis = 0L,
            phase = Gs1GattDeadlinePhase.HANDSHAKE,
        ).policy

        repeat(Gs1GattDeadlinePolicy.MAX_AUTH_RETRIES) { retryIndex ->
            val decision = policy.requestAuthRetry(nowElapsedMillis = retryIndex * 1_000L)
            assertTrue(decision is Gs1GattAuthRetryDecision.Allowed)
            decision as Gs1GattAuthRetryDecision.Allowed
            assertEquals(1_000L, decision.delayMillis)
            policy = decision.policy
        }

        assertEquals(
            Gs1GattAuthRetryDecision.Terminal("AUTH_RETRY_LIMIT", retryable = true),
            policy.requestAuthRetry(nowElapsedMillis = 4_000L),
        )
    }

    @Test
    fun authRetryCanBeScheduledBeforeTheCurrentWriteCallbackArrives() {
        val commandWrite = Gs1GattDeadlinePolicy.begin(
            generation = 15L,
            nowElapsedMillis = 0L,
            phase = Gs1GattDeadlinePhase.COMMAND_WRITE,
        ).policy

        val decision = commandWrite.requestAuthRetry(nowElapsedMillis = 100L)

        assertTrue(decision is Gs1GattAuthRetryDecision.Allowed)
        assertEquals(1_000L, (decision as Gs1GattAuthRetryDecision.Allowed).delayMillis)
    }

    @Test
    fun authRetryCannotOutliveOverallHandshakeDeadline() {
        val startedAt = 5_000L
        val policy = Gs1GattDeadlinePolicy.begin(
            generation = 12L,
            nowElapsedMillis = startedAt,
            phase = Gs1GattDeadlinePhase.HANDSHAKE,
        ).policy

        assertEquals(
            Gs1GattAuthRetryDecision.Terminal("HANDSHAKE_DEADLINE", retryable = true),
            policy.requestAuthRetry(
                nowElapsedMillis = startedAt + Gs1GattDeadlinePolicy.HANDSHAKE_BUDGET_MILLIS,
            ),
        )
        assertEquals(
            Gs1GattAuthRetryDecision.Terminal("HANDSHAKE_DEADLINE", retryable = true),
            policy.requestAuthRetry(
                nowElapsedMillis = startedAt +
                    Gs1GattDeadlinePolicy.HANDSHAKE_BUDGET_MILLIS -
                    999L,
            ),
        )
    }

    @Test
    fun successfulStreamingInvalidatesHandshakeTimerAndRetries() {
        val handshake = Gs1GattDeadlinePolicy.begin(
            generation = 13L,
            nowElapsedMillis = 0L,
            phase = Gs1GattDeadlinePhase.HANDSHAKE,
        )
        val streaming = handshake.policy.markStreaming()

        assertFalse(streaming.accepts(handshake.deadline.token))
        assertEquals(Gs1GattTimerDecision.Stale, streaming.onTimerFired(handshake.deadline.token))
        assertEquals(
            Gs1GattAuthRetryDecision.Terminal("HANDSHAKE_COMPLETE", retryable = false),
            streaming.requestAuthRetry(nowElapsedMillis = 1L),
        )
    }

    @Test
    fun elapsedArithmeticSaturatesWithoutOverflow() {
        val now = Long.MAX_VALUE - 100L
        val transition = Gs1GattDeadlinePolicy.begin(
            generation = 14L,
            nowElapsedMillis = now,
            phase = Gs1GattDeadlinePhase.HANDSHAKE,
        )

        assertEquals(Long.MAX_VALUE, transition.deadline.dueAtElapsedMillis)
        assertEquals(100L, transition.deadline.delayMillis)
        assertEquals(
            Gs1GattAuthRetryDecision.Terminal("HANDSHAKE_DEADLINE", retryable = true),
            transition.policy.requestAuthRetry(nowElapsedMillis = Long.MAX_VALUE),
        )
    }
}
