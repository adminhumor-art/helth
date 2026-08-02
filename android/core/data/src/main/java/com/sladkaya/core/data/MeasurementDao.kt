package com.sladkaya.core.data

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Transaction
import com.sladkaya.core.model.GlucoseReading

internal sealed interface ExactProductMeasurementDecision {
    data class Exact(val reading: GlucoseReading) : ExactProductMeasurementDecision
    data object Missing : ExactProductMeasurementDecision
}

@Dao
internal abstract class MeasurementDao {
    @Query(
        "SELECT * FROM measurements WHERE publicationApprovalId = :approvalId " +
            "AND publicationBindingId = :publicationBindingId " +
            "ORDER BY sensorTimeEpochMs DESC LIMIT :limit",
    )
    abstract suspend fun recentForPublication(
        approvalId: String,
        publicationBindingId: String,
        limit: Int,
    ): List<MeasurementEntity>

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

    @Transaction
    open suspend fun exactProduct(
        eventId: String,
        approvalId: String,
        publicationBindingId: String,
    ): ExactProductMeasurementDecision {
        require(eventId.isNotBlank())
        require(SHA256.matches(approvalId))
        require(SHA256.matches(publicationBindingId))
        val measurement = measurement(eventId) ?: return ExactProductMeasurementDecision.Missing
        return ExactProductMeasurementDecision.Exact(
            validateExactLocalProductMeasurement(
                measurement = measurement,
                raw = raw(eventId)
                    ?: throw SensorCoreConflictException("Product measurement has no raw lineage"),
                result = result(eventId)
                    ?: throw SensorCoreConflictException("Product measurement has no algorithm lineage"),
                activeBinding = activeSensorBinding()
                    ?: throw SensorCoreConflictException("Product measurement has no active local binding"),
                approval = physicalApproval(approvalId)
                    ?: throw SensorCoreConflictException("Product measurement has no physical approval"),
                expectedEventId = eventId,
                expectedApprovalId = approvalId,
                expectedPublicationBindingId = publicationBindingId,
            ),
        )
    }

    private companion object {
        val SHA256 = Regex("^[0-9a-f]{64}$")
    }
}
