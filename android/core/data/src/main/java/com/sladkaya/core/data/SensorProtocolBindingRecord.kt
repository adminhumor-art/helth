package com.sladkaya.core.data

import com.sladkaya.core.model.SensorFamily

data class SensorProtocolBindingRecord(
    val sensorId: String,
    val bluetoothAddress: String,
    val sensorFamily: SensorFamily,
    val transportVariant: Int,
    val sensitivityToken: String,
    val wireProfile: String,
    val transportProtocol: String,
    val transportCodecId: String,
    val algorithmProfile: String,
    val sensitivityEncoding: String,
    val evidenceKind: String,
    val evidenceSha256: String,
    val schemaVersion: Int,
) {
    init {
        require(sensorId.isNotBlank() && sensorId.length <= MAX_SENSOR_ID_CHARS)
        require(CANONICAL_BLUETOOTH_ADDRESS.matches(bluetoothAddress))
        require(sensorFamily != SensorFamily.SIMULATOR)
        require(transportVariant >= 0)
        require(sensitivityToken.length == SENSITIVITY_TOKEN_CHARS)
        require(sensitivityToken.all(Char::isAsciiLetterOrDigit))
        require(wireProfile.isNotBlank())
        require(transportProtocol.isNotBlank())
        require(transportCodecId.isNotBlank())
        require(algorithmProfile.isNotBlank())
        require(sensitivityEncoding in setOf("NORMAL", "FACTION"))
        require(evidenceKind.isNotBlank())
        require(SHA256.matches(evidenceSha256))
        require(schemaVersion == SCHEMA_VERSION)
    }

    companion object {
        const val SCHEMA_VERSION = 1
        private const val MAX_SENSOR_ID_CHARS = 128
        private const val SENSITIVITY_TOKEN_CHARS = 8
        private val CANONICAL_BLUETOOTH_ADDRESS = Regex("^(?:[0-9A-F]{2}:){5}[0-9A-F]{2}$")
        private val SHA256 = Regex("^[0-9a-f]{64}$")
    }
}

sealed interface SensorProtocolBindingCommitResult {
    data object Bound : SensorProtocolBindingCommitResult
    data object AlreadyBound : SensorProtocolBindingCommitResult
    data class Conflict(val reason: String) : SensorProtocolBindingCommitResult
}

private fun Char.isAsciiLetterOrDigit(): Boolean =
    this in '0'..'9' || this in 'A'..'Z' || this in 'a'..'z'
