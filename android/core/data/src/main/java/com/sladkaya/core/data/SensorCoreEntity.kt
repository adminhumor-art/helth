package com.sladkaya.core.data

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import com.sladkaya.core.model.SensorFamily

@Entity(
    tableName = "sensor_raw_samples",
    indices = [
        Index(value = ["sensorId", "sequence"], unique = true),
        Index(value = ["sourceIngressId", "sequence"]),
    ],
    foreignKeys = [
        ForeignKey(
            entity = SensorPacketIngressEntity::class,
            parentColumns = ["ingressId"],
            childColumns = ["sourceIngressId"],
            onDelete = ForeignKey.RESTRICT,
        ),
    ],
)
internal data class RawSensorSampleEntity(
    @PrimaryKey val eventId: String,
    val sourceIngressId: String,
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
    val publicationApprovalId: String? = null,
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
    val sensorStartTimeEpochMs: Long,
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
    val publicationApprovalId: String? = null,
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

@Entity(
    tableName = "physical_sensor_approvals",
    indices = [
        Index(value = ["sensorId"]),
        Index(value = ["bluetoothAddress"]),
    ],
)
internal data class PhysicalSensorApprovalEntity(
    @PrimaryKey val approvalId: String,
    val sensorId: String,
    val bluetoothAddress: String,
    val sensorFamily: String,
    val transportVariant: Int,
    val sensitivityToken: String,
    val wireProfile: String,
    val transportProtocol: String,
    val transportCodecId: String,
    val algorithmProfile: String,
    val algorithmVersion: String,
    val binarySetId: String,
    val sensitivityTokenSource: String,
    val sensitivityCoefficient: Double,
    val sensitivityEncoding: String,
    val initializationMode: String,
    val displayOffsetMmolL: Double,
    val protocolEvidenceKind: String,
    val protocolEvidenceSha256: String,
    val physicalValidationEvidenceSha256: String,
    val checkpointSchemaVersion: Int,
    val approvedSequence: Int,
    val approvedSensorTimeEpochMs: Long,
    val sensorStartTimeEpochMs: Long,
    val approvedCheckpointStateSha256: String,
    val nativeBinarySetSha256: String,
    val nativeDatahandleBinarySetSha256: String,
    val approvedAtEpochMs: Long,
    val schemaVersion: Int,
)

@Entity(tableName = "product_publication_bindings")
internal data class ProductPublicationBindingEntity(
    @PrimaryKey val publicationBindingId: String,
    val approvalId: String,
    val httpsOrigin: String,
    val backendBindingId: String,
    val credentialId: String,
    val credentialRevision: Long,
    val expectedPatientId: String,
    val expectedDeviceId: String,
    val createdAtEpochMs: Long,
    val schemaVersion: Int,
)

@Entity(tableName = "active_sensor_publication_binding")
internal data class ActiveSensorPublicationBindingEntity(
    @PrimaryKey val activeSlot: Int,
    val publicationBindingId: String,
)

@Entity(
    tableName = "measurement_upload_outbox",
    indices = [
        Index(value = ["eventId"], unique = true),
        Index(value = ["leaseToken"]),
        Index(value = ["state", "nextAttemptEpochMs", "outboxId"]),
    ],
)
internal data class UploadOutboxEntity(
    @PrimaryKey(autoGenerate = true) val outboxId: Long = 0,
    val eventId: String,
    val approvalId: String,
    val publicationBindingId: String,
    val httpsOrigin: String,
    val backendBindingId: String,
    val credentialId: String,
    val credentialRevision: Long,
    val expectedPatientId: String,
    val expectedDeviceId: String,
    val state: String,
    val attempts: Int,
    val enqueuedAtEpochMs: Long,
    val nextAttemptEpochMs: Long,
    val leaseToken: String?,
    val leaseExpiresAtEpochMs: Long?,
    val lastTransitionToken: String?,
    val sanitizedStatus: String?,
    val sanitizedDetail: String?,
) {
    companion object {
        fun pending(
            eventId: String,
            approvalId: String,
            publicationBindingId: String,
            httpsOrigin: String,
            backendBindingId: String,
            credentialId: String,
            credentialRevision: Long,
            expectedPatientId: String,
            expectedDeviceId: String,
            enqueuedAtEpochMs: Long,
        ) = UploadOutboxEntity(
            eventId = eventId,
            approvalId = approvalId,
            publicationBindingId = publicationBindingId,
            httpsOrigin = httpsOrigin,
            backendBindingId = backendBindingId,
            credentialId = credentialId,
            credentialRevision = credentialRevision,
            expectedPatientId = expectedPatientId,
            expectedDeviceId = expectedDeviceId,
            state = UploadOutboxState.PENDING.wireName,
            attempts = 0,
            enqueuedAtEpochMs = enqueuedAtEpochMs,
            nextAttemptEpochMs = enqueuedAtEpochMs,
            leaseToken = null,
            leaseExpiresAtEpochMs = null,
            lastTransitionToken = null,
            sanitizedStatus = null,
            sanitizedDetail = null,
        )
    }
}

internal data class UploadDeliveryReportEntity(
    val status: String,
    val detail: String?,
)

internal data class LeasedUploadEntityBundle(
    val outbox: UploadOutboxEntity,
    val measurement: MeasurementEntity,
)

internal data class SensorCoreEntityBundle(
    val raw: RawSensorSampleEntity,
    val result: SensorAlgorithmResultEntity,
    val checkpoint: SensorAlgorithmCheckpointEntity,
    val measurement: MeasurementEntity?,
    val publicationContext: ProductPublicationContext? = null,
    val approvedCheckpointContext: ApprovedCheckpointContext? =
        publicationContext?.approvedCheckpointContext(),
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
    sourceIngressId = sourceIngressId,
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

internal fun SensorAlgorithmResultRecord.toEntity(
    approvedCheckpointContext: ApprovedCheckpointContext? = null,
) = SensorAlgorithmResultEntity(
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
    publicationApprovalId = approvedCheckpointContext?.approvalId ?: publicationApprovalId,
)

internal fun SensorAlgorithmCheckpointRecord.toEntity(
    approvedCheckpointContext: ApprovedCheckpointContext? = null,
) = SensorAlgorithmCheckpointEntity(
    sensorId = sensorId,
    bluetoothAddress = bluetoothAddress,
    sensorFamily = sensorFamily.wireName,
    transportVariant = transportVariant,
    transportProtocol = transportProtocol,
    transportCodecId = transportCodecId,
    sequence = sequence,
    sensorTimeEpochMs = sensorTimeEpochMs,
    sensorStartTimeEpochMs = sensorStartTimeEpochMs,
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
    publicationApprovalId = approvedCheckpointContext?.approvalId ?: publicationApprovalId,
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
    sensorStartTimeEpochMs = sensorStartTimeEpochMs,
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
    publicationApprovalId = publicationApprovalId,
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

internal fun PhysicalSensorApprovalRecord.toEntity() = PhysicalSensorApprovalEntity(
    approvalId = approvalId,
    sensorId = sensorId,
    bluetoothAddress = bluetoothAddress,
    sensorFamily = sensorFamily.wireName,
    transportVariant = transportVariant,
    sensitivityToken = sensitivityToken,
    wireProfile = wireProfile,
    transportProtocol = transportProtocol,
    transportCodecId = transportCodecId,
    algorithmProfile = algorithmProfile,
    algorithmVersion = algorithmVersion,
    binarySetId = binarySetId,
    sensitivityTokenSource = sensitivityTokenSource,
    sensitivityCoefficient = sensitivityCoefficient,
    sensitivityEncoding = sensitivityEncoding,
    initializationMode = initializationMode,
    displayOffsetMmolL = displayOffsetMmolL,
    protocolEvidenceKind = protocolEvidenceKind,
    protocolEvidenceSha256 = protocolEvidenceSha256,
    physicalValidationEvidenceSha256 = physicalValidationEvidenceSha256,
    checkpointSchemaVersion = checkpointSchemaVersion,
    approvedSequence = approvedSequence,
    approvedSensorTimeEpochMs = approvedSensorTimeEpochMs,
    sensorStartTimeEpochMs = sensorStartTimeEpochMs,
    approvedCheckpointStateSha256 = approvedCheckpointStateSha256,
    nativeBinarySetSha256 = nativeBinarySetSha256,
    nativeDatahandleBinarySetSha256 = nativeDatahandleBinarySetSha256,
    approvedAtEpochMs = approvedAtEpochMs,
    schemaVersion = schemaVersion,
)

internal fun PhysicalSensorApprovalEntity.toRecord(): PhysicalSensorApprovalRecord =
    PhysicalSensorApprovalRecord(
    sensorId = sensorId,
    bluetoothAddress = bluetoothAddress,
    sensorFamily = SensorFamily.entries.first { it.wireName == sensorFamily },
    transportVariant = transportVariant,
    sensitivityToken = sensitivityToken,
    wireProfile = wireProfile,
    transportProtocol = transportProtocol,
    transportCodecId = transportCodecId,
    algorithmProfile = algorithmProfile,
    algorithmVersion = algorithmVersion,
    binarySetId = binarySetId,
    sensitivityTokenSource = sensitivityTokenSource,
    sensitivityCoefficient = sensitivityCoefficient,
    sensitivityEncoding = sensitivityEncoding,
    initializationMode = initializationMode,
    displayOffsetMmolL = displayOffsetMmolL,
    protocolEvidenceKind = protocolEvidenceKind,
    protocolEvidenceSha256 = protocolEvidenceSha256,
    physicalValidationEvidenceSha256 = physicalValidationEvidenceSha256,
    checkpointSchemaVersion = checkpointSchemaVersion,
    approvedSequence = approvedSequence,
    approvedSensorTimeEpochMs = approvedSensorTimeEpochMs,
    sensorStartTimeEpochMs = sensorStartTimeEpochMs,
    approvedCheckpointStateSha256 = approvedCheckpointStateSha256,
    nativeBinarySetSha256 = nativeBinarySetSha256,
    nativeDatahandleBinarySetSha256 = nativeDatahandleBinarySetSha256,
    approvedAtEpochMs = approvedAtEpochMs,
    schemaVersion = schemaVersion,
).also { record ->
        require(record.approvalId == approvalId) { "Stored physical approval identity is not canonical" }
    }

internal fun UploadOutboxEntity.toRecord() = UploadOutboxRecord(
    eventId = eventId,
    approvalId = approvalId,
    publicationBindingId = publicationBindingId,
    httpsOrigin = httpsOrigin,
    backendBindingId = backendBindingId,
    credentialId = credentialId,
    credentialRevision = credentialRevision,
    expectedPatientId = expectedPatientId,
    expectedDeviceId = expectedDeviceId,
    state = UploadOutboxState.entries.first { it.wireName == state },
    attempts = attempts,
    nextAttemptEpochMs = nextAttemptEpochMs,
    leaseToken = leaseToken,
    leaseExpiresAtEpochMs = leaseExpiresAtEpochMs,
    lastTransitionToken = lastTransitionToken,
    sanitizedStatus = sanitizedStatus?.let { value ->
        UploadDeliveryStatus.entries.first { it.wireName == value }
    },
    sanitizedDetail = sanitizedDetail,
)

internal fun UploadDeliveryReport.toEntity() = UploadDeliveryReportEntity(
    status = status.wireName,
    detail = detail,
)

internal fun ProductPublicationBindingRecord.toEntity() = ProductPublicationBindingEntity(
    publicationBindingId = publicationBindingId,
    approvalId = approvalId,
    httpsOrigin = httpsOrigin,
    backendBindingId = backendBindingId,
    credentialId = credentialId,
    credentialRevision = credentialRevision,
    expectedPatientId = expectedPatientId,
    expectedDeviceId = expectedDeviceId,
    createdAtEpochMs = createdAtEpochMs,
    schemaVersion = schemaVersion,
)

internal fun ProductPublicationBindingEntity.toRecord(): ProductPublicationBindingRecord =
    ProductPublicationBindingRecord(
        approvalId = approvalId,
        httpsOrigin = httpsOrigin,
        backendBindingId = backendBindingId,
        credentialId = credentialId,
        credentialRevision = credentialRevision,
        expectedPatientId = expectedPatientId,
        expectedDeviceId = expectedDeviceId,
        createdAtEpochMs = createdAtEpochMs,
        schemaVersion = schemaVersion,
    ).also { record ->
        require(record.publicationBindingId == publicationBindingId) {
            "Stored publication binding identity is not canonical"
        }
    }

internal const val ACTIVE_PUBLICATION_BINDING_SLOT = 1

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
