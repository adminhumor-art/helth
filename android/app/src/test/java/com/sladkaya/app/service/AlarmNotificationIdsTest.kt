package com.sladkaya.app.service

import com.sladkaya.core.model.AlarmKind
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AlarmNotificationIdsTest {
    @Test
    fun testAlarmIsShortLivedAndNeverUsesARealAlarmId() {
        val realIds = AlarmKind.values().map(AlarmNotificationIds::forAlarm).toSet()

        assertFalse(AlarmNotificationIds.TEST in realIds)
        assertTrue(AlarmNotificationIds.TEST_TIMEOUT_MS in 5_000L..30_000L)
    }
}
