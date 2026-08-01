package com.sladkaya.app

import com.sladkaya.core.model.AlarmKind
import com.sladkaya.core.model.GlucoseReading
import com.sladkaya.core.sensor.SensorDriverState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

data class GlucoseUiState(
    val latest: GlucoseReading? = null,
    val history: List<GlucoseReading> = emptyList(),
    val driverState: SensorDriverState = SensorDriverState.Idle,
    val activeAlarms: Set<AlarmKind> = emptySet(),
    val simulatorMode: Boolean = false,
)

object AppState {
    private val mutable = MutableStateFlow(GlucoseUiState())
    private val demoLock = Any()
    private var demoGeneration = 0L
    val state = mutable.asStateFlow()

    fun onDemoReading(
        generation: Long,
        reading: GlucoseReading,
        activeAlarms: Set<AlarmKind>,
    ): Boolean = synchronized(demoLock) {
        if (generation != demoGeneration || !mutable.value.simulatorMode ||
            reading.sensorFamily != com.sladkaya.core.model.SensorFamily.SIMULATOR
        ) {
            return@synchronized false
        }
        mutable.update { current ->
            val history = (current.history + reading)
                .distinctBy { it.eventId }
                .sortedBy { it.sensorTimeEpochMs }
                .takeLast(288)
            current.copy(latest = reading, history = history, activeAlarms = activeAlarms)
        }
        true
    }

    fun restoreProductHistory(readings: List<GlucoseReading>) = synchronized(demoLock) {
        demoGeneration += 1
        val history = readings.filter(GlucoseReading::isEligibleForProductPublication)
            .distinctBy { it.eventId }
            .sortedBy { it.sensorTimeEpochMs }
            .takeLast(288)
        mutable.update { current ->
            current.copy(
                latest = history.lastOrNull(),
                history = history,
                activeAlarms = emptySet(),
                simulatorMode = false,
            )
        }
    }

    fun onDriverState(state: SensorDriverState) {
        mutable.update { it.copy(driverState = state) }
    }

    fun onDemoDriverState(generation: Long, state: SensorDriverState): Boolean =
        synchronized(demoLock) {
            if (generation != demoGeneration || !mutable.value.simulatorMode) {
                return@synchronized false
            }
            mutable.update { it.copy(driverState = state) }
            true
        }

    fun onAlarmState(activeAlarms: Set<AlarmKind>) {
        mutable.update { it.copy(activeAlarms = activeAlarms) }
    }

    fun onDemoAlarmState(generation: Long, activeAlarms: Set<AlarmKind>): Boolean =
        synchronized(demoLock) {
            if (generation != demoGeneration || !mutable.value.simulatorMode) {
                return@synchronized false
            }
            mutable.update { it.copy(activeAlarms = activeAlarms) }
            true
        }

    fun onDemoStarting(): Long = synchronized(demoLock) {
        demoGeneration += 1
        mutable.update {
            it.copy(
                latest = null,
                history = emptyList(),
                driverState = SensorDriverState.WaitingForData(System.currentTimeMillis()),
                activeAlarms = emptySet(),
                simulatorMode = true,
            )
        }
        demoGeneration
    }

    fun onSetupRequired(message: String) = synchronized(demoLock) {
        demoGeneration += 1
        mutable.update {
            it.copy(
                latest = null,
                history = emptyList(),
                driverState = SensorDriverState.Failure(message, retryable = false),
                activeAlarms = emptySet(),
                simulatorMode = false,
            )
        }
    }
}
