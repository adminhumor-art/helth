package com.sladkaya.core.data

import com.sladkaya.core.model.GlucoseReading
import com.sladkaya.core.model.ReadingQuality
import com.sladkaya.core.model.SensorFamily
import java.security.MessageDigest
import kotlin.math.roundToInt

class RawSensorSampleRecord(
    val eventId: String,
    val sourceIngressId: String,
    val sensorId: String,
    val sensorFamily: SensorFamily,
    val sequence: Int,
    val sensorTimeEpochMs: Long,
    val phoneTimeEpochMs: Long,
    packet: ByteArray,
    val packetSha256: String,
    val currentRaw: Int,
    val temperatureRaw: Int,
    val historyDistance: Int,
    val transportVariant: Int,
    val sensorTimeWasClamped: Boolean = false,
    val addTimeSeconds: Int? = null,
) {
    private val packet = packet.copyOf()

    init {
        require(eventId.isNotBlank())
        require(sourceIngressId.isNotBlank() && sourceIngressId.length <= 128)
        require(sensorId.isNotBlank() && sensorId.length <= 128)
        require(sequence >= 0)
        require(sensorTimeEpochMs > 0)
        require(phoneTimeEpochMs > 0)
        require(packet.isNotEmpty())
        require(packet.size <= MAX_PACKET_BYTES)
        require(historyDistance >= 0)
        require(transportVariant >= 0)
        require(addTimeSeconds == null || addTimeSeconds in 0..0xffff)
        require(!sensorTimeWasClamped || addTimeSeconds != null)
        require(packetSha256 == this.packet.sha256())
    }

    fun packetCopy(): ByteArray = packet.copyOf()

    private companion object {
        const val MAX_PACKET_BYTES = 250
    }
}

data class SensorAlgorithmResultRecord(
    val eventId: String,
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
    val alarmEligible: Boolean = publishable,
    val algorithmErrorCode: String? = null,
    val publicationApprovalId: String? = null,
) {
    init {
        require(eventId.isNotBlank())
        require(sensorId.isNotBlank() && sensorId.length <= 128)
        require(sequence >= 0)
        require(sensorTimeEpochMs > 0)
        require(nativeGlucoseMmolL.isFinite())
        require(displayedGlucoseMmolL.isFinite())
        require(algorithmProfile.isNotBlank())
        require(algorithmVersion.isNotBlank())
        require(binarySetId.isNotBlank())
        requireSensitivityToken(sensitivityToken)
        require(sensitivityTokenSource in TOKEN_SOURCES)
        require(sensitivityCoefficient in MIN_SENSITIVITY..MAX_SENSITIVITY)
        require(sensitivityEncoding in SENSITIVITY_ENCODINGS)
        require(initializationMode in INITIALIZATION_MODES)
        require(!alarmEligible || publishable)
        require(algorithmErrorCode == null || algorithmErrorCode.isNotBlank() && !publishable)
        require(publicationApprovalId == null || SHA256.matches(publicationApprovalId))
    }
}

class SensorAlgorithmCheckpointRecord(
    val sensorId: String,
    val bluetoothAddress: String,
    val sensorFamily: SensorFamily,
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
    state: ByteArray,
    val stateSha256: String,
    val displayOffsetMmolL: Double,
    val schemaVersion: Int,
    val publicationApprovalId: String? = null,
) {
    private val state = state.copyOf()

    init {
        require(sensorId.isNotBlank() && sensorId.length <= 128)
        require(CANONICAL_BLUETOOTH_ADDRESS.matches(bluetoothAddress))
        require(sensorFamily != SensorFamily.SIMULATOR)
        require(transportVariant >= 0)
        require(transportProtocol.isNotBlank())
        require(transportCodecId.isNotBlank())
        require(sequence >= 0)
        require(sensorTimeEpochMs > 0)
        require(sensorStartTimeEpochMs > 0)
        require(sensorStartTimeEpochMs <= sensorTimeEpochMs)
        require(algorithmProfile.isNotBlank())
        if (algorithmProfile == "V116A") {
            require(
                sensorTimeEpochMs - sensorStartTimeEpochMs ==
                    sequence.toLong() * MILLIS_PER_SENSOR_SAMPLE,
            ) { "V116A checkpoint time does not match its durable sensor start" }
        }
        require(algorithmVersion.isNotBlank())
        require(binarySetId.isNotBlank())
        requireSensitivityToken(sensitivityToken)
        require(sensitivityTokenSource in TOKEN_SOURCES)
        require(sensitivityCoefficient in MIN_SENSITIVITY..MAX_SENSITIVITY)
        require(sensitivityEncoding in SENSITIVITY_ENCODINGS)
        require(initializationMode in INITIALIZATION_MODES)
        val expectedStateSize = ALGORITHM_STATE_SIZES[algorithmProfile]
            ?: throw IllegalArgumentException("Unsupported algorithm profile")
        require(state.size == expectedStateSize)
        require(stateSha256 == this.state.sha256())
        require(displayOffsetMmolL.isFinite())
        require(schemaVersion == CHECKPOINT_SCHEMA_VERSION)
        require(publicationApprovalId == null || SHA256.matches(publicationApprovalId))
    }

    fun stateCopy(): ByteArray = state.copyOf()

    fun copy(
        sensorId: String = this.sensorId,
        bluetoothAddress: String = this.bluetoothAddress,
        sensorFamily: SensorFamily = this.sensorFamily,
        transportVariant: Int = this.transportVariant,
        transportProtocol: String = this.transportProtocol,
        transportCodecId: String = this.transportCodecId,
        sequence: Int = this.sequence,
        sensorTimeEpochMs: Long = this.sensorTimeEpochMs,
        sensorStartTimeEpochMs: Long = this.sensorStartTimeEpochMs,
        algorithmProfile: String = this.algorithmProfile,
        algorithmVersion: String = this.algorithmVersion,
        binarySetId: String = this.binarySetId,
        sensitivityToken: String = this.sensitivityToken,
        sensitivityTokenSource: String = this.sensitivityTokenSource,
        sensitivityCoefficient: Double = this.sensitivityCoefficient,
        sensitivityEncoding: String = this.sensitivityEncoding,
        initializationMode: String = this.initializationMode,
        state: ByteArray = this.state,
        stateSha256: String = this.stateSha256,
        displayOffsetMmolL: Double = this.displayOffsetMmolL,
        schemaVersion: Int = this.schemaVersion,
        publicationApprovalId: String? = this.publicationApprovalId,
    ) = SensorAlgorithmCheckpointRecord(
        sensorId = sensorId,
        bluetoothAddress = bluetoothAddress,
        sensorFamily = sensorFamily,
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
        state = state,
        stateSha256 = stateSha256,
        displayOffsetMmolL = displayOffsetMmolL,
        schemaVersion = schemaVersion,
        publicationApprovalId = publicationApprovalId,
    )

    private companion object {
        const val MILLIS_PER_SENSOR_SAMPLE = 60_000L
    }
}

data class AtomicSensorCoreRecord(
    val raw: RawSensorSampleRecord,
    val result: SensorAlgorithmResultRecord,
    val checkpoint: SensorAlgorithmCheckpointRecord,
    val measurement: GlucoseReading?,
    val publicationContext: ProductPublicationContext? = null,
    val approvedCheckpointContext: ApprovedCheckpointContext? =
        publicationContext?.approvedCheckpointContext(),
) {
    init {
        require(result.eventId == raw.eventId)
        require(result.sensorId == raw.sensorId)
        require(result.sequence == raw.sequence)
        require(result.sensorTimeEpochMs == raw.sensorTimeEpochMs)

        require(checkpoint.sensorId == raw.sensorId)
        require(checkpoint.sensorFamily == raw.sensorFamily)
        require(checkpoint.transportVariant == raw.transportVariant)
        require(checkpoint.sequence == raw.sequence)
        require(checkpoint.sensorTimeEpochMs == raw.sensorTimeEpochMs)
        require(checkpoint.algorithmProfile == result.algorithmProfile)
        require(checkpoint.algorithmVersion == result.algorithmVersion)
        require(checkpoint.binarySetId == result.binarySetId)
        require(checkpoint.sensitivityToken == result.sensitivityToken)
        require(checkpoint.sensitivityTokenSource == result.sensitivityTokenSource)
        require(checkpoint.sensitivityCoefficient == result.sensitivityCoefficient)
        require(checkpoint.sensitivityEncoding == result.sensitivityEncoding)
        require(checkpoint.initializationMode == result.initializationMode)

        require(result.publishable == (measurement != null))
        require(measurement == null || approvedCheckpointContext != null)
        require(publicationContext == null || measurement != null)
        require(measurement == null || measurement.quality == ReadingQuality.VALID)
        require(!result.alarmEligible || measurement != null)
        if (approvedCheckpointContext == null) {
            require(publicationContext == null)
            require(result.publicationApprovalId == null)
            require(checkpoint.publicationApprovalId == null)
        } else {
            require(result.publicationApprovalId == approvedCheckpointContext.approvalId)
            require(checkpoint.publicationApprovalId == approvedCheckpointContext.approvalId)
            require(
                publicationContext == null ||
                    publicationContext.approvedCheckpointContext() == approvedCheckpointContext,
            )
        }
        measurement?.let {
            require(it.eventId == raw.eventId)
            require(it.sensorId == raw.sensorId)
            require(it.sensorFamily == raw.sensorFamily)
            require(it.sequence == raw.sequence.toLong())
            require(it.sensorTimeEpochMs == raw.sensorTimeEpochMs)
            require(it.phoneTimeEpochMs == raw.phoneTimeEpochMs)
            require(it.glucoseMgDl == (result.displayedGlucoseMmolL * MG_DL_PER_MMOL_L).roundToInt())
        }
    }

    internal fun toEntityBundle() = SensorCoreEntityBundle(
        raw = raw.toEntity(),
        result = result.toEntity(approvedCheckpointContext),
        checkpoint = checkpoint.toEntity(approvedCheckpointContext),
        measurement = measurement?.toEntity(
            approvedContext = checkNotNull(approvedCheckpointContext),
            publicationContext = publicationContext,
        ),
        publicationContext = publicationContext,
        approvedCheckpointContext = approvedCheckpointContext,
    )

    private companion object {
        const val MG_DL_PER_MMOL_L = 18.0
    }
}

private val TOKEN_SOURCES = setOf("PACKAGE_CODE")
private val SENSITIVITY_ENCODINGS = setOf("NORMAL", "FACTION")
private val INITIALIZATION_MODES = setOf("STANDARD", "FACTION")
private val CANONICAL_BLUETOOTH_ADDRESS = Regex("^(?:[0-9A-F]{2}:){5}[0-9A-F]{2}$")
private val SHA256 = Regex("^[0-9a-f]{64}$")
private val ALGORITHM_STATE_SIZES = mapOf("V116A" to 2_480, "V115G" to 2_336)
private const val CHECKPOINT_SCHEMA_VERSION = 1
private const val MIN_SENSITIVITY = 0.8
private const val MAX_SENSITIVITY = 2.5

private fun requireSensitivityToken(value: String) {
    require(value.length == 8)
    require(value.all { it in '0'..'9' || it in 'A'..'Z' || it in 'a'..'z' })
}

internal fun ByteArray.sha256(): String = MessageDigest.getInstance("SHA-256")
    .digest(this)
    .joinToString(separator = "") { byte -> "%02x".format(byte) }
