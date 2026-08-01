package com.sladkaya.sensor.simulator

import com.sladkaya.core.model.GlucoseReading
import com.sladkaya.core.model.ReadingQuality
import com.sladkaya.core.model.SensorFamily
import com.sladkaya.core.sensor.SensorConfiguration
import com.sladkaya.core.sensor.SensorDriver
import com.sladkaya.core.sensor.SensorDriverState
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

enum class SimulationScenario {
    NORMAL,
    NIGHT_LOW,
    HIGH,
    RAPID_FALL,
    SIGNAL_LOSS,
}

class SimulatorDriver(
    private val scenario: SimulationScenario = SimulationScenario.NIGHT_LOW,
    private val periodMs: Long = 2_000,
    private val clock: () -> Long = System::currentTimeMillis,
) : SensorDriver {
    override val family = SensorFamily.SIMULATOR
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val mutableState = MutableStateFlow<SensorDriverState>(SensorDriverState.Idle)
    override val state: StateFlow<SensorDriverState> = mutableState.asStateFlow()
    private val mutableReadings = MutableStateFlow<GlucoseReading?>(null)
    override val readings: StateFlow<GlucoseReading?> = mutableReadings.asStateFlow()
    private var job: Job? = null

    override suspend fun start(configuration: SensorConfiguration) {
        job?.cancel()
        mutableState.value = SensorDriverState.Streaming
        job = scope.launch {
            var sequence = 0L
            while (isActive) {
                val point = scenario.point(sequence)
                if (point != null) {
                    val now = clock()
                    mutableReadings.value = GlucoseReading(
                        eventId = UUID.randomUUID().toString(),
                        sensorId = configuration.sensorId,
                        sensorFamily = family,
                        sensorTimeEpochMs = now,
                        phoneTimeEpochMs = now,
                        glucoseMgDl = point.first,
                        trendMgDlPerMinute = point.second,
                        quality = ReadingQuality.VALID,
                        sequence = sequence,
                    )
                } else {
                    mutableState.value = SensorDriverState.WaitingForData(clock())
                }
                sequence++
                delay(periodMs)
            }
        }
    }

    override suspend fun stop() {
        job?.cancel()
        job = null
        mutableState.value = SensorDriverState.Idle
    }

    internal fun SimulationScenario.point(sequence: Long): Pair<Int, Double>? = when (this) {
        SimulationScenario.NORMAL -> (105 + (sequence % 7).toInt()) to 0.2
        SimulationScenario.NIGHT_LOW -> {
            val values = intArrayOf(112, 108, 101, 93, 82, 72, 65, 58, 62, 70, 78, 86)
            val index = (sequence % values.size).toInt()
            val previous = values[(index - 1).coerceAtLeast(0)]
            values[index] to ((values[index] - previous) / 5.0)
        }
        SimulationScenario.HIGH -> (220 + (sequence.coerceAtMost(8) * 7).toInt()) to 2.4
        SimulationScenario.RAPID_FALL -> (160 - (sequence.coerceAtMost(15) * 16).toInt()).coerceAtLeast(45) to -3.2
        SimulationScenario.SIGNAL_LOSS -> if (sequence < 3) 110 to 0.0 else null
    }
}
