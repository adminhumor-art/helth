package com.sladkaya.app.service

import com.sladkaya.core.model.AlarmKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AlarmReminderPolicyTest {
    @Test
    fun activeAlarmRepeatsOnlyAfterTheConfiguredInterval() {
        val policy = AlarmReminderPolicy(repeatIntervalMs = 2 * 60_000L)
        policy.onOpened(setOf(AlarmKind.LOW), NOW)

        assertTrue(policy.due(setOf(AlarmKind.LOW), NOW + 119_999L).isEmpty())
        assertEquals(setOf(AlarmKind.LOW), policy.due(setOf(AlarmKind.LOW), NOW + 120_000L))

        policy.markSent(setOf(AlarmKind.LOW), NOW + 120_000L)
        assertTrue(policy.due(setOf(AlarmKind.LOW), NOW + 120_001L).isEmpty())
    }

    @Test
    fun acknowledgementSilencesOnlyTheCurrentAlarmEpisode() {
        val policy = AlarmReminderPolicy(repeatIntervalMs = 2 * 60_000L)
        policy.onOpened(setOf(AlarmKind.LOW), NOW)
        policy.acknowledge(setOf(AlarmKind.LOW))

        assertTrue(policy.due(setOf(AlarmKind.LOW), NOW + 10 * 60_000L).isEmpty())

        policy.onClosed(setOf(AlarmKind.LOW))
        policy.onOpened(setOf(AlarmKind.LOW), NOW + 11 * 60_000L)
        assertEquals(
            setOf(AlarmKind.LOW),
            policy.due(setOf(AlarmKind.LOW), NOW + 13 * 60_000L),
        )
    }

    @Test
    fun closedAlarmsAreRemovedFromReminderState() {
        val policy = AlarmReminderPolicy(repeatIntervalMs = 1L)
        policy.onOpened(setOf(AlarmKind.HIGH), NOW)
        policy.onClosed(setOf(AlarmKind.HIGH))

        assertTrue(policy.due(emptySet(), NOW + 1L).isEmpty())
    }

    @Test
    fun clockRollbackFailsSafeAndDoesNotSilenceAnActiveAlarm() {
        val policy = AlarmReminderPolicy(repeatIntervalMs = 2 * 60_000L)
        policy.onOpened(setOf(AlarmKind.LOW), NOW)

        assertEquals(setOf(AlarmKind.LOW), policy.due(setOf(AlarmKind.LOW), NOW - 1L))
    }

    private companion object {
        const val NOW = 1_800_000_000_000L
    }
}
