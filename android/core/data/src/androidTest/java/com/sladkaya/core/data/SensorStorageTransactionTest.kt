package com.sladkaya.core.data

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.security.MessageDigest
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SensorStorageTransactionTest {
    private lateinit var database: SladkayaDatabase

    @Before
    fun createDatabase() {
        database = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            SladkayaDatabase::class.java,
        ).allowMainThreadQueries().build()
    }

    @After
    fun closeDatabase() {
        database.close()
    }

    @Test
    fun freshDatabaseStartsWithTheCompleteVersionOneSchema() {
        val sqlite = database.openHelper.writableDatabase

        assertEquals(1, sqlite.version)
        listOf(
            "measurements",
            "sensor_raw_samples",
            "sensor_algorithm_results",
            "sensor_algorithm_checkpoints",
            "sensor_ingestion_failures",
            "sensor_packet_ingress",
            "sensor_packet_ingress_outcomes",
        ).forEach { table ->
            sqlite.query(
                "SELECT COUNT(*) FROM sqlite_master WHERE type='table' AND name=?",
                arrayOf(table),
            ).use { found ->
                assertTrue(found.moveToFirst())
                assertEquals("missing table $table", 1, found.getInt(0))
            }
        }
    }

    @Test
    fun closeAndReopenCurrentDatabaseRestoresCheckpointAndAcceptsTheNextCommit() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val databaseName = "current-v1-reopen.db"
        context.deleteDatabase(databaseName)
        try {
            val first = Room.databaseBuilder(
                context,
                SladkayaDatabase::class.java,
                databaseName,
            ).build()
            try {
                assertEquals(
                    SensorCoreCommitDisposition.COMMITTED,
                    first.sensorCore().commit(coreBundle(sequence = 1)),
                )
            } finally {
                first.close()
            }

            val reopened = Room.databaseBuilder(
                context,
                SladkayaDatabase::class.java,
                databaseName,
            ).build()
            try {
                val restored = requireNotNull(reopened.sensorCore().checkpoint("sensor-a"))
                assertEquals(1, restored.sequence)
                assertEquals(
                    SensorCoreCommitDisposition.COMMITTED,
                    reopened.sensorCore().commit(coreBundle(sequence = 2)),
                )
                assertEquals(2, reopened.sensorCore().checkpoint("sensor-a")?.sequence)
                assertTableCount(reopened, "sensor_raw_samples", 2)
                assertTableCount(reopened, "sensor_algorithm_results", 2)
            } finally {
                reopened.close()
            }
        } finally {
            context.deleteDatabase(databaseName)
        }
    }

    @Test
    fun closeAndReopenRestoresCheckpointAndPendingIngressBeforeContinuingSession() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val databaseName = "current-v1-runtime-recovery.db"
        val encryptedPacket = byteArrayOf(9, 8, 7, 6)
        val pendingIngress = ingress(
            ingressId = "attempt-before-stop:0",
            attemptId = "attempt-before-stop",
            encryptedPacket = encryptedPacket,
        ).toRecord()
        context.deleteDatabase(databaseName)
        try {
            val first = Room.databaseBuilder(
                context,
                SladkayaDatabase::class.java,
                databaseName,
            ).build()
            try {
                assertEquals(
                    SensorCoreCommitDisposition.COMMITTED,
                    first.sensorCore().commit(coreBundle(sequence = 1)),
                )
                assertEquals(
                    SensorPacketIngressAppendResult.Appended,
                    RoomSensorPacketIngressJournal(first.sensorPacketIngress()).append(pendingIngress),
                )
            } finally {
                first.close()
            }

            val reopened = Room.databaseBuilder(
                context,
                SladkayaDatabase::class.java,
                databaseName,
            ).build()
            try {
                assertEquals(1, reopened.sensorCore().checkpoint("sensor-a")?.sequence)
                val journal = RoomSensorPacketIngressJournal(reopened.sensorPacketIngress())
                val restored = journal.pending("sensor-a", "AA:BB:CC:DD:EE:FF").single()
                assertEquals(pendingIngress.ingressId, restored.ingressId)
                assertEquals(pendingIngress.sensorFamily, restored.sensorFamily)
                assertEquals(pendingIngress.attemptId, restored.attemptId)
                assertEquals(pendingIngress.ordinal, restored.ordinal)
                assertEquals(pendingIngress.receivedAtEpochMs, restored.receivedAtEpochMs)
                assertArrayEquals(encryptedPacket, restored.encryptedPacketCopy())

                assertEquals(
                    SensorCoreCommitDisposition.COMMITTED,
                    reopened.sensorCore().commit(coreBundle(sequence = 2)),
                )
                assertEquals(
                    SensorPacketIngressMarkHandledResult.MarkedHandled,
                    journal.markHandled(
                        SensorPacketIngressOutcomeRecord(
                            ingressId = restored.ingressId,
                            status = SensorPacketIngressOutcomeStatus.CORE_COMMITTED,
                            handledAtEpochMs = restored.receivedAtEpochMs,
                            detail = null,
                        ),
                    ),
                )
            } finally {
                reopened.close()
            }

            val verified = Room.databaseBuilder(
                context,
                SladkayaDatabase::class.java,
                databaseName,
            ).build()
            try {
                assertEquals(2, verified.sensorCore().checkpoint("sensor-a")?.sequence)
                assertTrue(
                    RoomSensorPacketIngressJournal(verified.sensorPacketIngress())
                        .pending("sensor-a", "AA:BB:CC:DD:EE:FF")
                        .isEmpty(),
                )
                assertEquals(
                    "CORE_COMMITTED",
                    verified.sensorPacketIngress()
                        .outcomeByIngressId(pendingIngress.ingressId)
                        ?.status,
                )
            } finally {
                verified.close()
            }
        } finally {
            context.deleteDatabase(databaseName)
        }
    }

    @Test
    fun lateMeasurementConflictRollsBackRawResultAndCheckpointAsOneRoomTransaction() = runBlocking {
        val dao = database.sensorCore()
        val value = coreBundle()
        val conflictingMeasurement = requireNotNull(value.measurement).copy(glucoseMgDl = 999)
        dao.insertMeasurement(conflictingMeasurement)

        val conflict = assertThrows(SensorCoreConflictException::class.java) {
            runBlocking { dao.commit(value) }
        }

        assertEquals("Measurement conflicts with an existing event", conflict.message)
        assertNull(dao.rawByEvent(value.raw.eventId))
        assertNull(dao.resultByEvent(value.result.eventId))
        assertNull(dao.checkpoint(value.checkpoint.sensorId))
        assertEquals(conflictingMeasurement, dao.measurement(value.raw.eventId))
        assertTableCount("sensor_raw_samples", 0)
        assertTableCount("sensor_algorithm_results", 0)
        assertTableCount("sensor_algorithm_checkpoints", 0)
        assertTableCount("measurements", 1)
    }

    @Test
    fun exactCoreRetryIsIdempotentAgainstGeneratedRoomDao() = runBlocking {
        val dao = database.sensorCore()
        val value = coreBundle()

        assertEquals(SensorCoreCommitDisposition.COMMITTED, dao.commit(value))
        assertEquals(SensorCoreCommitDisposition.ALREADY_COMMITTED, dao.commit(value.copy()))

        assertTableCount("sensor_raw_samples", 1)
        assertTableCount("sensor_algorithm_results", 1)
        assertTableCount("sensor_algorithm_checkpoints", 1)
        assertTableCount("measurements", 1)
        assertArrayEquals(value.raw.packet, dao.rawByEvent(value.raw.eventId)?.packet)
        assertArrayEquals(value.checkpoint.state, dao.checkpoint(value.checkpoint.sensorId)?.state)
    }

    @Test
    fun ingressAndTerminalOutcomeRemainAppendOnlyAndIdempotentInRealSqlite() = runBlocking {
        val dao = database.sensorPacketIngress()
        val ingress = ingress()
        val outcome = SensorPacketIngressOutcomeEntity(
            ingressId = ingress.ingressId,
            status = "CORE_COMMITTED",
            handledAtEpochMs = 1_700_000_001_000L,
            detail = "durably committed",
        )

        assertEquals(SensorPacketIngressDisposition.APPENDED, dao.append(ingress))
        assertEquals(
            SensorPacketIngressDisposition.ALREADY_APPENDED,
            dao.append(ingress.copy(encryptedPacket = ingress.encryptedPacket.copyOf())),
        )
        assertThrows(SensorPacketIngressConflictException::class.java) {
            runBlocking {
                dao.append(
                    ingress.copy(
                        encryptedPacket = byteArrayOf(9, 8, 7),
                        packetSha256 = byteArrayOf(9, 8, 7).sha256(),
                    ),
                )
            }
        }

        assertEquals(
            SensorPacketIngressOutcomeDisposition.MARKED_HANDLED,
            dao.markHandled(outcome),
        )
        assertEquals(
            SensorPacketIngressOutcomeDisposition.ALREADY_HANDLED,
            dao.markHandled(outcome.copy()),
        )
        assertThrows(SensorPacketIngressConflictException::class.java) {
            runBlocking { dao.markHandled(outcome.copy(status = "QUARANTINED")) }
        }

        val savedIngress = requireNotNull(dao.byIngressId(ingress.ingressId))
        val savedOutcome = requireNotNull(dao.outcomeByIngressId(ingress.ingressId))
        assertArrayEquals(ingress.encryptedPacket, savedIngress.encryptedPacket)
        assertEquals(ingress.packetSha256, savedIngress.packetSha256)
        assertEquals(outcome, savedOutcome)
        assertTrue(dao.pending(ingress.sensorId, ingress.bluetoothAddress).isEmpty())
        assertTableCount("sensor_packet_ingress", 1)
        assertTableCount("sensor_packet_ingress_outcomes", 1)
    }

    private fun coreBundle(sequence: Int = 1): SensorCoreEntityBundle {
        val packet = byteArrayOf(1, 2, 3)
        val state = ByteArray(2_480) { index -> ((index + sequence) % 251).toByte() }
        val sensorTime = 1_700_000_000_000L + sequence * 60_000L
        val raw = RawSensorSampleEntity(
            eventId = "event-$sequence",
            sensorId = "sensor-a",
            sensorFamily = "sibionics_gs1",
            sequence = sequence,
            sensorTimeEpochMs = sensorTime,
            phoneTimeEpochMs = sensorTime + 1_000L,
            packet = packet,
            packetSha256 = packet.sha256(),
            currentRaw = 53,
            temperatureRaw = 322,
            historyDistance = 0,
            transportVariant = 0,
            sensorTimeWasClamped = false,
            addTimeSeconds = null,
        )
        val result = SensorAlgorithmResultEntity(
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
            binarySetId = "algorithm-set",
            sensitivityToken = "ABCDEFGH",
            sensitivityTokenSource = "PACKAGE_CODE",
            sensitivityCoefficient = 1.42,
            sensitivityEncoding = "NORMAL",
            initializationMode = "STANDARD",
            publishable = true,
            alarmEligible = true,
            algorithmErrorCode = null,
        )
        val checkpoint = SensorAlgorithmCheckpointEntity(
            sensorId = raw.sensorId,
            bluetoothAddress = "AA:BB:CC:DD:EE:FF",
            sensorFamily = raw.sensorFamily,
            transportVariant = raw.transportVariant,
            transportProtocol = "GS1_V120",
            transportCodecId = "transport-codec-test",
            sequence = raw.sequence,
            sensorTimeEpochMs = raw.sensorTimeEpochMs,
            algorithmProfile = result.algorithmProfile,
            algorithmVersion = result.algorithmVersion,
            binarySetId = result.binarySetId,
            sensitivityToken = result.sensitivityToken,
            sensitivityTokenSource = result.sensitivityTokenSource,
            sensitivityCoefficient = result.sensitivityCoefficient,
            sensitivityEncoding = result.sensitivityEncoding,
            initializationMode = result.initializationMode,
            state = state,
            stateSha256 = state.sha256(),
            displayOffsetMmolL = 0.0,
            schemaVersion = 1,
        )
        return SensorCoreEntityBundle(
            raw = raw,
            result = result,
            checkpoint = checkpoint,
            measurement = MeasurementEntity(
                eventId = raw.eventId,
                sensorId = raw.sensorId,
                sensorFamily = raw.sensorFamily,
                sensorTimeEpochMs = raw.sensorTimeEpochMs,
                phoneTimeEpochMs = raw.phoneTimeEpochMs,
                glucoseMgDl = 103,
                trendMgDlPerMinute = 0.0,
                quality = "valid",
                sequence = raw.sequence.toLong(),
            ),
        )
    }

    private fun ingress(
        ingressId: String = "ingress-a",
        attemptId: String = "attempt-a",
        encryptedPacket: ByteArray = byteArrayOf(1, 2, 3),
    ): SensorPacketIngressEntity {
        return SensorPacketIngressEntity(
            ingressId = ingressId,
            sensorId = "sensor-a",
            sensorFamily = "sibionics_gs1",
            bluetoothAddress = "AA:BB:CC:DD:EE:FF",
            attemptId = attemptId,
            ordinal = 0,
            receivedAtEpochMs = 1_700_000_000_000L,
            encryptedPacket = encryptedPacket,
            packetSha256 = encryptedPacket.sha256(),
        )
    }

    private fun assertTableCount(table: String, expected: Int) =
        assertTableCount(database, table, expected)

    private fun assertTableCount(
        source: SladkayaDatabase,
        table: String,
        expected: Int,
    ) {
        source.openHelper.writableDatabase.query("SELECT COUNT(*) FROM `$table`").use { count ->
            assertTrue(count.moveToFirst())
            assertEquals(expected, count.getInt(0))
        }
    }

    private fun ByteArray.sha256(): String = MessageDigest.getInstance("SHA-256")
        .digest(this)
        .joinToString(separator = "") { byte -> "%02x".format(byte) }
}
