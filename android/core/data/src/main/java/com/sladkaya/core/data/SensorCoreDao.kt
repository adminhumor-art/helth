package com.sladkaya.core.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Upsert

@Dao
internal abstract class SensorCoreDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    abstract suspend fun insertProtocolBinding(value: SensorProtocolBindingEntity): Long

    @Query("SELECT * FROM sensor_protocol_bindings WHERE sensorId = :sensorId LIMIT 1")
    abstract suspend fun protocolBinding(sensorId: String): SensorProtocolBindingEntity?

    @Query(
        "SELECT * FROM sensor_protocol_bindings " +
            "WHERE bluetoothAddress = :bluetoothAddress LIMIT 1",
    )
    abstract suspend fun protocolBindingByBluetoothAddress(
        bluetoothAddress: String,
    ): SensorProtocolBindingEntity?

    @Transaction
    open suspend fun bindProtocol(value: SensorProtocolBindingEntity): SensorCoreCommitDisposition {
        val physical = protocolBindingByBluetoothAddress(value.bluetoothAddress)
        if (physical != null && physical.sensorId != value.sensorId) {
            conflict("Bluetooth address is already bound to another protocol identity")
        }
        if (insertProtocolBinding(value) != INSERT_IGNORED) {
            return SensorCoreCommitDisposition.COMMITTED
        }
        if (protocolBinding(value.sensorId) == value) {
            return SensorCoreCommitDisposition.ALREADY_COMMITTED
        }
        conflict("Protocol binding is immutable and conflicts with stored evidence")
    }

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    abstract suspend fun insertRaw(value: RawSensorSampleEntity): Long

    @Query("SELECT * FROM sensor_raw_samples WHERE eventId = :eventId LIMIT 1")
    abstract suspend fun rawByEvent(eventId: String): RawSensorSampleEntity?

    @Query("SELECT * FROM sensor_raw_samples WHERE sensorId = :sensorId AND sequence = :sequence LIMIT 1")
    abstract suspend fun rawBySequence(sensorId: String, sequence: Int): RawSensorSampleEntity?

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    abstract suspend fun insertResult(value: SensorAlgorithmResultEntity): Long

    @Query("SELECT * FROM sensor_algorithm_results WHERE eventId = :eventId LIMIT 1")
    abstract suspend fun resultByEvent(eventId: String): SensorAlgorithmResultEntity?

    @Query("SELECT * FROM sensor_algorithm_results WHERE sensorId = :sensorId AND sequence = :sequence LIMIT 1")
    abstract suspend fun resultBySequence(sensorId: String, sequence: Int): SensorAlgorithmResultEntity?

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    abstract suspend fun insertMeasurement(value: MeasurementEntity): Long

    @Query("SELECT * FROM measurements WHERE eventId = :eventId LIMIT 1")
    abstract suspend fun measurement(eventId: String): MeasurementEntity?

    @Upsert
    abstract suspend fun replaceCheckpoint(value: SensorAlgorithmCheckpointEntity)

    @Query("SELECT * FROM sensor_algorithm_checkpoints WHERE sensorId = :sensorId LIMIT 1")
    abstract suspend fun checkpoint(sensorId: String): SensorAlgorithmCheckpointEntity?

    @Query(
        "SELECT * FROM sensor_algorithm_checkpoints " +
            "WHERE bluetoothAddress = :bluetoothAddress LIMIT 1",
    )
    abstract suspend fun checkpointByBluetoothAddress(
        bluetoothAddress: String,
    ): SensorAlgorithmCheckpointEntity?

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    abstract suspend fun insertFailure(value: SensorIngestionFailureEntity): Long

    @Query("SELECT * FROM sensor_ingestion_failures WHERE failureId = :failureId LIMIT 1")
    abstract suspend fun failure(failureId: String): SensorIngestionFailureEntity?

    @Transaction
    open suspend fun recordFailure(value: SensorIngestionFailureEntity): SensorCoreCommitDisposition {
        if (insertFailure(value) != INSERT_IGNORED) return SensorCoreCommitDisposition.COMMITTED
        if (failure(value.failureId)?.hasSameCauseAs(value) == true) {
            return SensorCoreCommitDisposition.ALREADY_COMMITTED
        }
        conflict("Ingestion failure ID conflicts with different contents")
    }

    @Transaction
    open suspend fun commit(value: SensorCoreEntityBundle): SensorCoreCommitDisposition {
        val protocolBinding = protocolBinding(value.checkpoint.sensorId)
            ?: conflict("Protocol must be durably bound before the first core commit")
        val physicalProtocolBinding = protocolBindingByBluetoothAddress(
            value.checkpoint.bluetoothAddress,
        )
        if (physicalProtocolBinding != protocolBinding ||
            !protocolBinding.matchesCheckpoint(value.checkpoint)
        ) {
            conflict("Core checkpoint does not match the durable protocol binding")
        }
        val savedCheckpoint = checkpoint(value.checkpoint.sensorId)
        val physicalCheckpoint = checkpointByBluetoothAddress(value.checkpoint.bluetoothAddress)
        when {
            physicalCheckpoint != null && physicalCheckpoint.sensorId != value.checkpoint.sensorId ->
                conflict("Bluetooth address is already bound to another sensor")
            savedCheckpoint == null && value.checkpoint.sequence != FIRST_SENSOR_INDEX ->
                conflict("A new checkpoint must start at sensor index $FIRST_SENSOR_INDEX")
            savedCheckpoint == null -> Unit
            savedCheckpoint.sequence > value.checkpoint.sequence -> conflict("Checkpoint regression")
            savedCheckpoint.sequence == value.checkpoint.sequence && !savedCheckpoint.sameAs(value.checkpoint) ->
                conflict("Checkpoint contents differ at the same sequence")
            savedCheckpoint.sequence < value.checkpoint.sequence &&
                !savedCheckpoint.hasSameImmutableProvenanceAs(value.checkpoint) ->
                conflict("Checkpoint provenance changed within an active sensor session")
            savedCheckpoint.sequence < value.checkpoint.sequence &&
                value.checkpoint.sequence != savedCheckpoint.sequence + 1 ->
                conflict("Checkpoint sequence gap")
            savedCheckpoint.sequence < value.checkpoint.sequence &&
                !savedCheckpoint.acceptsNextSensorTime(value.checkpoint) ->
                conflict("Checkpoint sensor time violates its transport contract")
        }

        var wroteAnything = false
        if (insertRaw(value.raw) == INSERT_IGNORED) {
            val byEvent = rawByEvent(value.raw.eventId)
            val bySequence = rawBySequence(value.raw.sensorId, value.raw.sequence)
            if (byEvent?.sameAs(value.raw) != true || bySequence?.sameAs(value.raw) != true) {
                conflict("Raw sample conflicts with an existing identity")
            }
        } else {
            wroteAnything = true
        }

        if (insertResult(value.result) == INSERT_IGNORED) {
            val byEvent = resultByEvent(value.result.eventId)
            val bySequence = resultBySequence(value.result.sensorId, value.result.sequence)
            if (byEvent != value.result || bySequence != value.result) {
                conflict("Algorithm result conflicts with an existing identity")
            }
        } else {
            wroteAnything = true
        }

        val incomingMeasurement = value.measurement
        if (incomingMeasurement == null) {
            if (measurement(value.raw.eventId) != null) {
                conflict("A non-publishable result conflicts with an existing measurement")
            }
        } else if (insertMeasurement(incomingMeasurement) == INSERT_IGNORED) {
            if (measurement(incomingMeasurement.eventId)?.hasSameMedicalDataAs(incomingMeasurement) != true) {
                conflict("Measurement conflicts with an existing event")
            }
        } else {
            wroteAnything = true
        }

        if (savedCheckpoint == null || savedCheckpoint.sequence < value.checkpoint.sequence) {
            replaceCheckpoint(value.checkpoint)
            wroteAnything = true
        }

        return if (wroteAnything) {
            SensorCoreCommitDisposition.COMMITTED
        } else {
            SensorCoreCommitDisposition.ALREADY_COMMITTED
        }
    }

    private fun conflict(message: String): Nothing = throw SensorCoreConflictException(message)

    private companion object {
        const val INSERT_IGNORED = -1L
        const val FIRST_SENSOR_INDEX = 1
        const val MILLIS_PER_SAMPLE = 60_000L
    }
}

internal enum class SensorCoreCommitDisposition {
    COMMITTED,
    ALREADY_COMMITTED,
}

internal class SensorCoreConflictException(message: String) : IllegalStateException(message)

private fun RawSensorSampleEntity.sameAs(other: RawSensorSampleEntity): Boolean =
    eventId == other.eventId &&
        sensorId == other.sensorId &&
        sensorFamily == other.sensorFamily &&
        sequence == other.sequence &&
        sensorTimeEpochMs == other.sensorTimeEpochMs &&
        phoneTimeEpochMs == other.phoneTimeEpochMs &&
        packet.contentEquals(other.packet) &&
        packetSha256 == other.packetSha256 &&
        currentRaw == other.currentRaw &&
        temperatureRaw == other.temperatureRaw &&
        historyDistance == other.historyDistance &&
        transportVariant == other.transportVariant &&
        sensorTimeWasClamped == other.sensorTimeWasClamped &&
        addTimeSeconds == other.addTimeSeconds

private fun SensorAlgorithmCheckpointEntity.acceptsNextSensorTime(
    next: SensorAlgorithmCheckpointEntity,
): Boolean = if (transportProtocol == "GS1_V115") {
    next.sensorTimeEpochMs >= sensorTimeEpochMs
} else {
    next.sensorTimeEpochMs == sensorTimeEpochMs + 60_000L
}

private fun SensorAlgorithmCheckpointEntity.sameAs(other: SensorAlgorithmCheckpointEntity): Boolean =
    sensorId == other.sensorId &&
        bluetoothAddress == other.bluetoothAddress &&
        sensorFamily == other.sensorFamily &&
        transportVariant == other.transportVariant &&
        transportProtocol == other.transportProtocol &&
        transportCodecId == other.transportCodecId &&
        sequence == other.sequence &&
        sensorTimeEpochMs == other.sensorTimeEpochMs &&
        algorithmProfile == other.algorithmProfile &&
        algorithmVersion == other.algorithmVersion &&
        binarySetId == other.binarySetId &&
        sensitivityToken == other.sensitivityToken &&
        sensitivityTokenSource == other.sensitivityTokenSource &&
        sensitivityCoefficient == other.sensitivityCoefficient &&
        sensitivityEncoding == other.sensitivityEncoding &&
        initializationMode == other.initializationMode &&
        state.contentEquals(other.state) &&
        stateSha256 == other.stateSha256 &&
        displayOffsetMmolL == other.displayOffsetMmolL &&
        schemaVersion == other.schemaVersion

private fun SensorAlgorithmCheckpointEntity.hasSameImmutableProvenanceAs(
    other: SensorAlgorithmCheckpointEntity,
): Boolean =
    sensorId == other.sensorId &&
        bluetoothAddress == other.bluetoothAddress &&
        sensorFamily == other.sensorFamily &&
        transportVariant == other.transportVariant &&
        transportProtocol == other.transportProtocol &&
        transportCodecId == other.transportCodecId &&
        algorithmProfile == other.algorithmProfile &&
        algorithmVersion == other.algorithmVersion &&
        binarySetId == other.binarySetId &&
        sensitivityToken == other.sensitivityToken &&
        sensitivityTokenSource == other.sensitivityTokenSource &&
        sensitivityCoefficient == other.sensitivityCoefficient &&
        sensitivityEncoding == other.sensitivityEncoding &&
        initializationMode == other.initializationMode &&
        schemaVersion == other.schemaVersion

private fun SensorProtocolBindingEntity.matchesCheckpoint(
    checkpoint: SensorAlgorithmCheckpointEntity,
): Boolean = sensorId == checkpoint.sensorId &&
    bluetoothAddress == checkpoint.bluetoothAddress &&
    sensorFamily == checkpoint.sensorFamily &&
    transportVariant == checkpoint.transportVariant &&
    sensitivityToken == checkpoint.sensitivityToken &&
    transportProtocol == checkpoint.transportProtocol &&
    transportCodecId == checkpoint.transportCodecId &&
    algorithmProfile == checkpoint.algorithmProfile &&
    sensitivityEncoding == checkpoint.sensitivityEncoding &&
    schemaVersion == checkpoint.schemaVersion

private fun MeasurementEntity.hasSameMedicalDataAs(other: MeasurementEntity): Boolean =
    eventId == other.eventId &&
        sensorId == other.sensorId &&
        sensorFamily == other.sensorFamily &&
        sensorTimeEpochMs == other.sensorTimeEpochMs &&
        phoneTimeEpochMs == other.phoneTimeEpochMs &&
        glucoseMgDl == other.glucoseMgDl &&
        trendMgDlPerMinute == other.trendMgDlPerMinute &&
        quality == other.quality &&
        sequence == other.sequence

private fun SensorIngestionFailureEntity.hasSameCauseAs(other: SensorIngestionFailureEntity): Boolean =
    failureId == other.failureId &&
        sensorId == other.sensorId &&
        sensorFamily == other.sensorFamily &&
        sequence == other.sequence &&
        reportedSensorTimeEpochSeconds == other.reportedSensorTimeEpochSeconds &&
        packet.contentEquals(other.packet) &&
        packetSha256 == other.packetSha256 &&
        currentRaw == other.currentRaw &&
        temperatureRaw == other.temperatureRaw &&
        historyDistance == other.historyDistance &&
        transportVariant == other.transportVariant &&
        failureCode == other.failureCode &&
        nativeStateMayHaveChanged == other.nativeStateMayHaveChanged
