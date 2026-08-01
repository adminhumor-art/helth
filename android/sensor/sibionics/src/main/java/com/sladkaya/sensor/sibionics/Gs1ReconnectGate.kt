package com.sladkaya.sensor.sibionics

internal data class Gs1ReconnectToken(val sessionId: Long)

internal data class Gs1ReconnectPlan(
    val token: Gs1ReconnectToken,
    val attempt: Int,
    val delayMillis: Long,
)

/** Thread-safe logical cancellation and bounded backoff for reconnect timers. */
internal class Gs1ReconnectGate {
    private var nextSessionId = 1L
    private var active: Active? = null

    @Synchronized
    fun begin(): Gs1ReconnectToken {
        val token = Gs1ReconnectToken(nextSessionId)
        nextSessionId = if (nextSessionId == Long.MAX_VALUE) 1L else nextSessionId + 1L
        active = Active(token)
        return token
    }

    @Synchronized
    fun onRetryableFailure(token: Gs1ReconnectToken): Gs1ReconnectPlan? {
        val current = active ?: return null
        if (current.token != token) return null
        current.attempt = if (current.attempt == Int.MAX_VALUE) Int.MAX_VALUE else current.attempt + 1
        current.consumedAttempt = 0
        return Gs1ReconnectPlan(
            token = token,
            attempt = current.attempt,
            delayMillis = retryDelay(current.attempt),
        )
    }

    @Synchronized
    fun consumeIfCurrent(plan: Gs1ReconnectPlan): Boolean {
        val current = active ?: return false
        if (current.token != plan.token || current.attempt != plan.attempt) return false
        if (current.consumedAttempt == plan.attempt) return false
        current.consumedAttempt = plan.attempt
        return true
    }

    @Synchronized
    fun markStable(token: Gs1ReconnectToken): Boolean {
        val current = active ?: return false
        if (current.token != token) return false
        current.attempt = 0
        current.consumedAttempt = 0
        return true
    }

    @Synchronized
    fun stop(token: Gs1ReconnectToken): Boolean {
        val current = active ?: return false
        if (current.token != token) return false
        active = null
        return true
    }

    private fun retryDelay(attempt: Int): Long {
        val shift = (attempt - 1).coerceIn(0, MAX_SHIFT)
        return (BASE_DELAY_MILLIS shl shift).coerceAtMost(MAX_DELAY_MILLIS)
    }

    private data class Active(
        val token: Gs1ReconnectToken,
        var attempt: Int = 0,
        var consumedAttempt: Int = 0,
    )

    private companion object {
        // Conservative engineering defaults pending physical disconnect traces.
        const val BASE_DELAY_MILLIS = 1_000L
        const val MAX_DELAY_MILLIS = 60_000L
        const val MAX_SHIFT = 16
    }
}
