package com.sladkaya.app.service

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat

internal enum class DirectBootRecoveryDecision {
    ShowUnlockRequiredWarning,
    ContinueNormalRecovery,
    Ignore,
}

internal data class DirectBootRecoveryWarningPresentation(
    val title: String,
    val message: String,
    val ongoing: Boolean,
    val opensCredentialProtectedUi: Boolean,
)

/**
 * Locked boot may use only device-protected/system state. It must never imply that the sensor
 * service, encrypted configuration, or alarm journal has already been restored.
 */
internal object DirectBootRecoveryPolicy {
    private const val LOCKED_BOOT_COMPLETED = "android.intent.action.LOCKED_BOOT_COMPLETED"
    private const val BOOT_COMPLETED = "android.intent.action.BOOT_COMPLETED"

    fun decide(action: String?): DirectBootRecoveryDecision = when (action) {
        LOCKED_BOOT_COMPLETED -> DirectBootRecoveryDecision.ShowUnlockRequiredWarning
        BOOT_COMPLETED -> DirectBootRecoveryDecision.ContinueNormalRecovery
        else -> DirectBootRecoveryDecision.Ignore
    }

    fun warningPresentation() = DirectBootRecoveryWarningPresentation(
        title = "Контроль не восстановлен",
        message = "Разблокируйте телефон — контроль не восстановлен",
        ongoing = true,
        opensCredentialProtectedUi = false,
    )

    inline fun initializeCredentialProtectedRuntimeIfAllowed(
        userUnlocked: Boolean,
        initialize: () -> Unit,
    ): Boolean {
        if (!userUnlocked) return false
        initialize()
        return true
    }
}

/** Runs in an isolated direct-boot-aware process and deliberately does not open app storage. */
class DirectBootRecoveryReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (
            DirectBootRecoveryPolicy.decide(intent.action) !=
            DirectBootRecoveryDecision.ShowUnlockRequiredWarning
        ) {
            return
        }
        DirectBootRecoveryNotification.show(context)
    }
}

internal object DirectBootRecoveryNotification {
    fun show(context: Context): Boolean {
        if (
            Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            return false
        }
        return runCatching {
            val manager = context.getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID,
                    "Восстановление контроля",
                    NotificationManager.IMPORTANCE_HIGH,
                ).apply {
                    description = "Требуется разблокировка после перезагрузки телефона"
                    enableVibration(true)
                    lockscreenVisibility = Notification.VISIBILITY_PUBLIC
                },
            )
            val presentation = DirectBootRecoveryPolicy.warningPresentation()
            val notification = NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.ic_dialog_alert)
                .setContentTitle(presentation.title)
                .setContentText(presentation.message)
                .setStyle(NotificationCompat.BigTextStyle().bigText(presentation.message))
                .setPriority(NotificationCompat.PRIORITY_MAX)
                .setCategory(NotificationCompat.CATEGORY_ERROR)
                .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
                .setOngoing(presentation.ongoing)
                .setAutoCancel(false)
                .setOnlyAlertOnce(true)
                .build()
            manager.notify(NOTIFICATION_ID, notification)
        }.isSuccess
    }

    fun cancel(context: Context): Boolean = runCatching {
        context.getSystemService(NotificationManager::class.java).cancel(NOTIFICATION_ID)
    }.isSuccess

    private const val CHANNEL_ID = "direct_boot_recovery_v1"
    private const val NOTIFICATION_ID = 4_800
}
