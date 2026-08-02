package com.sladkaya.core.data

import android.content.Context
import com.sladkaya.core.model.GlucoseReading

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

    companion object {
        fun create(context: Context): MeasurementRepository =
            MeasurementRepository(SladkayaDatabase.get(context).measurements())
    }
}

private val SHA256 = Regex("^[0-9a-f]{64}$")
