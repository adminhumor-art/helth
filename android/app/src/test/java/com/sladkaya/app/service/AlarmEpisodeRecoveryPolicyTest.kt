package com.sladkaya.app.service

import com.sladkaya.core.model.AlarmKind
import org.junit.Assert.assertEquals
import org.junit.Test

class AlarmEpisodeRecoveryPolicyTest {
    @Test
    fun unacknowledgedEpisodeRestoresVisibleNotificationAndRepeat() {
        assertEquals(
            AlarmEpisodeRecoveryAction.RESTORE_UNACKNOWLEDGED,
            AlarmEpisodeRecoveryPolicy.actionFor(
                AlarmEpisodeLoadResult.Active(episode(acknowledged = false)),
                AlarmEpisodeRecoveryTrigger.EXACT_ALARM_ACCESS_CHANGED,
                demoSessionLive = true,
            ),
        )
    }

    @Test
    fun acknowledgedEpisodeStaysVisibleButCannotRestartSound() {
        assertEquals(
            AlarmEpisodeRecoveryAction.SHOW_ACKNOWLEDGED,
            AlarmEpisodeRecoveryPolicy.actionFor(
                AlarmEpisodeLoadResult.Active(episode(acknowledged = true)),
                AlarmEpisodeRecoveryTrigger.EXACT_ALARM_ACCESS_CHANGED,
                demoSessionLive = true,
            ),
        )
    }

    @Test
    fun emptyAndCorruptStateAreNeverTreatedAsAnActiveAlarm() {
        assertEquals(
            AlarmEpisodeRecoveryAction.CLEAR_SCHEDULE,
            AlarmEpisodeRecoveryPolicy.actionFor(
                AlarmEpisodeLoadResult.Empty,
                AlarmEpisodeRecoveryTrigger.BOOT_OR_PACKAGE_REPLACED,
            ),
        )
        assertEquals(
            AlarmEpisodeRecoveryAction.FAIL_CLOSED,
            AlarmEpisodeRecoveryPolicy.actionFor(
                AlarmEpisodeLoadResult.Corrupt,
                AlarmEpisodeRecoveryTrigger.BOOT_OR_PACKAGE_REPLACED,
            ),
        )
    }

    @Test
    fun bootClearsDemoEpisodeInsteadOfRepeatingFrozenDemoSnapshot() {
        assertEquals(
            AlarmEpisodeRecoveryAction.CLEAR_EPISODE_AND_SCHEDULE,
            AlarmEpisodeRecoveryPolicy.actionFor(
                AlarmEpisodeLoadResult.Active(episode(acknowledged = false)),
                AlarmEpisodeRecoveryTrigger.BOOT_OR_PACKAGE_REPLACED,
            ),
        )
    }

    @Test
    fun processDeathMakesExternalDemoRepeatAndRecoveryDiscardFrozenEpisode() {
        val demo = episode(acknowledged = false)

        assertEquals(
            false,
            AlarmEpisodeLivenessPolicy.canDeliver(demo, demoSessionLive = false),
        )
        assertEquals(
            AlarmEpisodeRecoveryAction.CLEAR_EPISODE_AND_SCHEDULE,
            AlarmEpisodeRecoveryPolicy.actionFor(
                AlarmEpisodeLoadResult.Active(demo),
                AlarmEpisodeRecoveryTrigger.EXACT_ALARM_ACCESS_CHANGED,
                demoSessionLive = false,
            ),
        )
    }

    @Test
    fun bootRestoresOnlyRealProductEpisode() {
        assertEquals(
            AlarmEpisodeRecoveryAction.RESTORE_UNACKNOWLEDGED,
            AlarmEpisodeRecoveryPolicy.actionFor(
                AlarmEpisodeLoadResult.Active(
                    episode(acknowledged = false).copy(demo = false),
                ),
                AlarmEpisodeRecoveryTrigger.BOOT_OR_PACKAGE_REPLACED,
            ),
        )
    }

    @Test
    fun processDestructionPreservesEpisodeButExplicitStopOrModeSwitchClearsIt() {
        assertEquals(
            true,
            AlarmEpisodeStopPolicy.clearDemoEpisode(AlarmEpisodeStopReason.PROCESS_DESTROYED),
        )
        assertEquals(
            true,
            AlarmEpisodeStopPolicy.clearDemoEpisode(AlarmEpisodeStopReason.EXPLICIT_STOP),
        )
        assertEquals(
            true,
            AlarmEpisodeStopPolicy.clearDemoEpisode(AlarmEpisodeStopReason.MODE_SWITCH),
        )
    }

    @Test
    fun demoStartReReadsCurrentEpisodeSoConcurrentAcknowledgementIsPreserved() {
        val expected = episode(acknowledged = false)
        val acknowledged = expected.copy(acknowledged = true)

        assertEquals(
            DemoEpisodeStartResolution.Restored(acknowledged),
            DemoEpisodeStartPolicy.resolve(
                expectedEpisodeId = expected.id,
                loaded = AlarmEpisodeLoadResult.Active(acknowledged),
            ),
        )
        assertEquals(
            DemoEpisodeStartResolution.Conflict,
            DemoEpisodeStartPolicy.resolve(
                expectedEpisodeId = expected.id,
                loaded = AlarmEpisodeLoadResult.Empty,
            ),
        )
    }

    private fun episode(acknowledged: Boolean) = AlarmEpisode(
        id = "episode-recovery01",
        activeKinds = setOf(AlarmKind.LOW),
        acknowledged = acknowledged,
        openedAtEpochMs = 1_800_000_000_000L,
        lastAlertAtEpochMs = 1_800_000_000_000L,
        demo = true,
        reading = null,
    )
}
