package com.sladkaya.app.settings

import com.sladkaya.core.model.AlarmThresholds
import org.junit.Assert.assertEquals
import org.junit.Test

class AlarmSettingsEditorTest {
    @Test
    fun lowAndHighAdjustmentsUseFiveMgDlSteps() {
        val defaults = AlarmThresholds()

        assertEquals(65, AlarmSettingsEditor.adjustLow(defaults, -1).lowMgDl)
        assertEquals(75, AlarmSettingsEditor.adjustLow(defaults, 1).lowMgDl)
        assertEquals(245, AlarmSettingsEditor.adjustHigh(defaults, -1).highMgDl)
        assertEquals(255, AlarmSettingsEditor.adjustHigh(defaults, 1).highMgDl)
    }

    @Test
    fun adjustmentNeverCreatesAnInvalidOrOverlappingPolicy() {
        val narrow = AlarmThresholds(
            lowMgDl = 70,
            highMgDl = 76,
            recoveryHysteresisMgDl = 5,
        )

        assertEquals(narrow, AlarmSettingsEditor.adjustLow(narrow, 1))
        assertEquals(narrow, AlarmSettingsEditor.adjustHigh(narrow, -1))
        assertEquals(
            AlarmThresholds(lowMgDl = 20),
            AlarmSettingsEditor.adjustLow(AlarmThresholds(lowMgDl = 20), -1),
        )
        assertEquals(
            AlarmThresholds(highMgDl = 600),
            AlarmSettingsEditor.adjustHigh(AlarmThresholds(highMgDl = 600), 1),
        )
    }

    @Test
    fun signalLossTimeoutOnlyUsesExplicitUiOptions() {
        val defaults = AlarmThresholds()

        assertEquals(
            15 * 60_000L,
            AlarmSettingsEditor.withStaleAfterMinutes(defaults, 15).staleAfterMs,
        )
        assertEquals(defaults, AlarmSettingsEditor.withStaleAfterMinutes(defaults, 7))
        assertEquals(listOf(5, 10, 15, 20, 30), AlarmSettingsEditor.staleAfterMinuteOptions)
        assertEquals(5 * 60_000L, AlarmSettingsEditor.adjustStaleAfter(defaults, -1).staleAfterMs)
        assertEquals(15 * 60_000L, AlarmSettingsEditor.adjustStaleAfter(defaults, 1).staleAfterMs)
        val minimum = AlarmSettingsEditor.withStaleAfterMinutes(defaults, 5)
        assertEquals(minimum, AlarmSettingsEditor.adjustStaleAfter(minimum, -1))
    }
}
