package com.sladkaya.core.data

import android.content.Context
import com.sladkaya.core.model.GlucoseReading

sealed interface ExactProductMeasurementResult {
    data class Exact(val reading: GlucoseReading) : ExactProductMeasurementResult
    data object Missing : ExactProductMeasurementResult
    data class Conflict(val reason: String) : ExactProductMeasurementResult
}

class MeasurementRepository internal constructor(
    private val dao: MeasurementDao,
) {
    suspend fun recent(
        approvalId: String,
        publicationBindingId: String,
        limit: Int = 288,
    ): List<GlucoseReading> {
        require(SHA256.matches(approvalId))
        require(SHA256.matches(publicationBindingId))
        return dao.recentForPublication(
            approvalId = approvalId,
            publicationBindingId = publicationBindingId,
            limit = limit.coerceIn(1, 2_016),
        ).asReversed().map(MeasurementEntity::toModel)
    }

    suspend fun exactProduct(
        eventId: String,
        approvalId: String,
        publicationBindingId: String,
    ): ExactProductMeasurementResult = try {
        when (val result = dao.exactProduct(eventId, approvalId, publicationBindingId)) {
            is ExactProductMeasurementDecision.Exact ->
                ExactProductMeasurementResult.Exact(result.reading)
            ExactProductMeasurementDecision.Missing -> ExactProductMeasurementResult.Missing
        }
    } catch (conflict: SensorCoreConflictException) {
        ExactProductMeasurementResult.Conflict(
            conflict.message?.takeIf(String::isNotBlank) ?: "Product measurement conflict",
        )
    } catch (_: IllegalArgumentException) {
        ExactProductMeasurementResult.Conflict("Product measurement identity is malformed")
    }

    companion object {
        fun create(context: Context): MeasurementRepository =
            MeasurementRepository(SladkayaDatabase.get(context).measurements())
    }
}

private val SHA256 = Regex("^[0-9a-f]{64}$")
