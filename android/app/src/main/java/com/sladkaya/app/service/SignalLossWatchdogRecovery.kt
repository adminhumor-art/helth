package com.sladkaya.app.service

import android.content.Context

internal enum class SignalLossWatchdogRecoveryAction {
    CLEAR,
    RESTORE,
    FAIL_CLOSED,
}

internal object SignalLossWatchdogRecoveryPolicy {
    fun actionFor(
        loaded: SignalLossWatchdogLoadResult,
        trigger: AlarmEpisodeRecoveryTrigger,
        demoSessionLive: Boolean,
    ): SignalLossWatchdogRecoveryAction = when (loaded) {
        SignalLossWatchdogLoadResult.Empty -> SignalLossWatchdogRecoveryAction.CLEAR
        SignalLossWatchdogLoadResult.Corrupt -> SignalLossWatchdogRecoveryAction.FAIL_CLOSED
        is SignalLossWatchdogLoadResult.Active -> when {
            !loaded.state.demo -> SignalLossWatchdogRecoveryAction.RESTORE
            trigger == AlarmEpisodeRecoveryTrigger.BOOT_OR_PACKAGE_REPLACED -> {
                SignalLossWatchdogRecoveryAction.CLEAR
            }
            demoSessionLive -> SignalLossWatchdogRecoveryAction.RESTORE
            else -> SignalLossWatchdogRecoveryAction.CLEAR
        }
    }
}

internal class SignalLossWatchdogRecoveryCoordinator(context: Context) {
    private val appContext = context.applicationContext
    private val episodeStore = AlarmEpisodePreferenceStore(appContext)
    private val watchdogStore = SignalLossWatchdogPreferenceStore(appContext)
    private val scheduler = SignalLossWatchdogScheduler(appContext)

    fun restore(trigger: AlarmEpisodeRecoveryTrigger): Boolean = episodeStore.atomically {
        val loaded = watchdogStore.load()
        val action = SignalLossWatchdogRecoveryPolicy.actionFor(
            loaded = loaded,
            trigger = trigger,
            demoSessionLive = SensorForegroundService.isDemoSessionLive(),
        )
        when (action) {
            SignalLossWatchdogRecoveryAction.CLEAR -> {
                val cleared = watchdogStore.clear()
                val cancelled = scheduler.cancel()
                cleared && cancelled
            }
            SignalLossWatchdogRecoveryAction.RESTORE -> {
                val state = (loaded as SignalLossWatchdogLoadResult.Active).state
                val scheduled = scheduler.scheduleWithRetryFallback(state)
                scheduled && scheduler.cancelOtherSlots(state.generation)
            }
            SignalLossWatchdogRecoveryAction.FAIL_CLOSED -> {
                scheduler.cancel()
                false
            }
        }
    }
}
