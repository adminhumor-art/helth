package com.sladkaya.core.data

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Transaction
import kotlin.math.roundToInt

internal sealed interface LocalReadingEffectLeaseDecision {
    data class Leased(val value: LeasedLocalReadingEffect) : LocalReadingEffectLeaseDecision
    data object Empty : LocalReadingEffectLeaseDecision
    data class BlockedByActiveLease(
        val effectId: Long,
        val leaseExpiresAtEpochMs: Long,
    ) : LocalReadingEffectLeaseDecision
}

@Dao
internal abstract class LocalReadingEffectDao {
    @Query(
        "SELECT * FROM local_reading_effects " +
            "WHERE state != 'ACKNOWLEDGED' ORDER BY effectId ASC LIMIT 1",
    )
    abstract suspend fun earliestUnacknowledged(): LocalReadingEffectEntity?

    @Query("SELECT * FROM local_reading_effects WHERE effectId = :effectId LIMIT 1")
    abstract suspend fun effectById(effectId: Long): LocalReadingEffectEntity?

    @Query("SELECT * FROM local_reading_effects WHERE eventId = :eventId LIMIT 1")
    abstract suspend fun effectByEvent(eventId: String): LocalReadingEffectEntity?

    @Query(
        "SELECT * FROM local_reading_effects " +
            "WHERE leaseToken = :token OR lastTransitionToken = :token ORDER BY effectId ASC",
    )
    abstract suspend fun effectsByOperationToken(token: String): List<LocalReadingEffectEntity>

    @Query("SELECT * FROM measurements WHERE eventId = :eventId LIMIT 1")
    abstract suspend fun measurement(eventId: String): MeasurementEntity?

    @Query("SELECT * FROM sensor_raw_samples WHERE eventId = :eventId LIMIT 1")
    abstract suspend fun raw(eventId: String): RawSensorSampleEntity?

    @Query("SELECT * FROM sensor_algorithm_results WHERE eventId = :eventId LIMIT 1")
    abstract suspend fun result(eventId: String): SensorAlgorithmResultEntity?

    @Query(
        "SELECT * FROM active_sensor_publication_binding " +
            "WHERE activeSlot = $ACTIVE_PUBLICATION_BINDING_SLOT LIMIT 1",
    )
    abstract suspend fun activeSensorBinding(): ActiveSensorPublicationBindingEntity?

    @Query("SELECT * FROM physical_sensor_approvals WHERE approvalId = :approvalId LIMIT 1")
    abstract suspend fun physicalApproval(approvalId: String): PhysicalSensorApprovalEntity?

    @Query(
        "UPDATE local_reading_effects SET state = 'PENDING', " +
            "lastTransitionToken = leaseToken, leaseToken = NULL, leaseExpiresAtEpochMs = NULL " +
            "WHERE state = 'LEASED' AND leaseExpiresAtEpochMs <= :nowEpochMs",
    )
    abstract suspend fun recoverExpiredLeases(nowEpochMs: Long): Int

    @Query(
        "UPDATE local_reading_effects SET state = 'LEASED', attempts = attempts + 1, " +
            "leaseToken = :leaseToken, leaseExpiresAtEpochMs = :leaseExpiresAtEpochMs " +
            "WHERE effectId = :effectId AND state = 'PENDING'",
    )
    abstract suspend fun acquireLease(
        effectId: Long,
        leaseToken: String,
        leaseExpiresAtEpochMs: Long,
    ): Int

    @Query(
        "UPDATE local_reading_effects SET state = 'ACKNOWLEDGED', " +
            "leaseToken = NULL, leaseExpiresAtEpochMs = NULL, " +
            "lastTransitionToken = :leaseToken, acknowledgedAtEpochMs = :acknowledgedAtEpochMs " +
            "WHERE effectId = :effectId AND eventId = :eventId " +
            "AND state = 'LEASED' AND leaseToken = :leaseToken",
    )
    abstract suspend fun setAcknowledged(
        effectId: Long,
        eventId: String,
        leaseToken: String,
        acknowledgedAtEpochMs: Long,
    ): Int

    @Transaction
    open suspend fun leaseEarliest(
        nowEpochMs: Long,
        leaseToken: String,
        leaseExpiresAtEpochMs: Long,
    ): LocalReadingEffectLeaseDecision {
        require(nowEpochMs > 0)
        requireLeaseToken(leaseToken)
        require(leaseExpiresAtEpochMs > nowEpochMs)

        recoverExpiredLeases(nowEpochMs)
        val tokenHistory = effectsByOperationToken(leaseToken)
        val exactLease = tokenHistory.filter { it.leaseToken == leaseToken }
        if (exactLease.size == 1 && tokenHistory.size == 1) {
            val saved = exactLease.single()
            if (saved.state == LocalReadingEffectState.LEASED.wireName &&
                saved.leaseExpiresAtEpochMs == leaseExpiresAtEpochMs &&
                earliestUnacknowledged()?.effectId == saved.effectId
            ) {
                return LocalReadingEffectLeaseDecision.Leased(validatedLease(saved))
            }
            conflict("Local effect lease token was already used for another operation")
        }
        if (tokenHistory.isNotEmpty()) {
            conflict("Local effect lease token was already finalized or expired")
        }

        val earliest = earliestUnacknowledged()
            ?: return LocalReadingEffectLeaseDecision.Empty
        if (earliest.state == LocalReadingEffectState.LEASED.wireName) {
            val expiry = earliest.leaseExpiresAtEpochMs
                ?: conflict("Earliest local effect has a malformed active lease")
            validatedLease(earliest)
            return LocalReadingEffectLeaseDecision.BlockedByActiveLease(
                effectId = earliest.effectId,
                leaseExpiresAtEpochMs = expiry,
            )
        }
        if (earliest.state != LocalReadingEffectState.PENDING.wireName) {
            conflict("Earliest local effect has an unsupported state")
        }
        if (acquireLease(earliest.effectId, leaseToken, leaseExpiresAtEpochMs) != 1) {
            conflict("Earliest local effect changed while acquiring its lease")
        }
        val leased = effectById(earliest.effectId)
            ?: conflict("Leased local effect disappeared")
        return LocalReadingEffectLeaseDecision.Leased(validatedLease(leased))
    }

    @Transaction
    open suspend fun acknowledgeEarliest(
        effectId: Long,
        eventId: String,
        leaseToken: String,
        acknowledgedAtEpochMs: Long,
    ): SensorCoreCommitDisposition {
        require(effectId > 0)
        require(eventId.isNotBlank())
        requireLeaseToken(leaseToken)
        require(acknowledgedAtEpochMs > 0)

        val saved = effectById(effectId)
            ?: conflict("Local effect does not exist")
        if (saved.eventId != eventId) {
            conflict("Local effect identity does not match the acknowledgment")
        }
        if (saved.state == LocalReadingEffectState.ACKNOWLEDGED.wireName) {
            if (saved.lastTransitionToken == leaseToken &&
                saved.acknowledgedAtEpochMs == acknowledgedAtEpochMs
            ) {
                return SensorCoreCommitDisposition.ALREADY_COMMITTED
            }
            conflict("Local effect was acknowledged by another operation")
        }
        if (saved.state != LocalReadingEffectState.LEASED.wireName ||
            saved.leaseToken != leaseToken
        ) {
            conflict("Local effect acknowledgment requires its exact active lease")
        }
        if (earliestUnacknowledged()?.effectId != effectId) {
            conflict("Only the earliest local effect may be acknowledged")
        }
        val leaseExpiry = saved.leaseExpiresAtEpochMs
            ?: conflict("Local effect lease expiry is missing")
        if (acknowledgedAtEpochMs >= leaseExpiry) {
            conflict("Local effect acknowledgment time is outside its active lease")
        }
        validatedLease(saved)
        if (setAcknowledged(effectId, eventId, leaseToken, acknowledgedAtEpochMs) != 1) {
            conflict("Local effect lease changed while acknowledging it")
        }
        return SensorCoreCommitDisposition.COMMITTED
    }

    private suspend fun validatedLease(
        effect: LocalReadingEffectEntity,
    ): LeasedLocalReadingEffect {
        val measurement = measurement(effect.eventId)
            ?: conflict("Local effect has no durable product measurement")
        val raw = raw(effect.eventId)
            ?: conflict("Local effect has no durable raw provenance")
        val result = result(effect.eventId)
            ?: conflict("Local effect has no durable algorithm provenance")
        val activeBinding = activeSensorBinding()
            ?: conflict("Local effect has no active local sensor binding")
        val approval = physicalApproval(effect.approvalId)
            ?: conflict("Local effect has no durable physical approval")
        return validateLocalReadingEffect(
            effect,
            measurement,
            raw,
            result,
            activeBinding,
            approval,
        )
    }

    private fun conflict(message: String): Nothing = throw SensorCoreConflictException(message)
}

internal fun validateLocalReadingEffect(
    effect: LocalReadingEffectEntity,
    measurement: MeasurementEntity,
    raw: RawSensorSampleEntity,
    result: SensorAlgorithmResultEntity,
    activeBinding: ActiveSensorPublicationBindingEntity,
    approval: PhysicalSensorApprovalEntity,
): LeasedLocalReadingEffect {
    val effectRecord = try {
        effect.toRecord()
    } catch (_: IllegalArgumentException) {
        throw SensorCoreConflictException("Stored local effect is malformed")
    } catch (_: NoSuchElementException) {
        throw SensorCoreConflictException("Stored local effect contains an unsupported state")
    }
    if (effectRecord.state != LocalReadingEffectState.LEASED) {
        throw SensorCoreConflictException("Local effect is not leased")
    }
    if (!measurement.matchesLocalEffect(effect)) {
        throw SensorCoreConflictException("Local effect product lineage is inconsistent")
    }
    val reading = validateExactLocalProductMeasurement(
        measurement = measurement,
        raw = raw,
        result = result,
        activeBinding = activeBinding,
        approval = approval,
        expectedEventId = effect.eventId,
        expectedApprovalId = effect.approvalId,
        expectedPublicationBindingId = effect.publicationBindingId,
    )
    return try {
        LeasedLocalReadingEffect(effectRecord, reading)
    } catch (_: IllegalArgumentException) {
        throw SensorCoreConflictException("Stored local reading does not match its effect")
    }
}

internal fun validateExactLocalProductMeasurement(
    measurement: MeasurementEntity,
    raw: RawSensorSampleEntity,
    result: SensorAlgorithmResultEntity,
    activeBinding: ActiveSensorPublicationBindingEntity,
    approval: PhysicalSensorApprovalEntity,
    expectedEventId: String,
    expectedApprovalId: String,
    expectedPublicationBindingId: String,
): com.sladkaya.core.model.GlucoseReading {
    val approvalRecord = try {
        approval.toRecord()
    } catch (_: IllegalArgumentException) {
        throw SensorCoreConflictException("Stored local product approval is malformed")
    } catch (_: NoSuchElementException) {
        throw SensorCoreConflictException("Stored local product approval has unsupported typed data")
    }
    if (measurement.eventId != expectedEventId ||
        measurement.quality != "valid" ||
        measurement.publicationApprovalId != expectedApprovalId ||
        measurement.publicationBindingId != expectedPublicationBindingId ||
        activeBinding.activeSlot != ACTIVE_PUBLICATION_BINDING_SLOT ||
        activeBinding.publicationBindingId != expectedPublicationBindingId ||
        activeBinding.approvalId != expectedApprovalId ||
        approvalRecord.approvalId != expectedApprovalId ||
        !raw.matchesFreshMeasurement(measurement) ||
        raw.sensorId != approval.sensorId ||
        raw.sensorFamily != approval.sensorFamily ||
        raw.transportVariant != approval.transportVariant ||
        !result.matchesFreshMeasurement(measurement, expectedApprovalId) ||
        !result.matchesApprovedAlgorithm(approval)
    ) {
        throw SensorCoreConflictException("Local product measurement lineage is inconsistent")
    }
    return try {
        measurement.toModel().also { it.requireProductPublication() }
    } catch (_: IllegalArgumentException) {
        throw SensorCoreConflictException("Stored local product measurement is malformed")
    } catch (_: NoSuchElementException) {
        throw SensorCoreConflictException("Stored local product measurement has unsupported typed data")
    }
}

private fun MeasurementEntity.matchesLocalEffect(effect: LocalReadingEffectEntity): Boolean =
    eventId == effect.eventId &&
        quality == "valid" &&
        publicationApprovalId == effect.approvalId &&
        publicationBindingId == effect.publicationBindingId &&
        phoneTimeEpochMs == effect.enqueuedAtEpochMs

private fun RawSensorSampleEntity.matchesFreshMeasurement(
    measurement: MeasurementEntity,
): Boolean = eventId == measurement.eventId &&
    sensorId == measurement.sensorId &&
    sensorFamily == measurement.sensorFamily &&
    sequence.toLong() == measurement.sequence &&
    sensorTimeEpochMs == measurement.sensorTimeEpochMs &&
    phoneTimeEpochMs == measurement.phoneTimeEpochMs &&
    historyDistance == 0 &&
    phoneTimeEpochMs - sensorTimeEpochMs in 0 until MAX_REALTIME_AGE_MS

private fun SensorAlgorithmResultEntity.matchesFreshMeasurement(
    measurement: MeasurementEntity,
    approvalId: String,
): Boolean = eventId == measurement.eventId &&
    sensorId == measurement.sensorId &&
    sequence.toLong() == measurement.sequence &&
    sensorTimeEpochMs == measurement.sensorTimeEpochMs &&
    publishable && alarmEligible && algorithmErrorCode == null &&
    publicationApprovalId == approvalId &&
    (displayedGlucoseMmolL * MG_DL_PER_MMOL_L).roundToInt() == measurement.glucoseMgDl

private fun SensorAlgorithmResultEntity.matchesApprovedAlgorithm(
    approval: PhysicalSensorApprovalEntity,
): Boolean = algorithmProfile == approval.algorithmProfile &&
    algorithmVersion == approval.algorithmVersion &&
    binarySetId == approval.binarySetId &&
    sensitivityToken == approval.sensitivityToken &&
    sensitivityTokenSource == approval.sensitivityTokenSource &&
    sensitivityCoefficient.toBits() == approval.sensitivityCoefficient.toBits() &&
    sensitivityEncoding == approval.sensitivityEncoding &&
    initializationMode == approval.initializationMode

private const val MAX_REALTIME_AGE_MS = 330_000L
private const val MG_DL_PER_MMOL_L = 18.0
