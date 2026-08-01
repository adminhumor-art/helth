package com.sladkaya.core.data

import com.sladkaya.core.model.SensorFamily

class SensorIngestionFailureRecord(
    val failureId: String,
    val sensorId: String,
    val sensorFamily: SensorFamily,
    val sequence: Int,
    val reportedSensorTimeEpochSeconds: Long,
    val phoneTimeEpochMs: Long,
    packet: ByteArray,
    val packetSha256: String,
    val currentRaw: Int,
    val temperatureRaw: Int,
    val historyDistance: Int,
    val transportVariant: Int,
    val failureCode: String,
    val failureMessage: String,
    val nativeStateMayHaveChanged: Boolean,
) {
    private val packet = packet.copyOf()

    init {
        require(failureId.isNotBlank())
        require(sensorId.isNotBlank() && sensorId.length <= 128)
        require(sensorFamily != SensorFamily.SIMULATOR)
        // Zero is reserved for the narrow case where reading the phone clock
        // itself failed. The failure must still be journalled without inventing
        // a timestamp.
        require(phoneTimeEpochMs >= 0)
        require(packet.size <= MAX_FAILURE_PACKET_BYTES)
        require(packetSha256 == this.packet.sha256())
        require(transportVariant >= 0)
        require(failureCode.isNotBlank() && failureCode.length <= 128)
        require(failureMessage.isNotBlank() && failureMessage.length <= MAX_FAILURE_MESSAGE_CHARS)
    }

    fun packetCopy(): ByteArray = packet.copyOf()

    private companion object {
        const val MAX_FAILURE_PACKET_BYTES = 4_096
        const val MAX_FAILURE_MESSAGE_CHARS = 1_024
    }
}

sealed interface SensorFailureCommitResult {
    data object Committed : SensorFailureCommitResult
    data object AlreadyCommitted : SensorFailureCommitResult
    data class Conflict(val reason: String) : SensorFailureCommitResult
}
