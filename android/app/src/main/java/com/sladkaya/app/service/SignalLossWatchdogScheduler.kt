package com.sladkaya.app.service

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import com.sladkaya.core.model.AlarmKind
import java.util.UUID

internal class SignalLossWatchdogScheduler(context: Context) {
    private val appContext = context.applicationContext
    private val alarmManager = appContext.getSystemService(AlarmManager::class.java)

    fun schedule(
        state: SignalLossWatchdogState,
        nowEpochMs: Long = System.currentTimeMillis(),
    ): Boolean {
        val slot = SignalLossWatchdogSlotPolicy.slotFor(state.generation)
        val exactAccess = Build.VERSION.SDK_INT < 31 || runCatching {
            alarmManager.canScheduleExactAlarms()
        }.getOrDefault(false)
        val plan = SignalLossSchedulePolicy.plan(state, nowEpochMs, exactAccess)
        return when (plan.primaryKind) {
            AlarmRepeatScheduleKind.EXACT_WAKEUP -> {
                val watchdogScheduled = runCatching {
                    alarmManager.setAndAllowWhileIdle(
                        AlarmManager.RTC_WAKEUP,
                        checkNotNull(plan.revocationWatchdogAtEpochMs),
                        watchdogPendingIntent(slot, state),
                    )
                }.isSuccess
                val primaryScheduled = runCatching {
                    alarmManager.setExactAndAllowWhileIdle(
                        AlarmManager.RTC_WAKEUP,
                        plan.triggerAtEpochMs,
                        primaryPendingIntent(slot, state),
                    )
                }.isSuccess || runCatching {
                    alarmManager.setAndAllowWhileIdle(
                        AlarmManager.RTC_WAKEUP,
                        plan.triggerAtEpochMs,
                        primaryPendingIntent(slot, state),
                    )
                }.isSuccess
                watchdogScheduled && primaryScheduled
            }
            AlarmRepeatScheduleKind.INEXACT_WAKEUP -> {
                runCatching { alarmManager.cancel(watchdogPendingIntent(slot, state)) }
                runCatching {
                    alarmManager.setAndAllowWhileIdle(
                        AlarmManager.RTC_WAKEUP,
                        plan.triggerAtEpochMs,
                        primaryPendingIntent(slot, state),
                    )
                }.isSuccess
            }
            AlarmRepeatScheduleKind.NONE -> false
        }
    }

    fun scheduleRetry(
        state: SignalLossWatchdogState,
        nowEpochMs: Long,
    ): Boolean {
        val slot = SignalLossWatchdogSlotPolicy.slotFor(state.generation)
        val triggerAt = safeAdd(nowEpochMs, RETRY_INTERVAL_MS)
        return runCatching {
            alarmManager.setAndAllowWhileIdle(
                AlarmManager.RTC_WAKEUP,
                triggerAt,
                primaryPendingIntent(slot, state),
            )
        }.isSuccess || runCatching {
            alarmManager.set(
                AlarmManager.RTC_WAKEUP,
                triggerAt,
                primaryPendingIntent(slot, state),
            )
        }.isSuccess
    }

    fun scheduleWithRetryFallback(
        state: SignalLossWatchdogState,
        nowEpochMs: Long = System.currentTimeMillis(),
    ): Boolean = schedule(state, nowEpochMs) || scheduleRetry(state, nowEpochMs)

    fun cancelGeneration(generation: Long): Boolean =
        cancelSlot(SignalLossWatchdogSlotPolicy.slotFor(generation))

    fun cancelOtherSlots(currentGeneration: Long): Boolean {
        val currentSlot = SignalLossWatchdogSlotPolicy.slotFor(currentGeneration)
        return (0 until SignalLossWatchdogSlotPolicy.SLOT_COUNT)
            .filter { it != currentSlot }
            .map(::cancelSlot)
            .all { it }
    }

    fun cancel(): Boolean = (0 until SignalLossWatchdogSlotPolicy.SLOT_COUNT)
        .map(::cancelSlot)
        .all { it }

    private fun cancelSlot(slot: Int): Boolean = runCatching {
        alarmManager.cancel(primaryPendingIntent(slot, null))
        alarmManager.cancel(watchdogPendingIntent(slot, null))
        true
    }.getOrDefault(false)

    private fun primaryPendingIntent(
        slot: Int,
        state: SignalLossWatchdogState?,
    ): PendingIntent =
        PendingIntent.getBroadcast(
            appContext,
            when (slot) {
                0 -> AlarmNotificationIds.SIGNAL_LOSS_PRIMARY_SLOT_0_REQUEST
                1 -> AlarmNotificationIds.SIGNAL_LOSS_PRIMARY_SLOT_1_REQUEST
                else -> error("invalid signal-loss slot")
            },
            SignalLossWatchdogReceiver.intent(appContext, state),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

    private fun watchdogPendingIntent(
        slot: Int,
        state: SignalLossWatchdogState?,
    ): PendingIntent =
        PendingIntent.getBroadcast(
            appContext,
            when (slot) {
                0 -> AlarmNotificationIds.SIGNAL_LOSS_WATCHDOG_SLOT_0_REQUEST
                1 -> AlarmNotificationIds.SIGNAL_LOSS_WATCHDOG_SLOT_1_REQUEST
                else -> error("invalid signal-loss slot")
            },
            SignalLossWatchdogReceiver.watchdogIntent(appContext, state),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

    internal companion object {
        const val RETRY_INTERVAL_MS = 2 * 60_000L

        private fun safeAdd(value: Long, delta: Long): Long =
            if (value > Long.MAX_VALUE - delta) Long.MAX_VALUE else value + delta
    }
}

internal class SignalLossWatchdogReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_DEADLINE && intent.action != ACTION_WATCHDOG) return
        val generation = intent.getLongExtra(EXTRA_GENERATION, -1L)
        val identity = intent.getStringExtra(EXTRA_IDENTITY) ?: return
        if (generation <= 0L || !IDENTITY.matches(identity)) return
        val expectedPath = if (intent.action == ACTION_DEADLINE) "deadline" else "watchdog"
        if (intent.data?.lastPathSegment != expectedPath) return
        val powerManager = context.getSystemService(PowerManager::class.java)
        val wakeLock = powerManager.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            "com.sladkaya.app:signal_loss_delivery",
        )
        runCatching { wakeLock.acquire(AlarmDeliveryWakePolicy.TIMEOUT_MS) }
        try {
            deliver(context, generation, identity, System.currentTimeMillis())
        } finally {
            if (wakeLock.isHeld) runCatching { wakeLock.release() }
        }
    }

    private fun deliver(
        context: Context,
        deliveredGeneration: Long,
        deliveredIdentity: String,
        nowEpochMs: Long,
    ) {
        val episodeStore = AlarmEpisodePreferenceStore(context)
        val watchdogStore = SignalLossWatchdogPreferenceStore(context)
        val watchdogScheduler = SignalLossWatchdogScheduler(context)
        episodeStore.atomically {
            when (val loaded = watchdogStore.load()) {
                SignalLossWatchdogLoadResult.Empty -> {
                    if (!watchdogScheduler.cancel()) {
                        reportAlarmDeliveryFailure(context, WATCHDOG_CANCEL_FAILED_MESSAGE)
                    }
                }
                SignalLossWatchdogLoadResult.Corrupt -> {
                    watchdogScheduler.cancel()
                    reportAlarmDeliveryFailure(context, WATCHDOG_STATE_CORRUPT_MESSAGE)
                }
                is SignalLossWatchdogLoadResult.Active -> when (
                    SignalLossWatchdogPolicy.decide(
                        state = loaded.state,
                        deliveredGeneration = deliveredGeneration,
                        deliveredIdentity = deliveredIdentity,
                        nowEpochMs = nowEpochMs,
                        demoSessionLive = SensorForegroundService.isDemoSessionLive(),
                    )
                ) {
                    SignalLossWatchdogDecision.REARM_CURRENT -> {
                        val rearmed = watchdogScheduler.scheduleWithRetryFallback(
                            loaded.state,
                            nowEpochMs,
                        )
                        if (!rearmed) {
                            reportAlarmDeliveryFailure(
                                context,
                                WATCHDOG_SCHEDULE_FAILED_MESSAGE,
                            )
                        } else if (
                            SignalLossWatchdogSlotPolicy.slotFor(deliveredGeneration) !=
                            SignalLossWatchdogSlotPolicy.slotFor(loaded.state.generation)
                        ) {
                            watchdogScheduler.cancelGeneration(deliveredGeneration)
                        }
                    }
                    SignalLossWatchdogDecision.DISCARD_DEMO -> {
                        watchdogStore.clear()
                        watchdogScheduler.cancel()
                    }
                    SignalLossWatchdogDecision.RESCHEDULE -> {
                        if (!watchdogScheduler.schedule(loaded.state, nowEpochMs)) {
                            retryOrReport(context, watchdogScheduler, loaded.state, nowEpochMs)
                        }
                    }
                    SignalLossWatchdogDecision.OPEN_SIGNAL_LOSS -> openSignalLoss(
                        context = context,
                        episodeStore = episodeStore,
                        watchdogStore = watchdogStore,
                        watchdogScheduler = watchdogScheduler,
                        state = loaded.state,
                        nowEpochMs = nowEpochMs,
                    )
                }
            }
        }
    }

    private fun openSignalLoss(
        context: Context,
        episodeStore: AlarmEpisodePreferenceStore,
        watchdogStore: SignalLossWatchdogPreferenceStore,
        watchdogScheduler: SignalLossWatchdogScheduler,
        state: SignalLossWatchdogState,
        nowEpochMs: Long,
    ) {
        val previous = when (val loadedEpisode = episodeStore.load()) {
            AlarmEpisodeLoadResult.Empty -> null
            AlarmEpisodeLoadResult.Corrupt -> {
                reportAlarmDeliveryFailure(context, ALARM_STATE_CORRUPT_MESSAGE)
                retryOrReport(context, watchdogScheduler, state, nowEpochMs)
                return
            }
            is AlarmEpisodeLoadResult.Active -> loadedEpisode.episode.also { episode ->
                if (episode.demo != state.demo) {
                    reportAlarmDeliveryFailure(context, ALARM_MODE_CONFLICT_MESSAGE)
                    retryOrReport(context, watchdogScheduler, state, nowEpochMs)
                    return
                }
            }
        }
        val alreadyOpen = previous?.activeKinds?.contains(AlarmKind.SIGNAL_LOSS) == true
        val activeKinds = previous?.activeKinds.orEmpty() + AlarmKind.SIGNAL_LOSS
        val transition = AlarmEpisodePolicy.transition(
            previous = previous,
            activeKinds = activeKinds,
            newlyOpenedKinds = if (alreadyOpen) emptySet() else setOf(AlarmKind.SIGNAL_LOSS),
            nowEpochMs = nowEpochMs,
            snapshot = previous?.reading,
            demo = state.demo,
            nextEpisodeId = UUID.randomUUID().toString(),
        )
        val readiness = readAlarmReadiness(context)
        var durableEpisode = checkNotNull(transition.episode).let { episode ->
            if (transition.alertNow) {
                AlarmEpisodePolicy.markDeliveryPending(
                    episode,
                    nowEpochMs,
                    AlarmRepeatScheduler.REPEAT_INTERVAL_MS,
                )
            } else {
                episode
            }
        }
        val repeatScheduler = AlarmRepeatScheduler(context)
        val recoveryPrearmed = if (transition.alertNow && !durableEpisode.acknowledged) {
            repeatScheduler.scheduleReadinessRetryWithEmergencyFallback(
                durableEpisode,
                nowEpochMs,
            )
        } else {
            true
        }
        if (!episodeStore.save(durableEpisode)) {
            reportAlarmDeliveryFailure(context, ALARM_PERSISTENCE_FAILED_MESSAGE)
            retryOrReport(context, watchdogScheduler, state, nowEpochMs)
            return
        }
        SensorForegroundService.onSignalLossEpisodeOpened(durableEpisode)
        com.sladkaya.app.widget.GlucoseWidgetProvider.showNoFreshData(
            context,
            demoActive = state.demo,
        )
        val notifier = AlarmNotifier(context).also { it.createChannels() }
        val shown = notifier.showEpisode(durableEpisode, alert = transition.alertNow)
        val followUpScheduled = when {
            durableEpisode.acknowledged -> repeatScheduler.cancel()
            transition.alertNow && readiness.ready && shown -> {
                val delivered = AlarmEpisodePolicy.markAlerted(durableEpisode, nowEpochMs)
                if (episodeStore.save(delivered)) {
                    durableEpisode = delivered
                    SensorForegroundService.onSignalLossEpisodeOpened(delivered)
                } else {
                    reportAlarmDeliveryFailure(context, ALARM_PERSISTENCE_FAILED_MESSAGE)
                }
                recoveryPrearmed || repeatScheduler.scheduleWithEmergencyFallback(
                    durableEpisode,
                    nowEpochMs,
                )
            }
            transition.alertNow -> {
                recoveryPrearmed ||
                    repeatScheduler.scheduleReadinessRetryWithEmergencyFallback(
                        durableEpisode,
                        nowEpochMs,
                    )
            }
            else -> repeatScheduler.scheduleWithEmergencyFallback(durableEpisode, nowEpochMs)
        }
        if (!readiness.ready) {
            reportAlarmDeliveryFailure(context, readiness.userMessage())
        } else if (!shown) {
            reportAlarmDeliveryFailure(context, ALARM_NOTIFICATION_FAILED_MESSAGE)
        }
        if (!followUpScheduled) {
            reportAlarmDeliveryFailure(context, ALARM_SCHEDULE_FAILED_MESSAGE)
            retryOrReport(context, watchdogScheduler, state, nowEpochMs)
            return
        }
        val watchdogCleared = watchdogStore.clear()
        val watchdogCancelled = watchdogScheduler.cancel()
        if (!watchdogCleared || !watchdogCancelled) {
            reportAlarmDeliveryFailure(context, WATCHDOG_CLEAR_FAILED_MESSAGE)
        }
    }

    private fun retryOrReport(
        context: Context,
        scheduler: SignalLossWatchdogScheduler,
        state: SignalLossWatchdogState,
        nowEpochMs: Long,
    ) {
        if (!scheduler.scheduleRetry(state, nowEpochMs)) {
            reportAlarmDeliveryFailure(context, WATCHDOG_SCHEDULE_FAILED_MESSAGE)
        }
    }

    internal companion object {
        private const val ACTION_DEADLINE = "com.sladkaya.app.action.SIGNAL_LOSS_DEADLINE"
        private const val ACTION_WATCHDOG =
            "com.sladkaya.app.action.SIGNAL_LOSS_REVOCATION_WATCHDOG"
        private const val EXTRA_GENERATION = "signal_loss_generation"
        private const val EXTRA_IDENTITY = "signal_loss_identity"
        private const val ALARM_STATE_CORRUPT_MESSAGE =
            "Повреждено сохранённое состояние локальной тревоги"
        private const val ALARM_MODE_CONFLICT_MESSAGE =
            "Состояние потери сигнала относится к другому режиму"
        private const val ALARM_PERSISTENCE_FAILED_MESSAGE =
            "Не удалось сохранить тревогу потери сигнала"
        private const val ALARM_NOTIFICATION_FAILED_MESSAGE =
            "Android не подтвердил показ тревоги потери сигнала"
        private const val ALARM_SCHEDULE_FAILED_MESSAGE =
            "Android не подтвердил следующий повтор тревоги"
        private const val ALARM_SIDE_EFFECT_FAILED_MESSAGE =
            "Android не подтвердил состояние тревоги потери сигнала"
        private const val WATCHDOG_STATE_CORRUPT_MESSAGE =
            "Повреждено состояние контроля свежести данных"
        private const val WATCHDOG_CANCEL_FAILED_MESSAGE =
            "Android не подтвердил отмену старого контроля свежести"
        private const val WATCHDOG_CLEAR_FAILED_MESSAGE =
            "Не удалось закрыть завершённый контроль свежести"
        private const val WATCHDOG_SCHEDULE_FAILED_MESSAGE =
            "Android не подтвердил контроль потери сигнала"
        private val IDENTITY = Regex("^[0-9a-f]{64}$")

        fun intent(context: Context, state: SignalLossWatchdogState?): Intent = Intent(
            context,
            SignalLossWatchdogReceiver::class.java,
        ).setAction(ACTION_DEADLINE)
            .setData(Uri.parse("sladkaya://signal-loss/deadline"))
            .apply { state?.let { putState(it) } }

        fun watchdogIntent(context: Context, state: SignalLossWatchdogState?): Intent = Intent(
            context,
            SignalLossWatchdogReceiver::class.java,
        ).setAction(ACTION_WATCHDOG)
            .setData(Uri.parse("sladkaya://signal-loss/watchdog"))
            .apply { state?.let { putState(it) } }

        private fun Intent.putState(state: SignalLossWatchdogState) {
            putExtra(EXTRA_GENERATION, state.generation)
            putExtra(EXTRA_IDENTITY, state.readingIdentity)
        }
    }
}
