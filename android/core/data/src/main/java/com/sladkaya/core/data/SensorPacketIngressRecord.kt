package com.sladkaya.core.data

import com.sladkaya.core.model.SensorFamily

/** Immutable evidence captured at the BLE boundary, before packet decoding. */
class SensorPacketIngressRecord(
    val ingressId: String,
    val sensorId: String,
    val sensorFamily: SensorFamily,
    val bluetoothAddress: String,
    val attemptId: String,
    val ordinal: Long,
    val receivedAtEpochMs: Long,
    encryptedPacket: ByteArray,
    val packetSha256: String,
) {
    private val encryptedPacket = encryptedPacket.copyOf()

    init {
        require(ingressId.isNotBlank() && ingressId.length <= MAX_ID_CHARS)
        require(sensorId.isNotBlank() && sensorId.length <= MAX_ID_CHARS)
        require(sensorFamily == SensorFamily.SIBIONICS_GS1 || sensorFamily == SensorFamily.SIBIONICS_GS1SB)
        require(CANONICAL_BLUETOOTH_ADDRESS.matches(bluetoothAddress))
        require(attemptId.isNotBlank() && attemptId.length <= MAX_ID_CHARS)
        require(ordinal >= 0)
        require(receivedAtEpochMs > 0)
        require(this.encryptedPacket.isNotEmpty())
        require(this.encryptedPacket.size <= MAX_ENCRYPTED_PACKET_BYTES)
        require(packetSha256 == this.encryptedPacket.sha256())
    }

    fun encryptedPacketCopy(): ByteArray = encryptedPacket.copyOf()

    private companion object {
        const val MAX_ID_CHARS = 128
        const val MAX_ENCRYPTED_PACKET_BYTES = 4_096
        val CANONICAL_BLUETOOTH_ADDRESS = Regex("^(?:[0-9A-F]{2}:){5}[0-9A-F]{2}$")
    }
}

sealed interface SensorPacketIngressAppendResult {
    data object Appended : SensorPacketIngressAppendResult
    data object AlreadyAppended : SensorPacketIngressAppendResult
    data class Conflict(val reason: String) : SensorPacketIngressAppendResult
}

enum class SensorPacketIngressOutcomeStatus {
    CORE_COMMITTED,
    QUARANTINED,
    NON_DATA,
    ALREADY_COVERED,
}

data class SensorPacketIngressOutcomeRecord(
    val ingressId: String,
    val status: SensorPacketIngressOutcomeStatus,
    val handledAtEpochMs: Long,
    val detail: String?,
) {
    init {
        require(ingressId.isNotBlank() && ingressId.length <= MAX_ID_CHARS)
        require(handledAtEpochMs > 0)
        require(detail == null || detail.length <= MAX_DETAIL_CHARS)
    }

    private companion object {
        const val MAX_ID_CHARS = 128
        const val MAX_DETAIL_CHARS = 512
    }
}

sealed interface SensorPacketIngressMarkHandledResult {
    data object MarkedHandled : SensorPacketIngressMarkHandledResult
    data object AlreadyHandled : SensorPacketIngressMarkHandledResult
    data class Conflict(val reason: String) : SensorPacketIngressMarkHandledResult
}

interface SensorPacketIngressJournal {
    suspend fun append(record: SensorPacketIngressRecord): SensorPacketIngressAppendResult
    suspend fun pending(sensorId: String, canonicalMac: String): List<SensorPacketIngressRecord>
    suspend fun markHandled(record: SensorPacketIngressOutcomeRecord): SensorPacketIngressMarkHandledResult
}
