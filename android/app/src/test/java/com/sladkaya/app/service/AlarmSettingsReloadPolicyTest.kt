package com.sladkaya.app.service

import com.sladkaya.core.model.AlarmKind
import com.sladkaya.core.model.AlarmThresholds
import com.sladkaya.core.model.GlucoseReading
import com.sladkaya.core.model.ReadingQuality
import com.sladkaya.core.model.SensorFamily
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AlarmSettingsReloadPolicyTest {
    @Test
    fun staleLowReadingCannotOpenAValueAlarmDuringReload() {
        val result = AlarmSettingsReloadPolicy.replace(
            previousActive = emptySet(),
            latest = reading(55, NOW - 20 * 60_000L),
            thresholds = AlarmThresholds(),
            monitoringStartedAtEpochMs = NOW - 30 * 60_000L,
            nowEpochMs = NOW,
        )

        assertEquals(setOf(AlarmKind.SIGNAL_LOSS), result.changes.active)
        assertEquals(setOf(AlarmKind.SIGNAL_LOSS), result.changes.opened)
    }

    @Test
    fun firstReadingGraceUsesTheConfiguredTimeout() {
        val result = AlarmSettingsReloadPolicy.replace(
            previousActive = emptySet(),
            latest = null,
            thresholds = AlarmThresholds(staleAfterMs = 10 * 60_000L),
            monitoringStartedAtEpochMs = NOW - 9 * 60_000L,
            nowEpochMs = NOW,
        )

        assertTrue(result.changes.active.isEmpty())
    }

    @Test
    fun freshLowReadingStillOpensTheConfiguredAlarm() {
        val result = AlarmSettingsReloadPolicy.replace(
            previousActive = emptySet(),
            latest = reading(55, NOW - 60_000L),
            thresholds = AlarmThresholds(),
            monitoringStartedAtEpochMs = NOW - 30 * 60_000L,
            nowEpochMs = NOW,
        )

        assertEquals(setOf(AlarmKind.LOW), result.changes.active)
    }

    @Test
    fun reloadPreservesOpenLowUntilTheConfiguredHysteresisRecovers() {
        val result = AlarmSettingsReloadPolicy.replace(
            previousActive = setOf(AlarmKind.LOW),
            latest = reading(72, NOW - 60_000L),
            thresholds = AlarmThresholds(lowMgDl = 70, recoveryHysteresisMgDl = 5),
            monitoringStartedAtEpochMs = NOW - 30 * 60_000L,
            nowEpochMs = NOW,
        )

        assertEquals(setOf(AlarmKind.LOW), result.changes.active)
        assertTrue(result.changes.closed.isEmpty())
    }

    @Test
    fun reloadPreservesTheLastValidFreshnessBaselineWhenLatestReadingIsInvalid() {
        val result = AlarmSettingsReloadPolicy.replace(
            previousActive = emptySet(),
            latest = reading(
                value = 100,
                time = NOW - 60_000L,
                quality = ReadingQuality.DEGRADED,
            ),
            latestValidSensorTimeEpochMs = NOW - 5 * 60_000L,
            latestValidPhoneTimeEpochMs = NOW - 5 * 60_000L,
            thresholds = AlarmThresholds(staleAfterMs = 10 * 60_000L),
            monitoringStartedAtEpochMs = NOW - 60 * 60_000L,
            nowEpochMs = NOW,
        )

        assertTrue(result.changes.active.isEmpty())
    }

    @Test
    fun reloadCannotTreatAnOldSensorTimestampAsFreshBecausePhoneTimeIsCurrent() {
        val result = AlarmSettingsReloadPolicy.replace(
            previousActive = emptySet(),
            latest = reading(
                value = 100,
                time = NOW - 20 * 60_000L,
                phoneTime = NOW,
            ),
            latestValidSensorTimeEpochMs = NOW - 20 * 60_000L,
            latestValidPhoneTimeEpochMs = NOW,
            thresholds = AlarmThresholds(staleAfterMs = 10 * 60_000L),
            monitoringStartedAtEpochMs = NOW - 60 * 60_000L,
            nowEpochMs = NOW,
        )

        assertEquals(setOf(AlarmKind.SIGNAL_LOSS), result.changes.active)
    }

    private fun reading(
        value: Int,
        time: Long,
        phoneTime: Long = time,
        quality: ReadingQuality = ReadingQuality.VALID,
    ) = GlucoseReading(
        eventId = "event-$value-$time",
        sensorId = "sensor",
        sensorFamily = SensorFamily.SIBIONICS_GS1,
        sensorTimeEpochMs = time,
        phoneTimeEpochMs = phoneTime,
        glucoseMgDl = value,
        trendMgDlPerMinute = 0.0,
        quality = quality,
        sequence = time,
    )

    private companion object {
        const val NOW = 1_800_000_000_000L
    }
}
