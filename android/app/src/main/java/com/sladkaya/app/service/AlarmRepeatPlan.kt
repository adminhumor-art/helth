package com.sladkaya.app.service

internal enum class AlarmRepeatScheduleKind {
    NONE,
    EXACT_WAKEUP,
    INEXACT_WAKEUP,
}

internal data class AlarmRepeatPlan(
    val kind: AlarmRepeatScheduleKind,
    val triggerAtEpochMs: Long,
    val revocationWatchdogAtEpochMs: Long?,
) {
    companion object {
        val NONE = AlarmRepeatPlan(
            kind = AlarmRepeatScheduleKind.NONE,
            triggerAtEpochMs = 0L,
            revocationWatchdogAtEpochMs = null,
        )
    }
}

internal object AlarmRepeatPlanPolicy {
    const val MIN_DELAY_MS = 1_000L
    const val REVOCATION_WATCHDOG_GRACE_MS = 60_000L

    fun plan(
        episode: AlarmEpisode?,
        nowEpochMs: Long,
        repeatIntervalMs: Long,
        exactAlarmAccess: Boolean,
        notBeforeEpochMs: Long? = null,
    ): AlarmRepeatPlan {
        require(nowEpochMs > 0L)
        require(repeatIntervalMs > 0L)
        require(notBeforeEpochMs == null || notBeforeEpochMs > 0L)
        if (episode == null || episode.acknowledged) return AlarmRepeatPlan.NONE
        val nominal = if (episode.lastAlertAtEpochMs > Long.MAX_VALUE - repeatIntervalMs) {
            nowEpochMs
        } else {
            episode.lastAlertAtEpochMs + repeatIntervalMs
        }
        val clockRolledBack = episode.lastAlertAtEpochMs > nowEpochMs
        val calculatedTrigger = if (clockRolledBack || nominal <= nowEpochMs) {
            nowEpochMs + MIN_DELAY_MS
        } else {
            nominal
        }
        val trigger = maxOf(calculatedTrigger, notBeforeEpochMs ?: calculatedTrigger)
        return AlarmRepeatPlan(
            kind = if (exactAlarmAccess) {
                AlarmRepeatScheduleKind.EXACT_WAKEUP
            } else {
                AlarmRepeatScheduleKind.INEXACT_WAKEUP
            },
            triggerAtEpochMs = trigger,
            revocationWatchdogAtEpochMs = if (exactAlarmAccess) {
                safeAdd(trigger, REVOCATION_WATCHDOG_GRACE_MS)
            } else {
                null
            },
        )
    }

    private fun safeAdd(value: Long, delta: Long): Long =
        if (value > Long.MAX_VALUE - delta) Long.MAX_VALUE else value + delta
}

internal object AlarmDeliveryWakePolicy {
    const val TIMEOUT_MS = 10_000L
}

internal enum class AlarmRepeatDeliveryDecision(
    val attemptNotification: Boolean,
    val markDelivered: Boolean,
) {
    CANCEL(attemptNotification = false, markDelivered = false),
    RESCHEDULE(attemptNotification = false, markDelivered = false),
    BEST_EFFORT_RETRY(attemptNotification = true, markDelivered = false),
    ALERT(attemptNotification = true, markDelivered = true),
}

internal object AlarmRepeatDeliveryPolicy {
    fun decide(
        episode: AlarmEpisode,
        alarmReady: Boolean,
        repeatDue: Boolean,
    ): AlarmRepeatDeliveryDecision = when {
        episode.acknowledged -> AlarmRepeatDeliveryDecision.CANCEL
        !alarmReady -> AlarmRepeatDeliveryDecision.BEST_EFFORT_RETRY
        repeatDue -> AlarmRepeatDeliveryDecision.ALERT
        else -> AlarmRepeatDeliveryDecision.RESCHEDULE
    }
}
