package com.sladkaya.app.service

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.PowerManager

internal class AlarmRepeatScheduler(context: Context) {
    private val appContext = context.applicationContext
    private val alarmManager = appContext.getSystemService(AlarmManager::class.java)

    fun schedule(
        episode: AlarmEpisode,
        nowEpochMs: Long = System.currentTimeMillis(),
        notBeforeEpochMs: Long? = null,
    ): Boolean {
        val exactAccess = Build.VERSION.SDK_INT < 31 || runCatching {
            alarmManager.canScheduleExactAlarms()
        }.getOrDefault(false)
        val plan = AlarmRepeatPlanPolicy.plan(
            episode = episode,
            nowEpochMs = nowEpochMs,
            repeatIntervalMs = REPEAT_INTERVAL_MS,
            exactAlarmAccess = exactAccess,
            notBeforeEpochMs = notBeforeEpochMs,
        )
        return when (plan.kind) {
            AlarmRepeatScheduleKind.EXACT_WAKEUP -> {
                val watchdogScheduled = runCatching {
                    alarmManager.setAndAllowWhileIdle(
                        AlarmManager.RTC_WAKEUP,
                        checkNotNull(plan.revocationWatchdogAtEpochMs),
                        revocationWatchdogPendingIntent(),
                    )
                }.isSuccess
                val primaryScheduled = runCatching {
                    alarmManager.setExactAndAllowWhileIdle(
                        AlarmManager.RTC_WAKEUP,
                        plan.triggerAtEpochMs,
                        repeatPendingIntent(),
                    )
                }.isSuccess || runCatching {
                    alarmManager.setAndAllowWhileIdle(
                        AlarmManager.RTC_WAKEUP,
                        plan.triggerAtEpochMs,
                        repeatPendingIntent(),
                    )
                }.isSuccess
                watchdogScheduled && primaryScheduled
            }
            AlarmRepeatScheduleKind.INEXACT_WAKEUP -> {
                runCatching { alarmManager.cancel(revocationWatchdogPendingIntent()) }
                runCatching {
                    alarmManager.setAndAllowWhileIdle(
                        AlarmManager.RTC_WAKEUP,
                        plan.triggerAtEpochMs,
                        repeatPendingIntent(),
                    )
                }.isSuccess
            }
            AlarmRepeatScheduleKind.NONE -> cancel()
        }
    }

    fun scheduleReadinessRetry(
        episode: AlarmEpisode,
        nowEpochMs: Long,
    ): Boolean = schedule(
        episode = episode,
        nowEpochMs = nowEpochMs,
        notBeforeEpochMs = if (nowEpochMs > Long.MAX_VALUE - REPEAT_INTERVAL_MS) {
            Long.MAX_VALUE
        } else {
            nowEpochMs + REPEAT_INTERVAL_MS
        },
    )

    fun scheduleWithEmergencyFallback(
        episode: AlarmEpisode,
        nowEpochMs: Long = System.currentTimeMillis(),
    ): Boolean = schedule(episode, nowEpochMs) || scheduleEmergencyRetry(episode, nowEpochMs)

    fun scheduleReadinessRetryWithEmergencyFallback(
        episode: AlarmEpisode,
        nowEpochMs: Long,
    ): Boolean = scheduleReadinessRetry(episode, nowEpochMs) ||
        scheduleEmergencyRetry(episode, nowEpochMs)

    fun cancel(): Boolean = runCatching {
        alarmManager.cancel(repeatPendingIntent())
        alarmManager.cancel(revocationWatchdogPendingIntent())
        true
    }.getOrDefault(false)

    private fun scheduleEmergencyRetry(episode: AlarmEpisode, nowEpochMs: Long): Boolean {
        if (episode.acknowledged) return cancel()
        val triggerAt = safeAdd(nowEpochMs, REPEAT_INTERVAL_MS)
        return runCatching {
            alarmManager.setAndAllowWhileIdle(
                AlarmManager.RTC_WAKEUP,
                triggerAt,
                revocationWatchdogPendingIntent(),
            )
        }.isSuccess || runCatching {
            alarmManager.set(
                AlarmManager.RTC_WAKEUP,
                triggerAt,
                revocationWatchdogPendingIntent(),
            )
        }.isSuccess
    }

    private fun repeatPendingIntent(): PendingIntent = PendingIntent.getBroadcast(
        appContext,
        AlarmNotificationIds.REPEAT_REQUEST,
        AlarmRepeatReceiver.intent(appContext),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    private fun revocationWatchdogPendingIntent(): PendingIntent = PendingIntent.getBroadcast(
        appContext,
        AlarmNotificationIds.REVOCATION_WATCHDOG_REQUEST,
        AlarmRepeatReceiver.watchdogIntent(appContext),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    internal companion object {
        const val REPEAT_INTERVAL_MS = 2 * 60_000L

        private fun safeAdd(value: Long, delta: Long): Long =
            if (value > Long.MAX_VALUE - delta) Long.MAX_VALUE else value + delta
    }
}

internal class AlarmAcknowledgeReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_ACKNOWLEDGE) return
        val episodeId = intent.getStringExtra(EXTRA_EPISODE_ID) ?: return
        if (intent.data?.lastPathSegment != episodeId) return
        val store = AlarmEpisodePreferenceStore(context)
        store.atomically {
            when (val result = store.acknowledge(episodeId)) {
                is AlarmEpisodeStoreAcknowledgement.Accepted -> {
                    val cancelled = AlarmRepeatScheduler(context).cancel()
                    val shown = AlarmNotifier(context).showEpisode(
                        result.episode,
                        alert = false,
                    )
                    if (!cancelled || !shown) {
                        reportAlarmDeliveryFailure(
                            context,
                            "Android не подтвердил отключение звука тревоги",
                        )
                    }
                    SensorForegroundService.onAlarmEpisodeAcknowledged(result.episode.id)
                }
                AlarmEpisodeStoreAcknowledgement.Corrupt,
                AlarmEpisodeStoreAcknowledgement.Missing,
                AlarmEpisodeStoreAcknowledgement.PersistenceFailed,
                AlarmEpisodeStoreAcknowledgement.Stale,
                -> Unit
            }
        }
    }

    internal companion object {
        private const val ACTION_ACKNOWLEDGE =
            "com.sladkaya.app.action.ACKNOWLEDGE_ALARM_EPISODE"
        private const val EXTRA_EPISODE_ID = "alarm_episode_id"

        fun intent(context: Context, episodeId: String): Intent = Intent(
            context,
            AlarmAcknowledgeReceiver::class.java,
        ).setAction(ACTION_ACKNOWLEDGE)
            .setData(Uri.parse("sladkaya://alarm/ack/$episodeId"))
            .putExtra(EXTRA_EPISODE_ID, episodeId)

        fun pendingIntent(context: Context, episodeId: String): PendingIntent =
            PendingIntent.getBroadcast(
                context,
                AlarmNotificationIds.ACKNOWLEDGE_REQUEST,
                intent(context, episodeId),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
    }
}

internal class AlarmRepeatReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_REPEAT && intent.action != ACTION_REVOCATION_WATCHDOG) return
        val powerManager = context.getSystemService(PowerManager::class.java)
        val wakeLock = powerManager.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            "com.sladkaya.app:alarm_delivery",
        )
        runCatching { wakeLock.acquire(AlarmDeliveryWakePolicy.TIMEOUT_MS) }
        try {
            deliver(context, System.currentTimeMillis())
        } finally {
            if (wakeLock.isHeld) runCatching { wakeLock.release() }
        }
    }

    private fun deliver(context: Context, nowEpochMs: Long) {
        val store = AlarmEpisodePreferenceStore(context)
        val scheduler = AlarmRepeatScheduler(context)
        store.atomically {
            when (val loaded = store.load()) {
                AlarmEpisodeLoadResult.Empty -> {
                    val scheduleCancelled = scheduler.cancel()
                    val notificationCancelled = AlarmNotifier(context).cancelAllAlarms()
                    if (!scheduleCancelled || !notificationCancelled) {
                        reportAlarmDeliveryFailure(context, ALARM_CANCEL_FAILED_MESSAGE)
                    }
                }
                AlarmEpisodeLoadResult.Corrupt -> {
                    scheduler.cancel()
                    AlarmNotifier(context).cancelAllAlarms()
                    reportAlarmDeliveryFailure(context, ALARM_STATE_CORRUPT_MESSAGE)
                }
                is AlarmEpisodeLoadResult.Active -> {
                    val episode = loaded.episode
                    val demoSessionLive = SensorForegroundService.isAlarmEpisodeLive(episode.id)
                    if (!AlarmEpisodeLivenessPolicy.canDeliver(episode, demoSessionLive)) {
                        val cleared = store.clear()
                        val scheduleCancelled = scheduler.cancel()
                        val notificationCancelled = AlarmNotifier(context).cancelAllAlarms()
                        if (!cleared || !scheduleCancelled || !notificationCancelled) {
                            reportAlarmDeliveryFailure(
                                context,
                                ALARM_SIDE_EFFECT_FAILED_MESSAGE,
                            )
                        }
                        return@atomically
                    }
                    val repeatDue = AlarmEpisodePolicy.repeatDue(
                        episode,
                        nowEpochMs,
                        AlarmRepeatScheduler.REPEAT_INTERVAL_MS,
                    )
                    val readiness = readAlarmReadiness(context)
                    when (
                        AlarmRepeatDeliveryPolicy.decide(
                            episode = episode,
                            alarmReady = readiness.ready,
                            repeatDue = repeatDue,
                        )
                    ) {
                        AlarmRepeatDeliveryDecision.CANCEL -> {
                            val cancelled = scheduler.cancel()
                            val shown = AlarmNotifier(context).showEpisode(episode, alert = false)
                            if (!cancelled || !shown) {
                                reportAlarmDeliveryFailure(
                                    context,
                                    ALARM_SIDE_EFFECT_FAILED_MESSAGE,
                                )
                            }
                        }
                        AlarmRepeatDeliveryDecision.BEST_EFFORT_RETRY -> {
                            val retryScheduled =
                                scheduler.scheduleReadinessRetryWithEmergencyFallback(
                                    episode,
                                    nowEpochMs,
                                )
                            AlarmNotifier(context).showEpisode(episode, alert = true)
                            com.sladkaya.app.AppState.onSetupRequired(readiness.userMessage())
                            com.sladkaya.app.widget.GlucoseWidgetProvider.showSetupRequired(context)
                            if (!retryScheduled) {
                                reportAlarmDeliveryFailure(context, ALARM_SCHEDULE_FAILED_MESSAGE)
                            }
                        }
                        AlarmRepeatDeliveryDecision.RESCHEDULE -> {
                            if (!scheduler.scheduleWithEmergencyFallback(episode, nowEpochMs)) {
                                reportAlarmDeliveryFailure(context, ALARM_SCHEDULE_FAILED_MESSAGE)
                            }
                        }
                        AlarmRepeatDeliveryDecision.ALERT -> {
                            val recoveryPrearmed =
                                scheduler.scheduleReadinessRetryWithEmergencyFallback(
                                    episode,
                                    nowEpochMs,
                                )
                            val marked = AlarmEpisodePolicy.markAlerted(episode, nowEpochMs)
                            if (AlarmNotifier(context).showEpisode(marked, alert = true)) {
                                val deliveredPersisted = store.save(marked)
                                if (!deliveredPersisted) {
                                    reportAlarmDeliveryFailure(
                                        context,
                                        ALARM_PERSISTENCE_FAILED_MESSAGE,
                                    )
                                }
                                val followUpScheduled = recoveryPrearmed ||
                                    scheduler.scheduleWithEmergencyFallback(
                                        if (deliveredPersisted) marked else episode,
                                        nowEpochMs,
                                    )
                                if (!followUpScheduled) {
                                    reportAlarmDeliveryFailure(
                                        context,
                                        ALARM_SCHEDULE_FAILED_MESSAGE,
                                    )
                                }
                            } else {
                                reportAlarmDeliveryFailure(
                                    context,
                                    ALARM_NOTIFICATION_FAILED_MESSAGE,
                                )
                                if (!recoveryPrearmed) {
                                    val retryScheduled =
                                        scheduler.scheduleReadinessRetryWithEmergencyFallback(
                                            episode,
                                            nowEpochMs,
                                        )
                                    if (!retryScheduled) {
                                        reportAlarmDeliveryFailure(
                                            context,
                                            ALARM_SCHEDULE_FAILED_MESSAGE,
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    internal companion object {
        private const val ALARM_CANCEL_FAILED_MESSAGE =
            "Android не подтвердил отмену старого повтора тревоги"
        private const val ALARM_STATE_CORRUPT_MESSAGE =
            "Повреждено сохранённое состояние локальной тревоги"
        private const val ALARM_SIDE_EFFECT_FAILED_MESSAGE =
            "Android не подтвердил состояние локальной тревоги"
        private const val ALARM_SCHEDULE_FAILED_MESSAGE =
            "Android не подтвердил следующий повтор локальной тревоги"
        private const val ALARM_NOTIFICATION_FAILED_MESSAGE =
            "Android не подтвердил показ локальной тревоги"
        private const val ALARM_PERSISTENCE_FAILED_MESSAGE =
            "Не удалось сохранить состояние локальной тревоги"
        private const val ACTION_REPEAT = "com.sladkaya.app.action.REPEAT_ALARM_EPISODE"
        private const val ACTION_REVOCATION_WATCHDOG =
            "com.sladkaya.app.action.ALARM_EPISODE_REVOCATION_WATCHDOG"

        fun intent(context: Context): Intent = Intent(
            context,
            AlarmRepeatReceiver::class.java,
        ).setAction(ACTION_REPEAT)

        fun watchdogIntent(context: Context): Intent = Intent(
            context,
            AlarmRepeatReceiver::class.java,
        ).setAction(ACTION_REVOCATION_WATCHDOG)
    }
}

internal fun reportAlarmDeliveryFailure(context: Context, message: String) {
    com.sladkaya.app.AppState.onSetupRequired(message)
    com.sladkaya.app.widget.GlucoseWidgetProvider.showSetupRequired(context)
}
