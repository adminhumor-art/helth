package com.sladkaya.app.service

import android.Manifest
import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.sladkaya.app.MainActivity
import com.sladkaya.app.widget.GlucoseWidgetProvider
import com.sladkaya.core.model.AlarmKind
import com.sladkaya.core.model.GlucoseReading
import java.util.Locale
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

internal interface ProductAlarmNotificationPort {
    fun show(presentation: ProductAlarmPresentation, alert: Boolean): Boolean
    fun close(episodeId: String): Boolean
}

internal interface ProductWidgetUpdatePort {
    fun update(reading: GlucoseReading): Boolean
    fun showNoFreshData(): Boolean
}

internal sealed interface ProductAlarmCurrentValuePresentation {
    data class Available(val glucoseMgDl: Int) : ProductAlarmCurrentValuePresentation
    data object UNAVAILABLE : ProductAlarmCurrentValuePresentation
}

internal object ProductAlarmNotificationPresentationPolicy {
    fun showCurrentValue(presentation: ProductAlarmPresentation): Boolean =
        AlarmKind.SIGNAL_LOSS !in presentation.activeKinds

    fun currentValue(
        presentation: ProductAlarmPresentation,
    ): ProductAlarmCurrentValuePresentation = if (showCurrentValue(presentation)) {
        ProductAlarmCurrentValuePresentation.Available(
            checkNotNull(presentation.reading).glucoseMgDl,
        )
    } else {
        ProductAlarmCurrentValuePresentation.UNAVAILABLE
    }
}

/** A durable alarm is delivered only when Android can make the immediate alert audible. */
internal object ProductAlarmDeliveryReadinessPolicy {
    private val audibleBlockers = setOf(
        AlarmReadinessBlocker.NOTIFICATION_PERMISSION,
        AlarmReadinessBlocker.APPLICATION_NOTIFICATIONS,
        AlarmReadinessBlocker.ALARM_CHANNEL_MISSING,
        AlarmReadinessBlocker.ALARM_CHANNEL_IMPORTANCE,
        AlarmReadinessBlocker.ALARM_CHANNEL_SOUND,
        AlarmReadinessBlocker.ALARM_AUDIO_USAGE,
        AlarmReadinessBlocker.ALARM_CHANNEL_VIBRATION,
        AlarmReadinessBlocker.ALARM_VOLUME,
        AlarmReadinessBlocker.DO_NOT_DISTURB,
    )

    fun canDeliverAudibly(blockers: Set<AlarmReadinessBlocker>): Boolean =
        blockers.none(audibleBlockers::contains)
}

internal class AndroidProductLocalDeliveryEffects(
    private val notifications: ProductAlarmNotificationPort,
    private val widgets: ProductWidgetUpdatePort,
) : ProductLocalDeliveryEffects {
    constructor(context: Context) : this(
        notifications = AndroidProductAlarmNotificationPort(context.applicationContext),
        widgets = AndroidProductWidgetUpdatePort(context.applicationContext),
    )

    override suspend fun show(
        presentation: ProductAlarmPresentation,
    ): ProductLocalDeliveryEffectResult {
        if (!notifications.show(presentation, alert = true)) {
            return ProductLocalDeliveryEffectResult.TransientFailure
        }
        val previousEpisodeId = ProductAlarmNotificationReplacementPolicy.previousEpisodeId(
            presentation,
        )
        if (previousEpisodeId != null && !notifications.close(previousEpisodeId)) {
            return ProductLocalDeliveryEffectResult.TransientFailure
        }
        return ProductLocalDeliveryEffectResult.Applied
    }

    override suspend fun update(
        presentation: ProductAlarmPresentation,
    ): ProductLocalDeliveryEffectResult = notifications.result(presentation, alert = false)

    override suspend fun repeat(
        presentation: ProductAlarmPresentation,
    ): ProductLocalDeliveryEffectResult = notifications.result(presentation, alert = true)

    override suspend fun close(episodeId: String): ProductLocalDeliveryEffectResult =
        if (notifications.close(episodeId)) {
            ProductLocalDeliveryEffectResult.Applied
        } else {
            ProductLocalDeliveryEffectResult.TransientFailure
        }

    override suspend fun updateWidget(
        reading: GlucoseReading?,
    ): ProductLocalDeliveryEffectResult = if (
        if (reading == null) widgets.showNoFreshData() else widgets.update(reading)
    ) {
        ProductLocalDeliveryEffectResult.Applied
    } else {
        ProductLocalDeliveryEffectResult.TransientFailure
    }

    private fun ProductAlarmNotificationPort.result(
        presentation: ProductAlarmPresentation,
        alert: Boolean,
    ): ProductLocalDeliveryEffectResult = if (show(presentation, alert)) {
        ProductLocalDeliveryEffectResult.Applied
    } else {
        ProductLocalDeliveryEffectResult.TransientFailure
    }
}

/**
 * SHOW itself is durable. The replacement is shown before the preceding stable tag is closed, so
 * a failure cannot create an alert gap; both operations are idempotent on every durable retry.
 */
internal object ProductAlarmNotificationReplacementPolicy {
    fun previousEpisodeId(presentation: ProductAlarmPresentation): String? =
        presentation.generation.takeIf { it > 1L }?.let { generation ->
            ProductAlarmEpisodeIdentity.derive(
                publicationBindingId = presentation.publicationBindingId,
                generation = generation - 1L,
            )
        }
}

/** Product notifications use a durable episode tag and a durable mutation acknowledgement path. */
internal class AndroidProductAlarmNotificationPort(context: Context) :
    ProductAlarmNotificationPort {
    private val appContext = context.applicationContext

    override fun show(presentation: ProductAlarmPresentation, alert: Boolean): Boolean {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(appContext, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            return false
        }
        if (runCatching { AlarmNotifier(appContext).createChannels() }.isFailure) return false
        if (alert && !ProductAlarmDeliveryReadinessPolicy.canDeliverAudibly(
                readAlarmReadiness(appContext).blockers,
            )
        ) {
            return false
        }
        return runCatching {
            NotificationManagerCompat.from(appContext).notify(
                presentation.episodeId,
                AlarmNotificationIds.ACTIVE_EPISODE,
                notification(presentation, alert),
            )
        }.isSuccess
    }

    override fun close(episodeId: String): Boolean {
        if (!EPISODE_ID.matches(episodeId)) return false
        return runCatching {
            NotificationManagerCompat.from(appContext).cancel(
                episodeId,
                AlarmNotificationIds.ACTIVE_EPISODE,
            )
        }.isSuccess
    }

    private fun notification(
        presentation: ProductAlarmPresentation,
        alert: Boolean,
    ): android.app.Notification {
        val baseTitle = presentation.activeKinds.title()
        val title = if (presentation.acknowledged) {
            "Подтверждено · $baseTitle"
        } else {
            baseTitle
        }
        val message = buildString {
            append(title).append(" · ")
            when (val current = ProductAlarmNotificationPresentationPolicy.currentValue(presentation)) {
                is ProductAlarmCurrentValuePresentation.Available -> append(
                    String.format(
                        Locale.forLanguageTag("ru"),
                        "%.1f ммоль/л",
                        current.glucoseMgDl / 18.0,
                    ),
                )
                ProductAlarmCurrentValuePresentation.UNAVAILABLE ->
                    append("текущее значение недоступно")
            }
            if (presentation.acknowledged) {
                append(" · звук остановлен, тревога остаётся активной")
            }
        }
        return NotificationCompat.Builder(appContext, AlarmNotifier.ALARM_CHANNEL)
            .setSmallIcon(android.R.drawable.ic_dialog_alert)
            .setContentTitle(title)
            .setContentText(message)
            .setStyle(NotificationCompat.BigTextStyle().bigText(message))
            .setContentIntent(openAppIntent())
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setOngoing(true)
            .setOnlyAlertOnce(!alert)
            .setSilent(!alert)
            .apply {
                if (!presentation.acknowledged) {
                    addAction(
                        android.R.drawable.ic_menu_close_clear_cancel,
                        "Подтвердить",
                        ProductAlarmAcknowledgeReceiver.pendingIntent(
                            appContext,
                            presentation,
                        ),
                    )
                }
            }
            .build()
    }

    private fun openAppIntent(): PendingIntent = PendingIntent.getActivity(
        appContext,
        OPEN_APP_REQUEST,
        Intent(appContext, MainActivity::class.java).addFlags(
            Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP,
        ),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    private fun Set<AlarmKind>.title(): String = when {
        AlarmKind.LOW in this -> "Низкая глюкоза"
        AlarmKind.HIGH in this -> "Высокая глюкоза"
        AlarmKind.RAPID_FALL in this -> "Глюкоза быстро снижается"
        AlarmKind.RAPID_RISE in this -> "Глюкоза быстро повышается"
        else -> "Нет свежих данных"
    }

    private companion object {
        const val OPEN_APP_REQUEST = 4_109
        val EPISODE_ID = Regex("^[0-9a-f]{64}$")
    }
}

internal class AndroidProductWidgetUpdatePort(context: Context) : ProductWidgetUpdatePort {
    private val appContext = context.applicationContext

    override fun update(reading: GlucoseReading): Boolean = runCatching {
        reading.requireProductPublication()
        GlucoseWidgetProvider.updateAll(appContext, reading)
    }.isSuccess

    override fun showNoFreshData(): Boolean = runCatching {
        GlucoseWidgetProvider.showNoFreshData(appContext, demoActive = false)
    }.isSuccess
}

internal object ProductAlarmAcknowledgementIdentityPolicy {
    fun accepts(
        episodeId: String,
        publicationBindingId: String,
        generation: Long,
    ): Boolean = runCatching {
        ProductAlarmEpisodeIdentity.derive(publicationBindingId, generation) == episodeId
    }.getOrDefault(false)
}

internal data class ProductAlarmAcknowledgementMutationRequest(
    val episodeId: String,
    val publicationBindingId: String,
    val generation: Long,
    val acknowledgedAtEpochMs: Long,
) {
    init {
        require(
            ProductAlarmAcknowledgementIdentityPolicy.accepts(
                episodeId,
                publicationBindingId,
                generation,
            ),
        )
        require(acknowledgedAtEpochMs > 0)
    }
}

internal sealed interface ProductAlarmAcknowledgementMutationResult {
    data object Applied : ProductAlarmAcknowledgementMutationResult
    data object AlreadyApplied : ProductAlarmAcknowledgementMutationResult
    data object Stale : ProductAlarmAcknowledgementMutationResult
    data object TransientFailure : ProductAlarmAcknowledgementMutationResult
    data object Conflict : ProductAlarmAcknowledgementMutationResult
}

/**
 * Implementations must atomically acknowledge the exact durable generation and enqueue any
 * resulting notification delivery before returning [ProductAlarmAcknowledgementMutationResult.Applied].
 */
internal fun interface ProductAlarmAcknowledgementMutationPort {
    suspend fun acknowledge(
        request: ProductAlarmAcknowledgementMutationRequest,
    ): ProductAlarmAcknowledgementMutationResult
}

internal fun interface ProductAlarmAcknowledgementMutationFactory {
    fun create(context: Context): ProductAlarmAcknowledgementMutationPort
}

internal object ProductAlarmAcknowledgementReceiverRuntime {
    @Volatile
    private var factory: ProductAlarmAcknowledgementMutationFactory? = null

    fun install(value: ProductAlarmAcknowledgementMutationFactory) {
        factory = value
    }

    fun create(context: Context): ProductAlarmAcknowledgementMutationPort? =
        factory?.create(context.applicationContext)
}

internal class ProductAlarmAcknowledgeReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_ACKNOWLEDGE) return
        val episodeId = intent.getStringExtra(EXTRA_EPISODE_ID) ?: return
        val bindingId = intent.getStringExtra(EXTRA_BINDING_ID) ?: return
        val generation = intent.getLongExtra(EXTRA_GENERATION, -1L)
        if (intent.data?.lastPathSegment != episodeId ||
            !ProductAlarmAcknowledgementIdentityPolicy.accepts(
                episodeId,
                bindingId,
                generation,
            )
        ) {
            return
        }
        val mutation = runCatching {
            ProductAlarmAcknowledgementReceiverRuntime.create(context)
        }.getOrNull() ?: return
        val request = runCatching {
            ProductAlarmAcknowledgementMutationRequest(
                episodeId = episodeId,
                publicationBindingId = bindingId,
                generation = generation,
                acknowledgedAtEpochMs = System.currentTimeMillis(),
            )
        }.getOrNull() ?: return
        val pending = goAsync()
        val wakeLock = context.getSystemService(PowerManager::class.java).newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            ACK_WAKE_LOCK_TAG,
        )
        runCatching { wakeLock.acquire(ACK_WAKE_LOCK_TIMEOUT_MS) }
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                val result = try {
                    mutation.acknowledge(request)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: RuntimeException) {
                    ProductAlarmAcknowledgementMutationResult.TransientFailure
                }
                when (result) {
                    ProductAlarmAcknowledgementMutationResult.Applied,
                    ProductAlarmAcknowledgementMutationResult.AlreadyApplied,
                    -> try {
                        ProductLocalDeliveryReceiverRuntime.create(context)?.runBounded()
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (_: RuntimeException) {
                        Unit
                    }
                    ProductAlarmAcknowledgementMutationResult.Conflict,
                    ProductAlarmAcknowledgementMutationResult.Stale,
                    ProductAlarmAcknowledgementMutationResult.TransientFailure,
                    -> Unit
                }
            } finally {
                if (wakeLock.isHeld) runCatching { wakeLock.release() }
                pending.finish()
            }
        }
    }

    internal companion object {
        private const val ACTION_ACKNOWLEDGE =
            "com.sladkaya.app.action.ACKNOWLEDGE_PRODUCT_ALARM"
        private const val EXTRA_EPISODE_ID = "product_alarm_episode_id"
        private const val EXTRA_BINDING_ID = "product_alarm_binding_id"
        private const val EXTRA_GENERATION = "product_alarm_generation"
        private const val ACK_REQUEST = 4_112
        private const val ACK_WAKE_LOCK_TAG = "com.sladkaya.app:product_alarm_ack"
        private const val ACK_WAKE_LOCK_TIMEOUT_MS = 30_000L

        fun pendingIntent(
            context: Context,
            presentation: ProductAlarmPresentation,
        ): PendingIntent = PendingIntent.getBroadcast(
            context,
            ACK_REQUEST,
            Intent(context, ProductAlarmAcknowledgeReceiver::class.java)
                .setAction(ACTION_ACKNOWLEDGE)
                .setData(Uri.parse("sladkaya://alarm/product-ack/${presentation.episodeId}"))
                .putExtra(EXTRA_EPISODE_ID, presentation.episodeId)
                .putExtra(EXTRA_BINDING_ID, presentation.publicationBindingId)
                .putExtra(EXTRA_GENERATION, presentation.generation),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }
}

internal enum class ProductLocalDeliveryWakeKind {
    EXACT,
    INEXACT_FALLBACK,
}

internal data class ProductLocalDeliveryWakePlan(
    val primaryKind: ProductLocalDeliveryWakeKind,
    val deadlineEpochMs: Long,
    val watchdogEpochMs: Long?,
)

internal object ProductLocalDeliveryWakePlanPolicy {
    const val WATCHDOG_GRACE_MS = 60_000L

    fun plan(
        deadlineEpochMs: Long,
        exactAlarmAccess: Boolean,
    ): ProductLocalDeliveryWakePlan {
        require(deadlineEpochMs > 0)
        return ProductLocalDeliveryWakePlan(
            primaryKind = if (exactAlarmAccess) {
                ProductLocalDeliveryWakeKind.EXACT
            } else {
                ProductLocalDeliveryWakeKind.INEXACT_FALLBACK
            },
            deadlineEpochMs = deadlineEpochMs,
            watchdogEpochMs = if (exactAlarmAccess) {
                safeAdd(deadlineEpochMs, WATCHDOG_GRACE_MS)
            } else {
                null
            },
        )
    }

    private fun safeAdd(value: Long, delta: Long): Long =
        if (value > Long.MAX_VALUE - delta) Long.MAX_VALUE else value + delta
}

internal class AndroidProductLocalDeliveryWakeScheduler(context: Context) :
    ProductLocalDeliveryWakeScheduler {
    private val appContext = context.applicationContext
    private val alarmManager = appContext.getSystemService(AlarmManager::class.java)

    override fun schedule(deadlineEpochMs: Long): ProductLocalDeliveryWakeResult {
        if (deadlineEpochMs <= 0) return ProductLocalDeliveryWakeResult.Conflict
        val exactAccess = Build.VERSION.SDK_INT < Build.VERSION_CODES.S || runCatching {
            alarmManager.canScheduleExactAlarms()
        }.getOrDefault(false)
        val plan = ProductLocalDeliveryWakePlanPolicy.plan(deadlineEpochMs, exactAccess)
        return when (plan.primaryKind) {
            ProductLocalDeliveryWakeKind.EXACT -> {
                val watchdogScheduled = runCatching {
                    alarmManager.setAndAllowWhileIdle(
                        AlarmManager.RTC_WAKEUP,
                        checkNotNull(plan.watchdogEpochMs),
                        watchdogPendingIntent(),
                    )
                }.isSuccess
                val primaryScheduled = runCatching {
                    alarmManager.setExactAndAllowWhileIdle(
                        AlarmManager.RTC_WAKEUP,
                        plan.deadlineEpochMs,
                        primaryPendingIntent(),
                    )
                }.isSuccess || scheduleInexact(plan.deadlineEpochMs)
                if (primaryScheduled && watchdogScheduled) {
                    ProductLocalDeliveryWakeResult.Scheduled
                } else {
                    ProductLocalDeliveryWakeResult.TransientFailure
                }
            }
            ProductLocalDeliveryWakeKind.INEXACT_FALLBACK ->
                if (scheduleInexact(plan.deadlineEpochMs)) {
                    ProductLocalDeliveryWakeResult.Scheduled
                } else {
                    ProductLocalDeliveryWakeResult.TransientFailure
                }
        }
    }

    override fun cancel(): ProductLocalDeliveryWakeResult = if (runCatching {
            alarmManager.cancel(primaryPendingIntent())
            alarmManager.cancel(watchdogPendingIntent())
        }.isSuccess
    ) {
        ProductLocalDeliveryWakeResult.Scheduled
    } else {
        ProductLocalDeliveryWakeResult.TransientFailure
    }

    private fun scheduleInexact(deadlineEpochMs: Long): Boolean = runCatching {
        alarmManager.setAndAllowWhileIdle(
            AlarmManager.RTC_WAKEUP,
            deadlineEpochMs,
            primaryPendingIntent(),
        )
    }.isSuccess || runCatching {
        alarmManager.set(
            AlarmManager.RTC_WAKEUP,
            deadlineEpochMs,
            primaryPendingIntent(),
        )
    }.isSuccess

    private fun primaryPendingIntent(): PendingIntent = PendingIntent.getBroadcast(
        appContext,
        PRIMARY_REQUEST,
        ProductLocalDeliveryWakeReceiver.intent(appContext),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    private fun watchdogPendingIntent(): PendingIntent = PendingIntent.getBroadcast(
        appContext,
        WATCHDOG_REQUEST,
        ProductLocalDeliveryWakeReceiver.watchdogIntent(appContext),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    private companion object {
        const val PRIMARY_REQUEST = 4_110
        const val WATCHDOG_REQUEST = 4_111
    }
}

internal fun interface ProductLocalDeliveryRunnerFactory {
    fun create(context: Context): ProductLocalDeliveryDrain
}

/** Installed by application wiring before the first product delivery is scheduled. */
internal object ProductLocalDeliveryReceiverRuntime {
    @Volatile
    private var factory: ProductLocalDeliveryRunnerFactory? = null

    fun install(value: ProductLocalDeliveryRunnerFactory) {
        factory = value
    }

    fun create(context: Context): ProductLocalDeliveryDrain? =
        factory?.create(context.applicationContext)
}

internal class ProductLocalDeliveryWakeReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (!accepts(intent)) return
        val runner = runCatching {
            ProductLocalDeliveryReceiverRuntime.create(context)
        }.getOrNull() ?: return
        val pending = goAsync()
        val wakeLock = context.getSystemService(PowerManager::class.java).newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            WAKE_LOCK_TAG,
        )
        runCatching { wakeLock.acquire(WAKE_LOCK_TIMEOUT_MS) }
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                runner.runBounded()
            } finally {
                if (wakeLock.isHeld) runCatching { wakeLock.release() }
                pending.finish()
            }
        }
    }

    private fun accepts(intent: Intent): Boolean = when (intent.action) {
        ACTION_WAKE -> intent.data?.lastPathSegment == PRIMARY_PATH
        ACTION_WATCHDOG -> intent.data?.lastPathSegment == WATCHDOG_PATH
        else -> false
    }

    internal companion object {
        private const val ACTION_WAKE = "com.sladkaya.app.action.PRODUCT_LOCAL_DELIVERY_WAKE"
        private const val ACTION_WATCHDOG =
            "com.sladkaya.app.action.PRODUCT_LOCAL_DELIVERY_WATCHDOG"
        private const val PRIMARY_PATH = "primary"
        private const val WATCHDOG_PATH = "watchdog"
        private const val WAKE_LOCK_TAG = "com.sladkaya.app:product_local_delivery"
        private const val WAKE_LOCK_TIMEOUT_MS = 30_000L

        fun intent(context: Context): Intent = Intent(
            context,
            ProductLocalDeliveryWakeReceiver::class.java,
        ).setAction(ACTION_WAKE)
            .setData(Uri.parse("sladkaya://alarm/product-delivery/$PRIMARY_PATH"))

        fun watchdogIntent(context: Context): Intent = Intent(
            context,
            ProductLocalDeliveryWakeReceiver::class.java,
        ).setAction(ACTION_WATCHDOG)
            .setData(Uri.parse("sladkaya://alarm/product-delivery/$WATCHDOG_PATH"))
    }
}
