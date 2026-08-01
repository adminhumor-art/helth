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
import com.sladkaya.app.MainActivity
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

    fun show(kind: AlarmKind, reading: GlucoseReading?, demo: Boolean = false) {
        if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return
        val value = reading?.let { String.format(Locale.forLanguageTag("ru"), "%.1f ммоль/л", it.glucoseMmolL) }
        val title = when (kind) {
            AlarmKind.LOW -> "Низкая глюкоза"
            AlarmKind.HIGH -> "Высокая глюкоза"
            AlarmKind.RAPID_FALL -> "Глюкоза быстро снижается"
            AlarmKind.RAPID_RISE -> "Глюкоза быстро повышается"
            AlarmKind.SIGNAL_LOSS -> "Нет свежих данных"
        }
        val shownTitle = if (demo) "ДЕМО · $title" else title
        val message = if (demo) {
            value?.let { "$shownTitle · $it · тестовые данные" }
                ?: "$shownTitle · тестовые данные"
        } else {
            value?.let { "$shownTitle · $it" } ?: "$shownTitle · проверьте датчик и телефон"
        }
        val notification = NotificationCompat.Builder(context, ALARM_CHANNEL)
            .setSmallIcon(android.R.drawable.ic_dialog_alert)
            .setContentTitle(shownTitle)
            .setContentText(message)
            .setStyle(NotificationCompat.BigTextStyle().bigText(message))
            .setContentIntent(openAppIntent())
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setOngoing(true)
            .build()
        NotificationManagerCompat.from(context).notify(kind.notificationId, notification)
    }

    fun cancel(kind: AlarmKind) {
        NotificationManagerCompat.from(context).cancel(kind.notificationId)
    }

    fun cancelAllAlarms() {
        AlarmKind.values().forEach(::cancel)
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
        private val AlarmKind.notificationId: Int get() = 4_000 + ordinal
    }
}
