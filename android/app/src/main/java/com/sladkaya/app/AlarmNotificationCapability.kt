package com.sladkaya.app

import android.Manifest
import android.app.NotificationManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.sladkaya.app.service.AlarmNotifier

internal enum class AlarmNotificationCapability {
    AVAILABLE,
    INITIALIZING,
    BLOCKED_PERMISSION,
    BLOCKED_APPLICATION,
    BLOCKED_CHANNEL,
}

internal object AlarmSafetyUxCopy {
    const val NOTIFICATIONS_BLOCKED_DETAIL =
        "Локальная звуковая тревога недоступна. Получение данных может продолжаться, но на телефон нельзя полагаться как на будильник."
    const val EXACT_ALARM_BLOCKED_DETAIL =
        "Без точных таймеров повтор тревоги может задержаться, а виджет скроет значение. Получение данных продолжается."
}

internal object AlarmNotificationCapabilityPolicy {
    fun evaluate(
        sdkInt: Int,
        runtimePermissionGranted: Boolean,
        appNotificationsEnabled: Boolean,
        channelExists: Boolean,
        channelImportance: Int,
        channelHasSound: Boolean,
    ): AlarmNotificationCapability = when {
        sdkInt >= 33 && !runtimePermissionGranted ->
            AlarmNotificationCapability.BLOCKED_PERMISSION
        !appNotificationsEnabled -> AlarmNotificationCapability.BLOCKED_APPLICATION
        !channelExists -> AlarmNotificationCapability.INITIALIZING
        channelImportance < NotificationManager.IMPORTANCE_HIGH || !channelHasSound ->
            AlarmNotificationCapability.BLOCKED_CHANNEL
        else -> AlarmNotificationCapability.AVAILABLE
    }
}

internal fun readAlarmNotificationCapability(context: Context): AlarmNotificationCapability {
    val permissionGranted = Build.VERSION.SDK_INT < 33 ||
        ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.POST_NOTIFICATIONS,
        ) == PackageManager.PERMISSION_GRANTED
    val manager = context.getSystemService(NotificationManager::class.java)
    val channel = manager.getNotificationChannel(AlarmNotifier.ALARM_CHANNEL)
    return AlarmNotificationCapabilityPolicy.evaluate(
        sdkInt = Build.VERSION.SDK_INT,
        runtimePermissionGranted = permissionGranted,
        appNotificationsEnabled = NotificationManagerCompat.from(context).areNotificationsEnabled(),
        channelExists = channel != null,
        channelImportance = channel?.importance ?: NotificationManager.IMPORTANCE_NONE,
        channelHasSound = channel?.sound != null,
    )
}
