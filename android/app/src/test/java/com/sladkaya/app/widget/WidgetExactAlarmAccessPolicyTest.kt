package com.sladkaya.app.widget

import org.junit.Assert.assertEquals
import org.junit.Test

class WidgetExactAlarmAccessPolicyTest {
    @Test
    fun oldAndroidDoesNotRequireSpecialAccess() {
        assertEquals(
            WidgetExactAlarmAccess.AVAILABLE,
            WidgetExactAlarmAccessPolicy.evaluate(
                sdkInt = 30,
                canScheduleExactAlarms = false,
            ),
        )
    }

    @Test
    fun modernAndroidExplainsMissingSpecialAccess() {
        assertEquals(
            WidgetExactAlarmAccess.USER_ACTION_REQUIRED,
            WidgetExactAlarmAccessPolicy.evaluate(
                sdkInt = 31,
                canScheduleExactAlarms = false,
            ),
        )
        assertEquals(
            WidgetExactAlarmAccess.USER_ACTION_REQUIRED,
            WidgetExactAlarmAccessPolicy.evaluate(
                sdkInt = 37,
                canScheduleExactAlarms = false,
            ),
        )
    }

    @Test
    fun grantedSpecialAccessIsAvailable() {
        assertEquals(
            WidgetExactAlarmAccess.AVAILABLE,
            WidgetExactAlarmAccessPolicy.evaluate(
                sdkInt = 37,
                canScheduleExactAlarms = true,
            ),
        )
    }
}
