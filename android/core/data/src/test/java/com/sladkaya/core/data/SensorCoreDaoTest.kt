package com.sladkaya.core.data

import com.sladkaya.core.model.GlucoseReading
import com.sladkaya.core.model.ReadingQuality
import com.sladkaya.core.model.SensorFamily
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import java.security.MessageDigest

class SensorCoreDaoTest {
    @Test
    fun commitWritesEveryPartBeforeReplacingCheckpoint() = runBlocking {
        val dao = RecordingSensorCoreDao()

        val disposition = dao.commit(record().toEntityBundle())

        assertEquals(listOf("raw", "result", "measurement", "checkpoint"), dao.calls)
        assertEquals(SensorCoreCommitDisposition.COMMITTED, disposition)
    }

    @Test
    fun nonPublishableStepStillAdvancesRawResultAndCheckpointAtomically() = runBlocking {
        val dao = RecordingSensorCoreDao()
        val publishable = record()
        val withoutMeasurement = publishable.copy(
            result = publishable.result.copy(publishable = false, alarmEligible = false),
            measurement = null,
        ).toEntityBundle()

        dao.commit(withoutMeasurement)

        assertEquals(listOf("raw", "result", "checkpoint"), dao.calls)
    }

    @Test
    fun exactRetryIsAcceptedWithoutReplacingTheSameCheckpoint() = runBlocking {
        val dao = RecordingSensorCoreDao()
        val bundle = record().toEntityBundle()
        dao.commit(bundle)
        dao.calls.clear()

        val disposition = dao.commit(bundle)

        assertEquals(SensorCoreCommitDisposition.ALREADY_COMMITTED, disposition)
        assertEquals(listOf("raw", "result", "measurement"), dao.calls)
        assertEquals(1, dao.savedCheckpoint?.sequence)
    }

    @Test
    fun conflictingRetryIsRejectedWithoutReplacingCheckpoint() = runBlocking {
        val dao = RecordingSensorCoreDao()
        val first = record()
        dao.commit(first.toEntityBundle())
        dao.calls.clear()
        val conflict = first.copy(
            result = first.result.copy(nativeGlucoseMmolL = 9.9),
        )

        assertThrows(SensorCoreConflictException::class.java) {
            runBlocking { dao.commit(conflict.toEntityBundle()) }
        }

        assertEquals(listOf("raw", "result"), dao.calls)
        assertEquals(1, dao.savedCheckpoint?.sequence)
    }

    @Test
    fun olderCheckpointCanNeverReplaceNewerState() = runBlocking {
        val dao = RecordingSensorCoreDao()
        dao.commit(record(sequence = 1).toEntityBundle())
        dao.commit(record(sequence = 2).toEntityBundle())
        dao.calls.clear()

        assertThrows(SensorCoreConflictException::class.java) {
            runBlocking { dao.commit(record(sequence = 1).toEntityBundle()) }
        }

        assertEquals(emptyList<String>(), dao.calls)
        assertEquals(2, dao.savedCheckpoint?.sequence)
    }

    @Test
    fun firstCheckpointCannotPretendAStartedSensorIsFresh() {
        val dao = RecordingSensorCoreDao()

        assertThrows(SensorCoreConflictException::class.java) {
            runBlocking { dao.commit(record(sequence = 2).toEntityBundle()) }
        }

        assertEquals(emptyList<String>(), dao.calls)
    }

    @Test
    fun checkpointGapIsRejectedBeforeAnyWrite() = runBlocking {
        val dao = RecordingSensorCoreDao()
        dao.commit(record(sequence = 1).toEntityBundle())
        dao.calls.clear()

        assertThrows(SensorCoreConflictException::class.java) {
            runBlocking { dao.commit(record(sequence = 3).toEntityBundle()) }
        }

        assertEquals(emptyList<String>(), dao.calls)
        assertEquals(1, dao.savedCheckpoint?.sequence)
    }

    @Test
    fun nextCheckpointMustRepresentExactlyOneMinute() = runBlocking {
        val dao = RecordingSensorCoreDao()
        val first = record(sequence = 1)
        dao.commit(first.toEntityBundle())
        dao.calls.clear()
        val wrongTime = first.raw.sensorTimeEpochMs + 60_001L

        assertThrows(SensorCoreConflictException::class.java) {
            runBlocking { dao.commit(record(sequence = 2, sensorTime = wrongTime).toEntityBundle()) }
        }

        assertEquals(emptyList<String>(), dao.calls)
        assertEquals(1, dao.savedCheckpoint?.sequence)
    }

    @Test
    fun immutableCheckpointProvenanceCannotChangeMidSensor() = runBlocking {
        val dao = RecordingSensorCoreDao()
        dao.commit(record(sequence = 1).toEntityBundle())
        dao.calls.clear()
        val next = record(sequence = 2)
        val changed = next.copy(
            checkpoint = next.checkpoint.copy(transportProtocol = "GS1_UNKNOWN"),
        )

        assertThrows(SensorCoreConflictException::class.java) {
            runBlocking { dao.commit(changed.toEntityBundle()) }
        }

        assertEquals(emptyList<String>(), dao.calls)
        assertEquals(1, dao.savedCheckpoint?.sequence)
    }

    @Test
    fun physicalBluetoothIdentityCannotChangeMidSensor() = runBlocking {
        val dao = RecordingSensorCoreDao()
        dao.commit(record(sequence = 1).toEntityBundle())
        dao.calls.clear()
        val next = record(sequence = 2)
        val changed = next.copy(
            checkpoint = next.checkpoint.copy(bluetoothAddress = "AA:BB:CC:DD:EE:00"),
        )

        assertThrows(SensorCoreConflictException::class.java) {
            runBlocking { dao.commit(changed.toEntityBundle()) }
        }

        assertEquals(emptyList<String>(), dao.calls)
        assertEquals("AA:BB:CC:DD:EE:FF", dao.savedCheckpoint?.bluetoothAddress)
    }

    @Test
    fun physicalBluetoothIdentityCannotBeClaimedByAnotherLogicalSensor() = runBlocking {
        val dao = RecordingSensorCoreDao()
        dao.commit(record(sensorId = "sensor-a").toEntityBundle())
        dao.calls.clear()

        val conflict = assertThrows(SensorCoreConflictException::class.java) {
            runBlocking { dao.commit(record(sensorId = "sensor-b").toEntityBundle()) }
        }

        assertEquals("Bluetooth address is already bound to another sensor", conflict.message)
        assertEquals(emptyList<String>(), dao.calls)
    }

    @Test
    fun uploaderMetadataCannotTurnAnExactCoreRetryIntoConflict() = runBlocking {
        val dao = RecordingSensorCoreDao()
        val bundle = record().toEntityBundle()
        dao.commit(bundle)
        dao.markMeasurementUploaded(bundle.raw.eventId, 1_800_000_000_000L)
        dao.calls.clear()

        val retry = dao.commit(bundle)

        assertEquals(SensorCoreCommitDisposition.ALREADY_COMMITTED, retry)
        assertEquals(listOf("raw", "result", "measurement"), dao.calls)
    }

    @Test
    fun failureBeforeCheckpointPreventsCheckpointReplacement() = runBlocking {
        val dao = RecordingSensorCoreDao(failOnResult = true)

        runCatching { dao.commit(record().toEntityBundle()) }

        assertEquals(listOf("raw", "result"), dao.calls)
    }

    @Test
    fun exactFailureRetryIsIdempotent() = runBlocking {
        val dao = RecordingSensorCoreDao()
        val failure = failure()

        val first = dao.recordFailure(failure.toEntity())
        val retry = dao.recordFailure(failure.toEntity())

        assertEquals(SensorCoreCommitDisposition.COMMITTED, first)
        assertEquals(SensorCoreCommitDisposition.ALREADY_COMMITTED, retry)
        assertEquals(1, dao.failureCount)
    }

    @Test
    fun sameFailureIdWithDifferentEvidenceIsAConflict() = runBlocking {
        val dao = RecordingSensorCoreDao()
        dao.recordFailure(failure().toEntity())

        assertThrows(SensorCoreConflictException::class.java) {
            runBlocking {
                dao.recordFailure(failure(packet = byteArrayOf(1, 2, 3)).toEntity())
            }
        }
        assertEquals(1, dao.failureCount)
    }

    @Test
    fun repeatedCauseWithLaterObservationAndMessageIsDeduplicated() = runBlocking {
        val dao = RecordingSensorCoreDao()
        dao.recordFailure(failure().toEntity())

        val repeated = dao.recordFailure(
            failure(
                message = "same cause described by a newer build",
                phoneTime = 1_700_000_099_000L,
            ).toEntity(),
        )

        assertEquals(SensorCoreCommitDisposition.ALREADY_COMMITTED, repeated)
        assertEquals(1, dao.failureCount)
    }

    @Test
    fun distinctFailureIdsAreBothPreserved() = runBlocking {
        val dao = RecordingSensorCoreDao()

        dao.recordFailure(failure(id = "failure-a").toEntity())
        dao.recordFailure(failure(id = "failure-b").toEntity())

        assertEquals(2, dao.failureCount)
    }

    private fun record(
        sequence: Int = 1,
        sensorTime: Long = 1_700_000_000_000L + sequence * 60_000L,
        sensorId: String = "sensor-a",
    ): AtomicSensorCoreRecord {
        val packet = byteArrayOf(1, 2, sequence.toByte())
        val raw = RawSensorSampleRecord(
            eventId = "$sensorId:event-$sequence",
            sensorId = sensorId,
            sensorFamily = SensorFamily.SIBIONICS_GS1,
            sequence = sequence,
            sensorTimeEpochMs = sensorTime,
            phoneTimeEpochMs = sensorTime + 1_000L,
            packet = packet,
            packetSha256 = packet.sha256(),
            currentRaw = 53,
            temperatureRaw = 322,
            historyDistance = 0,
            transportVariant = 0,
        )
        val result = SensorAlgorithmResultRecord(
            eventId = raw.eventId,
            sensorId = raw.sensorId,
            sequence = raw.sequence,
            sensorTimeEpochMs = raw.sensorTimeEpochMs,
            nativeGlucoseMmolL = 5.7,
            displayedGlucoseMmolL = 5.7,
            nativeTrend = 2,
            glucoseWarning = 0,
            currentWarning = 0,
            temperatureWarning = 0,
            algorithmProfile = "V116A",
            algorithmVersion = "1.1.6A",
            binarySetId = "set",
            sensitivityToken = "ABCDEFGH",
            sensitivityTokenSource = "PACKAGE_CODE",
            sensitivityCoefficient = 1.42,
            sensitivityEncoding = "NORMAL",
            initializationMode = "STANDARD",
            publishable = true,
        )
        val checkpoint = SensorAlgorithmCheckpointRecord(
            sensorId = raw.sensorId,
            bluetoothAddress = "AA:BB:CC:DD:EE:FF",
            sensorFamily = raw.sensorFamily,
            transportVariant = raw.transportVariant,
            transportProtocol = "GS1_V120",
            dataHandleBinarySetId = "datahandle-test",
            sequence = raw.sequence,
            sensorTimeEpochMs = raw.sensorTimeEpochMs,
            algorithmProfile = "V116A",
            algorithmVersion = "1.1.6A",
            binarySetId = "set",
            sensitivityToken = "ABCDEFGH",
            sensitivityTokenSource = "PACKAGE_CODE",
            sensitivityCoefficient = 1.42,
            sensitivityEncoding = "NORMAL",
            initializationMode = "STANDARD",
            state = ByteArray(2_480),
            stateSha256 = ByteArray(2_480).sha256(),
            displayOffsetMmolL = 0.0,
            schemaVersion = 3,
        )
        val measurement = GlucoseReading(
            eventId = raw.eventId,
            sensorId = raw.sensorId,
            sensorFamily = raw.sensorFamily,
            sensorTimeEpochMs = raw.sensorTimeEpochMs,
            phoneTimeEpochMs = raw.phoneTimeEpochMs,
            glucoseMgDl = 103,
            trendMgDlPerMinute = 0.0,
            quality = ReadingQuality.VALID,
            sequence = raw.sequence.toLong(),
        )
        return AtomicSensorCoreRecord(raw, result, checkpoint, measurement)
    }

    private fun failure(
        id: String = "failure-a",
        message: String = "invalid packet",
        phoneTime: Long = 1_700_000_061_000L,
        packet: ByteArray = byteArrayOf(7, 8, 9),
    ): SensorIngestionFailureRecord {
        return SensorIngestionFailureRecord(
            failureId = id,
            sensorId = "sensor-a",
            sensorFamily = SensorFamily.SIBIONICS_GS1,
            sequence = 1,
            reportedSensorTimeEpochSeconds = 1_700_000_060L,
            phoneTimeEpochMs = phoneTime,
            packet = packet,
            packetSha256 = packet.sha256(),
            currentRaw = 50,
            temperatureRaw = 321,
            historyDistance = 0,
            transportVariant = 0,
            failureCode = "INVALID_PACKET",
            failureMessage = message,
            nativeStateMayHaveChanged = false,
        )
    }

    private fun ByteArray.sha256(): String = MessageDigest.getInstance("SHA-256")
        .digest(this)
        .joinToString("") { "%02x".format(it) }
}

private class RecordingSensorCoreDao(
    private val failOnResult: Boolean = false,
) : SensorCoreDao() {
    val calls = mutableListOf<String>()
    private val rawByEvent = mutableMapOf<String, RawSensorSampleEntity>()
    private val resultByEvent = mutableMapOf<String, SensorAlgorithmResultEntity>()
    private val measurementByEvent = mutableMapOf<String, MeasurementEntity>()
    private val checkpoints = mutableMapOf<String, SensorAlgorithmCheckpointEntity>()
    val savedCheckpoint: SensorAlgorithmCheckpointEntity?
        get() = checkpoints.values.singleOrNull()

    override suspend fun insertRaw(value: RawSensorSampleEntity): Long {
        calls += "raw"
        val conflict = rawByEvent.values.any {
            it.eventId == value.eventId || it.sensorId == value.sensorId && it.sequence == value.sequence
        }
        if (conflict) return -1
        rawByEvent[value.eventId] = value
        return 1
    }

    override suspend fun rawByEvent(eventId: String): RawSensorSampleEntity? = rawByEvent[eventId]

    override suspend fun rawBySequence(sensorId: String, sequence: Int): RawSensorSampleEntity? =
        rawByEvent.values.singleOrNull { it.sensorId == sensorId && it.sequence == sequence }

    override suspend fun insertResult(value: SensorAlgorithmResultEntity): Long {
        calls += "result"
        if (failOnResult) error("result write failed")
        val conflict = resultByEvent.values.any {
            it.eventId == value.eventId || it.sensorId == value.sensorId && it.sequence == value.sequence
        }
        if (conflict) return -1
        resultByEvent[value.eventId] = value
        return 1
    }

    override suspend fun resultByEvent(eventId: String): SensorAlgorithmResultEntity? = resultByEvent[eventId]

    override suspend fun resultBySequence(sensorId: String, sequence: Int): SensorAlgorithmResultEntity? =
        resultByEvent.values.singleOrNull { it.sensorId == sensorId && it.sequence == sequence }

    override suspend fun insertMeasurement(value: MeasurementEntity): Long {
        calls += "measurement"
        if (measurementByEvent.containsKey(value.eventId)) return -1
        measurementByEvent[value.eventId] = value
        return 1
    }

    override suspend fun measurement(eventId: String): MeasurementEntity? = measurementByEvent[eventId]

    fun markMeasurementUploaded(eventId: String, uploadedAt: Long) {
        measurementByEvent[eventId] = checkNotNull(measurementByEvent[eventId]).copy(
            uploadedAtEpochMs = uploadedAt,
            uploadAttempts = 2,
        )
    }

    override suspend fun replaceCheckpoint(value: SensorAlgorithmCheckpointEntity) {
        calls += "checkpoint"
        checkpoints[value.sensorId] = value
    }

    override suspend fun checkpoint(sensorId: String): SensorAlgorithmCheckpointEntity? =
        checkpoints[sensorId]

    override suspend fun checkpointByBluetoothAddress(
        bluetoothAddress: String,
    ): SensorAlgorithmCheckpointEntity? = checkpoints.values.singleOrNull {
        it.bluetoothAddress == bluetoothAddress
    }

    private val failures = mutableMapOf<String, SensorIngestionFailureEntity>()
    val failureCount: Int get() = failures.size

    override suspend fun insertFailure(value: SensorIngestionFailureEntity): Long {
        if (failures.containsKey(value.failureId)) return -1
        failures[value.failureId] = value
        return 1
    }

    override suspend fun failure(failureId: String): SensorIngestionFailureEntity? = failures[failureId]
}
