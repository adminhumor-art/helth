package com.sladkaya.app.service

import com.sladkaya.core.model.AlarmKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AlarmRepeatPlanPolicyTest {
    @Test
    fun unacknowledgedEpisodeGetsExactWakeupWhenAccessExists() {
        assertEquals(
            AlarmRepeatPlan(
                kind = AlarmRepeatScheduleKind.EXACT_WAKEUP,
                triggerAtEpochMs = NOW + REPEAT,
                revocationWatchdogAtEpochMs = NOW + REPEAT + WATCHDOG_GRACE,
            ),
            AlarmRepeatPlanPolicy.plan(
                episode = episode(),
                nowEpochMs = NOW,
                repeatIntervalMs = REPEAT,
                exactAlarmAccess = true,
            ),
        )
    }

    @Test
    fun exactWakeupAlwaysHasDistinctInexactRevocationWatchdog() {
        val plan = AlarmRepeatPlanPolicy.plan(
            episode = episode(),
            nowEpochMs = NOW,
            repeatIntervalMs = REPEAT,
            exactAlarmAccess = true,
        )

        assertEquals(AlarmRepeatScheduleKind.EXACT_WAKEUP, plan.kind)
        assertEquals(
            plan.triggerAtEpochMs + AlarmRepeatPlanPolicy.REVOCATION_WATCHDOG_GRACE_MS,
            plan.revocationWatchdogAtEpochMs,
        )
    }

    @Test
    fun deniedExactAccessUsesBoundedInexactWakeupAndAckCancels() {
        assertEquals(
            AlarmRepeatScheduleKind.INEXACT_WAKEUP,
            AlarmRepeatPlanPolicy.plan(
                episode = episode(),
                nowEpochMs = NOW,
                repeatIntervalMs = REPEAT,
                exactAlarmAccess = false,
            ).kind,
        )
        assertEquals(
            null,
            AlarmRepeatPlanPolicy.plan(
                episode = episode(),
                nowEpochMs = NOW,
                repeatIntervalMs = REPEAT,
                exactAlarmAccess = false,
            ).revocationWatchdogAtEpochMs,
        )
        assertEquals(
            AlarmRepeatPlan.NONE,
            AlarmRepeatPlanPolicy.plan(
                episode = episode().copy(acknowledged = true),
                nowEpochMs = NOW,
                repeatIntervalMs = REPEAT,
                exactAlarmAccess = true,
            ),
        )
    }

    @Test
    fun overdueOrClockRollbackNeverSchedulesInThePast() {
        listOf(NOW - 1L, NOW + REPEAT + 1L).forEach { lastAlert ->
            val plan = AlarmRepeatPlanPolicy.plan(
                episode = episode().copy(lastAlertAtEpochMs = lastAlert),
                nowEpochMs = NOW + REPEAT,
                repeatIntervalMs = REPEAT,
                exactAlarmAccess = true,
            )
            assertTrue(plan.triggerAtEpochMs >= NOW + REPEAT + AlarmRepeatPlanPolicy.MIN_DELAY_MS)
        }
    }

    @Test
    fun deliveryWakeLockIsStrictlyTimeBounded() {
        assertTrue(AlarmDeliveryWakePolicy.TIMEOUT_MS in 1_000L..10_000L)
    }

    @Test
    fun runtimeReadinessLossRetriesWithoutClaimingAlertWasDelivered() {
        assertEquals(
            AlarmRepeatDeliveryDecision.BEST_EFFORT_RETRY,
            AlarmRepeatDeliveryPolicy.decide(
                episode = episode(),
                alarmReady = false,
                repeatDue = true,
            ),
        )
        assertTrue(AlarmRepeatDeliveryDecision.BEST_EFFORT_RETRY.attemptNotification)
        assertEquals(false, AlarmRepeatDeliveryDecision.BEST_EFFORT_RETRY.markDelivered)
        assertEquals(
            AlarmRepeatDeliveryDecision.ALERT,
            AlarmRepeatDeliveryPolicy.decide(
                episode = episode(),
                alarmReady = true,
                repeatDue = true,
            ),
        )
    }

    @Test
    fun blockedReadinessRetryUsesBoundedDelayInsteadOfOneSecondLoop() {
        val plan = AlarmRepeatPlanPolicy.plan(
            episode = episode().copy(lastAlertAtEpochMs = NOW - REPEAT),
            nowEpochMs = NOW,
            repeatIntervalMs = REPEAT,
            exactAlarmAccess = true,
            notBeforeEpochMs = NOW + REPEAT,
        )

        assertEquals(NOW + REPEAT, plan.triggerAtEpochMs)
    }

    private fun episode() = AlarmEpisode(
        id = "episode-repeat001",
        activeKinds = setOf(AlarmKind.LOW),
        acknowledged = false,
        openedAtEpochMs = NOW,
        lastAlertAtEpochMs = NOW,
        demo = false,
        reading = null,
    )

    private companion object {
        const val NOW = 1_800_000_000_000L
        const val REPEAT = 120_000L
        const val WATCHDOG_GRACE = 60_000L
    }
}
