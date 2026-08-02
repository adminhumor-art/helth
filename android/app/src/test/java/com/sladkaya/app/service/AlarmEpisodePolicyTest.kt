package com.sladkaya.app.service

import com.sladkaya.core.model.AlarmKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AlarmEpisodePolicyTest {
    @Test
    fun firstActiveAlarmCreatesDurableUnacknowledgedEpisode() {
        val transition = AlarmEpisodePolicy.transition(
            previous = null,
            activeKinds = setOf(AlarmKind.LOW),
            newlyOpenedKinds = setOf(AlarmKind.LOW),
            nowEpochMs = NOW,
            snapshot = snapshot(60),
            demo = false,
            nextEpisodeId = "episode-00000001",
        )

        assertTrue(transition.alertNow)
        assertFalse(transition.cancelNotification)
        assertEquals("episode-00000001", transition.episode?.id)
        assertEquals(setOf(AlarmKind.LOW), transition.episode?.activeKinds)
        assertFalse(checkNotNull(transition.episode).acknowledged)
    }

    @Test
    fun staleAcknowledgementCannotSilenceCurrentEpisode() {
        val current = episode(id = "episode-current01")

        val result = AlarmEpisodePolicy.acknowledge(current, "episode-stale0001")

        assertTrue(result is AlarmEpisodeAcknowledgement.Stale)
        assertFalse(current.acknowledged)
    }

    @Test
    fun acknowledgementIsIdempotentAndStopsRepeatsForOnlyCurrentEpisode() {
        val current = episode(id = "episode-current01")
        val first = AlarmEpisodePolicy.acknowledge(current, current.id)
            as AlarmEpisodeAcknowledgement.Accepted
        val second = AlarmEpisodePolicy.acknowledge(first.episode, current.id)
            as AlarmEpisodeAcknowledgement.Accepted

        assertTrue(first.episode.acknowledged)
        assertEquals(first.episode, second.episode)
        assertFalse(AlarmEpisodePolicy.repeatDue(second.episode, NOW + REPEAT, REPEAT))
    }

    @Test
    fun newlyOpenedKindCreatesNewIdentityAndInvalidatesOldAction() {
        val previous = episode(
            id = "episode-old000001",
            kinds = setOf(AlarmKind.LOW),
            acknowledged = true,
        )

        val transition = AlarmEpisodePolicy.transition(
            previous = previous,
            activeKinds = setOf(AlarmKind.LOW, AlarmKind.RAPID_FALL),
            newlyOpenedKinds = setOf(AlarmKind.RAPID_FALL),
            nowEpochMs = NOW + 1_000L,
            snapshot = snapshot(55),
            demo = false,
            nextEpisodeId = "episode-new000001",
        )

        assertEquals("episode-new000001", transition.episode?.id)
        assertFalse(checkNotNull(transition.episode).acknowledged)
        assertTrue(transition.alertNow)
        assertTrue(transition.rescheduleRepeat)
        assertTrue(
            AlarmEpisodePolicy.acknowledge(
                transition.episode,
                previous.id,
            ) is AlarmEpisodeAcknowledgement.Stale,
        )
    }

    @Test
    fun ongoingEpisodeRefreshesSnapshotWithoutNewIdentityAlertOrRepeatReschedule() {
        val previous = episode(id = "episode-current01")
        val refreshed = snapshot(54)

        val transition = AlarmEpisodePolicy.transition(
            previous = previous,
            activeKinds = previous.activeKinds,
            newlyOpenedKinds = emptySet(),
            nowEpochMs = NOW + 60_000L,
            snapshot = refreshed,
            demo = false,
            nextEpisodeId = "must-not-be-used01",
        )

        assertEquals(previous.id, transition.episode?.id)
        assertEquals(refreshed, transition.episode?.reading)
        assertEquals(previous.openedAtEpochMs, transition.episode?.openedAtEpochMs)
        assertEquals(previous.lastAlertAtEpochMs, transition.episode?.lastAlertAtEpochMs)
        assertFalse(transition.alertNow)
        assertFalse(transition.rescheduleRepeat)
    }

    @Test
    fun closingLastAlarmClearsEpisodeAndNotification() {
        val transition = AlarmEpisodePolicy.transition(
            previous = episode(),
            activeKinds = emptySet(),
            newlyOpenedKinds = emptySet(),
            nowEpochMs = NOW,
            snapshot = null,
            demo = false,
            nextEpisodeId = "unused-episode-id",
        )

        assertNull(transition.episode)
        assertTrue(transition.cancelNotification)
        assertFalse(transition.alertNow)
    }

    @Test
    fun repeatSurvivesWallClockRollbackAndIsMarkedExactlyOnce() {
        val active = episode(lastAlertAtEpochMs = NOW)

        assertFalse(AlarmEpisodePolicy.repeatDue(active, NOW + REPEAT - 1L, REPEAT))
        assertTrue(AlarmEpisodePolicy.repeatDue(active, NOW + REPEAT, REPEAT))
        assertTrue(AlarmEpisodePolicy.repeatDue(active, NOW - 1L, REPEAT))

        val marked = AlarmEpisodePolicy.markAlerted(active, NOW + REPEAT)
        assertFalse(AlarmEpisodePolicy.repeatDue(marked, NOW + REPEAT + 1L, REPEAT))
    }

    @Test
    fun exactAndWatchdogDeliveryForSameIntervalCannotAlertTwice() {
        val active = episode(lastAlertAtEpochMs = NOW)
        val exactDeliveryAt = NOW + REPEAT
        val afterExact = AlarmEpisodePolicy.markAlerted(active, exactDeliveryAt)

        assertTrue(AlarmEpisodePolicy.repeatDue(active, exactDeliveryAt, REPEAT))
        assertFalse(
            AlarmEpisodePolicy.repeatDue(
                afterExact,
                exactDeliveryAt + AlarmRepeatPlanPolicy.REVOCATION_WATCHDOG_GRACE_MS,
                REPEAT,
            ),
        )
    }

    @Test
    fun pendingDeliveryRemainsDueUntilNotificationIsConfirmed() {
        val pending = AlarmEpisodePolicy.markDeliveryPending(
            episode = episode(),
            nowEpochMs = NOW,
            repeatIntervalMs = REPEAT,
        )

        assertEquals(NOW - REPEAT, pending.lastAlertAtEpochMs)
        assertTrue(AlarmEpisodePolicy.repeatDue(pending, NOW, REPEAT))

        val delivered = AlarmEpisodePolicy.markAlerted(pending, NOW)
        assertFalse(AlarmEpisodePolicy.repeatDue(delivered, NOW + 1L, REPEAT))
    }

    private fun episode(
        id: String = "episode-00000001",
        kinds: Set<AlarmKind> = setOf(AlarmKind.LOW),
        acknowledged: Boolean = false,
        lastAlertAtEpochMs: Long = NOW,
    ) = AlarmEpisode(
        id = id,
        activeKinds = kinds,
        acknowledged = acknowledged,
        openedAtEpochMs = NOW,
        lastAlertAtEpochMs = lastAlertAtEpochMs,
        demo = false,
        reading = snapshot(60),
    )

    private fun snapshot(value: Int) = AlarmReadingSnapshot(
        glucoseMgDl = value,
        sensorTimeEpochMs = NOW,
        phoneTimeEpochMs = NOW,
    )

    private companion object {
        const val NOW = 1_800_000_000_000L
        const val REPEAT = 120_000L
    }
}
