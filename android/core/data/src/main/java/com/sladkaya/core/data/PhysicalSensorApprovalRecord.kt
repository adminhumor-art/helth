package com.sladkaya.core.data

import com.sladkaya.core.model.SensorFamily
import java.security.MessageDigest

/**
 * Immutable evidence that one physical sensor/protocol/algorithm tuple passed the
 * separate physical-validation gate. This is deliberately not part of onboarding.
 */
data class PhysicalSensorApprovalRecord(
    val sensorId: String,
    val bluetoothAddress: String,
    val sensorFamily: SensorFamily,
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
    val schemaVersion: Int = SCHEMA_VERSION,
) {
    val approvalId: String = canonicalApprovalId()

    init {
        require(sensorId.isNotBlank() && sensorId.length <= MAX_SENSOR_ID_CHARS)
        require(CANONICAL_BLUETOOTH_ADDRESS.matches(bluetoothAddress))
        require(sensorFamily != SensorFamily.SIMULATOR)
        require(transportVariant >= 0)
        require(sensitivityToken.length == 8)
        require(sensitivityToken.all { it in '0'..'9' || it in 'A'..'Z' || it in 'a'..'z' })
        requireTypedValue(wireProfile)
        requireTypedValue(transportProtocol)
        requireTypedValue(transportCodecId)
        requireTypedValue(algorithmProfile)
        requireTypedValue(algorithmVersion)
        requireTypedValue(binarySetId)
        require(sensitivityTokenSource == "PACKAGE_CODE")
        require(sensitivityCoefficient in MIN_SENSITIVITY..MAX_SENSITIVITY)
        require(
            sensitivityEncoding == "NORMAL" && initializationMode == "STANDARD" ||
                sensitivityEncoding == "FACTION" && initializationMode == "FACTION",
        )
        require(displayOffsetMmolL.isFinite())
        requireTypedValue(protocolEvidenceKind)
        require(SHA256.matches(protocolEvidenceSha256))
        require(SHA256.matches(physicalValidationEvidenceSha256))
        require(checkpointSchemaVersion == CHECKPOINT_SCHEMA_VERSION)
        require(approvedSequence >= 1)
        require(approvedSensorTimeEpochMs > 0)
        require(sensorStartTimeEpochMs > 0)
        require(sensorStartTimeEpochMs <= approvedSensorTimeEpochMs)
        require(SHA256.matches(approvedCheckpointStateSha256))
        require(SHA256.matches(nativeBinarySetSha256))
        require(SHA256.matches(nativeDatahandleBinarySetSha256))
        require(approvedAtEpochMs > 0)
        require(schemaVersion == SCHEMA_VERSION)
    }

    private fun canonicalApprovalId(): String {
        val fields = listOf(
            sensorId,
            bluetoothAddress,
            sensorFamily.wireName,
            transportVariant.toString(),
            sensitivityToken,
            wireProfile,
            transportProtocol,
            transportCodecId,
            algorithmProfile,
            algorithmVersion,
            binarySetId,
            sensitivityTokenSource,
            java.lang.Double.toHexString(sensitivityCoefficient),
            sensitivityEncoding,
            initializationMode,
            java.lang.Double.toHexString(displayOffsetMmolL),
            protocolEvidenceKind,
            protocolEvidenceSha256,
            physicalValidationEvidenceSha256,
            checkpointSchemaVersion.toString(),
            approvedSequence.toString(),
            approvedSensorTimeEpochMs.toString(),
            sensorStartTimeEpochMs.toString(),
            approvedCheckpointStateSha256,
            nativeBinarySetSha256,
            nativeDatahandleBinarySetSha256,
            approvedAtEpochMs.toString(),
            schemaVersion.toString(),
        )
        val canonical = fields.joinToString(separator = "") { value ->
            "${value.encodeToByteArray().size}:$value"
        }
        return MessageDigest.getInstance("SHA-256")
            .digest(canonical.encodeToByteArray())
            .joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }
    }

    companion object {
        const val SCHEMA_VERSION = 1
        private const val MAX_SENSOR_ID_CHARS = 128
        private const val MAX_TYPED_VALUE_CHARS = 128
        private const val CHECKPOINT_SCHEMA_VERSION = 1
        private const val MIN_SENSITIVITY = 0.8
        private const val MAX_SENSITIVITY = 2.5
        private val CANONICAL_BLUETOOTH_ADDRESS = Regex("^(?:[0-9A-F]{2}:){5}[0-9A-F]{2}$")
        private val SHA256 = Regex("^[0-9a-f]{64}$")

        private fun requireTypedValue(value: String) {
            require(value.isNotBlank() && value.length <= MAX_TYPED_VALUE_CHARS)
        }

    }
}

sealed interface PhysicalSensorApprovalCommitResult {
    data object Approved : PhysicalSensorApprovalCommitResult
    data object AlreadyApproved : PhysicalSensorApprovalCommitResult
    data class Conflict(val reason: String) : PhysicalSensorApprovalCommitResult
}
