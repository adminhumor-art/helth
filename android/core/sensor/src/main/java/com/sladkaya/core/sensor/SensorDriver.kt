package com.sladkaya.core.sensor

import com.sladkaya.core.model.GlucoseReading
import com.sladkaya.core.model.SensorFamily
import kotlinx.coroutines.flow.StateFlow

interface SensorDriver {
    val family: SensorFamily
    val state: StateFlow<SensorDriverState>
    val readings: StateFlow<GlucoseReading?>

    suspend fun start(configuration: SensorConfiguration)
    suspend fun stop()
}

data class SensorConfiguration(
    val sensorId: String,
    val bluetoothAddress: String? = null,
    val pairingPayload: ByteArray? = null,
    val protocolVariant: Int? = null,
)

sealed interface SensorDriverState {
    data object Idle : SensorDriverState
    data object Scanning : SensorDriverState
    data class Connecting(val deviceName: String?) : SensorDriverState
    data object Authenticating : SensorDriverState
    data object Streaming : SensorDriverState
    data class WaitingForData(val sinceEpochMs: Long) : SensorDriverState
    data class Failure(val message: String, val retryable: Boolean) : SensorDriverState
}
