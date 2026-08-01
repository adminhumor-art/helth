package com.sladkaya.app

import com.sladkaya.core.model.AlarmKind
import com.sladkaya.core.model.GlucoseReading
import com.sladkaya.core.model.ReadingQuality
import com.sladkaya.core.model.SensorFamily
import com.sladkaya.core.sensor.SensorDriverState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AppStateFailClosedTest {
    @Test
    fun setupRequiredClearsDemoValueHistoryAndAlarms() {
        val generation = AppState.onDemoStarting()
        AppState.onDemoReading(generation, demoReading(), setOf(AlarmKind.LOW))

        AppState.onSetupRequired("Требуется настройка датчика")

        val state = AppState.state.value
        assertNull(state.latest)
        assertTrue(state.history.isEmpty())
        assertTrue(state.activeAlarms.isEmpty())
        assertFalse(state.simulatorMode)
        assertEquals(
            SensorDriverState.Failure("Требуется настройка датчика", retryable = false),
            state.driverState,
        )
    }

    @Test
    fun delayedDemoCallbackCannotOverwriteFailClosedState() {
        val generation = AppState.onDemoStarting()
        AppState.onSetupRequired("Требуется настройка датчика")

        val accepted = AppState.onDemoReading(
            generation,
            demoReading().copy(eventId = "late-demo-event"),
            setOf(AlarmKind.LOW),
        )

        assertFalse(accepted)
        assertNull(AppState.state.value.latest)
        assertTrue(AppState.state.value.history.isEmpty())
        assertFalse(AppState.state.value.simulatorMode)
    }

    @Test
    fun restoredProductHistoryDropsSimulatorRows() {
        AppState.onSetupRequired("reset")

        AppState.restoreProductHistory(
            listOf(
                demoReading(),
                demoReading().copy(
                    eventId = "real-event",
                    sensorId = "real-sensor",
                    sensorFamily = SensorFamily.SIBIONICS_GS1,
                    sequence = 2L,
                ),
            ),
        )

        assertEquals(listOf("real-event"), AppState.state.value.history.map { it.eventId })
        assertEquals("real-event", AppState.state.value.latest?.eventId)
    }

    private fun demoReading() = GlucoseReading(
        eventId = "demo-event",
        sensorId = "demo-sensor",
        sensorFamily = SensorFamily.SIMULATOR,
        sensorTimeEpochMs = 1_000L,
        phoneTimeEpochMs = 1_000L,
        glucoseMgDl = 58,
        trendMgDlPerMinute = -3.2,
        quality = ReadingQuality.VALID,
        sequence = 1L,
    )
}
