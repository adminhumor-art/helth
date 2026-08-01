package com.sladkaya.core.data

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "sensor_packet_ingress_outcomes")
internal data class SensorPacketIngressOutcomeEntity(
    @PrimaryKey val ingressId: String,
    val status: String,
    val handledAtEpochMs: Long,
    val detail: String?,
)

internal fun SensorPacketIngressOutcomeRecord.toEntity() = SensorPacketIngressOutcomeEntity(
    ingressId = ingressId,
    status = status.name,
    handledAtEpochMs = handledAtEpochMs,
    detail = detail,
)
