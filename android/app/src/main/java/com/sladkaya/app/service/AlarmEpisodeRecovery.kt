package com.sladkaya.app.service

import android.content.Context

internal enum class AlarmEpisodeRecoveryAction {
    CLEAR_SCHEDULE,
    CLEAR_EPISODE_AND_SCHEDULE,
    SHOW_ACKNOWLEDGED,
    RESTORE_UNACKNOWLEDGED,
    FAIL_CLOSED,
}

internal enum class AlarmEpisodeRecoveryTrigger {
    BOOT_OR_PACKAGE_REPLACED,
    EXACT_ALARM_ACCESS_CHANGED,
}

internal object AlarmEpisodeRecoveryPolicy {
    fun actionFor(
        loaded: AlarmEpisodeLoadResult,
        trigger: AlarmEpisodeRecoveryTrigger,
        demoSessionLive: Boolean = false,
    ): AlarmEpisodeRecoveryAction {
        if (
            loaded is AlarmEpisodeLoadResult.Active &&
            loaded.episode.demo &&
            (
                trigger == AlarmEpisodeRecoveryTrigger.BOOT_OR_PACKAGE_REPLACED ||
                    !demoSessionLive
                )
        ) {
            return AlarmEpisodeRecoveryAction.CLEAR_EPISODE_AND_SCHEDULE
        }
        return when (loaded) {
            AlarmEpisodeLoadResult.Empty -> AlarmEpisodeRecoveryAction.CLEAR_SCHEDULE
            AlarmEpisodeLoadResult.Corrupt -> AlarmEpisodeRecoveryAction.FAIL_CLOSED
            is AlarmEpisodeLoadResult.Active -> if (loaded.episode.acknowledged) {
                AlarmEpisodeRecoveryAction.SHOW_ACKNOWLEDGED
            } else {
                AlarmEpisodeRecoveryAction.RESTORE_UNACKNOWLEDGED
            }
        }
    }
}

internal enum class AlarmEpisodeStopReason {
    PROCESS_DESTROYED,
    EXPLICIT_STOP,
    MODE_SWITCH,
}

internal object AlarmEpisodeStopPolicy {
    fun clearDemoEpisode(reason: AlarmEpisodeStopReason): Boolean = when (reason) {
        AlarmEpisodeStopReason.PROCESS_DESTROYED,
        AlarmEpisodeStopReason.EXPLICIT_STOP,
        AlarmEpisodeStopReason.MODE_SWITCH,
        -> true
    }
}

internal object AlarmEpisodeLivenessPolicy {
    fun canDeliver(episode: AlarmEpisode, demoSessionLive: Boolean): Boolean =
        !episode.demo || demoSessionLive
}

internal sealed interface DemoEpisodeStartResolution {
    data object NoEpisode : DemoEpisodeStartResolution
    data class Restored(val episode: AlarmEpisode) : DemoEpisodeStartResolution
    data object Conflict : DemoEpisodeStartResolution
}

internal object DemoEpisodeStartPolicy {
    fun resolve(
        expectedEpisodeId: String?,
        loaded: AlarmEpisodeLoadResult,
    ): DemoEpisodeStartResolution = when (loaded) {
        AlarmEpisodeLoadResult.Corrupt -> DemoEpisodeStartResolution.Conflict
        AlarmEpisodeLoadResult.Empty -> if (expectedEpisodeId == null) {
            DemoEpisodeStartResolution.NoEpisode
        } else {
            DemoEpisodeStartResolution.Conflict
        }
        is AlarmEpisodeLoadResult.Active -> if (
            loaded.episode.demo && loaded.episode.id == expectedEpisodeId
        ) {
            DemoEpisodeStartResolution.Restored(loaded.episode)
        } else {
            DemoEpisodeStartResolution.Conflict
        }
    }
}

internal class AlarmEpisodeRecoveryCoordinator(context: Context) {
    private val appContext = context.applicationContext
    private val store = AlarmEpisodePreferenceStore(appContext)
    private val scheduler = AlarmRepeatScheduler(appContext)
    private val notifier = AlarmNotifier(appContext)

    fun restore(trigger: AlarmEpisodeRecoveryTrigger): Boolean {
        notifier.createChannels()
        return store.atomically {
            val loaded = store.load()
            val demoSessionLive = (loaded as? AlarmEpisodeLoadResult.Active)?.episode?.let {
                SensorForegroundService.isAlarmEpisodeLive(it.id)
            } == true
            when (
                AlarmEpisodeRecoveryPolicy.actionFor(
                    loaded,
                    trigger,
                    demoSessionLive,
                )
            ) {
                AlarmEpisodeRecoveryAction.CLEAR_SCHEDULE -> {
                    val scheduleCancelled = scheduler.cancel()
                    val notificationCancelled = notifier.cancelAllAlarms()
                    scheduleCancelled && notificationCancelled
                }
                AlarmEpisodeRecoveryAction.CLEAR_EPISODE_AND_SCHEDULE -> {
                    val cleared = store.clear()
                    val cancelled = scheduler.cancel()
                    val notificationCancelled = notifier.cancelAllAlarms()
                    cleared && cancelled && notificationCancelled
                }
                AlarmEpisodeRecoveryAction.SHOW_ACKNOWLEDGED -> {
                    val episode = (loaded as AlarmEpisodeLoadResult.Active).episode
                    val cancelled = scheduler.cancel()
                    val shown = notifier.showEpisode(episode, alert = false)
                    cancelled && shown
                }
                AlarmEpisodeRecoveryAction.RESTORE_UNACKNOWLEDGED -> {
                    val episode = (loaded as AlarmEpisodeLoadResult.Active).episode
                    val scheduled = scheduler.scheduleWithEmergencyFallback(episode)
                    val readiness = readAlarmReadiness(appContext)
                    if (!readiness.ready) {
                        com.sladkaya.app.AppState.onSetupRequired(readiness.userMessage())
                    }
                    val shown = notifier.showEpisode(episode, alert = false)
                    scheduled && shown && readiness.ready
                }
                AlarmEpisodeRecoveryAction.FAIL_CLOSED -> {
                    scheduler.cancel()
                    notifier.cancelAllAlarms()
                    false
                }
            }
        }
    }
}
