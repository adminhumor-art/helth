package com.sladkaya.app

import com.sladkaya.core.model.AlarmKind
import com.sladkaya.core.model.ReadingQuality
import com.sladkaya.core.model.SensorFamily
import com.sladkaya.sensor.sibionics.Gs1DiagnosticReading
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AppStateDiagnosticQuarantineTest {
    @Test
    fun diagnosticReadingIsVisibleOnlyInsideDiagnosticStatus() {
        AppState.onSetupRequired("reset")
        val generation = AppState.onDiagnosticStarting()

        AppState.onDiagnosticStatus(
            generation = generation,
            phaseLabel = "streaming",
            allowsReading = true,
            nowEpochMs = 1_100L,
        )
        val accepted = AppState.onDiagnosticReading(
            generation,
            diagnosticReading(),
            nowEpochMs = 1_100L,
        )

        assertTrue(accepted)
        val state = AppState.state.value
        assertNull(state.latest)
        assertTrue(state.history.isEmpty())
        assertTrue(state.activeAlarms.isEmpty())
        assertFalse(state.simulatorMode)
        assertEquals(91L, state.diagnostic.latestReading?.sequence)
        assertEquals(104, state.diagnostic.latestReading?.glucoseMgDl)
        assertEquals("private-diagnostic-event", state.diagnostic.latestReading?.eventId)
        assertEquals("private-diagnostic-sensor", state.diagnostic.latestReading?.sensorId)
    }

    @Test
    fun lateDiagnosticCallbackCannotOverwriteAnotherSession() {
        AppState.onSetupRequired("reset")
        val generation = AppState.onDiagnosticStarting()
        AppState.onDemoStarting()

        val accepted = AppState.onDiagnosticReading(generation, diagnosticReading())

        assertFalse(accepted)
        assertNull(AppState.state.value.diagnostic.latestReading)
    }

    @Test
    fun validReadingAndStreamingStatusAreSafeInEitherCollectorOrder() {
        AppState.onSetupRequired("reset")
        val readingFirstGeneration = AppState.onDiagnosticStarting()

        AppState.onDiagnosticReading(
            readingFirstGeneration,
            diagnosticReading(),
            nowEpochMs = 1_100L,
        )
        assertNull(AppState.state.value.diagnostic.latestReading)
        AppState.onDiagnosticStatus(
            generation = readingFirstGeneration,
            phaseLabel = "streaming",
            allowsReading = true,
            nowEpochMs = 1_100L,
        )
        assertEquals(91L, AppState.state.value.diagnostic.latestReading?.sequence)

        val statusFirstGeneration = AppState.onDiagnosticStarting()
        AppState.onDiagnosticStatus(
            generation = statusFirstGeneration,
            phaseLabel = "streaming",
            allowsReading = true,
            nowEpochMs = 1_100L,
        )
        AppState.onDiagnosticReading(
            statusFirstGeneration,
            diagnosticReading(),
            nowEpochMs = 1_100L,
        )
        assertEquals(91L, AppState.state.value.diagnostic.latestReading?.sequence)
    }

    @Test
    fun nonStreamingStatusClearsVisibleNumberAndLateUnreadyDataCannotRestoreIt() {
        AppState.onSetupRequired("reset")
        val generation = AppState.onDiagnosticStarting()
        AppState.onDiagnosticStatus(
            generation = generation,
            phaseLabel = "streaming",
            allowsReading = true,
            nowEpochMs = 1_100L,
        )
        AppState.onDiagnosticReading(generation, diagnosticReading(), nowEpochMs = 1_100L)
        assertEquals(91L, AppState.state.value.diagnostic.latestReading?.sequence)

        AppState.onDiagnosticStatus(
            generation = generation,
            phaseLabel = "not fresh",
            allowsReading = false,
            nowEpochMs = 1_100L,
        )
        AppState.onDiagnosticReading(
            generation,
            diagnosticReading(quality = ReadingQuality.WARMING_UP),
            nowEpochMs = 1_100L,
        )

        assertNull(AppState.state.value.diagnostic.latestReading)
    }

    @Test
    fun validButStaleOrFutureDatedDiagnosticReadingIsNeverShown() {
        AppState.onSetupRequired("reset")
        val generation = AppState.onDiagnosticStarting()
        AppState.onDiagnosticStatus(
            generation = generation,
            phaseLabel = "streaming",
            allowsReading = true,
            nowEpochMs = 1_100L,
        )

        AppState.onDiagnosticReading(generation, diagnosticReading(), nowEpochMs = 601_101L)
        assertNull(AppState.state.value.diagnostic.latestReading)

        AppState.onDiagnosticReading(generation, diagnosticReading(), nowEpochMs = 999L)
        assertNull(AppState.state.value.diagnostic.latestReading)
    }

    @Test
    fun diagnosticStartClearsAnyProductOrDemoPublicationState() {
        val demoGeneration = AppState.onDemoStarting()
        AppState.onDemoAlarmState(demoGeneration, setOf(AlarmKind.LOW))

        AppState.onDiagnosticStarting()

        val state = AppState.state.value
        assertNull(state.latest)
        assertTrue(state.history.isEmpty())
        assertTrue(state.activeAlarms.isEmpty())
        assertFalse(state.simulatorMode)
        assertTrue(state.diagnostic.active)
    }

    private fun diagnosticReading(quality: ReadingQuality = ReadingQuality.VALID) =
        Gs1DiagnosticReading(
        eventId = "private-diagnostic-event",
        sensorId = "private-diagnostic-sensor",
        sensorFamily = SensorFamily.SIBIONICS_GS1,
        sensorTimeEpochMs = 1_000L,
        phoneTimeEpochMs = 1_100L,
        glucoseMgDl = 104,
        trendMgDlPerMinute = -0.5,
        quality = quality,
        sequence = 91L,
        )
}
