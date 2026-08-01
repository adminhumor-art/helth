package com.sladkaya.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class AlarmThresholdsTest {
    @Test
    fun defaultsAreExplicitAndValid() {
        val defaults = AlarmThresholds()

        assertEquals(70, defaults.lowMgDl)
        assertEquals(250, defaults.highMgDl)
        assertEquals(10 * 60_000L, defaults.staleAfterMs)
    }

    @Test
    fun invalidOrSelfContradictoryThresholdsAreRejected() {
        listOf(
            { AlarmThresholds(lowMgDl = 19) },
            { AlarmThresholds(highMgDl = 601) },
            { AlarmThresholds(lowMgDl = 250, highMgDl = 250) },
            { AlarmThresholds(rapidFallMgDlPerMinute = 0.0) },
            { AlarmThresholds(rapidRiseMgDlPerMinute = 0.0) },
            { AlarmThresholds(recoveryHysteresisMgDl = 0) },
            { AlarmThresholds(lowMgDl = 70, highMgDl = 75, recoveryHysteresisMgDl = 5) },
            { AlarmThresholds(staleAfterMs = 59_999L) },
            { AlarmThresholds(staleAfterMs = 60 * 60_000L + 1L) },
        ).forEach { invalid ->
            assertThrows(IllegalArgumentException::class.java) { invalid() }
        }
    }
}
