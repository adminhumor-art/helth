package com.sladkaya.core.data

import android.content.Context
import com.sladkaya.core.model.GlucoseReading

class MeasurementRepository private constructor(
    private val dao: MeasurementDao,
) {
    suspend fun enqueue(reading: GlucoseReading) {
        reading.requireProductPublication()
        dao.insert(reading.toEntity())
    }

    suspend fun recent(limit: Int = 288): List<GlucoseReading> =
        dao.recent(limit.coerceIn(1, 2_016)).asReversed().map(MeasurementEntity::toModel)

    suspend fun pending(limit: Int = 100): List<GlucoseReading> =
        dao.pending(limit.coerceIn(1, 500)).map(MeasurementEntity::toModel)

    suspend fun markUploaded(eventId: String, uploadedAtEpochMs: Long) {
        dao.markUploaded(eventId, uploadedAtEpochMs)
    }

    suspend fun markAttemptFailed(eventId: String) {
        dao.markAttemptFailed(eventId)
    }

    suspend fun discardSimulation(eventId: String): Boolean =
        dao.deleteSimulation(eventId) > 0

    companion object {
        fun create(context: Context): MeasurementRepository =
            MeasurementRepository(SladkayaDatabase.get(context).measurements())
    }
}
