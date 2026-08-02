package com.sladkaya.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AlarmPolicyTest {
    @Test
    fun lowAlarmOpensOnceAndUsesHysteresis() {
        val policy = policy()
        assertEquals(setOf(AlarmKind.LOW), policy.evaluate(reading(65, sensorTime = 1_000)).opened)
        assertTrue(policy.evaluate(reading(66, sensorTime = 2_000)).opened.isEmpty())
        assertTrue(policy.evaluate(reading(72, sensorTime = 3_000)).closed.isEmpty())
        assertEquals(
            setOf(AlarmKind.LOW),
            policy.evaluate(reading(76, sensorTime = 4_000)).closed,
        )
    }

    @Test
    fun signalLossClosesAfterFreshReading() {
        val policy = policy()
        val first = reading(100, sensorTime = 1_000)
        policy.evaluate(first)
        assertEquals(setOf(AlarmKind.SIGNAL_LOSS), policy.evaluateFreshness(601_001).opened)
        policy.evaluate(reading(105, sensorTime = 602_000))
        assertEquals(setOf(AlarmKind.SIGNAL_LOSS), policy.evaluateFreshness(602_001).closed)
    }

    @Test
    fun degradedHistoryOrFutureValueCannotRaiseAlarmOrHideSignalLoss() {
        val policy = policy()
        val degraded = reading(
            value = 40,
            sensorTime = 9_000_000,
            phoneTime = 1_000,
            quality = ReadingQuality.DEGRADED,
        )

        assertTrue(policy.evaluate(degraded).opened.isEmpty())
        assertEquals(
            setOf(AlarmKind.SIGNAL_LOSS),
            policy.evaluateFreshness(601_000).opened,
        )
    }

    @Test
    fun degradedValueCannotCloseAnActiveMedicalAlarm() {
        val policy = policy()
        policy.evaluate(reading(60))

        val degradedRecovery = policy.evaluate(
            reading(
                value = 120,
                sensorTime = 2_000,
                quality = ReadingQuality.DEGRADED,
            ),
        )

        assertTrue(degradedRecovery.closed.isEmpty())
        assertEquals(setOf(AlarmKind.LOW), degradedRecovery.active)
    }

    @Test
    fun phoneClockMovingBehindTheLastReadingRaisesSignalLoss() {
        val policy = policy(monitoringStartedAtEpochMs = 1_000_000)
        policy.evaluate(reading(value = 100, sensorTime = 1_000_000, phoneTime = 1_000_000))

        val changes = policy.evaluateFreshness(nowEpochMs = 900_000)

        assertEquals(setOf(AlarmKind.SIGNAL_LOSS), changes.opened)

        policy.evaluate(reading(value = 105, sensorTime = 901_000, phoneTime = 901_000))
        assertEquals(
            setOf(AlarmKind.SIGNAL_LOSS),
            policy.evaluateFreshness(nowEpochMs = 902_000).closed,
        )
    }

    @Test
    fun missingFirstReadingUsesMonitoringStartAsGraceBaseline() {
        val policy = policy(monitoringStartedAtEpochMs = 1_000_000)

        assertTrue(policy.evaluateFreshness(1_599_999).opened.isEmpty())
        assertEquals(
            setOf(AlarmKind.SIGNAL_LOSS),
            policy.evaluateFreshness(1_600_000).opened,
        )
    }

    @Test
    fun oldSensorTimestampCannotHideSignalLossBehindFreshPhoneCaptureTime() {
        val policy = policy(monitoringStartedAtEpochMs = 1_000_000)
        policy.evaluate(
            reading(
                value = 100,
                sensorTime = 1_000_000,
                phoneTime = 2_000_000,
            ),
        )

        assertEquals(
            setOf(AlarmKind.SIGNAL_LOSS),
            policy.evaluateFreshness(nowEpochMs = 2_000_000).opened,
        )
    }

    @Test
    fun staleValidLowCannotOpenAValueAlarm() {
        val policy = policy(monitoringStartedAtEpochMs = 1_000_000)

        val changes = policy.evaluate(
            reading(value = 40, sensorTime = 1_000_000, phoneTime = 1_000_000),
            nowEpochMs = 2_000_000,
        )

        assertTrue(changes.active.isEmpty())
    }

    @Test
    fun asymmetricFutureTimestampsCannotRefreshSignalLossBaseline() {
        listOf(
            reading(value = 100, sensorTime = 2_000_000, phoneTime = 2_000_001),
            reading(
                value = 100,
                sensorTime = 2_000_000 + 5 * 60_000L + 1L,
                phoneTime = 2_000_000,
            ),
        ).forEach { mismatched ->
            val policy = policy(monitoringStartedAtEpochMs = 1_000_000)
            policy.evaluate(mismatched, nowEpochMs = 2_000_000)
            assertEquals(
                setOf(AlarmKind.SIGNAL_LOSS),
                policy.evaluateFreshness(nowEpochMs = 2_000_000).opened,
            )
        }
    }

    @Test
    fun delayedOlderLowOrHighCannotChangeThresholdAlarmState() {
        listOf(40, 300).forEach { delayedValue ->
            val policy = policy(monitoringStartedAtEpochMs = 10_000)
            policy.evaluate(reading(value = 100, sensorTime = 10_000, phoneTime = 10_000))

            val delayed = policy.evaluate(
                reading(value = delayedValue, sensorTime = 9_000, phoneTime = 9_500),
                nowEpochMs = 10_000,
            )

            assertTrue(delayed.opened.isEmpty())
            assertTrue(delayed.closed.isEmpty())
            assertTrue(delayed.active.isEmpty())
        }
    }

    @Test
    fun exactTimestampDuplicateIsIdempotentEvenIfPayloadConflicts() {
        val policy = policy(monitoringStartedAtEpochMs = 10_000)
        policy.evaluate(reading(value = 100, sensorTime = 10_000, phoneTime = 10_000))

        val conflictingDuplicate = policy.evaluate(
            reading(value = 40, sensorTime = 10_000, phoneTime = 10_000),
            nowEpochMs = 10_001,
        )

        assertTrue(conflictingDuplicate.opened.isEmpty())
        assertTrue(conflictingDuplicate.closed.isEmpty())
        assertTrue(conflictingDuplicate.active.isEmpty())
    }

    @Test
    fun firstReadingAfterPhoneClockRollbackResetsBaselineWithoutFalseRecovery() {
        val policy = policy(monitoringStartedAtEpochMs = 1_000_000)
        policy.evaluate(reading(value = 60, sensorTime = 1_000_000, phoneTime = 1_000_000))
        policy.evaluateFreshness(nowEpochMs = 900_000)

        val rollbackReading = policy.evaluate(
            reading(value = 120, sensorTime = 901_000, phoneTime = 901_000),
            nowEpochMs = 901_000,
        )

        assertTrue(rollbackReading.closed.isEmpty())
        assertEquals(
            setOf(AlarmKind.LOW, AlarmKind.SIGNAL_LOSS),
            rollbackReading.active,
        )
        assertEquals(
            setOf(AlarmKind.SIGNAL_LOSS),
            policy.evaluateFreshness(nowEpochMs = 902_000).closed,
        )
    }

    private fun policy(monitoringStartedAtEpochMs: Long = 1_000L) = AlarmPolicy(
        monitoringStartedAtEpochMs = monitoringStartedAtEpochMs,
    )

    private fun reading(
        value: Int,
        sensorTime: Long = 1_000,
        phoneTime: Long = sensorTime,
        quality: ReadingQuality = ReadingQuality.VALID,
    ): GlucoseReading = GlucoseReading(
        eventId = "event-$value-$sensorTime",
        sensorId = "sim-1",
        sensorFamily = SensorFamily.SIMULATOR,
        sensorTimeEpochMs = sensorTime,
        phoneTimeEpochMs = phoneTime,
        glucoseMgDl = value,
        trendMgDlPerMinute = 0.0,
        quality = quality,
        sequence = sensorTime,
    )
}
