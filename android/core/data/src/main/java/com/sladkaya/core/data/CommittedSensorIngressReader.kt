package com.sladkaya.core.data

import android.content.Context
import androidx.room.Dao
import androidx.room.Query
import androidx.room.Transaction
import com.sladkaya.core.model.GlucoseReading
import com.sladkaya.core.model.ReadingQuality
import com.sladkaya.core.model.SensorFamily
import kotlin.math.roundToInt

/** Product value and immutable approval lineage recovered from one atomic core commit. */
data class CommittedProductPublicationRecord(
    val reading: GlucoseReading,
    val approvalId: String,
    val publicationBindingId: String,
) {
    init {
        require(reading.quality == ReadingQuality.VALID)
        require(SHA256_HEX.matches(approvalId))
        require(SHA256_HEX.matches(publicationBindingId))
    }
}

/** One committed sample proven to originate from the exact durable BLE ingress. */
data class CommittedSensorIngressSampleRecord(
    val raw: RawSensorSampleRecord,
    val algorithmErrorCode: String?,
    val productPublication: CommittedProductPublicationRecord?,
) {
    init {
        require(algorithmErrorCode == null || algorithmErrorCode.isNotBlank())
        require(algorithmErrorCode == null || productPublication == null)
        productPublication?.reading?.let { reading ->
            require(reading.eventId == raw.eventId)
            require(reading.sensorId == raw.sensorId)
            require(reading.sensorFamily == raw.sensorFamily)
            require(reading.sequence == raw.sequence.toLong())
            require(reading.sensorTimeEpochMs == raw.sensorTimeEpochMs)
            require(reading.phoneTimeEpochMs == raw.phoneTimeEpochMs)
        }
    }
}

sealed interface CommittedSensorIngressReadResult {
    data class Exact(
        val samples: List<CommittedSensorIngressSampleRecord>,
    ) : CommittedSensorIngressReadResult

    /**
     * A later byte-identical ingress whose output was durably acknowledged on
     * one exact earlier ingress. Its publications must not be emitted again.
     */
    data class HandledDuplicate(
        val sourceIngress: SensorPacketIngressRecord,
        val samples: List<CommittedSensorIngressSampleRecord>,
        val outcomeStatus: SensorPacketIngressOutcomeStatus,
    ) : CommittedSensorIngressReadResult {
        init {
            require(samples.isNotEmpty())
            require(
                outcomeStatus == SensorPacketIngressOutcomeStatus.CORE_COMMITTED ||
                    outcomeStatus == SensorPacketIngressOutcomeStatus.ALREADY_COVERED,
            )
        }
    }

    data class Mismatch(val reason: String) : CommittedSensorIngressReadResult
}

data class CommittedSensorCoverageRequest(
    val sensorId: String,
    val sensorFamily: SensorFamily,
    val bluetoothAddress: String,
    val firstSequence: Int,
    val lastSequence: Int,
) {
    init {
        require(sensorId.isNotBlank())
        require(bluetoothAddress.isNotBlank())
        require(firstSequence in 1..0xffff)
        require(lastSequence in firstSequence..0xffff)
        require(lastSequence - firstSequence < MAX_COMMITTED_COVERAGE_SAMPLES)
    }
}

sealed interface CommittedSensorCoverageReadResult {
    data class Exact(
        val samples: List<CommittedSensorIngressSampleRecord>,
    ) : CommittedSensorCoverageReadResult

    data class Mismatch(val reason: String) : CommittedSensorCoverageReadResult
}

/** Read-only boundary used to reconstruct output without running the native algorithm again. */
fun interface CommittedSensorIngressReader {
    suspend fun read(ingress: SensorPacketIngressRecord): CommittedSensorIngressReadResult

    suspend fun readHandledCoverage(
        request: CommittedSensorCoverageRequest,
    ): CommittedSensorCoverageReadResult =
        CommittedSensorCoverageReadResult.Mismatch("Handled range proof is unavailable")
}

class RoomCommittedSensorIngressReader internal constructor(
    private val dao: CommittedSensorIngressDao,
) : CommittedSensorIngressReader {
    override suspend fun read(
        ingress: SensorPacketIngressRecord,
    ): CommittedSensorIngressReadResult = dao.readExact(ingress)

    override suspend fun readHandledCoverage(
        request: CommittedSensorCoverageRequest,
    ): CommittedSensorCoverageReadResult = dao.readHandledCoverage(request)

    companion object {
        fun create(context: Context): CommittedSensorIngressReader =
            RoomCommittedSensorIngressReader(
                SladkayaDatabase.get(context.applicationContext).committedSensorIngress(),
            )
    }
}

@Dao
internal abstract class CommittedSensorIngressDao {
    @Query("SELECT * FROM sensor_packet_ingress WHERE ingressId = :ingressId LIMIT 1")
    protected abstract suspend fun ingressById(ingressId: String): SensorPacketIngressEntity?

    @Query(
        "SELECT * FROM sensor_packet_ingress WHERE ingressId != :currentIngressId " +
            "AND sensorId = :sensorId AND sensorFamily = :sensorFamily " +
            "AND bluetoothAddress = :bluetoothAddress AND packetSha256 = :packetSha256 " +
            "ORDER BY receivedAtEpochMs ASC, attemptId ASC, ordinal ASC",
    )
    protected abstract suspend fun duplicateIngressCandidates(
        currentIngressId: String,
        sensorId: String,
        sensorFamily: String,
        bluetoothAddress: String,
        packetSha256: String,
    ): List<SensorPacketIngressEntity>

    @Query(
        "SELECT * FROM sensor_packet_ingress_outcomes WHERE ingressId = :ingressId LIMIT 1",
    )
    protected abstract suspend fun ingressOutcome(
        ingressId: String,
    ): SensorPacketIngressOutcomeEntity?

    @Query(
        "SELECT * FROM sensor_raw_samples WHERE sourceIngressId = :sourceIngressId " +
            "ORDER BY sequence ASC",
    )
    protected abstract suspend fun rawBySourceIngressId(
        sourceIngressId: String,
    ): List<RawSensorSampleEntity>

    @Query(
        "SELECT * FROM sensor_raw_samples WHERE sensorId = :sensorId " +
            "AND sensorFamily = :sensorFamily AND sequence BETWEEN :firstSequence AND :lastSequence " +
            "ORDER BY sequence ASC",
    )
    protected abstract suspend fun rawBySensorRange(
        sensorId: String,
        sensorFamily: String,
        firstSequence: Int,
        lastSequence: Int,
    ): List<RawSensorSampleEntity>

    @Query("SELECT * FROM sensor_algorithm_results WHERE eventId = :eventId LIMIT 1")
    protected abstract suspend fun resultByEvent(eventId: String): SensorAlgorithmResultEntity?

    @Query("SELECT * FROM measurements WHERE eventId = :eventId LIMIT 1")
    protected abstract suspend fun measurementByEvent(eventId: String): MeasurementEntity?

    @Query("SELECT * FROM measurement_upload_outbox WHERE eventId = :eventId LIMIT 1")
    protected abstract suspend fun outboxByEvent(eventId: String): UploadOutboxEntity?

    @Query("SELECT * FROM physical_sensor_approvals WHERE approvalId = :approvalId LIMIT 1")
    protected abstract suspend fun physicalApproval(
        approvalId: String,
    ): PhysicalSensorApprovalEntity?

    @Query(
        "SELECT * FROM product_publication_bindings " +
            "WHERE remotePublicationBindingId = :remotePublicationBindingId LIMIT 1",
    )
    protected abstract suspend fun publicationBinding(
        remotePublicationBindingId: String,
    ): ProductPublicationBindingEntity?

    @Query(
        "SELECT * FROM active_sensor_publication_binding " +
            "WHERE activeSlot = $ACTIVE_PUBLICATION_BINDING_SLOT LIMIT 1",
    )
    protected abstract suspend fun activeSensorBinding(): ActiveSensorPublicationBindingEntity?

    @Transaction
    open suspend fun readExact(
        ingress: SensorPacketIngressRecord,
    ): CommittedSensorIngressReadResult {
        val storedIngress = ingressById(ingress.ingressId)
            ?: return mismatch("Exact ingress identity is missing")
        if (!storedIngress.matchesExactly(ingress)) {
            return mismatch("Stored ingress identity differs from the requested evidence")
        }
        val exactPacket = ingress.encryptedPacketCopy()
        val currentRows = rawBySourceIngressId(ingress.ingressId)
        if (currentRows.isNotEmpty()) {
            return validateCommittedRows(ingress, exactPacket, currentRows)
        }
        return readHandledDuplicate(ingress, exactPacket)
    }

    @Transaction
    open suspend fun readHandledCoverage(
        request: CommittedSensorCoverageRequest,
    ): CommittedSensorCoverageReadResult {
        val rawRange = rawBySensorRange(
            sensorId = request.sensorId,
            sensorFamily = request.sensorFamily.wireName,
            firstSequence = request.firstSequence,
            lastSequence = request.lastSequence,
        )
        val expectedSequences = (request.firstSequence..request.lastSequence).toList()
        if (rawRange.map(RawSensorSampleEntity::sequence) != expectedSequences) {
            return coverageMismatch("Handled coverage is missing an exact contiguous raw range")
        }
        val validatedByEventId = mutableMapOf<String, CommittedSensorIngressSampleRecord>()
        for (sourceIngressId in rawRange.map(RawSensorSampleEntity::sourceIngressId).distinct()) {
            val sourceEntity = ingressById(sourceIngressId)
                ?: return coverageMismatch("Handled coverage source ingress is missing")
            if (sourceEntity.sensorId != request.sensorId ||
                sourceEntity.sensorFamily != request.sensorFamily.wireName ||
                sourceEntity.bluetoothAddress != request.bluetoothAddress
            ) {
                return coverageMismatch("Handled coverage source identity differs")
            }
            val validated = validateHandledOrigin(sourceEntity)
                ?: return coverageMismatch("Handled coverage source is not durably acknowledged")
            validated.samples.forEach { sample ->
                if (validatedByEventId.put(sample.raw.eventId, sample) != null) {
                    return coverageMismatch("Handled coverage contains duplicate event identity")
                }
            }
        }
        val selected = rawRange.map { raw ->
            validatedByEventId[raw.eventId]?.takeIf { sample ->
                sample.raw.sourceIngressId == raw.sourceIngressId &&
                    sample.raw.sequence == raw.sequence
            } ?: return coverageMismatch("Handled coverage row is outside its validated lineage")
        }
        return CommittedSensorCoverageReadResult.Exact(selected)
    }

    private suspend fun readHandledDuplicate(
        current: SensorPacketIngressRecord,
        exactPacket: ByteArray,
    ): CommittedSensorIngressReadResult {
        val candidates = duplicateIngressCandidates(
            currentIngressId = current.ingressId,
            sensorId = current.sensorId,
            sensorFamily = current.sensorFamily.wireName,
            bluetoothAddress = current.bluetoothAddress,
            packetSha256 = current.packetSha256,
        ).filter { candidate ->
            candidate.encryptedPacket.contentEquals(exactPacket)
        }
        val qualifyingOrigins = buildList {
            for (candidate in candidates) {
                validateHandledOrigin(candidate)?.let(::add)
            }
        }
        if (qualifyingOrigins.size != 1) {
            return mismatch("Handled duplicate requires exactly one earlier exact ingress")
        }
        val origin = qualifyingOrigins.single()
        return CommittedSensorIngressReadResult.HandledDuplicate(
            sourceIngress = origin.sourceIngress,
            samples = origin.samples,
            outcomeStatus = origin.outcomeStatus,
        )
    }

    private suspend fun validateHandledOrigin(
        candidate: SensorPacketIngressEntity,
    ): ValidatedDuplicateOrigin? {
        val sourceIngress = try {
            candidate.toRecord()
        } catch (_: IllegalArgumentException) {
            return null
        } catch (_: NoSuchElementException) {
            return null
        }
        val outcome = ingressOutcome(candidate.ingressId) ?: return null
        val outcomeStatus = try {
            SensorPacketIngressOutcomeStatus.valueOf(outcome.status)
        } catch (_: IllegalArgumentException) {
            return null
        }
        if (outcomeStatus != SensorPacketIngressOutcomeStatus.CORE_COMMITTED &&
            outcomeStatus != SensorPacketIngressOutcomeStatus.ALREADY_COVERED
        ) {
            return null
        }
        try {
            SensorPacketIngressOutcomeRecord(
                ingressId = outcome.ingressId,
                status = outcomeStatus,
                handledAtEpochMs = outcome.handledAtEpochMs,
                detail = outcome.detail,
            )
        } catch (_: IllegalArgumentException) {
            return null
        }
        if (outcome.ingressId != candidate.ingressId) {
            return null
        }
        if (outcome.handledAtEpochMs != candidate.receivedAtEpochMs || outcome.detail != null) {
            return null
        }
        val sourceRows = rawBySourceIngressId(candidate.ingressId)
        if (sourceRows.isEmpty()) {
            return null
        }
        return when (
            val validated = validateCommittedRows(
                ingress = sourceIngress,
                exactPacket = sourceIngress.encryptedPacketCopy(),
                rawEntities = sourceRows,
            )
        ) {
            is CommittedSensorIngressReadResult.Exact ->
                ValidatedDuplicateOrigin(
                    sourceIngress = sourceIngress,
                    samples = validated.samples,
                    outcomeStatus = outcomeStatus,
                )
            is CommittedSensorIngressReadResult.HandledDuplicate ->
                null
            is CommittedSensorIngressReadResult.Mismatch -> null
        }
    }

    private suspend fun validateCommittedRows(
        ingress: SensorPacketIngressRecord,
        exactPacket: ByteArray,
        rawEntities: List<RawSensorSampleEntity>,
    ): CommittedSensorIngressReadResult {
        val committed = mutableListOf<CommittedSensorIngressSampleRecord>()
        for (rawEntity in rawEntities) {
            if (!rawEntity.matchesIngress(ingress, exactPacket)) {
                return mismatch("Committed raw sample does not match its source ingress")
            }
            val raw = try {
                rawEntity.toCommittedRawRecord()
            } catch (_: IllegalArgumentException) {
                return mismatch("Committed raw sample is malformed")
            }
            val result = resultByEvent(raw.eventId)
                ?: return mismatch("Committed raw sample has no algorithm result")
            if (!result.matches(raw)) {
                return mismatch("Algorithm result does not match committed raw identity")
            }
            try {
                result.requireValidRecord()
            } catch (_: IllegalArgumentException) {
                return mismatch("Stored algorithm result is malformed")
            }

            val measurement = measurementByEvent(raw.eventId)
            val outbox = outboxByEvent(raw.eventId)
            if (result.publishable != (measurement != null)) {
                return mismatch("Algorithm publication state and measurement differ")
            }
            if (measurement == null) {
                if (outbox != null) {
                    return mismatch("Non-publishable sample unexpectedly has an upload record")
                }
                committed += CommittedSensorIngressSampleRecord(
                    raw = raw,
                    algorithmErrorCode = result.algorithmErrorCode,
                    productPublication = null,
                )
                continue
            }

            val publication = validatePublication(
                ingress = ingress,
                raw = raw,
                result = result,
                measurement = measurement,
                outbox = outbox,
            ) ?: return mismatch("Publishable measurement lineage is malformed")
            committed += CommittedSensorIngressSampleRecord(
                raw = raw,
                algorithmErrorCode = null,
                productPublication = publication,
            )
        }
        return CommittedSensorIngressReadResult.Exact(committed.toList())
    }

    private suspend fun validatePublication(
        ingress: SensorPacketIngressRecord,
        raw: RawSensorSampleRecord,
        result: SensorAlgorithmResultEntity,
        measurement: MeasurementEntity,
        outbox: UploadOutboxEntity?,
    ): CommittedProductPublicationRecord? {
        if (result.algorithmErrorCode != null || !result.alarmEligible ||
            !measurement.matches(raw) || measurement.quality != ReadingQuality.VALID.wireName
        ) return null
        val approvalId = measurement.publicationApprovalId ?: return null
        val publicationBindingId = measurement.publicationBindingId ?: return null
        if (result.publicationApprovalId != approvalId) return null
        val activeBinding = activeSensorBinding() ?: return null
        if (activeBinding.activeSlot != ACTIVE_PUBLICATION_BINDING_SLOT ||
            activeBinding.approvalId != approvalId ||
            activeBinding.publicationBindingId != publicationBindingId
        ) return null
        val approval = try {
            physicalApproval(approvalId)?.toRecord()
        } catch (_: IllegalArgumentException) {
            null
        } catch (_: NoSuchElementException) {
            null
        } ?: return null
        if (approval.approvalId != approvalId ||
            approval.sensorId != ingress.sensorId ||
            approval.sensorFamily != ingress.sensorFamily ||
            approval.bluetoothAddress != ingress.bluetoothAddress ||
            approval.transportVariant != raw.transportVariant ||
            !result.matchesApproval(approval) ||
            !measurement.matchesDisplayContract(result)
        ) return null
        val remoteFields = listOf(
            measurement.remotePublicationBindingId,
            measurement.httpsOrigin,
            measurement.backendBindingId,
            measurement.credentialId,
            measurement.credentialRevision,
            measurement.expectedPatientId,
            measurement.expectedDeviceId,
        )
        val hasRemoteLineage = remoteFields.all { it != null }
        if (!hasRemoteLineage && remoteFields.any { it != null }) return null
        if (hasRemoteLineage) {
            val exactOutbox = outbox ?: return null
            try {
                exactOutbox.toRecord()
            } catch (_: IllegalArgumentException) {
                return null
            } catch (_: NoSuchElementException) {
                return null
            }
            if (!exactOutbox.matches(measurement)) return null
            val binding = try {
                publicationBinding(requireNotNull(measurement.remotePublicationBindingId))?.toRecord()
            } catch (_: IllegalArgumentException) {
                null
            } catch (_: NoSuchElementException) {
                null
            } ?: return null
            if (binding.publicationBindingId != publicationBindingId ||
                binding.remotePublicationBindingId != measurement.remotePublicationBindingId ||
                binding.approvalId != approval.approvalId ||
                !binding.matches(measurement)
            ) return null
        } else if (outbox != null) {
            return null
        }
        val reading = try {
            measurement.toModel()
        } catch (_: IllegalArgumentException) {
            return null
        } catch (_: NoSuchElementException) {
            return null
        }
        return try {
            CommittedProductPublicationRecord(reading, approvalId, publicationBindingId)
        } catch (_: IllegalArgumentException) {
            null
        }
    }

    private fun mismatch(reason: String) = CommittedSensorIngressReadResult.Mismatch(reason)

    private fun coverageMismatch(reason: String) =
        CommittedSensorCoverageReadResult.Mismatch(reason)

    private data class ValidatedDuplicateOrigin(
        val sourceIngress: SensorPacketIngressRecord,
        val samples: List<CommittedSensorIngressSampleRecord>,
        val outcomeStatus: SensorPacketIngressOutcomeStatus,
    )
}

private fun SensorPacketIngressEntity.matchesExactly(record: SensorPacketIngressRecord): Boolean =
    ingressId == record.ingressId &&
        sensorId == record.sensorId &&
        sensorFamily == record.sensorFamily.wireName &&
        bluetoothAddress == record.bluetoothAddress &&
        attemptId == record.attemptId &&
        ordinal == record.ordinal &&
        receivedAtEpochMs == record.receivedAtEpochMs &&
        encryptedPacket.contentEquals(record.encryptedPacketCopy()) &&
        packetSha256 == record.packetSha256

private fun RawSensorSampleEntity.matchesIngress(
    ingress: SensorPacketIngressRecord,
    exactPacket: ByteArray,
): Boolean = sourceIngressId == ingress.ingressId &&
    sensorId == ingress.sensorId &&
    sensorFamily == ingress.sensorFamily.wireName &&
    phoneTimeEpochMs == ingress.receivedAtEpochMs &&
    packetSha256 == ingress.packetSha256 &&
    packet.contentEquals(exactPacket)

private fun RawSensorSampleEntity.toCommittedRawRecord() = RawSensorSampleRecord(
    eventId = eventId,
    sourceIngressId = sourceIngressId,
    sensorId = sensorId,
    sensorFamily = SensorFamily.entries.first { it.wireName == sensorFamily },
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

private fun SensorAlgorithmResultEntity.matches(raw: RawSensorSampleRecord): Boolean =
    eventId == raw.eventId && sensorId == raw.sensorId && sequence == raw.sequence &&
        sensorTimeEpochMs == raw.sensorTimeEpochMs

private fun SensorAlgorithmResultEntity.requireValidRecord() {
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
}

private fun MeasurementEntity.matches(raw: RawSensorSampleRecord): Boolean =
    eventId == raw.eventId && sensorId == raw.sensorId && sensorFamily == raw.sensorFamily.wireName &&
        sequence == raw.sequence.toLong() && sensorTimeEpochMs == raw.sensorTimeEpochMs &&
        phoneTimeEpochMs == raw.phoneTimeEpochMs

private fun MeasurementEntity.matchesDisplayContract(
    result: SensorAlgorithmResultEntity,
): Boolean = glucoseMgDl == (result.displayedGlucoseMmolL * MG_DL_PER_MMOL_L).roundToInt() &&
    trendMgDlPerMinute.toBits() ==
        (result.nativeTrend * NATIVE_TREND_SCALE).coerceIn(
            MIN_PRODUCT_TREND,
            MAX_PRODUCT_TREND,
        ).toBits()

private fun SensorAlgorithmResultEntity.matchesApproval(
    approval: PhysicalSensorApprovalRecord,
): Boolean = algorithmProfile == approval.algorithmProfile &&
    algorithmVersion == approval.algorithmVersion &&
    binarySetId == approval.binarySetId &&
    sensitivityToken == approval.sensitivityToken &&
    sensitivityTokenSource == approval.sensitivityTokenSource &&
    sensitivityCoefficient.toBits() == approval.sensitivityCoefficient.toBits() &&
    sensitivityEncoding == approval.sensitivityEncoding &&
    initializationMode == approval.initializationMode

private fun UploadOutboxEntity.matches(measurement: MeasurementEntity): Boolean =
    eventId == measurement.eventId && approvalId == measurement.publicationApprovalId &&
        publicationBindingId == measurement.publicationBindingId &&
        remotePublicationBindingId == measurement.remotePublicationBindingId &&
        httpsOrigin == measurement.httpsOrigin && backendBindingId == measurement.backendBindingId &&
        credentialId == measurement.credentialId && credentialRevision == measurement.credentialRevision &&
        expectedPatientId == measurement.expectedPatientId &&
        expectedDeviceId == measurement.expectedDeviceId

private fun ProductPublicationBindingRecord.matches(
    measurement: MeasurementEntity,
): Boolean = publicationBindingId == measurement.publicationBindingId &&
    remotePublicationBindingId == measurement.remotePublicationBindingId &&
    httpsOrigin == measurement.httpsOrigin &&
    backendBindingId == measurement.backendBindingId && credentialId == measurement.credentialId &&
    credentialRevision == measurement.credentialRevision &&
    expectedPatientId == measurement.expectedPatientId && expectedDeviceId == measurement.expectedDeviceId

private val SHA256_HEX = Regex("^[0-9a-f]{64}$")
private const val MAX_COMMITTED_COVERAGE_SAMPLES = 250
private const val MG_DL_PER_MMOL_L = 18.0
private const val NATIVE_TREND_SCALE = 1.3
private const val MIN_PRODUCT_TREND = -20.0
private const val MAX_PRODUCT_TREND = 20.0
