package com.sladkaya.app.widget

import android.app.AlarmManager
import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.os.SystemClock
import android.os.Build
import android.widget.RemoteViews
import androidx.core.content.edit
import com.sladkaya.app.MainActivity
import com.sladkaya.app.R
import com.sladkaya.app.settings.AlarmSettingsStore
import com.sladkaya.core.model.GlucoseReading
import com.sladkaya.core.model.ReadingQuality
import com.sladkaya.core.model.SensorFamily
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class GlucoseWidgetProvider : AppWidgetProvider() {
    override fun onUpdate(context: Context, manager: AppWidgetManager, appWidgetIds: IntArray) {
        refreshAll(context)
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == ACTION_EXPIRE_READING ||
            intent.action == ACTION_EXPIRY_REVOCATION_WATCHDOG
        ) {
            expireIfNeeded(context)
            return
        }
        if (intent.action == AlarmManager.ACTION_SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED) {
            refreshAll(context)
            return
        }
        super.onReceive(context, intent)
    }

    override fun onDisabled(context: Context) {
        cancelExpiry(context)
        super.onDisabled(context)
    }

    companion object {
        private const val PREFS = "widget_glucose"
        private const val KEY_MG_DL = "mg_dl"
        private const val KEY_TREND = "trend_mg_dl_per_minute"
        private const val KEY_SENSOR_TIME = "sensor_time_epoch_ms"
        private const val KEY_PHONE_TIME = "phone_time_epoch_ms"
        private const val KEY_QUALITY = "quality"
        private const val KEY_DEMO_ACTIVE = "demo_active"
        private const val KEY_EXPIRY_ELAPSED = "expiry_elapsed_realtime_ms"
        private const val KEY_STALE_AFTER = "expiry_stale_after_ms"
        private const val ACTION_EXPIRE_READING =
            "com.sladkaya.app.widget.EXPIRE_READING"
        private const val ACTION_EXPIRY_REVOCATION_WATCHDOG =
            "com.sladkaya.app.widget.EXPIRY_REVOCATION_WATCHDOG"

        fun updateAll(context: Context, reading: GlucoseReading) {
            val nowEpochMs = System.currentTimeMillis()
            val staleAfterMs = AlarmSettingsStore(context).load().thresholds.staleAfterMs
            if (!WidgetReadingPersistencePolicy.canPersist(reading) ||
                !WidgetReadingPresentationPolicy.canShowValue(
                    demoActive = false,
                    quality = reading.quality,
                    sensorTimeEpochMs = reading.sensorTimeEpochMs,
                    phoneTimeEpochMs = reading.phoneTimeEpochMs,
                    nowEpochMs = nowEpochMs,
                    staleAfterMs = staleAfterMs,
                )
            ) {
                showNoFreshData(context, demoActive = false)
                return
            }
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit {
                putInt(KEY_MG_DL, reading.glucoseMgDl)
                putFloat(KEY_TREND, reading.trendMgDlPerMinute.toFloat())
                putLong(KEY_SENSOR_TIME, reading.sensorTimeEpochMs)
                putLong(KEY_PHONE_TIME, reading.phoneTimeEpochMs)
                putString(KEY_QUALITY, reading.quality.wireName)
                putBoolean(KEY_DEMO_ACTIVE, false)
            }
            if (scheduleExpiry(
                context = context,
                sensorTimeEpochMs = reading.sensorTimeEpochMs,
                phoneTimeEpochMs = reading.phoneTimeEpochMs,
            )) {
                updateComponents(context, views(context))
            }
        }

        fun updateDemoAll(context: Context, reading: GlucoseReading) {
            val nowEpochMs = System.currentTimeMillis()
            val staleAfterMs = AlarmSettingsStore(context).load().thresholds.staleAfterMs
            if (reading.sensorFamily != SensorFamily.SIMULATOR ||
                !WidgetReadingPresentationPolicy.canShowValue(
                    demoActive = false,
                    quality = reading.quality,
                    sensorTimeEpochMs = reading.sensorTimeEpochMs,
                    phoneTimeEpochMs = reading.phoneTimeEpochMs,
                    nowEpochMs = nowEpochMs,
                    staleAfterMs = staleAfterMs,
                )
            ) {
                showNoFreshData(context, demoActive = true)
                return
            }
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit {
                putBoolean(KEY_DEMO_ACTIVE, true)
                putLong(KEY_SENSOR_TIME, reading.sensorTimeEpochMs)
                putLong(KEY_PHONE_TIME, reading.phoneTimeEpochMs)
                putString(KEY_QUALITY, reading.quality.wireName)
            }
            if (scheduleExpiry(
                context = context,
                sensorTimeEpochMs = reading.sensorTimeEpochMs,
                phoneTimeEpochMs = reading.phoneTimeEpochMs,
            )) {
                updateComponents(
                    context,
                    views(context, transientReading = reading, transientDemo = true),
                )
            }
        }

        fun showDemoWaiting(context: Context) {
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit {
                removeReading()
                putBoolean(KEY_DEMO_ACTIVE, true)
            }
            cancelExpiry(context)
            updateComponents(context, views(context))
        }

        fun refreshAll(context: Context) {
            val preferences = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            val demoActive = preferences.getBoolean(KEY_DEMO_ACTIVE, false)
            val sensorTime = preferences.getLong(KEY_SENSOR_TIME, 0L)
            val phoneTime = preferences.getLong(KEY_PHONE_TIME, 0L)
            val quality = preferences.getString(KEY_QUALITY, null)?.let { wireName ->
                ReadingQuality.entries.firstOrNull { it.wireName == wireName }
            }
            val now = System.currentTimeMillis()
            val nowElapsed = SystemClock.elapsedRealtime()
            val staleAfterMs = AlarmSettingsStore(context).load().thresholds.staleAfterMs
            val storedExpiryElapsed = preferences.getLong(KEY_EXPIRY_ELAPSED, 0L)
            val storedStaleAfterMs = preferences.getLong(KEY_STALE_AFTER, 0L)
            val elapsedDeadlineUsable = if (storedStaleAfterMs == staleAfterMs) {
                storedExpiryElapsed > nowElapsed
            } else {
                storedExpiryElapsed > 0L
            }
            val hasAnyTimestamp = sensorTime > 0L || phoneTime > 0L
            val hasCompleteTimestamp = sensorTime > 0L && phoneTime > 0L
            val canKeep = if (demoActive) {
                quality == ReadingQuality.VALID && hasCompleteTimestamp &&
                    elapsedDeadlineUsable &&
                    WidgetExpiryPolicy.deadlineEpochMs(sensorTime, phoneTime, staleAfterMs) > now
            } else {
                elapsedDeadlineUsable && WidgetReadingPresentationPolicy.canShowValue(
                    demoActive = false,
                    quality = quality,
                    sensorTimeEpochMs = sensorTime,
                    phoneTimeEpochMs = phoneTime,
                    nowEpochMs = now,
                    staleAfterMs = staleAfterMs,
                )
            }
            if (hasAnyTimestamp && !canKeep) {
                showNoFreshData(context, demoActive)
                return
            }
            if (canKeep) {
                if (!scheduleExpiry(
                        context = context,
                        sensorTimeEpochMs = sensorTime,
                        phoneTimeEpochMs = phoneTime,
                        preserveExistingDeadline = storedStaleAfterMs == staleAfterMs,
                    )
                ) {
                    return
                }
            } else {
                cancelExpiry(context)
            }
            updateComponents(context, views(context))
        }

        fun showNoFreshData(context: Context, demoActive: Boolean) {
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit {
                removeReading()
                putBoolean(KEY_DEMO_ACTIVE, demoActive)
            }
            cancelExpiry(context)
            updateComponents(
                context,
                views(
                    context = context,
                    emptyMessage = context.getString(
                        if (demoActive) {
                            R.string.widget_demo_no_fresh_data
                        } else {
                            R.string.widget_no_fresh_data
                        },
                    ),
                    forceNoFreshData = true,
                ),
            )
        }

        fun showSetupRequired(context: Context) {
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit { clear() }
            cancelExpiry(context)
            updateComponents(context, views(context, context.getString(R.string.widget_setup_required)))
        }

        private fun expireIfNeeded(context: Context) {
            val preferences = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            val demoActive = preferences.getBoolean(KEY_DEMO_ACTIVE, false)
            val expiryElapsed = preferences.getLong(KEY_EXPIRY_ELAPSED, 0L)
            if (expiryElapsed > SystemClock.elapsedRealtime()) {
                if (!armExpiry(context, expiryElapsed)) {
                    showNoFreshData(context, demoActive)
                }
            } else {
                showNoFreshData(context, demoActive)
            }
        }

        private fun scheduleExpiry(
            context: Context,
            sensorTimeEpochMs: Long,
            phoneTimeEpochMs: Long,
            preserveExistingDeadline: Boolean = false,
        ): Boolean {
            val staleAfterMs = AlarmSettingsStore(context).load().thresholds.staleAfterMs
            val remainingMs = WidgetExpiryPolicy.deadlineEpochMs(
                sensorTimeEpochMs = sensorTimeEpochMs,
                phoneTimeEpochMs = phoneTimeEpochMs,
                staleAfterMs = staleAfterMs,
            ) - System.currentTimeMillis()
            if (remainingMs <= 0L) {
                showNoFreshData(
                    context,
                    context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                        .getBoolean(KEY_DEMO_ACTIVE, false),
                )
                return false
            }
            val preferences = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            val existingDeadline = preferences.getLong(KEY_EXPIRY_ELAPSED, 0L)
            val nowElapsed = SystemClock.elapsedRealtime()
            val expiryElapsed = WidgetExpiryPolicy.elapsedDeadlineMs(
                existingDeadlineMs = existingDeadline,
                nowElapsedMs = nowElapsed,
                remainingFreshMs = remainingMs,
                preserveExistingDeadline = preserveExistingDeadline,
            )
            if (expiryElapsed <= nowElapsed) {
                showNoFreshData(
                    context,
                    preferences.getBoolean(KEY_DEMO_ACTIVE, false),
                )
                return false
            }
            preferences.edit {
                putLong(KEY_EXPIRY_ELAPSED, expiryElapsed)
                putLong(KEY_STALE_AFTER, staleAfterMs)
            }
            val armed = armExpiry(context, expiryElapsed)
            if (!armed) {
                showNoFreshData(
                    context,
                    context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                        .getBoolean(KEY_DEMO_ACTIVE, false),
                )
            }
            return armed
        }

        private fun armExpiry(context: Context, expiryElapsed: Long): Boolean {
            return runCatching {
                val manager = context.getSystemService(AlarmManager::class.java)
                val canScheduleExact = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    manager.canScheduleExactAlarms()
                } else {
                    true
                }
                val plan = WidgetExpiryAlarmPlanPolicy.plan(
                    sdkInt = Build.VERSION.SDK_INT,
                    canScheduleExactAlarms = canScheduleExact,
                )
                check(WidgetExpiryAlarmKind.EXACT_EXPIRY in plan) {
                    "Exact widget expiry is unavailable"
                }
                if (WidgetExpiryAlarmKind.INEXACT_REVOCATION_WATCHDOG in plan) {
                    manager.setAndAllowWhileIdle(
                        AlarmManager.ELAPSED_REALTIME_WAKEUP,
                        expiryElapsed,
                        expiryRevocationWatchdogIntent(context),
                    )
                }
                manager.setExactAndAllowWhileIdle(
                    AlarmManager.ELAPSED_REALTIME_WAKEUP,
                    expiryElapsed,
                    expiryIntent(context),
                )
            }.isSuccess
        }

        private fun cancelExpiry(context: Context) {
            runCatching {
                context.getSystemService(AlarmManager::class.java).also { manager ->
                    manager.cancel(expiryIntent(context))
                    manager.cancel(expiryRevocationWatchdogIntent(context))
                }
            }
        }

        private fun expiryIntent(context: Context): PendingIntent = PendingIntent.getBroadcast(
            context,
            12,
            Intent(context, GlucoseWidgetProvider::class.java).setAction(ACTION_EXPIRE_READING),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        private fun expiryRevocationWatchdogIntent(context: Context): PendingIntent =
            PendingIntent.getBroadcast(
                context,
                13,
                Intent(context, GlucoseWidgetProvider::class.java)
                    .setAction(ACTION_EXPIRY_REVOCATION_WATCHDOG),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )

        private fun SharedPreferences.Editor.removeReading() {
            remove(KEY_MG_DL)
            remove(KEY_TREND)
            remove(KEY_SENSOR_TIME)
            remove(KEY_PHONE_TIME)
            remove(KEY_QUALITY)
            remove(KEY_EXPIRY_ELAPSED)
            remove(KEY_STALE_AFTER)
        }

        private fun updateComponents(context: Context, remoteViews: RemoteViews) {
            val manager = AppWidgetManager.getInstance(context)
            val component = ComponentName(context, GlucoseWidgetProvider::class.java)
            manager.getAppWidgetIds(component).forEach { manager.updateAppWidget(it, remoteViews) }
        }

        private fun views(
            context: Context,
            emptyMessage: String = context.getString(R.string.widget_waiting),
            transientReading: GlucoseReading? = null,
            transientDemo: Boolean = false,
            forceNoFreshData: Boolean = false,
        ): RemoteViews {
            val preferences = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            val demoActive = preferences.getBoolean(KEY_DEMO_ACTIVE, false)
            val persistedQuality = preferences.getString(KEY_QUALITY, null)?.let { wireName ->
                ReadingQuality.entries.firstOrNull { it.wireName == wireName }
            }
            val persistedMgDl = preferences.getInt(KEY_MG_DL, 0)
            val persistedTrend = preferences.getFloat(KEY_TREND, 0f)
            val persistedSensorTime = preferences.getLong(KEY_SENSOR_TIME, 0L)
            val persistedPhoneTime = preferences.getLong(KEY_PHONE_TIME, 0L)
            val persistedExpiryElapsed = preferences.getLong(KEY_EXPIRY_ELAPSED, 0L)
            val canShowPersisted = !forceNoFreshData &&
                persistedMgDl in 20..600 &&
                persistedTrend.isFinite() &&
                persistedExpiryElapsed > SystemClock.elapsedRealtime() &&
                WidgetReadingPresentationPolicy.canShowValue(
                    demoActive = demoActive,
                    quality = persistedQuality,
                    sensorTimeEpochMs = persistedSensorTime,
                    phoneTimeEpochMs = persistedPhoneTime,
                    nowEpochMs = System.currentTimeMillis(),
                    staleAfterMs = AlarmSettingsStore(context).load().thresholds.staleAfterMs,
                )
            val demoReading = transientReading.takeIf { transientDemo }
            val mgDl = when {
                demoReading != null -> demoReading.glucoseMgDl
                canShowPersisted -> persistedMgDl
                else -> 0
            }
            val trend = when {
                demoReading != null -> demoReading.trendMgDlPerMinute.toFloat()
                canShowPersisted -> persistedTrend
                else -> 0f
            }
            val time = when {
                demoReading != null -> demoReading.sensorTimeEpochMs
                canShowPersisted -> persistedSensorTime
                else -> 0L
            }
            val noValueMessage = when {
                forceNoFreshData -> emptyMessage
                demoActive && demoReading == null -> context.getString(R.string.widget_demo_open_app)
                persistedSensorTime != 0L -> context.getString(R.string.widget_no_fresh_data)
                else -> emptyMessage
            }
            return RemoteViews(context.packageName, R.layout.glucose_widget).apply {
                setTextViewText(R.id.widget_value, if (mgDl == 0) context.getString(R.string.widget_empty_value) else String.format(Locale.forLanguageTag("ru"), "%.1f", mgDl / 18.0))
                setTextViewText(R.id.widget_trend, if (mgDl == 0) "" else trendArrow(trend))
                setTextViewText(
                    R.id.widget_time,
                    if (time == 0L) {
                        noValueMessage
                    } else {
                        "${if (transientDemo) "ДЕМО · " else ""}Обновлено ${
                            SimpleDateFormat("HH:mm", Locale.forLanguageTag("ru")).format(Date(time))
                        }"
                    },
                )
                val open = PendingIntent.getActivity(
                    context, 11, Intent(context, MainActivity::class.java),
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                )
                setOnClickPendingIntent(R.id.widget_root, open)
            }
        }

        private fun trendArrow(trend: Float): String = when {
            trend <= -3f -> "↓↓"
            trend <= -1f -> "↓"
            trend >= 3f -> "↑↑"
            trend >= 1f -> "↑"
            else -> "→"
        }
    }
}

internal object WidgetExactAlarmPolicy {
    fun canArm(sdkInt: Int, canScheduleExactAlarms: Boolean): Boolean =
        sdkInt < 31 || canScheduleExactAlarms
}

internal enum class WidgetExpiryAlarmKind {
    EXACT_EXPIRY,
    INEXACT_REVOCATION_WATCHDOG,
}

internal object WidgetExpiryAlarmPlanPolicy {
    fun plan(sdkInt: Int, canScheduleExactAlarms: Boolean): Set<WidgetExpiryAlarmKind> =
        if (WidgetExactAlarmPolicy.canArm(sdkInt, canScheduleExactAlarms)) {
            setOf(
                WidgetExpiryAlarmKind.EXACT_EXPIRY,
                WidgetExpiryAlarmKind.INEXACT_REVOCATION_WATCHDOG,
            )
        } else {
            emptySet()
        }
}

internal object WidgetReadingPersistencePolicy {
    fun canPersist(reading: GlucoseReading): Boolean =
        reading.sensorFamily != SensorFamily.SIMULATOR && reading.quality == ReadingQuality.VALID
}

internal object WidgetReadingPresentationPolicy {
    fun canShowValue(
        demoActive: Boolean,
        quality: ReadingQuality?,
        sensorTimeEpochMs: Long,
        phoneTimeEpochMs: Long,
        nowEpochMs: Long,
        staleAfterMs: Long,
    ): Boolean {
        require(staleAfterMs > 0L)
        if (demoActive || quality != ReadingQuality.VALID) return false
        if (sensorTimeEpochMs <= 0L || phoneTimeEpochMs <= 0L) return false
        if (sensorTimeEpochMs > nowEpochMs + MAX_SENSOR_FUTURE_SKEW_MS ||
            phoneTimeEpochMs > nowEpochMs
        ) {
            return false
        }
        return nowEpochMs - sensorTimeEpochMs < staleAfterMs &&
            nowEpochMs - phoneTimeEpochMs < staleAfterMs
    }

    private const val MAX_SENSOR_FUTURE_SKEW_MS = 5 * 60_000L
}

internal object WidgetExpiryPolicy {
    fun deadlineEpochMs(
        sensorTimeEpochMs: Long,
        phoneTimeEpochMs: Long,
        staleAfterMs: Long,
    ): Long {
        require(sensorTimeEpochMs > 0L)
        require(phoneTimeEpochMs > 0L)
        require(staleAfterMs > 0L)
        val baseline = minOf(sensorTimeEpochMs, phoneTimeEpochMs)
        return if (Long.MAX_VALUE - baseline < staleAfterMs) {
            Long.MAX_VALUE
        } else {
            baseline + staleAfterMs
        }
    }

    fun elapsedDeadlineMs(
        existingDeadlineMs: Long,
        nowElapsedMs: Long,
        remainingFreshMs: Long,
        preserveExistingDeadline: Boolean,
    ): Long {
        require(existingDeadlineMs >= 0L)
        require(nowElapsedMs >= 0L)
        require(remainingFreshMs > 0L)
        val calculated = if (Long.MAX_VALUE - nowElapsedMs < remainingFreshMs) {
            Long.MAX_VALUE
        } else {
            nowElapsedMs + remainingFreshMs
        }
        return if (preserveExistingDeadline && existingDeadlineMs > 0L) {
            minOf(existingDeadlineMs, calculated)
        } else {
            calculated
        }
    }
}
