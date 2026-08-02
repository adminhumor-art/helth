package com.sladkaya.app.widget

import android.app.AlarmManager
import android.content.Context
import android.os.Build

internal enum class WidgetExactAlarmAccess {
    AVAILABLE,
    USER_ACTION_REQUIRED,
}

internal object WidgetExactAlarmAccessPolicy {
    fun evaluate(sdkInt: Int, canScheduleExactAlarms: Boolean): WidgetExactAlarmAccess =
        if (sdkInt < Build.VERSION_CODES.S || canScheduleExactAlarms) {
            WidgetExactAlarmAccess.AVAILABLE
        } else {
            WidgetExactAlarmAccess.USER_ACTION_REQUIRED
        }
}

internal fun readWidgetExactAlarmAccess(context: Context): WidgetExactAlarmAccess {
    val canSchedule = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        context.getSystemService(AlarmManager::class.java).canScheduleExactAlarms()
    } else {
        true
    }
    return WidgetExactAlarmAccessPolicy.evaluate(Build.VERSION.SDK_INT, canSchedule)
}
