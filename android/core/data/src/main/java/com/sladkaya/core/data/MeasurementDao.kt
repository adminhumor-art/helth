package com.sladkaya.core.data

import androidx.room.Dao
import androidx.room.Query

@Dao
internal interface MeasurementDao {
    @Query(
        "SELECT * FROM measurements WHERE publicationApprovalId = :approvalId " +
            "AND publicationBindingId = :publicationBindingId " +
            "ORDER BY sensorTimeEpochMs DESC LIMIT :limit",
    )
    suspend fun recentForPublication(
        approvalId: String,
        publicationBindingId: String,
        limit: Int,
    ): List<MeasurementEntity>
}
