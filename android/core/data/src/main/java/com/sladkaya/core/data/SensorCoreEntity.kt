package com.sladkaya.core.data

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import com.sladkaya.core.model.SensorFamily

@Entity(
    tableName = "sensor_raw_samples",
    indices = [Index(value = ["sensorId", "sequence"], unique = true)],
)
internal data class RawSensorSampleEntity(
    @PrimaryKey val eventId: String,
    val sensorId: String,
    val sensorFamily: String,
    val sequence: Int,
    val sensorTimeEpochMs: Long,
    val phoneTimeEpochMs: Long,
    val packet: ByteArray,
    val packetSha256: String,
    val currentRaw: Int,
    val temperatureRaw: Int,
    val historyDistance: Int,
    val transportVariant: Int,
    val sensorTimeWasClamped: Boolean,
    val addTimeSeconds: Int?,
)

@Entity(
    tableName = "sensor_algorithm_results",
    indices = [Index(value = ["sensorId", "sequence"], unique = true)],
)
internal data class SensorAlgorithmResultEntity(
    @PrimaryKey val eventId: String,
    val sensorId: String,
    val sequence: Int,
    val sensorTimeEpochMs: Long,
    val nativeGlucoseMmolL: Double,
    val displayedGlucoseMmolL: Double,
    val nativeTrend: Int,
    val glucoseWarning: Int,
    val currentWarning: Int,
    val temperatureWarning: Int,
    val algorithmProfile: String,
    val algorithmVersion: String,
    val binarySetId: String,
    val sensitivityToken: String,
    val sensitivityTokenSource: String,
    val sensitivityCoefficient: Double,
    val sensitivityEncoding: String,
    val initializationMode: String,
    val publishable: Boolean,
    val alarmEligible: Boolean,
    val algorithmErrorCode: String?,
)

@Entity(tableName = "sensor_algorithm_checkpoints")
internal data class SensorAlgorithmCheckpointEntity(
    @PrimaryKey val sensorId: String,
    val bluetoothAddress: String,
    val sensorFamily: String,
    val transportVariant: Int,
    val transportProtocol: String,
    val transportCodecId: String,
    val sequence: Int,
    val sensorTimeEpochMs: Long,
    val algorithmProfile: String,
    val algorithmVersion: String,
    val binarySetId: String,
    val sensitivityToken: String,
    val sensitivityTokenSource: String,
    val sensitivityCoefficient: Double,
    val sensitivityEncoding: String,
    val initializationMode: String,
    val state: ByteArray,
    val stateSha256: String,
    val displayOffsetMmolL: Double,
    val schemaVersion: Int,
)

@Entity(
    tableName = "sensor_protocol_bindings",
    indices = [Index(value = ["bluetoothAddress"], unique = true)],
)
internal data class SensorProtocolBindingEntity(
    @PrimaryKey val sensorId: String,
    val bluetoothAddress: String,
    val sensorFamily: String,
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
)

internal data class SensorCoreEntityBundle(
    val raw: RawSensorSampleEntity,
    val result: SensorAlgorithmResultEntity,
    val checkpoint: SensorAlgorithmCheckpointEntity,
    val measurement: MeasurementEntity?,
)

@Entity(
    tableName = "sensor_ingestion_failures",
    indices = [Index(value = ["sensorId", "sequence"])],
)
internal data class SensorIngestionFailureEntity(
    @PrimaryKey val failureId: String,
    val sensorId: String,
    val sensorFamily: String,
    val sequence: Int,
    val reportedSensorTimeEpochSeconds: Long,
    val phoneTimeEpochMs: Long,
    val packet: ByteArray,
    val packetSha256: String,
    val currentRaw: Int,
    val temperatureRaw: Int,
    val historyDistance: Int,
    val transportVariant: Int,
    val failureCode: String,
    val failureMessage: String,
    val nativeStateMayHaveChanged: Boolean,
)

internal fun RawSensorSampleRecord.toEntity() = RawSensorSampleEntity(
    eventId = eventId,
    sensorId = sensorId,
    sensorFamily = sensorFamily.wireName,
    sequence = sequence,
    sensorTimeEpochMs = sensorTimeEpochMs,
    phoneTimeEpochMs = phoneTimeEpochMs,
    packet = packetCopy(),
    packetSha256 = packetSha256,
    currentRaw = currentRaw,
    temperatureRaw = temperatureRaw,
    historyDistance = historyDistance,
    transportVariant = transportVariant,
    sensorTimeWasClamped = sensorTimeWasClamped,
    addTimeSeconds = addTimeSeconds,
)

internal fun SensorAlgorithmResultRecord.toEntity() = SensorAlgorithmResultEntity(
    eventId = eventId,
    sensorId = sensorId,
    sequence = sequence,
    sensorTimeEpochMs = sensorTimeEpochMs,
    nativeGlucoseMmolL = nativeGlucoseMmolL,
    displayedGlucoseMmolL = displayedGlucoseMmolL,
    nativeTrend = nativeTrend,
    glucoseWarning = glucoseWarning,
    currentWarning = currentWarning,
    temperatureWarning = temperatureWarning,
    algorithmProfile = algorithmProfile,
    algorithmVersion = algorithmVersion,
    binarySetId = binarySetId,
    sensitivityToken = sensitivityToken,
    sensitivityTokenSource = sensitivityTokenSource,
    sensitivityCoefficient = sensitivityCoefficient,
    sensitivityEncoding = sensitivityEncoding,
    initializationMode = initializationMode,
    publishable = publishable,
    alarmEligible = alarmEligible,
    algorithmErrorCode = algorithmErrorCode,
)

internal fun SensorAlgorithmCheckpointRecord.toEntity() = SensorAlgorithmCheckpointEntity(
    sensorId = sensorId,
    bluetoothAddress = bluetoothAddress,
    sensorFamily = sensorFamily.wireName,
    transportVariant = transportVariant,
    transportProtocol = transportProtocol,
    transportCodecId = transportCodecId,
    sequence = sequence,
    sensorTimeEpochMs = sensorTimeEpochMs,
    algorithmProfile = algorithmProfile,
    algorithmVersion = algorithmVersion,
    binarySetId = binarySetId,
    sensitivityToken = sensitivityToken,
    sensitivityTokenSource = sensitivityTokenSource,
    sensitivityCoefficient = sensitivityCoefficient,
    sensitivityEncoding = sensitivityEncoding,
    initializationMode = initializationMode,
    state = stateCopy(),
    stateSha256 = stateSha256,
    displayOffsetMmolL = displayOffsetMmolL,
    schemaVersion = schemaVersion,
)

internal fun SensorAlgorithmCheckpointEntity.toRecord() = SensorAlgorithmCheckpointRecord(
    sensorId = sensorId,
    bluetoothAddress = bluetoothAddress,
    sensorFamily = SensorFamily.entries.first { it.wireName == sensorFamily },
    transportVariant = transportVariant,
    transportProtocol = transportProtocol,
    transportCodecId = transportCodecId,
    sequence = sequence,
    sensorTimeEpochMs = sensorTimeEpochMs,
    algorithmProfile = algorithmProfile,
    algorithmVersion = algorithmVersion,
    binarySetId = binarySetId,
    sensitivityToken = sensitivityToken,
    sensitivityTokenSource = sensitivityTokenSource,
    sensitivityCoefficient = sensitivityCoefficient,
    sensitivityEncoding = sensitivityEncoding,
    initializationMode = initializationMode,
    state = state.copyOf(),
    stateSha256 = stateSha256,
    displayOffsetMmolL = displayOffsetMmolL,
    schemaVersion = schemaVersion,
)

internal fun SensorProtocolBindingRecord.toEntity() = SensorProtocolBindingEntity(
    sensorId = sensorId,
    bluetoothAddress = bluetoothAddress,
    sensorFamily = sensorFamily.wireName,
    transportVariant = transportVariant,
    sensitivityToken = sensitivityToken,
    wireProfile = wireProfile,
    transportProtocol = transportProtocol,
    transportCodecId = transportCodecId,
    algorithmProfile = algorithmProfile,
    sensitivityEncoding = sensitivityEncoding,
    evidenceKind = evidenceKind,
    evidenceSha256 = evidenceSha256,
    schemaVersion = schemaVersion,
)

internal fun SensorProtocolBindingEntity.toRecord() = SensorProtocolBindingRecord(
    sensorId = sensorId,
    bluetoothAddress = bluetoothAddress,
    sensorFamily = SensorFamily.entries.first { it.wireName == sensorFamily },
    transportVariant = transportVariant,
    sensitivityToken = sensitivityToken,
    wireProfile = wireProfile,
    transportProtocol = transportProtocol,
    transportCodecId = transportCodecId,
    algorithmProfile = algorithmProfile,
    sensitivityEncoding = sensitivityEncoding,
    evidenceKind = evidenceKind,
    evidenceSha256 = evidenceSha256,
    schemaVersion = schemaVersion,
)

internal fun SensorIngestionFailureRecord.toEntity() = SensorIngestionFailureEntity(
    failureId = failureId,
    sensorId = sensorId,
    sensorFamily = sensorFamily.wireName,
    sequence = sequence,
    reportedSensorTimeEpochSeconds = reportedSensorTimeEpochSeconds,
    phoneTimeEpochMs = phoneTimeEpochMs,
    packet = packetCopy(),
    packetSha256 = packetSha256,
    currentRaw = currentRaw,
    temperatureRaw = temperatureRaw,
    historyDistance = historyDistance,
    transportVariant = transportVariant,
    failureCode = failureCode,
    failureMessage = failureMessage,
    nativeStateMayHaveChanged = nativeStateMayHaveChanged,
)
