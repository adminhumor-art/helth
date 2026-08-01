package com.sladkaya.sensor.sibionics

internal data class Gs1StreamFreshnessToken(
    val generation: Long,
    val sequence: Long,
)

internal data class Gs1StreamFreshnessDeadline(
    val token: Gs1StreamFreshnessToken,
    val delayMillis: Long,
    val dueAtElapsedMillis: Long,
)

internal data class Gs1StreamFreshnessTransition(
    val policy: Gs1StreamFreshnessPolicy,
    val deadline: Gs1StreamFreshnessDeadline,
)

internal sealed interface Gs1StreamFreshnessDecision {
    data object Stale : Gs1StreamFreshnessDecision
    data object Silent : Gs1StreamFreshnessDecision
}

/**
 * Monotonic transport-silence watchdog re-armed after durable core progress.
 * It does not claim that history/warm-up data is medically fresh. The timeout
 * is an engineering safety default pending physical trace validation.
 */
internal class Gs1StreamFreshnessPolicy private constructor(
    private val generation: Long,
    private val nextSequence: Long,
    private val activeToken: Gs1StreamFreshnessToken?,
) {
    fun arm(nowElapsedMillis: Long): Gs1StreamFreshnessTransition {
        require(nowElapsedMillis >= 0L) { "elapsed time must be non-negative" }
        val token = Gs1StreamFreshnessToken(generation, nextSequence)
        val dueAt = saturatingAdd(nowElapsedMillis, SILENCE_TIMEOUT_MILLIS)
        val delay = dueAt - nowElapsedMillis
        return Gs1StreamFreshnessTransition(
            policy = Gs1StreamFreshnessPolicy(
                generation = generation,
                nextSequence = increment(nextSequence),
                activeToken = token,
            ),
            deadline = Gs1StreamFreshnessDeadline(
                token = token,
                delayMillis = delay,
                dueAtElapsedMillis = dueAt,
            ),
        )
    }

    fun onTimerFired(token: Gs1StreamFreshnessToken): Gs1StreamFreshnessDecision =
        if (activeToken == token) {
            Gs1StreamFreshnessDecision.Silent
        } else {
            Gs1StreamFreshnessDecision.Stale
        }

    internal companion object {
        const val SILENCE_TIMEOUT_MILLIS = 180_000L

        fun begin(generation: Long): Gs1StreamFreshnessPolicy =
            Gs1StreamFreshnessPolicy(
                generation = generation,
                nextSequence = 1L,
                activeToken = null,
            )

        private fun increment(value: Long): Long =
            if (value == Long.MAX_VALUE) 1L else value + 1L

        private fun saturatingAdd(left: Long, right: Long): Long =
            if (left > Long.MAX_VALUE - right) Long.MAX_VALUE else left + right
    }
}
