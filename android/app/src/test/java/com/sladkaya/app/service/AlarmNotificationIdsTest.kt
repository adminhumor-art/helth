package com.sladkaya.app.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AlarmNotificationIdsTest {
    @Test
    fun testAlarmIsShortLivedAndNeverUsesARealAlarmId() {
        assertFalse(AlarmNotificationIds.TEST == AlarmNotificationIds.ACTIVE_EPISODE)
        assertTrue(AlarmNotificationIds.TEST_TIMEOUT_MS in 5_000L..30_000L)
    }

    @Test
    fun notificationIdsAreExplicitAndCannotMoveWithEnumOrdinal() {
        assertEquals(4_100, AlarmNotificationIds.ACTIVE_EPISODE)
        assertEquals(4_101, AlarmNotificationIds.ACKNOWLEDGE_REQUEST)
        assertEquals(4_102, AlarmNotificationIds.REPEAT_REQUEST)
        assertEquals(4_103, AlarmNotificationIds.REVOCATION_WATCHDOG_REQUEST)
        assertEquals(4_104, AlarmNotificationIds.SIGNAL_LOSS_PRIMARY_SLOT_0_REQUEST)
        assertEquals(4_105, AlarmNotificationIds.SIGNAL_LOSS_WATCHDOG_SLOT_0_REQUEST)
        assertEquals(4_106, AlarmNotificationIds.SIGNAL_LOSS_PRIMARY_SLOT_1_REQUEST)
        assertEquals(4_107, AlarmNotificationIds.SIGNAL_LOSS_WATCHDOG_SLOT_1_REQUEST)
        assertEquals(
            8,
            setOf(
                AlarmNotificationIds.ACTIVE_EPISODE,
                AlarmNotificationIds.ACKNOWLEDGE_REQUEST,
                AlarmNotificationIds.REPEAT_REQUEST,
                AlarmNotificationIds.REVOCATION_WATCHDOG_REQUEST,
                AlarmNotificationIds.SIGNAL_LOSS_PRIMARY_SLOT_0_REQUEST,
                AlarmNotificationIds.SIGNAL_LOSS_WATCHDOG_SLOT_0_REQUEST,
                AlarmNotificationIds.SIGNAL_LOSS_PRIMARY_SLOT_1_REQUEST,
                AlarmNotificationIds.SIGNAL_LOSS_WATCHDOG_SLOT_1_REQUEST,
            ).size,
        )
    }
}
