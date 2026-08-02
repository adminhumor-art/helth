package com.sladkaya.app.service

import org.junit.Assert.assertEquals
import org.junit.Test

class SignalLossWatchdogRecoveryPolicyTest {
    @Test
    fun productStateRestoresAfterBootButDemoStateIsDiscarded() {
        assertEquals(
            SignalLossWatchdogRecoveryAction.RESTORE,
            SignalLossWatchdogRecoveryPolicy.actionFor(
                SignalLossWatchdogLoadResult.Active(state(demo = false)),
                AlarmEpisodeRecoveryTrigger.BOOT_OR_PACKAGE_REPLACED,
                demoSessionLive = false,
            ),
        )
        assertEquals(
            SignalLossWatchdogRecoveryAction.CLEAR,
            SignalLossWatchdogRecoveryPolicy.actionFor(
                SignalLossWatchdogLoadResult.Active(state(demo = true)),
                AlarmEpisodeRecoveryTrigger.BOOT_OR_PACKAGE_REPLACED,
                demoSessionLive = true,
            ),
        )
    }

    @Test
    fun exactAlarmChangeCanRearmOnlyAStillLiveDemo() {
        assertEquals(
            SignalLossWatchdogRecoveryAction.RESTORE,
            SignalLossWatchdogRecoveryPolicy.actionFor(
                SignalLossWatchdogLoadResult.Active(state(demo = true)),
                AlarmEpisodeRecoveryTrigger.EXACT_ALARM_ACCESS_CHANGED,
                demoSessionLive = true,
            ),
        )
        assertEquals(
            SignalLossWatchdogRecoveryAction.CLEAR,
            SignalLossWatchdogRecoveryPolicy.actionFor(
                SignalLossWatchdogLoadResult.Active(state(demo = true)),
                AlarmEpisodeRecoveryTrigger.EXACT_ALARM_ACCESS_CHANGED,
                demoSessionLive = false,
            ),
        )
    }

    @Test
    fun corruptStateFailsClosed() {
        assertEquals(
            SignalLossWatchdogRecoveryAction.FAIL_CLOSED,
            SignalLossWatchdogRecoveryPolicy.actionFor(
                SignalLossWatchdogLoadResult.Corrupt,
                AlarmEpisodeRecoveryTrigger.BOOT_OR_PACKAGE_REPLACED,
                demoSessionLive = false,
            ),
        )
    }

    private fun state(demo: Boolean) = SignalLossWatchdogState(
        generation = 1L,
        readingIdentity = "e".repeat(64),
        sensorTimeEpochMs = 1_800_000_000_000L,
        phoneTimeEpochMs = 1_800_000_000_000L,
        staleAfterMs = 600_000L,
        demo = demo,
    )
}
