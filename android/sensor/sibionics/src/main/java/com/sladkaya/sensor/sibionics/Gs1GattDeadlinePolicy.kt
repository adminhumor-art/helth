package com.sladkaya.sensor.sibionics

internal enum class Gs1GattDeadlinePhase {
    CONNECTING,
    DISCOVERING,
    SUBSCRIBING,
    COMMAND_WRITE,
    HANDSHAKE,
}

internal data class Gs1GattDeadlineToken(
    val generation: Long,
    val sequence: Long,
    val phase: Gs1GattDeadlinePhase,
)

internal data class Gs1GattDeadline(
    val token: Gs1GattDeadlineToken,
    val delayMillis: Long,
    val dueAtElapsedMillis: Long,
)

internal data class Gs1GattDeadlineTransition(
    val policy: Gs1GattDeadlinePolicy,
    val deadline: Gs1GattDeadline,
)

internal sealed interface Gs1GattTimerDecision {
    data object Stale : Gs1GattTimerDecision
    data class TerminalTimeout(val phase: Gs1GattDeadlinePhase) : Gs1GattTimerDecision
}

internal sealed interface Gs1GattAuthRetryDecision {
    data class Allowed(
        val policy: Gs1GattDeadlinePolicy,
        val delayMillis: Long,
    ) : Gs1GattAuthRetryDecision

    data class Terminal(
        val code: String,
        val retryable: Boolean,
    ) : Gs1GattAuthRetryDecision
}

/**
 * Immutable deadline and authentication-retry policy for the GATT actor.
 *
 * Durations below are conservative engineering defaults, not timings claimed
 * by the sensor manufacturer. They remain pending validation with a physical
 * sensor trace. The policy uses monotonic elapsed time supplied by its caller.
 */
internal class Gs1GattDeadlinePolicy private constructor(
    private val generation: Long,
    private val nextSequence: Long,
    private val activeDeadlineToken: Gs1GattDeadlineToken?,
    private val handshakeDueAtElapsedMillis: Long,
    private val authRetryCount: Int,
    private val handshakeComplete: Boolean,
) {
    fun enter(
        phase: Gs1GattDeadlinePhase,
        nowElapsedMillis: Long,
    ): Gs1GattDeadlineTransition {
        require(nowElapsedMillis >= 0L) { "elapsed time must be non-negative" }
        check(!handshakeComplete) { "handshake is already complete" }

        val token = Gs1GattDeadlineToken(
            generation = generation,
            sequence = nextSequence,
            phase = phase,
        )
        val phaseDelay = phaseDurationMillis(phase)
        val handshakeRemaining = remainingUntil(handshakeDueAtElapsedMillis, nowElapsedMillis)
        val delay = minOf(phaseDelay, handshakeRemaining)
        val dueAt = saturatingAdd(nowElapsedMillis, delay)
        val next = Gs1GattDeadlinePolicy(
            generation = generation,
            nextSequence = incrementSequence(nextSequence),
            activeDeadlineToken = token,
            handshakeDueAtElapsedMillis = handshakeDueAtElapsedMillis,
            authRetryCount = authRetryCount,
            handshakeComplete = false,
        )
        return Gs1GattDeadlineTransition(
            policy = next,
            deadline = Gs1GattDeadline(
                token = token,
                delayMillis = delay,
                dueAtElapsedMillis = dueAt,
            ),
        )
    }

    fun accepts(token: Gs1GattDeadlineToken): Boolean =
        !handshakeComplete && activeDeadlineToken == token

    fun onTimerFired(token: Gs1GattDeadlineToken): Gs1GattTimerDecision =
        if (accepts(token)) {
            Gs1GattTimerDecision.TerminalTimeout(token.phase)
        } else {
            Gs1GattTimerDecision.Stale
        }

    fun requestAuthRetry(nowElapsedMillis: Long): Gs1GattAuthRetryDecision {
        require(nowElapsedMillis >= 0L) { "elapsed time must be non-negative" }
        if (handshakeComplete) {
            return Gs1GattAuthRetryDecision.Terminal("HANDSHAKE_COMPLETE", retryable = false)
        }
        if (activeDeadlineToken?.phase != Gs1GattDeadlinePhase.HANDSHAKE &&
            activeDeadlineToken?.phase != Gs1GattDeadlinePhase.COMMAND_WRITE
        ) {
            return Gs1GattAuthRetryDecision.Terminal("AUTH_RETRY_WRONG_PHASE", retryable = false)
        }
        if (nowElapsedMillis >= handshakeDueAtElapsedMillis) {
            return Gs1GattAuthRetryDecision.Terminal("HANDSHAKE_DEADLINE", retryable = true)
        }
        if (authRetryCount >= MAX_AUTH_RETRIES) {
            return Gs1GattAuthRetryDecision.Terminal("AUTH_RETRY_LIMIT", retryable = true)
        }

        val retryDueAt = saturatingAdd(nowElapsedMillis, AUTH_RETRY_DELAY_MILLIS)
        if (retryDueAt >= handshakeDueAtElapsedMillis) {
            return Gs1GattAuthRetryDecision.Terminal("HANDSHAKE_DEADLINE", retryable = true)
        }
        return Gs1GattAuthRetryDecision.Allowed(
            policy = Gs1GattDeadlinePolicy(
                generation = generation,
                nextSequence = nextSequence,
                activeDeadlineToken = activeDeadlineToken,
                handshakeDueAtElapsedMillis = handshakeDueAtElapsedMillis,
                authRetryCount = authRetryCount + 1,
                handshakeComplete = false,
            ),
            delayMillis = AUTH_RETRY_DELAY_MILLIS,
        )
    }

    fun markStreaming(): Gs1GattDeadlinePolicy = Gs1GattDeadlinePolicy(
        generation = generation,
        nextSequence = nextSequence,
        activeDeadlineToken = null,
        handshakeDueAtElapsedMillis = handshakeDueAtElapsedMillis,
        authRetryCount = authRetryCount,
        handshakeComplete = true,
    )

    internal companion object {
        // Conservative engineering defaults pending physical sensor traces.
        const val HANDSHAKE_BUDGET_MILLIS = 75_000L
        const val MAX_AUTH_RETRIES = 3
        private const val AUTH_RETRY_DELAY_MILLIS = 1_000L
        private const val CONNECTING_TIMEOUT_MILLIS = 20_000L
        private const val DISCOVERING_TIMEOUT_MILLIS = 15_000L
        private const val SUBSCRIBING_TIMEOUT_MILLIS = 10_000L
        private const val COMMAND_WRITE_TIMEOUT_MILLIS = 8_000L
        private const val HANDSHAKE_TIMEOUT_MILLIS = 20_000L

        fun begin(
            generation: Long,
            nowElapsedMillis: Long,
            phase: Gs1GattDeadlinePhase = Gs1GattDeadlinePhase.CONNECTING,
        ): Gs1GattDeadlineTransition {
            require(nowElapsedMillis >= 0L) { "elapsed time must be non-negative" }
            val handshakeDueAt = saturatingAdd(nowElapsedMillis, HANDSHAKE_BUDGET_MILLIS)
            return Gs1GattDeadlinePolicy(
                generation = generation,
                nextSequence = 1L,
                activeDeadlineToken = null,
                handshakeDueAtElapsedMillis = handshakeDueAt,
                authRetryCount = 0,
                handshakeComplete = false,
            ).enter(phase, nowElapsedMillis)
        }

        private fun phaseDurationMillis(phase: Gs1GattDeadlinePhase): Long = when (phase) {
            Gs1GattDeadlinePhase.CONNECTING -> CONNECTING_TIMEOUT_MILLIS
            Gs1GattDeadlinePhase.DISCOVERING -> DISCOVERING_TIMEOUT_MILLIS
            Gs1GattDeadlinePhase.SUBSCRIBING -> SUBSCRIBING_TIMEOUT_MILLIS
            Gs1GattDeadlinePhase.COMMAND_WRITE -> COMMAND_WRITE_TIMEOUT_MILLIS
            Gs1GattDeadlinePhase.HANDSHAKE -> HANDSHAKE_TIMEOUT_MILLIS
        }

        private fun remainingUntil(deadline: Long, now: Long): Long =
            if (now >= deadline) 0L else deadline - now

        private fun saturatingAdd(value: Long, increment: Long): Long =
            if (increment > Long.MAX_VALUE - value) Long.MAX_VALUE else value + increment

        private fun incrementSequence(value: Long): Long =
            if (value == Long.MAX_VALUE) 1L else value + 1L
    }
}
