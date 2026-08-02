package com.sladkaya.core.data

import android.content.Context
import java.security.MessageDigest
import java.util.concurrent.CancellationException

/** Exact durable diagnostic point which may be promoted only by an explicit owner action. */
data class PhysicalSensorDiagnosticAnchor(
    val protocol: SensorProtocolBindingRecord,
    val raw: RawSensorSampleRecord,
    val result: SensorAlgorithmResultRecord,
    val checkpoint: SensorAlgorithmCheckpointRecord,
) {
    fun isEligibleForLocalPrototypeActivation(): Boolean {
        if (raw.eventId != result.eventId ||
            raw.sensorId != result.sensorId ||
            raw.sensorId != checkpoint.sensorId ||
            raw.sensorId != protocol.sensorId ||
            raw.sensorFamily != checkpoint.sensorFamily ||
            raw.sensorFamily != protocol.sensorFamily ||
            raw.transportVariant != checkpoint.transportVariant ||
            raw.transportVariant != protocol.transportVariant ||
            raw.sequence != result.sequence ||
            raw.sequence != checkpoint.sequence ||
            raw.sensorTimeEpochMs != result.sensorTimeEpochMs ||
            raw.sensorTimeEpochMs != checkpoint.sensorTimeEpochMs ||
            raw.historyDistance != 0 ||
            raw.phoneTimeEpochMs - raw.sensorTimeEpochMs !in 0 until MAX_REALTIME_AGE_MS ||
            result.algorithmErrorCode != null ||
            result.publishable ||
            result.alarmEligible ||
            result.publicationApprovalId != null ||
            checkpoint.publicationApprovalId != null
        ) return false
        if (protocol.bluetoothAddress != checkpoint.bluetoothAddress ||
            protocol.transportProtocol != checkpoint.transportProtocol ||
            protocol.transportCodecId != checkpoint.transportCodecId ||
            protocol.algorithmProfile != checkpoint.algorithmProfile ||
            protocol.sensitivityToken != checkpoint.sensitivityToken ||
            protocol.sensitivityEncoding != checkpoint.sensitivityEncoding ||
            result.algorithmProfile != checkpoint.algorithmProfile ||
            result.algorithmVersion != checkpoint.algorithmVersion ||
            result.binarySetId != checkpoint.binarySetId ||
            result.sensitivityToken != checkpoint.sensitivityToken ||
            result.sensitivityTokenSource != checkpoint.sensitivityTokenSource ||
            result.sensitivityCoefficient.toBits() != checkpoint.sensitivityCoefficient.toBits() ||
            result.sensitivityEncoding != checkpoint.sensitivityEncoding ||
            result.initializationMode != checkpoint.initializationMode
        ) return false
        return checkpoint.algorithmProfile != V116A_PROFILE ||
            checkpoint.sensorTimeEpochMs >
            checkpoint.sensorStartTimeEpochMs + V116A_WARMUP_MINUTES * MILLIS_PER_SAMPLE
    }

    private companion object {
        const val MAX_REALTIME_AGE_MS = 330_000L
        const val MILLIS_PER_SAMPLE = 60_000L
        const val V116A_WARMUP_MINUTES = 45L
        const val V116A_PROFILE = "V116A"
    }
}

data class PhysicalSensorActivationCommand(
    val diagnosticEventId: String,
    val approval: PhysicalSensorApprovalRecord,
    val publicationBindingId: String,
    val expectedPreviousPublicationBindingId: String? = null,
) {
    init {
        require(diagnosticEventId.isNotBlank() && diagnosticEventId.length <= 128)
        require(SHA256.matches(publicationBindingId))
        require(expectedPreviousPublicationBindingId == null ||
            SHA256.matches(expectedPreviousPublicationBindingId))
    }
}

sealed interface PhysicalSensorActivationCommitResult {
    data object Activated : PhysicalSensorActivationCommitResult
    data object AlreadyActive : PhysicalSensorActivationCommitResult
    data class Conflict(val reason: String) : PhysicalSensorActivationCommitResult
    data class StorageUnavailable(val detail: String? = null) :
        PhysicalSensorActivationCommitResult
}

interface PhysicalSensorActivationStore {
    suspend fun diagnosticAnchor(eventId: String): PhysicalSensorDiagnosticAnchor?

    suspend fun approveAndActivate(
        command: PhysicalSensorActivationCommand,
    ): PhysicalSensorActivationCommitResult
}

/** Room adapter. Approval and local activation cross one transaction in [SensorCoreDao]. */
class PhysicalSensorActivationRepository internal constructor(
    private val dao: SensorCoreDao,
) : PhysicalSensorActivationStore {
    override suspend fun diagnosticAnchor(eventId: String): PhysicalSensorDiagnosticAnchor? {
        require(eventId.isNotBlank() && eventId.length <= 128)
        val raw = dao.rawByEvent(eventId)?.toPhysicalActivationRecord() ?: return null
        val result = dao.resultByEvent(eventId)?.toPhysicalActivationRecord() ?: return null
        val checkpoint = dao.checkpoint(raw.sensorId)?.toRecord() ?: return null
        val protocol = dao.protocolBinding(raw.sensorId)?.toRecord() ?: return null
        return PhysicalSensorDiagnosticAnchor(protocol, raw, result, checkpoint)
    }

    override suspend fun approveAndActivate(
        command: PhysicalSensorActivationCommand,
    ): PhysicalSensorActivationCommitResult = try {
        when (
            dao.approveAndActivatePhysicalSensor(
                diagnosticEventId = command.diagnosticEventId,
                approval = command.approval.toEntity(),
                publicationBindingId = command.publicationBindingId,
                expectedPreviousPublicationBindingId =
                    command.expectedPreviousPublicationBindingId,
            )
        ) {
            SensorCoreCommitDisposition.COMMITTED ->
                PhysicalSensorActivationCommitResult.Activated
            SensorCoreCommitDisposition.ALREADY_COMMITTED ->
                PhysicalSensorActivationCommitResult.AlreadyActive
        }
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (conflict: SensorCoreConflictException) {
        PhysicalSensorActivationCommitResult.Conflict(
            conflict.message?.takeIf(String::isNotBlank) ?: "Physical sensor activation conflict",
        )
    } catch (failure: Exception) {
        PhysicalSensorActivationCommitResult.StorageUnavailable(failure.message)
    }

    companion object {
        fun create(context: Context): PhysicalSensorActivationStore =
            PhysicalSensorActivationRepository(
                SladkayaDatabase.get(context.applicationContext).sensorCore(),
            )
    }
}

object PhysicalSensorActivationIdentity {
    fun localPrototypeApproval(
        anchor: PhysicalSensorDiagnosticAnchor,
        nativeBinarySetSha256: String,
        nativeDatahandleBinarySetSha256: String,
    ): PhysicalSensorApprovalRecord {
        require(anchor.isEligibleForLocalPrototypeActivation())
        return PhysicalSensorApprovalRecord(
            sensorId = anchor.protocol.sensorId,
            bluetoothAddress = anchor.protocol.bluetoothAddress,
            sensorFamily = anchor.protocol.sensorFamily,
            transportVariant = anchor.protocol.transportVariant,
            sensitivityToken = anchor.protocol.sensitivityToken,
            wireProfile = anchor.protocol.wireProfile,
            transportProtocol = anchor.protocol.transportProtocol,
            transportCodecId = anchor.protocol.transportCodecId,
            algorithmProfile = anchor.checkpoint.algorithmProfile,
            algorithmVersion = anchor.checkpoint.algorithmVersion,
            binarySetId = anchor.checkpoint.binarySetId,
            sensitivityTokenSource = anchor.checkpoint.sensitivityTokenSource,
            sensitivityCoefficient = anchor.checkpoint.sensitivityCoefficient,
            sensitivityEncoding = anchor.checkpoint.sensitivityEncoding,
            initializationMode = anchor.checkpoint.initializationMode,
            displayOffsetMmolL = anchor.checkpoint.displayOffsetMmolL,
            protocolEvidenceKind = anchor.protocol.evidenceKind,
            protocolEvidenceSha256 = anchor.protocol.evidenceSha256,
            physicalValidationEvidenceSha256 = localPrototypeEvidenceSha256(
                anchor,
                nativeBinarySetSha256,
                nativeDatahandleBinarySetSha256,
            ),
            checkpointSchemaVersion = anchor.checkpoint.schemaVersion,
            approvedSequence = anchor.checkpoint.sequence,
            approvedSensorTimeEpochMs = anchor.checkpoint.sensorTimeEpochMs,
            sensorStartTimeEpochMs = anchor.checkpoint.sensorStartTimeEpochMs,
            approvedCheckpointStateSha256 = anchor.checkpoint.stateSha256,
            nativeBinarySetSha256 = nativeBinarySetSha256,
            nativeDatahandleBinarySetSha256 = nativeDatahandleBinarySetSha256,
            // The exact committed receive time makes retries deterministic. The explicit
            // owner confirmation is the UI action which invokes the atomic transaction.
            approvedAtEpochMs = anchor.raw.phoneTimeEpochMs,
        )
    }

    fun localPrototypeEvidenceSha256(
        anchor: PhysicalSensorDiagnosticAnchor,
        nativeBinarySetSha256: String,
        nativeDatahandleBinarySetSha256: String,
    ): String {
        require(SHA256.matches(nativeBinarySetSha256))
        require(SHA256.matches(nativeDatahandleBinarySetSha256))
        return canonicalSha256(
            listOf(
                LOCAL_PROTOTYPE_EVIDENCE_V1,
                anchor.raw.eventId,
                anchor.raw.packetSha256,
                anchor.protocol.evidenceKind,
                anchor.protocol.evidenceSha256,
                anchor.checkpoint.stateSha256,
                anchor.result.algorithmVersion,
                anchor.result.binarySetId,
                nativeBinarySetSha256,
                nativeDatahandleBinarySetSha256,
            ),
        )
    }

    fun localPublicationBindingId(approvalId: String): String {
        require(SHA256.matches(approvalId))
        return canonicalSha256(listOf(LOCAL_PUBLICATION_BINDING_V1, approvalId))
    }

    private fun canonicalSha256(fields: List<String>): String {
        val canonical = fields.joinToString(separator = "") { value ->
            "${value.encodeToByteArray().size}:$value"
        }
        return MessageDigest.getInstance("SHA-256")
            .digest(canonical.encodeToByteArray())
            .joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }
    }

    private const val LOCAL_PROTOTYPE_EVIDENCE_V1 =
        "SLADKAYA_EXACT_LOCAL_PROTOTYPE_ACTIVATION_EVIDENCE_V1"
    private const val LOCAL_PUBLICATION_BINDING_V1 =
        "SLADKAYA_STABLE_LOCAL_SENSOR_BINDING_V1"
}

private val SHA256 = Regex("^[0-9a-f]{64}$")

internal fun RawSensorSampleEntity.toPhysicalActivationRecord() = RawSensorSampleRecord(
    eventId = eventId,
    sourceIngressId = sourceIngressId,
    sensorId = sensorId,
    sensorFamily = com.sladkaya.core.model.SensorFamily.entries.firstOrNull {
        it.wireName == sensorFamily
    } ?: throw IllegalStateException("Stored diagnostic sensor family is unsupported"),
    sequence = sequence,
    sensorTimeEpochMs = sensorTimeEpochMs,
    phoneTimeEpochMs = phoneTimeEpochMs,
    packet = packet,
    packetSha256 = packetSha256,
    currentRaw = currentRaw,
    temperatureRaw = temperatureRaw,
    historyDistance = historyDistance,
    transportVariant = transportVariant,
    sensorTimeWasClamped = sensorTimeWasClamped,
    addTimeSeconds = addTimeSeconds,
)

internal fun SensorAlgorithmResultEntity.toPhysicalActivationRecord() =
    SensorAlgorithmResultRecord(
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
        publicationApprovalId = publicationApprovalId,
    )
