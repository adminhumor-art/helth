package com.sladkaya.core.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
internal interface MeasurementDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(value: MeasurementEntity): Long

    @Query("SELECT * FROM measurements ORDER BY sensorTimeEpochMs DESC LIMIT :limit")
    suspend fun recent(limit: Int): List<MeasurementEntity>

    @Query("SELECT * FROM measurements WHERE uploadedAtEpochMs IS NULL ORDER BY phoneTimeEpochMs ASC LIMIT :limit")
    suspend fun pending(limit: Int): List<MeasurementEntity>

    @Query("UPDATE measurements SET uploadedAtEpochMs = :uploadedAt WHERE eventId = :eventId AND uploadedAtEpochMs IS NULL")
    suspend fun markUploaded(eventId: String, uploadedAt: Long)

    @Query("UPDATE measurements SET uploadAttempts = uploadAttempts + 1 WHERE eventId = :eventId AND uploadedAtEpochMs IS NULL")
    suspend fun markAttemptFailed(eventId: String)

    @Query("DELETE FROM measurements WHERE eventId = :eventId AND sensorFamily = 'simulator'")
    suspend fun deleteSimulation(eventId: String): Int
}
