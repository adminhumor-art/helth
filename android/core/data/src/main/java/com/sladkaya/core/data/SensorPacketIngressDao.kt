package com.sladkaya.core.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction

@Dao
internal abstract class SensorPacketIngressDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    abstract suspend fun insert(value: SensorPacketIngressEntity): Long

    @Query("SELECT * FROM sensor_packet_ingress WHERE ingressId = :ingressId LIMIT 1")
    abstract suspend fun byIngressId(ingressId: String): SensorPacketIngressEntity?

    @Query(
        "SELECT * FROM sensor_packet_ingress " +
            "WHERE attemptId = :attemptId AND ordinal = :ordinal LIMIT 1",
    )
    abstract suspend fun byAttemptOrdinal(attemptId: String, ordinal: Long): SensorPacketIngressEntity?

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    abstract suspend fun insertOutcome(value: SensorPacketIngressOutcomeEntity): Long

    @Query("SELECT * FROM sensor_packet_ingress_outcomes WHERE ingressId = :ingressId LIMIT 1")
    abstract suspend fun outcomeByIngressId(ingressId: String): SensorPacketIngressOutcomeEntity?

    @Query(
        "SELECT ingress.* FROM sensor_packet_ingress AS ingress " +
            "LEFT JOIN sensor_packet_ingress_outcomes AS outcome " +
            "ON outcome.ingressId = ingress.ingressId " +
            "WHERE ingress.sensorId = :sensorId " +
            "AND ingress.bluetoothAddress = :bluetoothAddress " +
            "AND outcome.ingressId IS NULL " +
            "ORDER BY ingress.receivedAtEpochMs ASC, ingress.attemptId ASC, ingress.ordinal ASC",
    )
    abstract suspend fun pending(
        sensorId: String,
        bluetoothAddress: String,
    ): List<SensorPacketIngressEntity>

    @Transaction
    open suspend fun append(value: SensorPacketIngressEntity): SensorPacketIngressDisposition {
        if (insert(value) != INSERT_IGNORED) return SensorPacketIngressDisposition.APPENDED

        val byId = byIngressId(value.ingressId)
        val byAttemptOrdinal = byAttemptOrdinal(value.attemptId, value.ordinal)
        if (byId?.sameAs(value) == true && byAttemptOrdinal?.sameAs(value) == true) {
            return SensorPacketIngressDisposition.ALREADY_APPENDED
        }
        throw SensorPacketIngressConflictException("Ingress identity conflicts with different contents")
    }

    @Transaction
    open suspend fun markHandled(
        value: SensorPacketIngressOutcomeEntity,
    ): SensorPacketIngressOutcomeDisposition {
        if (byIngressId(value.ingressId) == null) {
            throw SensorPacketIngressConflictException("Ingress does not exist")
        }
        if (insertOutcome(value) != INSERT_IGNORED) {
            return SensorPacketIngressOutcomeDisposition.MARKED_HANDLED
        }
        if (outcomeByIngressId(value.ingressId) == value) {
            return SensorPacketIngressOutcomeDisposition.ALREADY_HANDLED
        }
        throw SensorPacketIngressConflictException("Ingress outcome conflicts with existing outcome")
    }

    private companion object {
        const val INSERT_IGNORED = -1L
    }
}

internal enum class SensorPacketIngressDisposition {
    APPENDED,
    ALREADY_APPENDED,
}

internal enum class SensorPacketIngressOutcomeDisposition {
    MARKED_HANDLED,
    ALREADY_HANDLED,
}

internal class SensorPacketIngressConflictException(message: String) : IllegalStateException(message)

private fun SensorPacketIngressEntity.sameAs(other: SensorPacketIngressEntity): Boolean =
    ingressId == other.ingressId &&
        sensorId == other.sensorId &&
        sensorFamily == other.sensorFamily &&
        bluetoothAddress == other.bluetoothAddress &&
        attemptId == other.attemptId &&
        ordinal == other.ordinal &&
        receivedAtEpochMs == other.receivedAtEpochMs &&
        encryptedPacket.contentEquals(other.encryptedPacket) &&
        packetSha256 == other.packetSha256
