package com.sladkaya.app

import com.sladkaya.core.model.GlucoseReading
import com.sladkaya.core.model.AlarmThresholds
import com.sladkaya.core.model.ReadingQuality
import com.sladkaya.core.model.SensorFamily
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GlucosePresentationPolicyTest {
    @Test
    fun missingReadingIsNeverShownAsConnected() {
        assertEquals(
            ReadingFreshness.MISSING,
            ReadingFreshnessPolicy.evaluate(latest = null, nowEpochMs = NOW),
        )
    }

    @Test
    fun freshnessRequiresBothRecentSensorAndPhoneTimes() {
        assertEquals(
            ReadingFreshness.FRESH,
            ReadingFreshnessPolicy.evaluate(
                reading(sensorTime = NOW - 60_000L, phoneTime = NOW - 10_000L),
                NOW,
            ),
        )
        assertEquals(
            ReadingFreshness.STALE,
            ReadingFreshnessPolicy.evaluate(
                reading(sensorTime = NOW - 11 * 60_000L, phoneTime = NOW - 5_000L),
                NOW,
            ),
        )
        assertEquals(
            ReadingFreshness.STALE,
            ReadingFreshnessPolicy.evaluate(
                reading(sensorTime = NOW - 60_000L, phoneTime = NOW - 11 * 60_000L),
                NOW,
            ),
        )
    }

    @Test
    fun clockFarInFutureIsExplicitAndNotFresh() {
        assertEquals(
            ReadingFreshness.CLOCK_MISMATCH,
            ReadingFreshnessPolicy.evaluate(
                reading(sensorTime = NOW + 6 * 60_000L, phoneTime = NOW),
                NOW,
            ),
        )
    }

    @Test
    fun phoneCaptureTimeInFutureFailsClosedEvenWithinSensorClockTolerance() {
        assertEquals(
            ReadingFreshness.CLOCK_MISMATCH,
            ReadingFreshnessPolicy.evaluate(
                reading(sensorTime = NOW, phoneTime = NOW + 1L),
                NOW,
            ),
        )
        assertEquals(
            ReadingFreshness.FRESH,
            ReadingFreshnessPolicy.evaluate(
                reading(sensorTime = NOW + 4 * 60_000L, phoneTime = NOW),
                NOW,
            ),
        )
    }

    @Test
    fun recentWarmingOrDegradedReadingIsNotCalledFresh() {
        listOf(ReadingQuality.WARMING_UP, ReadingQuality.DEGRADED).forEach { quality ->
            assertEquals(
                ReadingFreshness.NOT_READY,
                ReadingFreshnessPolicy.evaluate(
                    reading(sensorTime = NOW - 60_000L, quality = quality),
                    NOW,
                ),
            )
        }
    }

    @Test
    fun largeCurrentNumberIsHiddenWhenReadingIsStaleOrClockIsInvalid() {
        assertTrue(CurrentGlucoseNumberPolicy.show(ReadingFreshness.FRESH))
        assertTrue(!CurrentGlucoseNumberPolicy.show(ReadingFreshness.NOT_READY))
        assertTrue(!CurrentGlucoseNumberPolicy.show(ReadingFreshness.MISSING))
        assertTrue(!CurrentGlucoseNumberPolicy.show(ReadingFreshness.STALE))
        assertTrue(!CurrentGlucoseNumberPolicy.show(ReadingFreshness.CLOCK_MISMATCH))
    }

    @Test
    fun chartDoesNotConnectAcrossMissingMeasurements() {
        val series = GlucoseChartPolicy.build(
            listOf(
                reading(eventId = "one", sensorTime = NOW - 10 * 60_000L, glucose = 100),
                reading(eventId = "two", sensorTime = NOW - 9 * 60_000L, glucose = 105),
                reading(eventId = "three", sensorTime = NOW - 2 * 60_000L, glucose = 82),
            ),
        )

        assertEquals(3, series.points.size)
        assertEquals(listOf(ChartConnection(0, 1)), series.connections)
    }

    @Test
    fun chartUsesRealTimeSpacingAndRejectsNonIncreasingTimestamps() {
        val series = GlucoseChartPolicy.build(
            listOf(
                reading(eventId = "late", sensorTime = NOW - 60_000L, glucose = 110),
                reading(eventId = "first", sensorTime = NOW - 4 * 60_000L, glucose = 100),
                reading(eventId = "duplicate-time", sensorTime = NOW - 4 * 60_000L, glucose = 101),
            ),
        )

        assertEquals(2, series.points.size)
        assertEquals(0f, series.points.first().xFraction)
        assertEquals(1f, series.points.last().xFraction)
        assertTrue(series.connections.isEmpty())
    }

    @Test
    fun chartUsesConfiguredAlarmRangeAndDropsNonValidValues() {
        val series = GlucoseChartPolicy.build(
            history = listOf(
                reading(eventId = "low", sensorTime = NOW - 3 * 60_000L, glucose = 75),
                reading(eventId = "normal", sensorTime = NOW - 2 * 60_000L, glucose = 100),
                reading(
                    eventId = "degraded",
                    sensorTime = NOW - 60_000L,
                    glucose = 300,
                    quality = ReadingQuality.DEGRADED,
                ),
            ),
            thresholds = AlarmThresholds(lowMgDl = 80, highMgDl = 180),
        )

        assertEquals(listOf(true, false), series.points.map { it.outsideAlarmRange })
        assertEquals(listOf("low", "normal"), series.points.map { it.reading.eventId })
    }

    @Test
    fun chartScaleAlwaysIncludesConfiguredThresholdsAndValidReadings() {
        val thresholds = AlarmThresholds(
            lowMgDl = 20,
            highMgDl = 600,
            recoveryHysteresisMgDl = 5,
        )
        val series = GlucoseChartPolicy.build(
            history = listOf(reading(sensorTime = NOW, glucose = 350)),
            thresholds = thresholds,
        )

        val scale = GlucoseChartScalePolicy.build(series, thresholds)

        assertTrue(scale.minMgDl <= 20f)
        assertTrue(scale.maxMgDl >= 600f)
        assertTrue(350f in scale.minMgDl..scale.maxMgDl)
    }

    private fun reading(
        eventId: String = "event",
        sensorTime: Long,
        phoneTime: Long = sensorTime,
        glucose: Int = 100,
        quality: ReadingQuality = ReadingQuality.VALID,
    ) = GlucoseReading(
        eventId = eventId,
        sensorId = "sensor",
        sensorFamily = SensorFamily.SIBIONICS_GS1,
        sensorTimeEpochMs = sensorTime,
        phoneTimeEpochMs = phoneTime,
        glucoseMgDl = glucose,
        trendMgDlPerMinute = 0.0,
        quality = quality,
        sequence = 1L,
    )

    private companion object {
        const val NOW = 1_800_000_000_000L
    }
}
