package com.sladkaya.app

import android.app.NotificationManager
import org.junit.Assert.assertEquals
import org.junit.Test

class AlarmNotificationCapabilityPolicyTest {
    @Test
    fun deniedAndroidThirteenPermissionIsExplicitlyBlocked() {
        assertEquals(
            AlarmNotificationCapability.BLOCKED_PERMISSION,
            AlarmNotificationCapabilityPolicy.evaluate(
                sdkInt = 33,
                runtimePermissionGranted = false,
                appNotificationsEnabled = true,
                channelExists = true,
                channelImportance = NotificationManager.IMPORTANCE_HIGH,
                channelHasSound = true,
            ),
        )
    }

    @Test
    fun disabledApplicationOrAlarmChannelCannotClaimAvailability() {
        assertEquals(
            AlarmNotificationCapability.BLOCKED_APPLICATION,
            AlarmNotificationCapabilityPolicy.evaluate(
                33,
                true,
                false,
                true,
                NotificationManager.IMPORTANCE_HIGH,
                true,
            ),
        )
        assertEquals(
            AlarmNotificationCapability.BLOCKED_CHANNEL,
            AlarmNotificationCapabilityPolicy.evaluate(
                33,
                true,
                true,
                true,
                NotificationManager.IMPORTANCE_NONE,
                true,
            ),
        )
    }

    @Test
    fun preAndroidThirteenDoesNotRequireNotificationRuntimePermission() {
        assertEquals(
            AlarmNotificationCapability.AVAILABLE,
            AlarmNotificationCapabilityPolicy.evaluate(
                32,
                false,
                true,
                true,
                NotificationManager.IMPORTANCE_HIGH,
                true,
            ),
        )
    }

    @Test
    fun channelCreationWindowIsNotReportedAsAvailable() {
        assertEquals(
            AlarmNotificationCapability.INITIALIZING,
            AlarmNotificationCapabilityPolicy.evaluate(
                33,
                true,
                true,
                false,
                NotificationManager.IMPORTANCE_NONE,
                false,
            ),
        )
    }

    @Test
    fun lowImportanceOrMissingSoundCannotClaimAlarmCapability() {
        assertEquals(
            AlarmNotificationCapability.BLOCKED_CHANNEL,
            AlarmNotificationCapabilityPolicy.evaluate(
                37,
                true,
                true,
                true,
                NotificationManager.IMPORTANCE_DEFAULT,
                true,
            ),
        )
        assertEquals(
            AlarmNotificationCapability.BLOCKED_CHANNEL,
            AlarmNotificationCapabilityPolicy.evaluate(
                37,
                true,
                true,
                true,
                NotificationManager.IMPORTANCE_HIGH,
                false,
            ),
        )
    }
}
