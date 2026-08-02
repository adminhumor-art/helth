package com.sladkaya.app

import com.sladkaya.core.model.AlarmKind
import com.sladkaya.core.model.GlucoseReading
import com.sladkaya.core.model.ReadingQuality
import com.sladkaya.core.model.SensorFamily
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class AppStateProductSessionTest {
    @Before
    fun reset() {
        AppState.onSetupRequired("reset")
    }

    @Test
    fun activeProductGenerationAcceptsOnlyPublishablePhysicalReadings() {
        val generation = AppState.onProductStarting(listOf(productReading(sequence = 1)))

        assertTrue(
            AppState.onProductReading(
                generation = generation,
                reading = productReading(sequence = 2),
                activeAlarms = setOf(AlarmKind.LOW),
            ),
        )
        assertFalse(
            AppState.onProductReading(
                generation = generation,
                reading = productReading(sequence = 3).copy(quality = ReadingQuality.DEGRADED),
                activeAlarms = emptySet(),
            ),
        )

        val state = AppState.state.value
        assertEquals(listOf(1L, 2L), state.history.map(GlucoseReading::sequence))
        assertEquals(2L, state.latest?.sequence)
        assertEquals(setOf(AlarmKind.LOW), state.activeAlarms)
        assertFalse(state.simulatorMode)
        assertFalse(state.diagnostic.active)
    }

    @Test
    fun delayedProductCallbackCannotOverwriteAnotherSessionOrFailClosedState() {
        val generation = AppState.onProductStarting(emptyList())
        AppState.onDemoStarting()

        assertFalse(
            AppState.onProductReading(
                generation,
                productReading(sequence = 1),
                setOf(AlarmKind.HIGH),
            ),
        )
        assertNull(AppState.state.value.latest)

        val nextGeneration = AppState.onProductStarting(emptyList())
        AppState.onSetupRequired("stop")

        assertFalse(
            AppState.onProductReading(
                nextGeneration,
                productReading(sequence = 2),
                setOf(AlarmKind.LOW),
            ),
        )
        assertNull(AppState.state.value.latest)
    }

    @Test
    fun durableWatchdogCanUpdateOnlyTheCurrentlyBoundProductSession() {
        AppState.onProductStarting(BINDING, listOf(productReading(sequence = 1)))

        assertFalse(
            AppState.onProductAlarmDelivery("33".repeat(32), setOf(AlarmKind.SIGNAL_LOSS)),
        )
        assertTrue(
            AppState.onProductAlarmDelivery(BINDING, setOf(AlarmKind.SIGNAL_LOSS)),
        )
        assertEquals(setOf(AlarmKind.SIGNAL_LOSS), AppState.state.value.activeAlarms)

        AppState.onDemoStarting()
        assertFalse(AppState.onProductAlarmDelivery(BINDING, setOf(AlarmKind.LOW)))
        assertTrue(AppState.state.value.activeAlarms.isEmpty())
    }

    private fun productReading(sequence: Long) = GlucoseReading(
        eventId = "product-$sequence",
        sensorId = "sensor-a",
        sensorFamily = SensorFamily.SIBIONICS_GS1,
        sensorTimeEpochMs = 1_000L + sequence * 60_000L,
        phoneTimeEpochMs = 1_100L + sequence * 60_000L,
        glucoseMgDl = 68,
        trendMgDlPerMinute = -2.0,
        quality = ReadingQuality.VALID,
        sequence = sequence,
    )

    private companion object {
        const val BINDING =
            "2222222222222222222222222222222222222222222222222222222222222222"
    }
}
