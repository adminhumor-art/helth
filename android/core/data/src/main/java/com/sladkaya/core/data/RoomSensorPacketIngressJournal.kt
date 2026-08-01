package com.sladkaya.core.data

import android.content.Context

class RoomSensorPacketIngressJournal internal constructor(
    private val dao: SensorPacketIngressDao,
) : SensorPacketIngressJournal {
    override suspend fun append(record: SensorPacketIngressRecord): SensorPacketIngressAppendResult = try {
        when (dao.append(record.toEntity())) {
            SensorPacketIngressDisposition.APPENDED -> SensorPacketIngressAppendResult.Appended
            SensorPacketIngressDisposition.ALREADY_APPENDED -> SensorPacketIngressAppendResult.AlreadyAppended
        }
    } catch (conflict: SensorPacketIngressConflictException) {
        SensorPacketIngressAppendResult.Conflict(
            conflict.message ?: "Ingress identity conflicts with different contents",
        )
    }

    override suspend fun pending(
        sensorId: String,
        canonicalMac: String,
    ): List<SensorPacketIngressRecord> {
        require(sensorId.isNotBlank() && sensorId.length <= MAX_ID_CHARS)
        require(CANONICAL_BLUETOOTH_ADDRESS.matches(canonicalMac))
        return dao.pending(sensorId, canonicalMac).map(SensorPacketIngressEntity::toRecord)
    }

    override suspend fun markHandled(
        record: SensorPacketIngressOutcomeRecord,
    ): SensorPacketIngressMarkHandledResult = try {
        when (dao.markHandled(record.toEntity())) {
            SensorPacketIngressOutcomeDisposition.MARKED_HANDLED ->
                SensorPacketIngressMarkHandledResult.MarkedHandled
            SensorPacketIngressOutcomeDisposition.ALREADY_HANDLED ->
                SensorPacketIngressMarkHandledResult.AlreadyHandled
        }
    } catch (conflict: SensorPacketIngressConflictException) {
        SensorPacketIngressMarkHandledResult.Conflict(
            conflict.message ?: "Ingress outcome conflict",
        )
    }

    companion object {
        private const val MAX_ID_CHARS = 128
        private val CANONICAL_BLUETOOTH_ADDRESS = Regex("^(?:[0-9A-F]{2}:){5}[0-9A-F]{2}$")

        fun create(context: Context): RoomSensorPacketIngressJournal =
            RoomSensorPacketIngressJournal(SladkayaDatabase.get(context).sensorPacketIngress())
    }
}
