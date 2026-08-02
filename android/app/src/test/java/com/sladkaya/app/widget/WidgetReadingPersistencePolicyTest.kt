package com.sladkaya.app.widget

import com.sladkaya.core.model.GlucoseReading
import com.sladkaya.core.model.ReadingQuality
import com.sladkaya.core.model.SensorFamily
import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class WidgetReadingPersistencePolicyTest {
    @Test
    fun onlyValidProductReadingsMayBePersistedByWidget() {
        assertFalse(WidgetReadingPersistencePolicy.canPersist(reading(SensorFamily.SIMULATOR)))
        assertTrue(WidgetReadingPersistencePolicy.canPersist(reading(SensorFamily.SIBIONICS_GS1)))
        assertFalse(
            WidgetReadingPersistencePolicy.canPersist(
                reading(SensorFamily.SIBIONICS_GS1, ReadingQuality.WARMING_UP),
            ),
        )
        assertFalse(
            WidgetReadingPersistencePolicy.canPersist(
                reading(SensorFamily.SIBIONICS_GS1, ReadingQuality.DEGRADED),
            ),
        )
    }

    @Test
    fun displayFailsClosedForDemoStaleClockMismatchAndInvalidQuality() {
        assertTrue(
            WidgetReadingPresentationPolicy.canShowValue(
                demoActive = false,
                quality = ReadingQuality.VALID,
                sensorTimeEpochMs = NOW - 60_000L,
                phoneTimeEpochMs = NOW - 30_000L,
                nowEpochMs = NOW,
                staleAfterMs = 10 * 60_000L,
            ),
        )
        assertFalse(
            WidgetReadingPresentationPolicy.canShowValue(
                false,
                ReadingQuality.VALID,
                NOW - 11 * 60_000L,
                NOW,
                NOW,
                10 * 60_000L,
            ),
        )
        assertFalse(
            WidgetReadingPresentationPolicy.canShowValue(
                false,
                ReadingQuality.VALID,
                NOW,
                NOW + 1L,
                NOW,
                10 * 60_000L,
            ),
        )
        assertFalse(
            WidgetReadingPresentationPolicy.canShowValue(
                false,
                ReadingQuality.VALID,
                NOW + 6 * 60_000L,
                NOW,
                NOW,
                10 * 60_000L,
            ),
        )
        assertFalse(
            WidgetReadingPresentationPolicy.canShowValue(
                true,
                ReadingQuality.VALID,
                NOW,
                NOW,
                NOW,
                10 * 60_000L,
            ),
        )
        assertFalse(
            WidgetReadingPresentationPolicy.canShowValue(
                false,
                ReadingQuality.DEGRADED,
                NOW,
                NOW,
                NOW,
                10 * 60_000L,
            ),
        )
    }

    @Test
    fun expiryUsesTheFirstTimestampThatCanBecomeStale() {
        assertEquals(
            NOW + 9 * 60_000L,
            WidgetExpiryPolicy.deadlineEpochMs(
                sensorTimeEpochMs = NOW,
                phoneTimeEpochMs = NOW + 60_000L,
                staleAfterMs = 9 * 60_000L,
            ),
        )
    }

    @Test
    fun elapsedDeadlineCannotBeExtendedByARebootOrClockRollback() {
        assertEquals(
            610_000L,
            WidgetExpiryPolicy.elapsedDeadlineMs(
                existingDeadlineMs = 50_000_000L,
                nowElapsedMs = 10_000L,
                remainingFreshMs = 600_000L,
                preserveExistingDeadline = true,
            ),
        )
        assertEquals(
            500_000L,
            WidgetExpiryPolicy.elapsedDeadlineMs(
                existingDeadlineMs = 500_000L,
                nowElapsedMs = 10_000L,
                remainingFreshMs = 600_000L,
                preserveExistingDeadline = true,
            ),
        )
    }

    @Test
    fun persistedValueRequiresExactExpiryCapabilityOnModernAndroid() {
        assertTrue(WidgetExactAlarmPolicy.canArm(sdkInt = 30, canScheduleExactAlarms = false))
        assertTrue(WidgetExactAlarmPolicy.canArm(sdkInt = 31, canScheduleExactAlarms = true))
        assertFalse(WidgetExactAlarmPolicy.canArm(sdkInt = 31, canScheduleExactAlarms = false))
        assertFalse(WidgetExactAlarmPolicy.canArm(sdkInt = 37, canScheduleExactAlarms = false))
    }

    @Test
    fun everyVisibleValueHasExactExpiryAndARevocationWatchdog() {
        assertEquals(
            setOf(
                WidgetExpiryAlarmKind.EXACT_EXPIRY,
                WidgetExpiryAlarmKind.INEXACT_REVOCATION_WATCHDOG,
            ),
            WidgetExpiryAlarmPlanPolicy.plan(
                sdkInt = 37,
                canScheduleExactAlarms = true,
            ),
        )
        assertEquals(
            emptySet<WidgetExpiryAlarmKind>(),
            WidgetExpiryAlarmPlanPolicy.plan(
                sdkInt = 37,
                canScheduleExactAlarms = false,
            ),
        )
    }

    private fun reading(
        family: SensorFamily,
        quality: ReadingQuality = ReadingQuality.VALID,
    ) = GlucoseReading(
        eventId = "event-${family.wireName}",
        sensorId = "sensor-${family.wireName}",
        sensorFamily = family,
        sensorTimeEpochMs = 1_000L,
        phoneTimeEpochMs = 1_000L,
        glucoseMgDl = 100,
        trendMgDlPerMinute = 0.0,
        quality = quality,
        sequence = 1L,
    )

    private companion object {
        const val NOW = 1_800_000_000_000L
    }
}
