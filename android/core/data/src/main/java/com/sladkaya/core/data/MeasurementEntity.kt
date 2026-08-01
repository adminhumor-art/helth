package com.sladkaya.core.data

import androidx.room.Entity
import androidx.room.PrimaryKey
import com.sladkaya.core.model.GlucoseReading
import com.sladkaya.core.model.ReadingQuality
import com.sladkaya.core.model.SensorFamily

@Entity(tableName = "measurements")
data class MeasurementEntity(
    @PrimaryKey val eventId: String,
    val sensorId: String,
    val sensorFamily: String,
    val sensorTimeEpochMs: Long,
    val phoneTimeEpochMs: Long,
    val glucoseMgDl: Int,
    val trendMgDlPerMinute: Double,
    val quality: String,
    val sequence: Long,
    val uploadedAtEpochMs: Long? = null,
    val uploadAttempts: Int = 0,
)

internal fun GlucoseReading.toEntity() = MeasurementEntity(
    eventId = eventId,
    sensorId = sensorId,
    sensorFamily = sensorFamily.wireName,
    sensorTimeEpochMs = sensorTimeEpochMs,
    phoneTimeEpochMs = phoneTimeEpochMs,
    glucoseMgDl = glucoseMgDl,
    trendMgDlPerMinute = trendMgDlPerMinute,
    quality = quality.wireName,
    sequence = sequence,
)

internal fun MeasurementEntity.toModel() = GlucoseReading(
    eventId = eventId,
    sensorId = sensorId,
    sensorFamily = SensorFamily.entries.first { it.wireName == sensorFamily },
    sensorTimeEpochMs = sensorTimeEpochMs,
    phoneTimeEpochMs = phoneTimeEpochMs,
    glucoseMgDl = glucoseMgDl,
    trendMgDlPerMinute = trendMgDlPerMinute,
    quality = ReadingQuality.entries.first { it.wireName == quality },
    sequence = sequence,
)
