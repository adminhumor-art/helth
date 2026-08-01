package com.sladkaya.app.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews
import androidx.core.content.edit
import com.sladkaya.app.MainActivity
import com.sladkaya.app.R
import com.sladkaya.core.model.GlucoseReading
import com.sladkaya.core.model.SensorFamily
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class GlucoseWidgetProvider : AppWidgetProvider() {
    override fun onUpdate(context: Context, manager: AppWidgetManager, appWidgetIds: IntArray) {
        appWidgetIds.forEach { manager.updateAppWidget(it, views(context)) }
    }

    companion object {
        private const val PREFS = "widget_glucose"

        fun updateAll(context: Context, reading: GlucoseReading) {
            require(WidgetReadingPersistencePolicy.canPersist(reading))
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit {
                putInt("mgdl", reading.glucoseMgDl)
                putFloat("trend", reading.trendMgDlPerMinute.toFloat())
                putLong("time", reading.sensorTimeEpochMs)
                remove("demo")
            }
            updateComponents(context, views(context))
        }

        fun updateDemoAll(context: Context, reading: GlucoseReading) {
            require(!WidgetReadingPersistencePolicy.canPersist(reading))
            updateComponents(context, views(context, transientReading = reading, transientDemo = true))
        }

        fun showSetupRequired(context: Context) {
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit { clear() }
            updateComponents(context, views(context, context.getString(R.string.widget_setup_required)))
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
        ): RemoteViews {
            val preferences = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            val legacyDemo = preferences.getBoolean("demo", false)
            if (legacyDemo) preferences.edit { clear() }
            val mgDl = transientReading?.glucoseMgDl
                ?: if (legacyDemo) 0 else preferences.getInt("mgdl", 0)
            val trend = transientReading?.trendMgDlPerMinute?.toFloat()
                ?: if (legacyDemo) 0f else preferences.getFloat("trend", 0f)
            val time = transientReading?.sensorTimeEpochMs
                ?: if (legacyDemo) 0L else preferences.getLong("time", 0L)
            return RemoteViews(context.packageName, R.layout.glucose_widget).apply {
                setTextViewText(R.id.widget_value, if (mgDl == 0) context.getString(R.string.widget_empty_value) else String.format(Locale.forLanguageTag("ru"), "%.1f", mgDl / 18.0))
                setTextViewText(R.id.widget_trend, trendArrow(trend))
                setTextViewText(
                    R.id.widget_time,
                    if (time == 0L) {
                        emptyMessage
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

internal object WidgetReadingPersistencePolicy {
    fun canPersist(reading: GlucoseReading): Boolean =
        reading.sensorFamily != SensorFamily.SIMULATOR
}
