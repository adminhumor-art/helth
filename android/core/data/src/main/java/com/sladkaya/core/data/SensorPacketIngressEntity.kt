package com.sladkaya.core.data

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import com.sladkaya.core.model.SensorFamily

@Entity(
    tableName = "sensor_packet_ingress",
    indices = [Index(value = ["attemptId", "ordinal"], unique = true)],
)
internal data class SensorPacketIngressEntity(
    @PrimaryKey val ingressId: String,
    val sensorId: String,
    val sensorFamily: String,
    val bluetoothAddress: String,
    val attemptId: String,
    val ordinal: Long,
    val receivedAtEpochMs: Long,
    val encryptedPacket: ByteArray,
    val packetSha256: String,
)

internal fun SensorPacketIngressRecord.toEntity() = SensorPacketIngressEntity(
    ingressId = ingressId,
    sensorId = sensorId,
    sensorFamily = sensorFamily.wireName,
    bluetoothAddress = bluetoothAddress,
    attemptId = attemptId,
    ordinal = ordinal,
    receivedAtEpochMs = receivedAtEpochMs,
    encryptedPacket = encryptedPacketCopy(),
    packetSha256 = packetSha256,
)

internal fun SensorPacketIngressEntity.toRecord() = SensorPacketIngressRecord(
    ingressId = ingressId,
    sensorId = sensorId,
    sensorFamily = SensorFamily.entries.first { it.wireName == sensorFamily },
    bluetoothAddress = bluetoothAddress,
    attemptId = attemptId,
    ordinal = ordinal,
    receivedAtEpochMs = receivedAtEpochMs,
    encryptedPacket = encryptedPacket,
    packetSha256 = packetSha256,
)
