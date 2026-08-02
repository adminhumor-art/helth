package com.sladkaya.core.data

import android.content.Context

sealed interface SensorCoreCommitResult {
    data object Committed : SensorCoreCommitResult
    data object AlreadyCommitted : SensorCoreCommitResult
    data class Conflict(val reason: String) : SensorCoreCommitResult
}

interface SensorCoreStore {
    suspend fun bindProtocol(record: SensorProtocolBindingRecord): SensorProtocolBindingCommitResult
    suspend fun protocolBinding(sensorId: String): SensorProtocolBindingRecord?
    suspend fun protocolBindingByBluetoothAddress(
        bluetoothAddress: String,
    ): SensorProtocolBindingRecord?
    suspend fun commit(record: AtomicSensorCoreRecord): SensorCoreCommitResult
    suspend fun checkpoint(sensorId: String): SensorAlgorithmCheckpointRecord?
    suspend fun checkpointByBluetoothAddress(
        bluetoothAddress: String,
    ): SensorAlgorithmCheckpointRecord?
    suspend fun recordFailure(record: SensorIngestionFailureRecord): SensorFailureCommitResult
}

class SensorCoreRepository private constructor(
    private val dao: SensorCoreDao,
) : SensorCoreStore {
    override suspend fun bindProtocol(
        record: SensorProtocolBindingRecord,
    ): SensorProtocolBindingCommitResult = try {
        when (dao.bindProtocol(record.toEntity())) {
            SensorCoreCommitDisposition.COMMITTED -> SensorProtocolBindingCommitResult.Bound
            SensorCoreCommitDisposition.ALREADY_COMMITTED -> SensorProtocolBindingCommitResult.AlreadyBound
        }
    } catch (conflict: SensorCoreConflictException) {
        SensorProtocolBindingCommitResult.Conflict(
            conflict.message ?: "Protocol binding conflicts with stored evidence",
        )
    }

    override suspend fun protocolBinding(sensorId: String): SensorProtocolBindingRecord? {
        require(sensorId.isNotBlank())
        return dao.protocolBinding(sensorId)?.toRecord()
    }

    override suspend fun protocolBindingByBluetoothAddress(
        bluetoothAddress: String,
    ): SensorProtocolBindingRecord? {
        require(CANONICAL_BLUETOOTH_ADDRESS.matches(bluetoothAddress))
        return dao.protocolBindingByBluetoothAddress(bluetoothAddress)?.toRecord()
    }

    override suspend fun commit(record: AtomicSensorCoreRecord): SensorCoreCommitResult = try {
        when (dao.commit(record.toEntityBundle())) {
            SensorCoreCommitDisposition.COMMITTED -> SensorCoreCommitResult.Committed
            SensorCoreCommitDisposition.ALREADY_COMMITTED -> SensorCoreCommitResult.AlreadyCommitted
        }
    } catch (conflict: SensorCoreConflictException) {
        SensorCoreCommitResult.Conflict(conflict.message ?: "Sensor core record conflicts with stored state")
    }

    override suspend fun checkpoint(sensorId: String): SensorAlgorithmCheckpointRecord? {
        require(sensorId.isNotBlank())
        return dao.checkpoint(sensorId)?.toRecord()
    }

    override suspend fun checkpointByBluetoothAddress(
        bluetoothAddress: String,
    ): SensorAlgorithmCheckpointRecord? {
        require(CANONICAL_BLUETOOTH_ADDRESS.matches(bluetoothAddress))
        return dao.checkpointByBluetoothAddress(bluetoothAddress)?.toRecord()
    }

    override suspend fun recordFailure(record: SensorIngestionFailureRecord): SensorFailureCommitResult = try {
        when (dao.recordFailure(record.toEntity())) {
            SensorCoreCommitDisposition.COMMITTED -> SensorFailureCommitResult.Committed
            SensorCoreCommitDisposition.ALREADY_COMMITTED -> SensorFailureCommitResult.AlreadyCommitted
        }
    } catch (conflict: SensorCoreConflictException) {
        SensorFailureCommitResult.Conflict(
            conflict.message ?: "Sensor ingestion failure conflicts with stored evidence",
        )
    }

    companion object {
        fun create(context: Context): SensorCoreRepository =
            SensorCoreRepository(SladkayaDatabase.get(context).sensorCore())
    }
}

private val CANONICAL_BLUETOOTH_ADDRESS = Regex("^(?:[0-9A-F]{2}:){5}[0-9A-F]{2}$")
