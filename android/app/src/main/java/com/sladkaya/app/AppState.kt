package com.sladkaya.app

import com.sladkaya.core.model.AlarmKind
import com.sladkaya.core.model.GlucoseReading
import com.sladkaya.core.model.ReadingQuality
import com.sladkaya.core.model.SensorFamily
import com.sladkaya.core.sensor.SensorDriverState
import com.sladkaya.sensor.sibionics.Gs1DiagnosticReading
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

data class DiagnosticReadingUi(
    val family: SensorFamily,
    val sensorTimeEpochMs: Long,
    val phoneTimeEpochMs: Long,
    val glucoseMgDl: Int,
    val trendMgDlPerMinute: Double,
    val quality: ReadingQuality,
    val sequence: Long,
)

data class DiagnosticUiState(
    val active: Boolean = false,
    val phaseLabel: String = "Диагностика не запущена",
    val technicalCode: String? = null,
    val retryable: Boolean = false,
    val readingAllowed: Boolean = false,
    val latestReading: DiagnosticReadingUi? = null,
)

data class GlucoseUiState(
    val latest: GlucoseReading? = null,
    val history: List<GlucoseReading> = emptyList(),
    val driverState: SensorDriverState = SensorDriverState.Idle,
    val activeAlarms: Set<AlarmKind> = emptySet(),
    val simulatorMode: Boolean = false,
    val diagnostic: DiagnosticUiState = DiagnosticUiState(),
)

object AppState {
    private val mutable = MutableStateFlow(GlucoseUiState())
    private val demoLock = Any()
    private var demoGeneration = 0L
    private var diagnosticGeneration = 0L
    private var productGeneration = 0L
    private var pendingDiagnosticReading: DiagnosticReadingUi? = null
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

    fun restoreProductHistory(readings: List<GlucoseReading>) {
        onProductStarting(readings)
    }

    fun onProductStarting(readings: List<GlucoseReading>): Long = synchronized(demoLock) {
        demoGeneration += 1
        diagnosticGeneration += 1
        productGeneration += 1
        pendingDiagnosticReading = null
        val history = readings.filter(GlucoseReading::isEligibleForProductPublication)
            .distinctBy { it.eventId }
            .sortedBy { it.sensorTimeEpochMs }
            .takeLast(288)
        mutable.update { current ->
            current.copy(
                latest = history.lastOrNull(),
                history = history,
                driverState = SensorDriverState.WaitingForData(System.currentTimeMillis()),
                activeAlarms = emptySet(),
                simulatorMode = false,
                diagnostic = DiagnosticUiState(),
            )
        }
        productGeneration
    }

    fun onProductReading(
        generation: Long,
        reading: GlucoseReading,
        activeAlarms: Set<AlarmKind>,
    ): Boolean = synchronized(demoLock) {
        if (generation != productGeneration || mutable.value.simulatorMode ||
            mutable.value.diagnostic.active || !reading.isEligibleForProductPublication
        ) {
            return@synchronized false
        }
        mutable.update { current ->
            val history = (current.history + reading)
                .distinctBy { it.eventId }
                .sortedBy { it.sensorTimeEpochMs }
                .takeLast(288)
            current.copy(
                latest = history.lastOrNull(),
                history = history,
                activeAlarms = activeAlarms,
            )
        }
        true
    }

    fun onProductDriverState(generation: Long, state: SensorDriverState): Boolean =
        synchronized(demoLock) {
            if (generation != productGeneration || mutable.value.simulatorMode ||
                mutable.value.diagnostic.active
            ) {
                return@synchronized false
            }
            mutable.update { it.copy(driverState = state) }
            true
        }

    fun onProductAlarmState(generation: Long, activeAlarms: Set<AlarmKind>): Boolean =
        synchronized(demoLock) {
            if (generation != productGeneration || mutable.value.simulatorMode ||
                mutable.value.diagnostic.active
            ) {
                return@synchronized false
            }
            mutable.update { it.copy(activeAlarms = activeAlarms) }
            true
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
        diagnosticGeneration += 1
        productGeneration += 1
        pendingDiagnosticReading = null
        mutable.update {
            it.copy(
                latest = null,
                history = emptyList(),
                driverState = SensorDriverState.WaitingForData(System.currentTimeMillis()),
                activeAlarms = emptySet(),
                simulatorMode = true,
                diagnostic = DiagnosticUiState(),
            )
        }
        demoGeneration
    }

    fun onDiagnosticStarting(): Long = synchronized(demoLock) {
        demoGeneration += 1
        diagnosticGeneration += 1
        productGeneration += 1
        pendingDiagnosticReading = null
        mutable.update {
            it.copy(
                latest = null,
                history = emptyList(),
                driverState = SensorDriverState.WaitingForData(System.currentTimeMillis()),
                activeAlarms = emptySet(),
                simulatorMode = false,
                diagnostic = DiagnosticUiState(
                    active = true,
                    phaseLabel = "Подготовка диагностического подключения",
                ),
            )
        }
        diagnosticGeneration
    }

    fun onDiagnosticStatus(
        generation: Long,
        phaseLabel: String,
        technicalCode: String? = null,
        retryable: Boolean = false,
        allowsReading: Boolean = false,
        nowEpochMs: Long = System.currentTimeMillis(),
    ): Boolean = synchronized(demoLock) {
        if (generation != diagnosticGeneration || !mutable.value.diagnostic.active) {
            return@synchronized false
        }
        if (!allowsReading) pendingDiagnosticReading = null
        val visibleReading = pendingDiagnosticReading?.takeIf { reading ->
            allowsReading && DiagnosticReadingUiPolicy.canDisplay(reading, nowEpochMs)
        }
        mutable.update { current ->
            current.copy(
                diagnostic = current.diagnostic.copy(
                    phaseLabel = phaseLabel,
                    technicalCode = technicalCode,
                    retryable = retryable,
                    readingAllowed = allowsReading,
                    latestReading = visibleReading,
                ),
            )
        }
        true
    }

    fun onDiagnosticReading(
        generation: Long,
        reading: Gs1DiagnosticReading,
        nowEpochMs: Long = System.currentTimeMillis(),
    ): Boolean = synchronized(demoLock) {
        if (generation != diagnosticGeneration || !mutable.value.diagnostic.active) {
            return@synchronized false
        }
        val candidate = DiagnosticReadingUi(
            family = reading.sensorFamily,
            sensorTimeEpochMs = reading.sensorTimeEpochMs,
            phoneTimeEpochMs = reading.phoneTimeEpochMs,
            glucoseMgDl = reading.glucoseMgDl,
            trendMgDlPerMinute = reading.trendMgDlPerMinute,
            quality = reading.quality,
            sequence = reading.sequence,
        ).takeIf { DiagnosticReadingUiPolicy.canDisplay(it, nowEpochMs) }
        pendingDiagnosticReading = candidate
        mutable.update { current ->
            current.copy(
                latest = null,
                history = emptyList(),
                activeAlarms = emptySet(),
                simulatorMode = false,
                diagnostic = current.diagnostic.copy(
                    latestReading = candidate.takeIf { current.diagnostic.readingAllowed },
                ),
            )
        }
        true
    }

    fun onSetupRequired(message: String) = synchronized(demoLock) {
        demoGeneration += 1
        diagnosticGeneration += 1
        productGeneration += 1
        pendingDiagnosticReading = null
        mutable.update {
            it.copy(
                latest = null,
                history = emptyList(),
                driverState = SensorDriverState.Failure(message, retryable = false),
                activeAlarms = emptySet(),
                simulatorMode = false,
                diagnostic = DiagnosticUiState(),
            )
        }
    }
}
