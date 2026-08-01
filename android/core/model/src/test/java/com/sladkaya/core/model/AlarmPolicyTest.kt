package com.sladkaya.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AlarmPolicyTest {
    @Test
    fun lowAlarmOpensOnceAndUsesHysteresis() {
        val policy = AlarmPolicy()
        assertEquals(setOf(AlarmKind.LOW), policy.evaluate(reading(65)).opened)
        assertTrue(policy.evaluate(reading(66)).opened.isEmpty())
        assertTrue(policy.evaluate(reading(72)).closed.isEmpty())
        assertEquals(setOf(AlarmKind.LOW), policy.evaluate(reading(76)).closed)
    }

    @Test
    fun signalLossClosesAfterFreshReading() {
        val policy = AlarmPolicy()
        val first = reading(100, sensorTime = 1_000)
        policy.evaluate(first)
        assertEquals(setOf(AlarmKind.SIGNAL_LOSS), policy.evaluateFreshness(601_001).opened)
        policy.evaluate(reading(105, sensorTime = 602_000))
        assertEquals(setOf(AlarmKind.SIGNAL_LOSS), policy.evaluateFreshness(602_001).closed)
    }

    @Test
    fun degradedHistoryOrFutureValueCannotRaiseAlarmOrHideSignalLoss() {
        val policy = AlarmPolicy()
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
        val policy = AlarmPolicy()
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
