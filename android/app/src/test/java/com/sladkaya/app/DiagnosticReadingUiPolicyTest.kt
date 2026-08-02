package com.sladkaya.app

import com.sladkaya.core.model.ReadingQuality
import com.sladkaya.core.model.SensorFamily
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DiagnosticReadingUiPolicyTest {
    @Test
    fun onlyValidRecentNonFutureReadingCanBeDisplayed() {
        val valid = reading()

        assertTrue(DiagnosticReadingUiPolicy.canDisplay(valid, nowEpochMs = 1_100L))
        assertFalse(
            DiagnosticReadingUiPolicy.canDisplay(
                valid.copy(quality = ReadingQuality.WARMING_UP),
                nowEpochMs = 1_100L,
            ),
        )
        assertFalse(DiagnosticReadingUiPolicy.canDisplay(valid, nowEpochMs = 601_100L))
        assertFalse(DiagnosticReadingUiPolicy.canDisplay(valid, nowEpochMs = 999L))
    }

    private fun reading() = DiagnosticReadingUi(
        eventId = "diagnostic-event",
        sensorId = "diagnostic-sensor",
        family = SensorFamily.SIBIONICS_GS1,
        sensorTimeEpochMs = 1_000L,
        phoneTimeEpochMs = 1_100L,
        glucoseMgDl = 104,
        trendMgDlPerMinute = -0.5,
        quality = ReadingQuality.VALID,
        sequence = 91L,
    )
}
