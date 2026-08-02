package com.sladkaya.app.service

import android.app.NotificationManager
import android.media.AudioAttributes
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AlarmReadinessPolicyTest {
    @Test
    fun fullyAudibleAlarmPathIsReady() {
        val readiness = AlarmReadinessPolicy.evaluate(readyInputs())

        assertTrue(readiness.ready)
        assertTrue(readiness.blockers.isEmpty())
    }

    @Test
    fun everyRequiredAndroidCapabilityFailsClosed() {
        val cases = linkedMapOf(
            AlarmReadinessBlocker.NOTIFICATION_PERMISSION to readyInputs(
                runtimePermissionGranted = false,
            ),
            AlarmReadinessBlocker.APPLICATION_NOTIFICATIONS to readyInputs(
                appNotificationsEnabled = false,
            ),
            AlarmReadinessBlocker.ALARM_CHANNEL_MISSING to readyInputs(
                channelExists = false,
            ),
            AlarmReadinessBlocker.ALARM_CHANNEL_IMPORTANCE to readyInputs(
                channelImportance = NotificationManager.IMPORTANCE_DEFAULT,
            ),
            AlarmReadinessBlocker.ALARM_CHANNEL_SOUND to readyInputs(
                channelHasSound = false,
            ),
            AlarmReadinessBlocker.ALARM_AUDIO_USAGE to readyInputs(
                channelAudioUsage = AudioAttributes.USAGE_NOTIFICATION,
            ),
            AlarmReadinessBlocker.ALARM_CHANNEL_VIBRATION to readyInputs(
                channelVibrates = false,
            ),
            AlarmReadinessBlocker.ALARM_VOLUME to readyInputs(alarmStreamVolume = 0),
            AlarmReadinessBlocker.DO_NOT_DISTURB to readyInputs(
                interruptionFilter = NotificationManager.INTERRUPTION_FILTER_NONE,
            ),
            AlarmReadinessBlocker.EXACT_ALARM_ACCESS to readyInputs(
                exactAlarmAccess = false,
            ),
            AlarmReadinessBlocker.BATTERY_OPTIMIZATION to readyInputs(
                batteryOptimizationExempt = false,
            ),
        )

        cases.forEach { (expected, inputs) ->
            val result = AlarmReadinessPolicy.evaluate(inputs)
            assertFalse(expected.name, result.ready)
            assertTrue(expected.name, expected in result.blockers)
        }
    }

    @Test
    fun dndAllowsAlarmFilterOrExplicitChannelBypassOnly() {
        assertTrue(
            AlarmReadinessPolicy.evaluate(
                readyInputs(interruptionFilter = NotificationManager.INTERRUPTION_FILTER_ALARMS),
            ).ready,
        )
        assertTrue(
            AlarmReadinessPolicy.evaluate(
                readyInputs(
                    interruptionFilter = NotificationManager.INTERRUPTION_FILTER_PRIORITY,
                    channelCanBypassDnd = true,
                ),
            ).ready,
        )
        assertTrue(
            AlarmReadinessPolicy.evaluate(
                readyInputs(
                    interruptionFilter = NotificationManager.INTERRUPTION_FILTER_PRIORITY,
                    priorityAllowsAlarms = true,
                ),
            ).ready,
        )
        assertEquals(
            setOf(AlarmReadinessBlocker.DO_NOT_DISTURB),
            AlarmReadinessPolicy.evaluate(
                readyInputs(
                    interruptionFilter = NotificationManager.INTERRUPTION_FILTER_PRIORITY,
                    channelCanBypassDnd = false,
                    priorityAllowsAlarms = false,
                ),
            ).blockers,
        )
        assertEquals(
            setOf(AlarmReadinessBlocker.DO_NOT_DISTURB),
            AlarmReadinessPolicy.evaluate(
                readyInputs(
                    interruptionFilter = NotificationManager.INTERRUPTION_FILTER_PRIORITY,
                    channelCanBypassDnd = false,
                    priorityAllowsAlarms = null,
                ),
            ).blockers,
        )
    }

    @Test
    fun preAndroidThirteenDoesNotRequireRuntimeNotificationPermission() {
        assertTrue(
            AlarmReadinessPolicy.evaluate(
                readyInputs(sdkInt = 32, runtimePermissionGranted = false),
            ).ready,
        )
    }

    private fun readyInputs(
        sdkInt: Int = 37,
        runtimePermissionGranted: Boolean = true,
        appNotificationsEnabled: Boolean = true,
        channelExists: Boolean = true,
        channelImportance: Int = NotificationManager.IMPORTANCE_HIGH,
        channelHasSound: Boolean = true,
        channelAudioUsage: Int = AudioAttributes.USAGE_ALARM,
        channelVibrates: Boolean = true,
        alarmStreamVolume: Int = 5,
        interruptionFilter: Int = NotificationManager.INTERRUPTION_FILTER_ALL,
        channelCanBypassDnd: Boolean = false,
        priorityAllowsAlarms: Boolean? = false,
        exactAlarmAccess: Boolean = true,
        batteryOptimizationExempt: Boolean = true,
    ) = AlarmReadinessInputs(
        sdkInt = sdkInt,
        runtimePermissionGranted = runtimePermissionGranted,
        appNotificationsEnabled = appNotificationsEnabled,
        channelExists = channelExists,
        channelImportance = channelImportance,
        channelHasSound = channelHasSound,
        channelAudioUsage = channelAudioUsage,
        channelVibrates = channelVibrates,
        alarmStreamVolume = alarmStreamVolume,
        interruptionFilter = interruptionFilter,
        channelCanBypassDnd = channelCanBypassDnd,
        priorityAllowsAlarms = priorityAllowsAlarms,
        exactAlarmAccess = exactAlarmAccess,
        batteryOptimizationExempt = batteryOptimizationExempt,
    )
}
