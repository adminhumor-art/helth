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
    val publicationApprovalId: String? = null,
    val publicationBindingId: String? = null,
    val remotePublicationBindingId: String? = null,
    val httpsOrigin: String? = null,
    val backendBindingId: String? = null,
    val credentialId: String? = null,
    val credentialRevision: Long? = null,
    val expectedPatientId: String? = null,
    val expectedDeviceId: String? = null,
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

internal fun GlucoseReading.toEntity(context: ProductPublicationContext) = MeasurementEntity(
    eventId = eventId,
    sensorId = sensorId,
    sensorFamily = sensorFamily.wireName,
    sensorTimeEpochMs = sensorTimeEpochMs,
    phoneTimeEpochMs = phoneTimeEpochMs,
    glucoseMgDl = glucoseMgDl,
    trendMgDlPerMinute = trendMgDlPerMinute,
    quality = quality.wireName,
    sequence = sequence,
    publicationApprovalId = context.approvalId,
    publicationBindingId = context.publicationBindingId,
    remotePublicationBindingId = context.remotePublicationBindingId,
    httpsOrigin = context.httpsOrigin,
    backendBindingId = context.backendBindingId,
    credentialId = context.credentialId,
    credentialRevision = context.credentialRevision,
    expectedPatientId = context.expectedPatientId,
    expectedDeviceId = context.expectedDeviceId,
)

internal fun GlucoseReading.toEntity(
    approvedContext: ApprovedCheckpointContext,
    publicationContext: ProductPublicationContext?,
) = MeasurementEntity(
    eventId = eventId,
    sensorId = sensorId,
    sensorFamily = sensorFamily.wireName,
    sensorTimeEpochMs = sensorTimeEpochMs,
    phoneTimeEpochMs = phoneTimeEpochMs,
    glucoseMgDl = glucoseMgDl,
    trendMgDlPerMinute = trendMgDlPerMinute,
    quality = quality.wireName,
    sequence = sequence,
    publicationApprovalId = approvedContext.approvalId,
    publicationBindingId = approvedContext.publicationBindingId,
    remotePublicationBindingId = publicationContext?.remotePublicationBindingId,
    httpsOrigin = publicationContext?.httpsOrigin,
    backendBindingId = publicationContext?.backendBindingId,
    credentialId = publicationContext?.credentialId,
    credentialRevision = publicationContext?.credentialRevision,
    expectedPatientId = publicationContext?.expectedPatientId,
    expectedDeviceId = publicationContext?.expectedDeviceId,
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
