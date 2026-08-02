package com.sladkaya.app.service

import android.Manifest
import android.app.AlarmManager
import android.app.NotificationManager
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.AudioManager
import android.os.Build
import android.os.PowerManager
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat

internal enum class AlarmReadinessBlocker {
    NOTIFICATION_PERMISSION,
    APPLICATION_NOTIFICATIONS,
    ALARM_CHANNEL_MISSING,
    ALARM_CHANNEL_IMPORTANCE,
    ALARM_CHANNEL_SOUND,
    ALARM_AUDIO_USAGE,
    ALARM_CHANNEL_VIBRATION,
    ALARM_VOLUME,
    DO_NOT_DISTURB,
    EXACT_ALARM_ACCESS,
    BATTERY_OPTIMIZATION,
}

internal data class AlarmReadinessInputs(
    val sdkInt: Int,
    val runtimePermissionGranted: Boolean,
    val appNotificationsEnabled: Boolean,
    val channelExists: Boolean,
    val channelImportance: Int,
    val channelHasSound: Boolean,
    val channelAudioUsage: Int?,
    val channelVibrates: Boolean,
    val alarmStreamVolume: Int,
    val interruptionFilter: Int,
    val channelCanBypassDnd: Boolean,
    val priorityAllowsAlarms: Boolean?,
    val exactAlarmAccess: Boolean,
    val batteryOptimizationExempt: Boolean,
)

internal data class AlarmReadiness(
    val blockers: Set<AlarmReadinessBlocker>,
) {
    val ready: Boolean get() = blockers.isEmpty()
}

internal object AlarmReadinessPolicy {
    fun evaluate(inputs: AlarmReadinessInputs): AlarmReadiness {
        val blockers = linkedSetOf<AlarmReadinessBlocker>()
        if (inputs.sdkInt >= 33 && !inputs.runtimePermissionGranted) {
            blockers += AlarmReadinessBlocker.NOTIFICATION_PERMISSION
        }
        if (!inputs.appNotificationsEnabled) {
            blockers += AlarmReadinessBlocker.APPLICATION_NOTIFICATIONS
        }
        if (!inputs.channelExists) {
            blockers += AlarmReadinessBlocker.ALARM_CHANNEL_MISSING
        } else {
            if (inputs.channelImportance < NotificationManager.IMPORTANCE_HIGH) {
                blockers += AlarmReadinessBlocker.ALARM_CHANNEL_IMPORTANCE
            }
            if (!inputs.channelHasSound) {
                blockers += AlarmReadinessBlocker.ALARM_CHANNEL_SOUND
            }
            if (inputs.channelAudioUsage != AudioAttributes.USAGE_ALARM) {
                blockers += AlarmReadinessBlocker.ALARM_AUDIO_USAGE
            }
            if (!inputs.channelVibrates) {
                blockers += AlarmReadinessBlocker.ALARM_CHANNEL_VIBRATION
            }
        }
        if (inputs.alarmStreamVolume <= 0) {
            blockers += AlarmReadinessBlocker.ALARM_VOLUME
        }
        val dndAllowsAlarm = when (inputs.interruptionFilter) {
            NotificationManager.INTERRUPTION_FILTER_ALL,
            NotificationManager.INTERRUPTION_FILTER_ALARMS,
            -> true
            NotificationManager.INTERRUPTION_FILTER_PRIORITY ->
                inputs.channelCanBypassDnd || inputs.priorityAllowsAlarms == true
            else -> false
        }
        if (!dndAllowsAlarm) blockers += AlarmReadinessBlocker.DO_NOT_DISTURB
        if (inputs.sdkInt >= 31 && !inputs.exactAlarmAccess) {
            blockers += AlarmReadinessBlocker.EXACT_ALARM_ACCESS
        }
        if (inputs.sdkInt >= 23 && !inputs.batteryOptimizationExempt) {
            blockers += AlarmReadinessBlocker.BATTERY_OPTIMIZATION
        }
        return AlarmReadiness(blockers)
    }
}

internal fun readAlarmReadiness(context: Context): AlarmReadiness = runCatching {
    val notificationManager = context.getSystemService(NotificationManager::class.java)
    val audioManager = context.getSystemService(AudioManager::class.java)
    val alarmManager = context.getSystemService(AlarmManager::class.java)
    val powerManager = context.getSystemService(PowerManager::class.java)
    val channel = notificationManager.getNotificationChannel(AlarmNotifier.ALARM_CHANNEL)
    AlarmReadinessPolicy.evaluate(
        AlarmReadinessInputs(
            sdkInt = Build.VERSION.SDK_INT,
            runtimePermissionGranted = Build.VERSION.SDK_INT < 33 ||
                ContextCompat.checkSelfPermission(
                    context,
                    Manifest.permission.POST_NOTIFICATIONS,
                ) == PackageManager.PERMISSION_GRANTED,
            appNotificationsEnabled = NotificationManagerCompat.from(context)
                .areNotificationsEnabled(),
            channelExists = channel != null,
            channelImportance = channel?.importance ?: NotificationManager.IMPORTANCE_NONE,
            channelHasSound = channel?.sound != null,
            channelAudioUsage = channel?.audioAttributes?.usage,
            channelVibrates = channel?.shouldVibrate() == true,
            alarmStreamVolume = audioManager.getStreamVolume(AudioManager.STREAM_ALARM),
            interruptionFilter = notificationManager.currentInterruptionFilter,
            channelCanBypassDnd = channel?.canBypassDnd() == true,
            priorityAllowsAlarms = runCatching {
                notificationManager.notificationPolicy.priorityCategories and
                    NotificationManager.Policy.PRIORITY_CATEGORY_ALARMS != 0
            }.getOrNull(),
            exactAlarmAccess = Build.VERSION.SDK_INT < 31 ||
                alarmManager.canScheduleExactAlarms(),
            batteryOptimizationExempt = Build.VERSION.SDK_INT < 23 ||
                powerManager.isIgnoringBatteryOptimizations(context.packageName),
        ),
    )
}.getOrElse {
    AlarmReadiness(AlarmReadinessBlocker.entries.toSet())
}

internal fun AlarmReadiness.userMessage(): String {
    if (ready) return "Локальная тревога готова"
    val first = blockers.first()
    return when (first) {
        AlarmReadinessBlocker.NOTIFICATION_PERMISSION ->
            "Разрешите уведомления Android перед запуском тревог"
        AlarmReadinessBlocker.APPLICATION_NOTIFICATIONS ->
            "Уведомления приложения выключены в Android"
        AlarmReadinessBlocker.ALARM_CHANNEL_MISSING ->
            "Канал тревог Android не создан"
        AlarmReadinessBlocker.ALARM_CHANNEL_IMPORTANCE ->
            "Канал тревог должен иметь высокий приоритет"
        AlarmReadinessBlocker.ALARM_CHANNEL_SOUND ->
            "Для канала тревог не выбран звук"
        AlarmReadinessBlocker.ALARM_AUDIO_USAGE ->
            "Канал тревог не использует громкость будильника"
        AlarmReadinessBlocker.ALARM_CHANNEL_VIBRATION ->
            "Для канала тревог выключена вибрация"
        AlarmReadinessBlocker.ALARM_VOLUME ->
            "Громкость будильника равна нулю"
        AlarmReadinessBlocker.DO_NOT_DISTURB ->
            "Режим «Не беспокоить» блокирует локальную тревогу"
        AlarmReadinessBlocker.EXACT_ALARM_ACCESS ->
            "Разрешите приложению точные будильники в настройках Android"
        AlarmReadinessBlocker.BATTERY_OPTIMIZATION ->
            "Отключите оптимизацию батареи Android для приложения"
    }
}
