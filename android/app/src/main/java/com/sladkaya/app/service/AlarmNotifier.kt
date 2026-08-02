package com.sladkaya.app.service

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.RingtoneManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.sladkaya.app.AlarmNotificationCapability
import com.sladkaya.app.MainActivity
import com.sladkaya.app.readAlarmNotificationCapability
import com.sladkaya.core.model.AlarmKind
import com.sladkaya.core.model.GlucoseReading
import java.util.Locale

class AlarmNotifier(private val context: Context) {
    private val manager = context.getSystemService(NotificationManager::class.java)

    fun createChannels() {
        val alarmSound = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
        val audio = AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ALARM).build()
        val alarms = NotificationChannel(ALARM_CHANNEL, "Критические значения", NotificationManager.IMPORTANCE_HIGH).apply {
            description = "Громкие локальные тревоги глюкозы"
            enableVibration(true)
            vibrationPattern = longArrayOf(0, 700, 300, 700, 300, 1_200)
            setSound(alarmSound, audio)
            lockscreenVisibility = Notification.VISIBILITY_PUBLIC
        }
        val status = NotificationChannel(STATUS_CHANNEL, "Подключение к датчику", NotificationManager.IMPORTANCE_LOW).apply {
            description = "Постоянное состояние получения данных"
            setSound(null, null)
        }
        manager.createNotificationChannels(listOf(alarms, status))
    }

    fun foreground(reading: GlucoseReading?): Notification {
        val text = reading?.let { String.format(Locale.forLanguageTag("ru"), "ДЕМО · %.1f ммоль/л · тестовые данные", it.glucoseMmolL) }
            ?: "Демо: ожидание тестовых данных"
        return foregroundStatus(text)
    }

    fun foregroundStatus(text: String): Notification {
        return NotificationCompat.Builder(context, STATUS_CHANNEL)
            .setSmallIcon(android.R.drawable.ic_popup_sync)
            .setContentTitle("Сладкая работает")
            .setContentText(text)
            .setContentIntent(openAppIntent())
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .build()
    }

    internal fun episodeNotification(episode: AlarmEpisode, alert: Boolean): Notification {
        val primaryKind = episode.activeKinds.firstBySafetyPriority()
        val baseTitle = when (primaryKind) {
            AlarmKind.LOW -> "Низкая глюкоза"
            AlarmKind.HIGH -> "Высокая глюкоза"
            AlarmKind.RAPID_FALL -> "Глюкоза быстро снижается"
            AlarmKind.RAPID_RISE -> "Глюкоза быстро повышается"
            AlarmKind.SIGNAL_LOSS -> "Нет свежих данных"
        }
        val sourceTitle = if (episode.demo) "ДЕМО · $baseTitle" else baseTitle
        val title = if (episode.acknowledged) "Подтверждено · $sourceTitle" else sourceTitle
        val value = episode.reading
            ?.takeUnless { AlarmKind.SIGNAL_LOSS in episode.activeKinds }
            ?.let {
                String.format(
                    Locale.forLanguageTag("ru"),
                    "%.1f ммоль/л",
                    it.glucoseMgDl / 18.0,
                )
            }
        val message = buildString {
            append(title)
            value?.let { append(" · ").append(it) }
            if (episode.demo) append(" · тестовые данные")
            if (episode.acknowledged) {
                append(" · звук остановлен, тревога остаётся активной")
            }
        }
        return NotificationCompat.Builder(context, ALARM_CHANNEL)
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
                if (!episode.acknowledged) {
                    addAction(
                        android.R.drawable.ic_menu_close_clear_cancel,
                        "Подтвердить",
                        AlarmAcknowledgeReceiver.pendingIntent(context, episode.id),
                    )
                }
            }
            .build()
    }

    internal fun showEpisode(episode: AlarmEpisode, alert: Boolean): Boolean {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            return false
        }
        return runCatching {
            NotificationManagerCompat.from(context).notify(
                AlarmNotificationIds.ACTIVE_EPISODE,
                episodeNotification(episode, alert),
            )
        }.isSuccess
    }

    fun cancelAllAlarms(): Boolean = runCatching {
        NotificationManagerCompat.from(context).cancel(AlarmNotificationIds.ACTIVE_EPISODE)
    }.isSuccess

    fun showTest(): Boolean {
        createChannels()
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            return false
        }
        if (readAlarmNotificationCapability(context) != AlarmNotificationCapability.AVAILABLE) {
            return false
        }
        val notification = NotificationCompat.Builder(context, ALARM_CHANNEL)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle("Тест звука «Сладкой»")
            .setContentText("Это проверка телефона, не тревога глюкозы")
            .setStyle(
                NotificationCompat.BigTextStyle()
                    .bigText("Это проверка телефона, не тревога глюкозы. Уведомление исчезнет автоматически."),
            )
            .setContentIntent(openAppIntent())
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setOngoing(false)
            .setAutoCancel(true)
            .setTimeoutAfter(AlarmNotificationIds.TEST_TIMEOUT_MS)
            .build()
        return runCatching {
            NotificationManagerCompat.from(context)
                .notify(AlarmNotificationIds.TEST, notification)
        }.isSuccess
    }

    fun cancelTest() {
        NotificationManagerCompat.from(context).cancel(AlarmNotificationIds.TEST)
    }

    private fun openAppIntent(): PendingIntent = PendingIntent.getActivity(
        context,
        0,
        Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    companion object {
        const val ALARM_CHANNEL = "glucose_alarms_v1"
        const val STATUS_CHANNEL = "sensor_status_v1"
    }
}

private fun Set<AlarmKind>.firstBySafetyPriority(): AlarmKind = when {
    AlarmKind.LOW in this -> AlarmKind.LOW
    AlarmKind.HIGH in this -> AlarmKind.HIGH
    AlarmKind.RAPID_FALL in this -> AlarmKind.RAPID_FALL
    AlarmKind.RAPID_RISE in this -> AlarmKind.RAPID_RISE
    else -> AlarmKind.SIGNAL_LOSS
}

internal object AlarmNotificationIds {
    const val ACTIVE_EPISODE = 4_100
    const val ACKNOWLEDGE_REQUEST = 4_101
    const val REPEAT_REQUEST = 4_102
    const val REVOCATION_WATCHDOG_REQUEST = 4_103
    const val SIGNAL_LOSS_PRIMARY_SLOT_0_REQUEST = 4_104
    const val SIGNAL_LOSS_WATCHDOG_SLOT_0_REQUEST = 4_105
    const val SIGNAL_LOSS_PRIMARY_SLOT_1_REQUEST = 4_106
    const val SIGNAL_LOSS_WATCHDOG_SLOT_1_REQUEST = 4_107
    const val TEST = 4_900
    const val TEST_TIMEOUT_MS = 10_000L

}
