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

private val OFFLINE_BINDING_ID = "56".repeat(32)

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
            "sensor_protocol_bindings",
            "physical_sensor_approvals",
            "product_publication_bindings",
            "active_sensor_publication_binding",
            "measurement_upload_outbox",
            "local_reading_effects",
            "local_alarm_monitoring_starts",
            "local_alarm_states",
            "local_alarm_applications",
            "local_alarm_watchdog_applications",
            "local_alarm_deliveries",
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
                    commitCore(first, coreBundle(sequence = 1)),
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
                    commitCore(reopened, coreBundle(sequence = 2)),
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
                    commitCore(first, coreBundle(sequence = 1)),
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
                    commitCore(reopened, coreBundle(sequence = 2)),
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
        appendSourceIngress(database, value)

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

        assertEquals(SensorCoreCommitDisposition.COMMITTED, commitCore(database, value))
        assertEquals(SensorCoreCommitDisposition.ALREADY_COMMITTED, commitCore(database, value.copy()))

        assertTableCount("sensor_raw_samples", 1)
        assertTableCount("sensor_algorithm_results", 1)
        assertTableCount("sensor_algorithm_checkpoints", 1)
        assertTableCount("measurements", 1)
        assertArrayEquals(value.raw.packet, dao.rawByEvent(value.raw.eventId)?.packet)
        assertArrayEquals(value.checkpoint.state, dao.checkpoint(value.checkpoint.sensorId)?.state)
    }

    @Test
    fun coreCommitRejectsPacketThatDoesNotMatchItsExactDurableIngress() = runBlocking {
        val dao = database.sensorCore()
        val value = coreBundle(publishable = false)
        appendSourceIngress(database, value)
        val differentPacket = byteArrayOf(9, 8, 7)
        val mismatched = value.copy(
            raw = value.raw.copy(
                packet = differentPacket,
                packetSha256 = differentPacket.sha256(),
            ),
        )

        assertThrows(SensorCoreConflictException::class.java) {
            runBlocking { dao.commit(mismatched) }
        }

        assertNull(dao.rawByEvent(value.raw.eventId))
        assertTableCount("sensor_raw_samples", 0)
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

    @Test
    fun exactIngressReaderReturnsOnlyRowsAtomicallyCommittedFromThatIngress() = runBlocking {
        val dao = approvedDaoWithDiagnosticCheckpoint()
        val packet = byteArrayOf(7, 8, 9, 10)
        val receivedAt = 1_700_000_500_000L
        val ingress = ingress(
            ingressId = "product-attempt:0",
            attemptId = "product-attempt",
            encryptedPacket = packet,
            receivedAtEpochMs = receivedAt,
        )
        database.sensorPacketIngress().append(ingress)
        commitCore(
            database,
            coreBundle(
                sequence = 2,
                publishable = true,
                sourceIngressId = ingress.ingressId,
                packet = packet,
                phoneTimeEpochMs = receivedAt,
            ),
        )

        val result = RoomCommittedSensorIngressReader(database.committedSensorIngress())
            .read(ingress.toRecord()) as CommittedSensorIngressReadResult.Exact

        assertEquals(listOf(2), result.samples.map { it.raw.sequence })
        val sample = result.samples.single()
        assertEquals(ingress.ingressId, sample.raw.sourceIngressId)
        assertArrayEquals(packet, sample.raw.packetCopy())
        assertEquals(receivedAt, sample.raw.phoneTimeEpochMs)
        val publication = requireNotNull(sample.productPublication)
        assertEquals("event-2", publication.reading.eventId)
        assertEquals(physicalApprovalRecord().approvalId, publication.approvalId)
        assertEquals(publicationBindingRecord().publicationBindingId, publication.publicationBindingId)
    }

    @Test
    fun exactIngressReaderFailsClosedWhenTheEvidenceTupleIsAmbiguous() = runBlocking {
        val packet = byteArrayOf(7, 8, 9, 10)
        val receivedAt = 1_700_000_500_000L
        val first = ingress(
            ingressId = "attempt-a:0",
            attemptId = "attempt-a",
            encryptedPacket = packet,
            receivedAtEpochMs = receivedAt,
        )
        val second = ingress(
            ingressId = "attempt-b:0",
            attemptId = "attempt-b",
            encryptedPacket = packet,
            receivedAtEpochMs = receivedAt,
        )
        database.sensorPacketIngress().append(first)
        database.sensorPacketIngress().append(second)

        val result = RoomCommittedSensorIngressReader(database.committedSensorIngress())
            .read(first.toRecord())

        assertTrue(result is CommittedSensorIngressReadResult.Mismatch)
    }

    @Test
    fun exactIngressReaderFailsClosedForNonCanonicalPublicationLineage() = runBlocking {
        approvedDaoWithDiagnosticCheckpoint()
        val value = coreBundle(sequence = 2, publishable = true)
        commitCore(database, value)
        database.openHelper.writableDatabase.execSQL(
            "UPDATE product_publication_bindings SET createdAtEpochMs = 0 " +
                "WHERE publicationBindingId = ?",
            arrayOf<Any>(publicationBindingRecord().publicationBindingId),
        )

        val result = RoomCommittedSensorIngressReader(database.committedSensorIngress()).read(
            requireNotNull(
                database.sensorPacketIngress().byIngressId(value.raw.sourceIngressId),
            ).toRecord(),
        )

        assertTrue(result is CommittedSensorIngressReadResult.Mismatch)
    }

    @Test
    fun physicalApprovalIsCanonicalAppendOnlyAndExactRetryIsIdempotent() = runBlocking {
        val dao = database.sensorCore()
        val diagnostic = coreBundle(sequence = 1, publishable = false)
        dao.bindProtocol(protocolBinding())
        commitCore(database, diagnostic)
        val approval = physicalApproval()

        assertEquals(SensorCoreCommitDisposition.COMMITTED, dao.approvePhysicalSensor(approval))
        assertEquals(SensorCoreCommitDisposition.ALREADY_COMMITTED, dao.approvePhysicalSensor(approval.copy()))
        assertEquals(approval, dao.physicalApproval(approval.approvalId))

        assertThrows(SensorCoreConflictException::class.java) {
            runBlocking {
                dao.approvePhysicalSensor(
                    approval.copy(algorithmVersion = "tampered-without-new-canonical-id"),
                )
            }
        }
        assertEquals(approval, dao.physicalApproval(approval.approvalId))
        assertTableCount("physical_sensor_approvals", 1)
    }

    @Test
    fun approvalMustExactlyMatchTheDurableBindingAndCheckpointProvenance() = runBlocking {
        val dao = database.sensorCore()
        dao.bindProtocol(protocolBinding())
        commitCore(database, coreBundle(sequence = 1, publishable = false))

        assertThrows(SensorCoreConflictException::class.java) {
            runBlocking {
                dao.approvePhysicalSensor(
                    physicalApproval().copy(algorithmVersion = "different-algorithm"),
                )
            }
        }
        assertNull(dao.physicalApproval(physicalApproval().approvalId))
        assertTableCount("physical_sensor_approvals", 0)
    }

    @Test
    fun prototypeApprovalAndLocalBindingRollbackTogetherOnLateActivationFailure() = runBlocking {
        val dao = database.sensorCore()
        dao.bindProtocol(protocolBinding())
        (1..46).forEach { sequence ->
            val diagnostic = coreBundle(sequence = sequence, publishable = false).let { value ->
                value.copy(result = value.result.copy(algorithmErrorCode = null))
            }
            commitCore(database, diagnostic)
        }
        val eventId = "event-46"
        val anchor = PhysicalSensorDiagnosticAnchor(
            protocol = checkNotNull(dao.protocolBinding("sensor-a")).toRecord(),
            raw = checkNotNull(dao.rawByEvent(eventId)).toPhysicalActivationRecord(),
            result = checkNotNull(dao.resultByEvent(eventId)).toPhysicalActivationRecord(),
            checkpoint = checkNotNull(dao.checkpoint("sensor-a")).toRecord(),
        )
        val approval = PhysicalSensorActivationIdentity.localPrototypeApproval(
            anchor = anchor,
            nativeBinarySetSha256 = "12".repeat(32),
            nativeDatahandleBinarySetSha256 = "34".repeat(32),
        ).toEntity()

        assertThrows(IllegalArgumentException::class.java) {
            runBlocking {
                dao.approveAndActivatePhysicalSensor(
                    diagnosticEventId = eventId,
                    approval = approval,
                    publicationBindingId = "not-a-sha256",
                    expectedPreviousPublicationBindingId = null,
                )
            }
        }

        assertNull(dao.physicalApproval(approval.approvalId))
        assertNull(dao.activeSensorBinding())
        assertTableCount("physical_sensor_approvals", 0)
        assertTableCount("active_sensor_publication_binding", 0)
    }

    @Test
    fun publishableCommitCreatesMeasurementRemoteOutboxAndLocalEffectExactlyOnce() = runBlocking {
        val dao = database.sensorCore()
        dao.bindProtocol(protocolBinding())
        commitCore(database, coreBundle(sequence = 1, publishable = false))

        val withoutApproval = coreBundle(sequence = 2, publishable = true)
        appendSourceIngress(database, withoutApproval)
        assertThrows(SensorCoreConflictException::class.java) {
            runBlocking { dao.commit(withoutApproval) }
        }
        assertNull(dao.rawByEvent(withoutApproval.raw.eventId))
        assertNull(dao.outboxByEvent(withoutApproval.raw.eventId))
        assertNull(dao.localEffectByEvent(withoutApproval.raw.eventId))
        assertEquals(1, dao.checkpoint("sensor-a")?.sequence)

        dao.approvePhysicalSensor(physicalApproval())
        dao.activateLocalSensorBinding(
            approvalId = physicalApproval().approvalId,
            publicationBindingId = OFFLINE_BINDING_ID,
            expectedPreviousPublicationBindingId = null,
        )
        dao.activatePublicationBinding(publicationBinding(), null)
        assertEquals(SensorCoreCommitDisposition.COMMITTED, dao.commit(withoutApproval))
        assertEquals(SensorCoreCommitDisposition.ALREADY_COMMITTED, dao.commit(withoutApproval.copy()))

        val outbox = requireNotNull(dao.outboxByEvent(withoutApproval.raw.eventId))
        assertEquals("backend-binding-a", outbox.backendBindingId)
        assertEquals(3L, outbox.credentialRevision)
        assertEquals("PENDING", outbox.state)
        assertEquals(0, outbox.attempts)
        assertEquals(withoutApproval.measurement?.phoneTimeEpochMs, outbox.nextAttemptEpochMs)
        assertNull(outbox.leaseToken)
        assertNull(outbox.leaseExpiresAtEpochMs)
        assertNull(outbox.sanitizedStatus)
        assertNull(outbox.sanitizedDetail)
        val localEffect = requireNotNull(dao.localEffectByEvent(withoutApproval.raw.eventId))
        assertEquals(withoutApproval.raw.eventId, localEffect.eventId)
        assertEquals(physicalApproval().approvalId, localEffect.approvalId)
        assertEquals(publicationBinding().publicationBindingId, localEffect.publicationBindingId)
        assertEquals("PENDING", localEffect.state)
        assertEquals(0, localEffect.attempts)
        assertEquals(withoutApproval.measurement?.phoneTimeEpochMs, localEffect.enqueuedAtEpochMs)
        assertNull(localEffect.leaseToken)
        assertNull(localEffect.leaseExpiresAtEpochMs)
        assertNull(localEffect.acknowledgedAtEpochMs)
        assertTableCount("measurement_upload_outbox", 1)
        assertTableCount("local_reading_effects", 1)

        val leaseNow = requireNotNull(withoutApproval.measurement).phoneTimeEpochMs + 1L
        val lease = database.localReadingEffects().leaseEarliest(
            nowEpochMs = leaseNow,
            leaseToken = "product-commit-local-lease",
            leaseExpiresAtEpochMs = leaseNow + 10_000L,
        ) as LocalReadingEffectLeaseDecision.Leased
        assertEquals(
            SensorCoreCommitDisposition.COMMITTED,
            database.localReadingEffects().acknowledgeEarliest(
                effectId = lease.value.effect.effectId,
                eventId = lease.value.effect.eventId,
                leaseToken = "product-commit-local-lease",
                acknowledgedAtEpochMs = leaseNow + 1_000L,
            ),
        )
        assertEquals(
            SensorCoreCommitDisposition.ALREADY_COMMITTED,
            dao.commit(withoutApproval.copy()),
        )
        assertEquals(
            "ACKNOWLEDGED",
            dao.localEffectByEvent(withoutApproval.raw.eventId)?.state,
        )
        assertTableCount("local_reading_effects", 1)
    }

    @Test
    fun offlineProductCommitReaderAndAlarmSettlementNeedNoRemoteRouteOrOutbox() = runBlocking {
        val dao = database.sensorCore()
        dao.bindProtocol(protocolBinding())
        commitCore(database, coreBundle(sequence = 1, publishable = false))
        dao.approvePhysicalSensor(physicalApproval())
        assertEquals(
            SensorCoreCommitDisposition.COMMITTED,
            dao.activateLocalSensorBinding(
                approvalId = physicalApproval().approvalId,
                publicationBindingId = OFFLINE_BINDING_ID,
                expectedPreviousPublicationBindingId = null,
            ),
        )
        val base = productBundle(sequence = 2, glucoseMgDl = 60)
        val localContext = ApprovedCheckpointContext(
            approvalId = physicalApproval().approvalId,
            publicationBindingId = OFFLINE_BINDING_ID,
            nativeBinarySetSha256 = "12".repeat(32),
            nativeDatahandleBinarySetSha256 = "34".repeat(32),
        )
        val offline = base.copy(
            measurement = requireNotNull(base.measurement).copy(
                trendMgDlPerMinute = 2.6,
                publicationBindingId = OFFLINE_BINDING_ID,
                remotePublicationBindingId = null,
                httpsOrigin = null,
                backendBindingId = null,
                credentialId = null,
                credentialRevision = null,
                expectedPatientId = null,
                expectedDeviceId = null,
            ),
            publicationContext = null,
            approvedCheckpointContext = localContext,
        )

        assertEquals(SensorCoreCommitDisposition.COMMITTED, commitCore(database, offline))
        assertNull(dao.outboxByEvent(offline.raw.eventId))
        assertTableCount("measurement_upload_outbox", 0)
        val effect = requireNotNull(dao.localEffectByEvent(offline.raw.eventId))
        assertEquals(OFFLINE_BINDING_ID, effect.publicationBindingId)

        val recovered = RoomCommittedSensorIngressReader(database.committedSensorIngress()).read(
            requireNotNull(
                database.sensorPacketIngress().byIngressId(offline.raw.sourceIngressId),
            ).toRecord(),
        ) as CommittedSensorIngressReadResult.Exact
        assertEquals(
            OFFLINE_BINDING_ID,
            recovered.samples.single().productPublication?.publicationBindingId,
        )
        val exactMeasurement = MeasurementRepository(database.measurements()).exactProduct(
            eventId = offline.raw.eventId,
            approvalId = physicalApproval().approvalId,
            publicationBindingId = OFFLINE_BINDING_ID,
        ) as ExactProductMeasurementResult.Exact
        assertEquals(offline.raw.eventId, exactMeasurement.reading.eventId)

        val leaseNow = requireNotNull(offline.measurement).phoneTimeEpochMs + 1L
        val lease = database.localReadingEffects().leaseEarliest(
            leaseNow,
            "offline-alarm-lease",
            leaseNow + 30_000L,
        ) as LocalReadingEffectLeaseDecision.Leased
        val applied = database.localAlarms().applyLeased(
            alarmApplyRequest(lease.value, leaseNow + 1_000L),
        ) as LocalAlarmApplyDecision.Applied

        assertEquals(OFFLINE_BINDING_ID, applied.settlement.publicationBindingId)
        assertEquals(
            com.sladkaya.core.model.AlarmKind.LOW,
            applied.settlement.activeKinds.single(),
        )
        assertEquals("ACKNOWLEDGED", dao.localEffectByEvent(offline.raw.eventId)?.state)
    }

    @Test
    fun localBindingStoreCanRotateRemoteRouteWithoutChangingTheLocalBinding() = runBlocking {
        val dao = database.sensorCore()
        dao.bindProtocol(protocolBinding())
        commitCore(database, coreBundle(sequence = 1, publishable = false))
        dao.approvePhysicalSensor(physicalApproval())
        val store = LocalSensorBindingRepository(dao)
        assertEquals(
            LocalSensorBindingActivationResult.Activated,
            store.activate(
                approvalId = physicalApproval().approvalId,
                publicationBindingId = OFFLINE_BINDING_ID,
            ),
        )

        assertEquals(
            LocalSensorBindingActivationResult.Activated,
            store.activateRemote(
                binding = publicationBindingRecord(),
                expectedPreviousRemotePublicationBindingId = null,
            ),
        )
        val active = requireNotNull(store.active())
        assertEquals(publicationBindingRecord(), active.remotePublicationBinding)
        assertEquals(
            LocalSensorBindingActivationResult.AlreadyActive,
            store.activateRemote(
                binding = publicationBindingRecord(),
                expectedPreviousRemotePublicationBindingId = null,
            ),
        )

        val rotated = publicationBindingRecord().copy(
            credentialId = "credential-b",
            credentialRevision = 4L,
            createdAtEpochMs = 1_700_000_002_000L,
        )
        assertEquals(
            LocalSensorBindingActivationResult.Activated,
            store.activateRemote(
                binding = rotated,
                expectedPreviousRemotePublicationBindingId =
                    publicationBindingRecord().remotePublicationBindingId,
            ),
        )
        val afterRotation = requireNotNull(store.active())
        assertEquals(OFFLINE_BINDING_ID, afterRotation.publicationBindingId)
        assertEquals(physicalApproval().approvalId, afterRotation.approval.approvalId)
        assertEquals(rotated, afterRotation.remotePublicationBinding)
        assertEquals(
            publicationBinding(),
            dao.publicationBinding(publicationBindingRecord().remotePublicationBindingId),
        )
    }

    @Test
    fun localEffectInsertFailureRollsBackTheEntireProductCommit() = runBlocking {
        val dao = approvedDaoWithDiagnosticCheckpoint()
        val next = coreBundle(sequence = 2, publishable = true)
        database.openHelper.writableDatabase.execSQL(
            "CREATE TRIGGER reject_local_effect BEFORE INSERT ON local_reading_effects " +
                "BEGIN SELECT RAISE(ABORT, 'local effect rejected'); END",
        )
        appendSourceIngress(database, next)

        assertThrows(RuntimeException::class.java) {
            runBlocking { dao.commit(next) }
        }

        assertNull(dao.rawByEvent(next.raw.eventId))
        assertNull(dao.resultByEvent(next.raw.eventId))
        assertNull(dao.measurement(next.raw.eventId))
        assertNull(dao.outboxByEvent(next.raw.eventId))
        assertNull(dao.localEffectByEvent(next.raw.eventId))
        assertEquals(1, dao.checkpoint("sensor-a")?.sequence)
    }

    @Test
    fun aConflictingOutboxIdentityRollsBackMeasurementAndCheckpointAdvance() = runBlocking {
        val dao = database.sensorCore()
        dao.bindProtocol(protocolBinding())
        commitCore(database, coreBundle(sequence = 1, publishable = false))
        dao.approvePhysicalSensor(physicalApproval())
        dao.activateLocalSensorBinding(
            approvalId = physicalApproval().approvalId,
            publicationBindingId = OFFLINE_BINDING_ID,
            expectedPreviousPublicationBindingId = null,
        )
        dao.activatePublicationBinding(publicationBinding(), null)
        val next = coreBundle(sequence = 2, publishable = true)
        dao.insertOutbox(
            UploadOutboxEntity.pending(
                eventId = next.raw.eventId,
                approvalId = physicalApproval().approvalId,
                publicationBindingId = publicationBinding().publicationBindingId,
                remotePublicationBindingId = publicationBinding().remotePublicationBindingId,
                httpsOrigin = "https://api.sladkaya.test",
                backendBindingId = "wrong-backend-binding",
                credentialId = "credential-a",
                credentialRevision = 3L,
                expectedPatientId = "patient-a",
                expectedDeviceId = "device-a",
                enqueuedAtEpochMs = requireNotNull(next.measurement).phoneTimeEpochMs,
            ),
        )
        appendSourceIngress(database, next)

        assertThrows(SensorCoreConflictException::class.java) {
            runBlocking { dao.commit(next) }
        }

        assertNull(dao.rawByEvent(next.raw.eventId))
        assertNull(dao.resultByEvent(next.result.eventId))
        assertNull(dao.measurement(next.raw.eventId))
        assertEquals(1, dao.checkpoint("sensor-a")?.sequence)
        assertEquals("wrong-backend-binding", dao.outboxByEvent(next.raw.eventId)?.backendBindingId)
    }

    @Test
    fun diagnosticCommitNeverCreatesRemoteOrLocalDeliveryWork() = runBlocking {
        val dao = database.sensorCore()
        dao.bindProtocol(protocolBinding())
        val diagnostic = coreBundle(sequence = 1, publishable = false)

        assertEquals(SensorCoreCommitDisposition.COMMITTED, commitCore(database, diagnostic))

        assertNull(dao.outboxByEvent(diagnostic.raw.eventId))
        assertNull(dao.localEffectByEvent(diagnostic.raw.eventId))
        assertTableCount("measurement_upload_outbox", 0)
        assertTableCount("local_reading_effects", 0)
    }

    @Test
    fun localEffectsLeaseAndAcknowledgeOnlyTheStrictEarliestReading() = runBlocking {
        approvedDaoWithDiagnosticCheckpoint()
        val second = coreBundle(sequence = 2, publishable = true)
        val third = coreBundle(sequence = 3, publishable = true)
        commitCore(database, second)
        commitCore(database, third)
        val dao = database.localReadingEffects()
        val now = requireNotNull(third.measurement).phoneTimeEpochMs + 1L

        val firstLease = dao.leaseEarliest(
            nowEpochMs = now,
            leaseToken = "local-effect-lease-a",
            leaseExpiresAtEpochMs = now + 10_000L,
        ) as LocalReadingEffectLeaseDecision.Leased
        assertEquals(second.raw.eventId, firstLease.value.effect.eventId)
        assertEquals(second.raw.eventId, firstLease.value.reading.eventId)
        assertEquals(1, firstLease.value.effect.attempts)
        assertEquals(
            firstLease,
            dao.leaseEarliest(now, "local-effect-lease-a", now + 10_000L),
        )

        val blocked = dao.leaseEarliest(
            nowEpochMs = now,
            leaseToken = "local-effect-lease-b",
            leaseExpiresAtEpochMs = now + 10_000L,
        )
        assertTrue(blocked is LocalReadingEffectLeaseDecision.BlockedByActiveLease)

        assertThrows(SensorCoreConflictException::class.java) {
            runBlocking {
                dao.acknowledgeEarliest(
                    effectId = firstLease.value.effect.effectId,
                    eventId = firstLease.value.effect.eventId,
                    leaseToken = "wrong-local-lease",
                    acknowledgedAtEpochMs = now + 1_000L,
                )
            }
        }
        assertEquals(
            SensorCoreCommitDisposition.COMMITTED,
            dao.acknowledgeEarliest(
                effectId = firstLease.value.effect.effectId,
                eventId = firstLease.value.effect.eventId,
                leaseToken = "local-effect-lease-a",
                acknowledgedAtEpochMs = now + 1_000L,
            ),
        )
        assertEquals(
            SensorCoreCommitDisposition.ALREADY_COMMITTED,
            dao.acknowledgeEarliest(
                effectId = firstLease.value.effect.effectId,
                eventId = firstLease.value.effect.eventId,
                leaseToken = "local-effect-lease-a",
                acknowledgedAtEpochMs = now + 1_000L,
            ),
        )

        val secondLease = dao.leaseEarliest(
            nowEpochMs = now + 1_001L,
            leaseToken = "local-effect-lease-b",
            leaseExpiresAtEpochMs = now + 20_000L,
        ) as LocalReadingEffectLeaseDecision.Leased
        assertEquals(third.raw.eventId, secondLease.value.effect.eventId)
    }

    @Test
    fun expiredLocalLeaseRetriesTheSameEarliestEffectBeforeLaterReadings() = runBlocking {
        approvedDaoWithDiagnosticCheckpoint()
        val second = coreBundle(sequence = 2, publishable = true)
        val third = coreBundle(sequence = 3, publishable = true)
        commitCore(database, second)
        commitCore(database, third)
        val dao = database.localReadingEffects()
        val now = requireNotNull(third.measurement).phoneTimeEpochMs + 1L

        dao.leaseEarliest(now, "local-effect-lease-a", now + 10_000L)
        val recovered = dao.leaseEarliest(
            nowEpochMs = now + 10_001L,
            leaseToken = "local-effect-lease-b",
            leaseExpiresAtEpochMs = now + 20_000L,
        ) as LocalReadingEffectLeaseDecision.Leased

        assertEquals(second.raw.eventId, recovered.value.effect.eventId)
        assertEquals(2, recovered.value.effect.attempts)
    }

    @Test
    fun leasedReadingAtomicallyWritesAlarmStateDeliveriesApplicationAndEffectAck() = runBlocking {
        approvedDaoWithDiagnosticCheckpoint()
        val low = productBundle(sequence = 2, glucoseMgDl = 60)
        commitCore(database, low)
        val leaseNow = requireNotNull(low.measurement).phoneTimeEpochMs + 1L
        val lease = database.localReadingEffects().leaseEarliest(
            leaseNow,
            "alarm-apply-lease-a",
            leaseNow + 30_000L,
        ) as LocalReadingEffectLeaseDecision.Leased
        val request = alarmApplyRequest(lease.value, leaseNow + 1_000L)

        val applied = database.localAlarms().applyLeased(request)
            as LocalAlarmApplyDecision.Applied

        assertEquals(low.raw.eventId, applied.settlement.eventId)
        assertEquals(setOf(com.sladkaya.core.model.AlarmKind.LOW), applied.settlement.activeKinds)
        val state = requireNotNull(
            database.localAlarms().alarmState(publicationBinding().publicationBindingId),
        ).toRecord()
        assertEquals(lease.value.effect.effectId, state.lastEffectId)
        assertEquals(low.raw.eventId, state.lastEventId)
        assertEquals(setOf(com.sladkaya.core.model.AlarmKind.LOW), state.policyState.active)
        assertEquals(1L, state.episodeGeneration)
        assertEquals(false, state.episodeAcknowledged)
        assertEquals(
            setOf("SHOW", "REPEAT", "WATCHDOG", "WIDGET"),
            database.localAlarms().deliveriesForEffect(lease.value.effect.effectId)
                .mapTo(linkedSetOf(), LocalAlarmDeliveryEntity::kind),
        )
        assertEquals(
            "ACKNOWLEDGED",
            database.sensorCore().localEffectByEvent(low.raw.eventId)?.state,
        )
        assertEquals(
            LocalAlarmApplyDecision.AlreadyApplied(applied.settlement),
            database.localAlarms().applyLeased(request),
        )
        assertTableCount("local_alarm_states", 1)
        assertTableCount("local_alarm_applications", 1)
        assertTableCount("local_alarm_deliveries", 4)
    }

    @Test
    fun monitoringStartIsAtomicAndRestartKeepsTheOriginalSignalLossDeadline() = runBlocking {
        approvedDaoWithDiagnosticCheckpoint()
        val alarms = database.localAlarms()
        val thresholds = AlarmThresholdSnapshot.from(
            com.sladkaya.core.model.AlarmThresholds(staleAfterMs = 600_000L),
        )
        val firstRequest = LocalAlarmMonitoringStartRequest(
            publicationBindingId = OFFLINE_BINDING_ID,
            approvalId = physicalApprovalRecord().approvalId,
            monitoringStartedAtEpochMs = 1_700_000_000_000L,
            approvedSequence = 1L,
            thresholds = thresholds,
        )

        val initialized = alarms.initializeMonitoring(firstRequest)
            as LocalAlarmMonitoringStartDecision.Initialized
        val laterRetry = alarms.initializeMonitoring(
            firstRequest.copy(
                monitoringStartedAtEpochMs = firstRequest.monitoringStartedAtEpochMs + 86_400_000L,
                thresholds = AlarmThresholdSnapshot.from(
                    com.sladkaya.core.model.AlarmThresholds(staleAfterMs = 1_200_000L),
                ),
            ),
        ) as LocalAlarmMonitoringStartDecision.AlreadyInitialized
        val clockRollbackRetry = alarms.initializeMonitoring(
            firstRequest.copy(
                monitoringStartedAtEpochMs = firstRequest.monitoringStartedAtEpochMs - 60_000L,
            ),
        ) as LocalAlarmMonitoringStartDecision.AlreadyInitialized

        assertEquals(
            firstRequest.monitoringStartedAtEpochMs,
            laterRetry.settlement.start.monitoringStartedAtEpochMs,
        )
        assertEquals(
            firstRequest.monitoringStartedAtEpochMs + thresholds.staleAfterMs,
            laterRetry.settlement.watchdogDeadlineEpochMs,
        )
        assertEquals(initialized.settlement.start, laterRetry.settlement.start)
        assertEquals(initialized.settlement.start, clockRollbackRetry.settlement.start)
        assertEquals(
            initialized.settlement.watchdogDeadlineEpochMs,
            clockRollbackRetry.settlement.watchdogDeadlineEpochMs,
        )
        assertEquals(0L, initialized.settlement.state.lastEffectId)
        assertEquals(
            initialized.settlement.start.startId,
            initialized.settlement.state.lastEventId,
        )
        assertTableCount("local_alarm_monitoring_starts", 1)
        assertTableCount("local_alarm_states", 1)
        assertTableCount("local_alarm_deliveries", 1)
    }

    @Test
    fun firstVerifiedReadingMakesTheStartupWatchdogObsoleteWithoutSecondEpisode() = runBlocking {
        approvedDaoWithDiagnosticCheckpoint()
        val alarms = database.localAlarms()
        val initialized = alarms.initializeMonitoring(
            LocalAlarmMonitoringStartRequest(
                publicationBindingId = OFFLINE_BINDING_ID,
                approvalId = physicalApprovalRecord().approvalId,
                monitoringStartedAtEpochMs = 1_700_000_000_000L,
                approvedSequence = 1L,
                thresholds = AlarmThresholdSnapshot.from(
                    com.sladkaya.core.model.AlarmThresholds(),
                ),
            ),
        ) as LocalAlarmMonitoringStartDecision.Initialized
        val first = productBundle(sequence = 2, glucoseMgDl = 100)
        commitCore(database, first)
        val leaseNow = requireNotNull(first.measurement).phoneTimeEpochMs + 1L
        val lease = database.localReadingEffects().leaseEarliest(
            leaseNow,
            "startup-first-reading-lease",
            leaseNow + 30_000L,
        ) as LocalReadingEffectLeaseDecision.Leased
        val applied = alarms.applyLeased(
            alarmApplyRequest(
                effect = lease.value,
                processedAt = leaseNow + 1_000L,
                expectedPreviousThresholdFingerprint = initialized.settlement.state
                    .thresholds.fingerprint,
            ),
        ) as LocalAlarmApplyDecision.Applied

        assertTrue(applied.settlement.activeKinds.isEmpty())
        assertTrue(
            alarms.applyWatchdog(
                publicationBindingId = OFFLINE_BINDING_ID,
                expectedStateSha256 = initialized.settlement.state.stateSha256,
                nowEpochMs = initialized.settlement.watchdogDeadlineEpochMs,
            ) is LocalAlarmWatchdogDecision.Obsolete,
        )
        assertTableCount("local_alarm_watchdog_applications", 0)
    }

    @Test
    fun quarantinedMonitoringStartWatchdogCannotBeSilentlyTreatedAsArmed() = runBlocking {
        approvedDaoWithDiagnosticCheckpoint()
        val alarms = database.localAlarms()
        val request = LocalAlarmMonitoringStartRequest(
            publicationBindingId = OFFLINE_BINDING_ID,
            approvalId = physicalApprovalRecord().approvalId,
            monitoringStartedAtEpochMs = 1_700_000_000_000L,
            approvedSequence = 1L,
            thresholds = AlarmThresholdSnapshot.from(
                com.sladkaya.core.model.AlarmThresholds(staleAfterMs = 600_000L),
            ),
        )
        val initialized = alarms.initializeMonitoring(request)
            as LocalAlarmMonitoringStartDecision.Initialized
        val deadline = initialized.settlement.watchdogDeadlineEpochMs
        val leaseToken = "quarantine-monitoring-start-watchdog"
        val leased = database.localAlarmDeliveries().leaseDueEarliest(
            nowEpochMs = deadline,
            leaseToken = leaseToken,
            leaseExpiresAtEpochMs = deadline + 10_000L,
        ) as LocalAlarmDeliveryLeaseDecision.Leased
        assertEquals(initialized.settlement.start.watchdogDeliveryId, leased.value.deliveryId)
        database.localAlarmDeliveries().quarantine(
            deliveryId = leased.value.deliveryId,
            leaseToken = leaseToken,
            cancelledAtEpochMs = deadline + 1L,
        )

        val failure = try {
            alarms.initializeMonitoring(request)
            null
        } catch (caught: SensorCoreConflictException) {
            caught
        }

        assertTrue(failure is SensorCoreConflictException)
    }

    @Test
    fun deliveryInsertFailureRollsBackStateApplicationAndEffectAck() = runBlocking {
        approvedDaoWithDiagnosticCheckpoint()
        val low = productBundle(sequence = 2, glucoseMgDl = 60)
        commitCore(database, low)
        val leaseNow = requireNotNull(low.measurement).phoneTimeEpochMs + 1L
        val lease = database.localReadingEffects().leaseEarliest(
            leaseNow,
            "alarm-apply-lease-a",
            leaseNow + 30_000L,
        ) as LocalReadingEffectLeaseDecision.Leased
        database.openHelper.writableDatabase.execSQL(
            "CREATE TRIGGER reject_alarm_delivery BEFORE INSERT ON local_alarm_deliveries " +
                "BEGIN SELECT RAISE(ABORT, 'alarm delivery rejected'); END",
        )

        assertThrows(RuntimeException::class.java) {
            runBlocking {
                database.localAlarms().applyLeased(
                    alarmApplyRequest(lease.value, leaseNow + 1_000L),
                )
            }
        }

        assertNull(database.localAlarms().alarmState(publicationBinding().publicationBindingId))
        assertNull(database.localAlarms().alarmApplication(lease.value.effect.effectId))
        assertTrue(database.localAlarms().deliveriesForEffect(lease.value.effect.effectId).isEmpty())
        assertEquals(
            "LEASED",
            database.sensorCore().localEffectByEvent(low.raw.eventId)?.state,
        )
    }

    @Test
    fun exactSettlementOfAnEarlierEventSurvivesLaterAlarmStateProgress() = runBlocking {
        approvedDaoWithDiagnosticCheckpoint()
        val low = productBundle(sequence = 2, glucoseMgDl = 60)
        commitCore(database, low)
        val firstLeaseNow = requireNotNull(low.measurement).phoneTimeEpochMs + 1L
        val firstLease = database.localReadingEffects().leaseEarliest(
            firstLeaseNow,
            "alarm-apply-lease-a",
            firstLeaseNow + 30_000L,
        ) as LocalReadingEffectLeaseDecision.Leased
        val first = database.localAlarms().applyLeased(
            alarmApplyRequest(firstLease.value, firstLeaseNow + 1_000L),
        ) as LocalAlarmApplyDecision.Applied

        val recovered = productBundle(sequence = 3, glucoseMgDl = 100)
        commitCore(database, recovered)
        val secondLeaseNow = requireNotNull(recovered.measurement).phoneTimeEpochMs + 1L
        val secondLease = database.localReadingEffects().leaseEarliest(
            secondLeaseNow,
            "alarm-apply-lease-b",
            secondLeaseNow + 30_000L,
        ) as LocalReadingEffectLeaseDecision.Leased
        database.localAlarms().applyLeased(
            alarmApplyRequest(
                effect = secondLease.value,
                processedAt = secondLeaseNow + 1_000L,
                expectedPreviousThresholdFingerprint = first.settlement.thresholdFingerprint,
            ),
        )

        val restored = database.localAlarms().readSettlement(
            eventId = low.raw.eventId,
            approvalId = physicalApproval().approvalId,
            publicationBindingId = publicationBinding().publicationBindingId,
        ) as LocalAlarmSettlementReadDecision.Exact

        assertEquals(first.settlement, restored.settlement)
        assertEquals(
            recovered.raw.eventId,
            database.localAlarms().alarmState(publicationBinding().publicationBindingId)?.lastEventId,
        )
    }

    @Test
    fun alarmDeliveryLeaseExpiryAckAndRetryAreDurableAndStrictFifo() = runBlocking {
        approvedDaoWithDiagnosticCheckpoint()
        val low = productBundle(sequence = 2, glucoseMgDl = 60)
        commitCore(database, low)
        val leaseNow = requireNotNull(low.measurement).phoneTimeEpochMs + 1L
        val effect = database.localReadingEffects().leaseEarliest(
            leaseNow,
            "alarm-effect-delivery-test",
            leaseNow + 30_000L,
        ) as LocalReadingEffectLeaseDecision.Leased
        val processedAt = leaseNow + 1_000L
        database.localAlarms().applyLeased(alarmApplyRequest(effect.value, processedAt))
        val deliveries = database.localAlarmDeliveries()

        val first = deliveries.leaseDueEarliest(
            processedAt,
            "delivery-room-lease-a",
            processedAt + 10_000L,
        ) as LocalAlarmDeliveryLeaseDecision.Leased
        assertEquals(LocalAlarmDeliveryKind.SHOW, first.value.kind)
        assertEquals(
            first,
            deliveries.leaseDueEarliest(
                processedAt,
                "delivery-room-lease-a",
                processedAt + 10_000L,
            ),
        )
        assertTrue(
            deliveries.leaseDueEarliest(
                processedAt,
                "delivery-room-lease-b",
                processedAt + 10_000L,
            ) is LocalAlarmDeliveryLeaseDecision.BlockedByActiveLease,
        )

        val recovered = deliveries.leaseDueEarliest(
            processedAt + 10_001L,
            "delivery-room-lease-c",
            processedAt + 20_000L,
        ) as LocalAlarmDeliveryLeaseDecision.Leased
        assertEquals(first.value.deliveryId, recovered.value.deliveryId)
        assertEquals(2, recovered.value.attempts)
        assertEquals(
            LocalAlarmDeliveryTransitionDecision.APPLIED,
            deliveries.markDelivered(
                recovered.value.deliveryId,
                "delivery-room-lease-c",
                processedAt + 11_000L,
            ),
        )
        val widget = deliveries.leaseDueEarliest(
            processedAt + 11_001L,
            "delivery-room-lease-d",
            processedAt + 20_000L,
        ) as LocalAlarmDeliveryLeaseDecision.Leased
        assertEquals(LocalAlarmDeliveryKind.WIDGET, widget.value.kind)
        assertEquals(
            LocalAlarmDeliveryTransitionDecision.APPLIED,
            deliveries.reschedule(
                widget.value.deliveryId,
                "delivery-room-lease-d",
                processedAt + 12_000L,
                processedAt + 60_000L,
            ),
        )
        assertEquals(
            LocalAlarmDeliveryTransitionDecision.ALREADY_APPLIED,
            deliveries.reschedule(
                widget.value.deliveryId,
                "delivery-room-lease-d",
                processedAt + 12_000L,
                processedAt + 60_000L,
            ),
        )
    }

    @Test
    fun acknowledgingCurrentEpisodePersistsTimestampAndCancelsPendingRepeatExactlyOnce() = runBlocking {
        approvedDaoWithDiagnosticCheckpoint()
        val low = productBundle(sequence = 2, glucoseMgDl = 60)
        commitCore(database, low)
        val leaseNow = requireNotNull(low.measurement).phoneTimeEpochMs + 1L
        val effect = database.localReadingEffects().leaseEarliest(
            leaseNow,
            "alarm-episode-ack-effect",
            leaseNow + 30_000L,
        ) as LocalReadingEffectLeaseDecision.Leased
        val applied = database.localAlarms().applyLeased(
            alarmApplyRequest(effect.value, leaseNow + 1_000L),
        ) as LocalAlarmApplyDecision.Applied
        val acknowledgedAt = applied.settlement.appliedAtEpochMs + 1_000L

        val acknowledged = database.localAlarms().acknowledgeEpisode(
            publicationBindingId = applied.settlement.publicationBindingId,
            expectedEpisodeGeneration = applied.settlement.episodeGeneration,
            acknowledgedAtEpochMs = acknowledgedAt,
        ) as LocalAlarmEpisodeAcknowledgeDecision.Applied

        assertTrue(acknowledged.state.episodeAcknowledged)
        assertEquals(acknowledgedAt, acknowledged.state.episodeAcknowledgedAtEpochMs)
        val repeat = database.localAlarms().deliveriesForEffect(effect.value.effect.effectId)
            .single { it.kind == LocalAlarmDeliveryKind.REPEAT.wireName }
        assertEquals(LocalAlarmDeliveryState.CANCELLED.wireName, repeat.state)
        assertEquals(
            LocalAlarmDeliveryTransitionKind.CANCELLED.wireName,
            repeat.lastTransitionKind,
        )
        val acknowledgementUpdate = database.localAlarms()
            .deliveriesForEffect(effect.value.effect.effectId)
            .single {
                it.kind == LocalAlarmDeliveryKind.UPDATE.wireName &&
                    it.createdAtEpochMs == acknowledgedAt
            }
        assertEquals(LocalAlarmDeliveryState.PENDING.wireName, acknowledgementUpdate.state)
        assertTrue(acknowledgementUpdate.episodeAcknowledged)
        assertEquals(acknowledged.state.stateSha256, acknowledgementUpdate.resultingStateSha256)
        assertEquals(
            LocalAlarmEpisodeAcknowledgeDecision.AlreadyApplied(acknowledged.state),
            database.localAlarms().acknowledgeEpisode(
                publicationBindingId = applied.settlement.publicationBindingId,
                expectedEpisodeGeneration = applied.settlement.episodeGeneration,
                acknowledgedAtEpochMs = acknowledgedAt,
            ),
        )
        assertEquals(
            1,
            database.localAlarms().deliveriesForEffect(effect.value.effect.effectId)
                .count {
                    it.kind == LocalAlarmDeliveryKind.UPDATE.wireName &&
                        it.createdAtEpochMs == acknowledgedAt
                },
        )

        val continuingLow = productBundle(sequence = 3, glucoseMgDl = 62)
        commitCore(database, continuingLow)
        val nextLeaseNow = requireNotNull(continuingLow.measurement).phoneTimeEpochMs + 1L
        val nextEffect = database.localReadingEffects().leaseEarliest(
            nextLeaseNow,
            "alarm-episode-ack-next-effect",
            nextLeaseNow + 30_000L,
        ) as LocalReadingEffectLeaseDecision.Leased
        database.localAlarms().applyLeased(
            alarmApplyRequest(
                effect = nextEffect.value,
                processedAt = nextLeaseNow + 1_000L,
                expectedPreviousThresholdFingerprint = acknowledged.state.thresholds.fingerprint,
            ),
        )
        val progressed = requireNotNull(
            database.localAlarms().alarmState(applied.settlement.publicationBindingId),
        ).toRecord()
        assertTrue(progressed.episodeAcknowledged)
        assertEquals(acknowledgedAt, progressed.episodeAcknowledgedAtEpochMs)
        assertEquals(
            LocalAlarmEpisodeAcknowledgeDecision.AlreadyApplied(progressed),
            database.localAlarms().acknowledgeEpisode(
                publicationBindingId = applied.settlement.publicationBindingId,
                expectedEpisodeGeneration = applied.settlement.episodeGeneration,
                acknowledgedAtEpochMs = acknowledgedAt,
            ),
        )
        assertTrue(
            database.localAlarms().acknowledgeEpisode(
                publicationBindingId = applied.settlement.publicationBindingId,
                expectedEpisodeGeneration = applied.settlement.episodeGeneration + 1L,
                acknowledgedAtEpochMs = acknowledgedAt,
            ) is LocalAlarmEpisodeAcknowledgeDecision.Stale,
        )
    }

    @Test
    fun watchdogRejectsObsoleteStateAndDurablyOpensSignalLossExactlyOnce() = runBlocking {
        approvedDaoWithDiagnosticCheckpoint()
        val firstReading = productBundle(sequence = 2, glucoseMgDl = 100)
        commitCore(database, firstReading)
        val firstLeaseNow = requireNotNull(firstReading.measurement).phoneTimeEpochMs + 1L
        val firstEffect = database.localReadingEffects().leaseEarliest(
            firstLeaseNow,
            "watchdog-effect-a",
            firstLeaseNow + 30_000L,
        ) as LocalReadingEffectLeaseDecision.Leased
        database.localAlarms().applyLeased(
            alarmApplyRequest(firstEffect.value, firstLeaseNow + 1_000L),
        )
        val staleState = requireNotNull(
            database.localAlarms().alarmState(publicationBinding().publicationBindingId),
        ).toRecord()

        val nextReading = productBundle(sequence = 3, glucoseMgDl = 101)
        commitCore(database, nextReading)
        val nextLeaseNow = requireNotNull(nextReading.measurement).phoneTimeEpochMs + 1L
        val nextEffect = database.localReadingEffects().leaseEarliest(
            nextLeaseNow,
            "watchdog-effect-b",
            nextLeaseNow + 30_000L,
        ) as LocalReadingEffectLeaseDecision.Leased
        database.localAlarms().applyLeased(
            alarmApplyRequest(
                effect = nextEffect.value,
                processedAt = nextLeaseNow + 1_000L,
                expectedPreviousThresholdFingerprint = staleState.thresholds.fingerprint,
            ),
        )
        val current = requireNotNull(
            database.localAlarms().alarmState(publicationBinding().publicationBindingId),
        ).toRecord()
        val obsoleteDueAt = minOf(
            staleState.policyState.latestFreshSensorTimeEpochMs,
            staleState.policyState.latestFreshPhoneTimeEpochMs,
        ) + staleState.thresholds.staleAfterMs
        assertTrue(
            database.localAlarms().applyWatchdog(
                publicationBindingId = staleState.publicationBindingId,
                expectedStateSha256 = staleState.stateSha256,
                nowEpochMs = obsoleteDueAt,
            ) is LocalAlarmWatchdogDecision.Obsolete,
        )
        assertEquals(
            current,
            database.localAlarms().alarmState(current.publicationBindingId)?.toRecord(),
        )

        val dueAt = minOf(
            current.policyState.latestFreshSensorTimeEpochMs,
            current.policyState.latestFreshPhoneTimeEpochMs,
        ) + current.thresholds.staleAfterMs
        val applied = database.localAlarms().applyWatchdog(
            publicationBindingId = current.publicationBindingId,
            expectedStateSha256 = current.stateSha256,
            nowEpochMs = dueAt,
        ) as LocalAlarmWatchdogDecision.Applied

        assertEquals(
            setOf(com.sladkaya.core.model.AlarmKind.SIGNAL_LOSS),
            applied.settlement.activeKinds,
        )
        val watchdogDeliveries = database.localAlarms()
            .deliveriesForEffect(current.lastEffectId)
            .filter { it.createdAtEpochMs == dueAt }
        assertEquals(
            setOf(
                LocalAlarmDeliveryKind.SHOW.wireName,
                LocalAlarmDeliveryKind.REPEAT.wireName,
                LocalAlarmDeliveryKind.WIDGET.wireName,
            ),
            watchdogDeliveries.mapTo(linkedSetOf(), LocalAlarmDeliveryEntity::kind),
        )
        assertEquals(
            dueAt + 120_000L,
            watchdogDeliveries.single {
                it.kind == LocalAlarmDeliveryKind.REPEAT.wireName
            }.notBeforeEpochMs,
        )
        assertEquals(
            LocalAlarmWatchdogDecision.AlreadyApplied(applied.settlement),
            database.localAlarms().applyWatchdog(
                publicationBindingId = current.publicationBindingId,
                expectedStateSha256 = current.stateSha256,
                nowEpochMs = dueAt,
            ),
        )
        assertTableCount("local_alarm_watchdog_applications", 1)
    }

    @Test
    fun productHistoryQueryExcludesDiagnosticAndOtherPublicationLineage() = runBlocking {
        val approvalId = physicalApprovalRecord().approvalId
        val bindingId = publicationBindingRecord().publicationBindingId
        val base = coreBundle(sequence = 2, publishable = true).measurement!!
        val dao = database.sensorCore()
        dao.insertMeasurement(base)
        dao.insertMeasurement(
            base.copy(
                eventId = "diagnostic-row",
                publicationApprovalId = null,
                publicationBindingId = null,
                remotePublicationBindingId = null,
                httpsOrigin = null,
                backendBindingId = null,
                credentialId = null,
                credentialRevision = null,
                expectedPatientId = null,
                expectedDeviceId = null,
            ),
        )
        dao.insertMeasurement(
            base.copy(
                eventId = "other-publication",
                publicationBindingId = "ef".repeat(32),
            ),
        )

        val visible = database.measurements().recentForPublication(
            approvalId = approvalId,
            publicationBindingId = bindingId,
            limit = 100,
        )

        assertEquals(listOf(base.eventId), visible.map { it.eventId })
    }

    @Test
    fun dueAndRecoveredLeasesRemainFifoAndExactLeaseRetryIsIdempotent() = runBlocking {
        val dao = approvedDaoWithDiagnosticCheckpoint()
        val second = coreBundle(sequence = 2, publishable = true)
        val third = coreBundle(sequence = 3, publishable = true)
        commitCore(database, second)
        commitCore(database, third)
        val now = requireNotNull(third.measurement).phoneTimeEpochMs + 1L

        val firstLease = dao.leaseDueOutbox(
            nowEpochMs = now,
            leaseToken = "lease-token-a",
            leaseExpiresAtEpochMs = now + 10_000L,
            limit = 1,
        )
        assertEquals(listOf(second.raw.eventId), firstLease.map { it.eventId })
        assertEquals(1, firstLease.single().attempts)
        assertEquals(
            firstLease,
            dao.leaseDueOutbox(now, "lease-token-a", now + 10_000L, 1),
        )

        val secondLease = dao.leaseDueOutbox(
            nowEpochMs = now,
            leaseToken = "lease-token-b",
            leaseExpiresAtEpochMs = now + 10_000L,
            limit = 1,
        )
        assertEquals(listOf(third.raw.eventId), secondLease.map { it.eventId })

        val recovered = dao.leaseDueOutbox(
            nowEpochMs = now + 10_001L,
            leaseToken = "lease-token-c",
            leaseExpiresAtEpochMs = now + 20_000L,
            limit = 2,
        )
        assertEquals(listOf(second.raw.eventId, third.raw.eventId), recovered.map { it.eventId })
        assertEquals(listOf(2, 2), recovered.map { it.attempts })
    }

    @Test
    fun outboxTerminalAndRetryTransitionsRequireTheExactLease() = runBlocking {
        val dao = approvedDaoWithDiagnosticCheckpoint()
        val second = coreBundle(sequence = 2, publishable = true)
        commitCore(database, second)
        val now = requireNotNull(second.measurement).phoneTimeEpochMs + 1L
        dao.leaseDueOutbox(now, "lease-token-a", now + 10_000L, 1)
        val retryReport = UploadDeliveryReportEntity(
            status = "RETRYABLE_NETWORK",
            detail = "CONNECT_TIMEOUT",
        )

        assertEquals(
            SensorCoreCommitDisposition.COMMITTED,
            dao.rescheduleOutbox(
                eventId = second.raw.eventId,
                leaseToken = "lease-token-a",
                nextAttemptEpochMs = now + 60_000L,
                report = retryReport,
            ),
        )
        assertEquals(
            SensorCoreCommitDisposition.ALREADY_COMMITTED,
            dao.rescheduleOutbox(
                eventId = second.raw.eventId,
                leaseToken = "lease-token-a",
                nextAttemptEpochMs = now + 60_000L,
                report = retryReport.copy(),
            ),
        )
        assertThrows(SensorCoreConflictException::class.java) {
            runBlocking {
                dao.markOutboxSent(
                    second.raw.eventId,
                    "stale-lease-token",
                    UploadDeliveryReportEntity("ACCEPTED", "HTTP_202"),
                )
            }
        }

        dao.leaseDueOutbox(
            nowEpochMs = now + 60_000L,
            leaseToken = "lease-token-b",
            leaseExpiresAtEpochMs = now + 70_000L,
            limit = 1,
        )
        val accepted = UploadDeliveryReportEntity("ACCEPTED", "HTTP_202")
        assertEquals(
            SensorCoreCommitDisposition.COMMITTED,
            dao.markOutboxSent(second.raw.eventId, "lease-token-b", accepted),
        )
        assertEquals(
            SensorCoreCommitDisposition.ALREADY_COMMITTED,
            dao.markOutboxSent(second.raw.eventId, "lease-token-b", accepted.copy()),
        )
        assertEquals("SENT", dao.outboxByEvent(second.raw.eventId)?.state)
    }

    @Test
    fun settingsReductionIsAtomicIdempotentAndOpensAlreadyStaleSignalLossWithoutNewReading() =
        runBlocking {
            approvedDaoWithDiagnosticCheckpoint()
            val initialThresholds = AlarmThresholdSnapshot.from(
                com.sladkaya.core.model.AlarmThresholds(staleAfterMs = 30 * 60_000L),
            )
            database.localAlarms().initializeMonitoring(
                LocalAlarmMonitoringStartRequest(
                    publicationBindingId = publicationBinding().publicationBindingId,
                    approvalId = physicalApproval().approvalId,
                    monitoringStartedAtEpochMs = 1_700_000_000_000L,
                    approvedSequence = 1L,
                    thresholds = initialThresholds,
                ),
            )
            val current = productBundle(sequence = 2, glucoseMgDl = 103)
            commitCore(database, current)
            val readingTime = requireNotNull(current.measurement).phoneTimeEpochMs
            val lease = database.localReadingEffects().leaseEarliest(
                nowEpochMs = readingTime + 1L,
                leaseToken = "settings-initial-reading",
                leaseExpiresAtEpochMs = readingTime + 30_000L,
            ) as LocalReadingEffectLeaseDecision.Leased
            database.localAlarms().applyLeased(
                LocalAlarmApplyRequest(
                    effectId = lease.value.effect.effectId,
                    eventId = lease.value.effect.eventId,
                    leaseToken = checkNotNull(lease.value.effect.leaseToken),
                    processedAtEpochMs = readingTime + 2L,
                    monitoringStartedAtEpochMs = 1_700_000_000_000L,
                    thresholds = initialThresholds,
                    expectedPreviousThresholdFingerprint = initialThresholds.fingerprint,
                ),
            )
            val before = database.localAlarms()
                .verifiedAlarmState(publicationBinding().publicationBindingId)
                ?: error("missing alarm state")
            val request = LocalAlarmSettingsApplyRequest(
                publicationBindingId = publicationBinding().publicationBindingId,
                expectedStateSha256 = before.stateSha256,
                thresholds = AlarmThresholdSnapshot.from(
                    com.sladkaya.core.model.AlarmThresholds(staleAfterMs = 5 * 60_000L),
                ),
                appliedAtEpochMs = requireNotNull(current.measurement).sensorTimeEpochMs +
                    10 * 60_000L,
            )

            val first = database.localAlarms().applySettings(request)
                as LocalAlarmSettingsApplyDecision.Applied
            val retry = database.localAlarms().applySettings(request)
                as LocalAlarmSettingsApplyDecision.AlreadyApplied

            assertEquals(first.settlement, retry.settlement)
            assertEquals(setOf(com.sladkaya.core.model.AlarmKind.SIGNAL_LOSS), first.settlement.activeKinds)
            assertEquals(
                setOf(
                    LocalAlarmDeliveryKind.SHOW,
                    LocalAlarmDeliveryKind.REPEAT,
                    LocalAlarmDeliveryKind.WATCHDOG,
                    LocalAlarmDeliveryKind.WIDGET,
                ),
                first.settlement.deliveryIds.map { deliveryId ->
                    requireNotNull(database.localAlarms().alarmDelivery(deliveryId)).toRecord().kind
                }.toSet(),
            )
            assertEquals(1, database.localAlarms().productMeasurementCount(
                publicationBinding().publicationBindingId,
            ))
            assertEquals(
                request.thresholds.fingerprint,
                database.localAlarms().verifiedAlarmState(
                    publicationBinding().publicationBindingId,
                )?.thresholds?.fingerprint,
            )
        }

    private suspend fun approvedDaoWithDiagnosticCheckpoint(): SensorCoreDao {
        val dao = database.sensorCore()
        dao.bindProtocol(protocolBinding())
        commitCore(database, coreBundle(sequence = 1, publishable = false))
        dao.approvePhysicalSensor(physicalApproval())
        dao.activateLocalSensorBinding(
            approvalId = physicalApproval().approvalId,
            publicationBindingId = OFFLINE_BINDING_ID,
            expectedPreviousPublicationBindingId = null,
        )
        dao.activatePublicationBinding(publicationBinding(), null)
        return dao
    }

    private suspend fun commitCore(
        source: SladkayaDatabase,
        value: SensorCoreEntityBundle,
    ): SensorCoreCommitDisposition {
        appendSourceIngress(source, value)
        return source.sensorCore().commit(value)
    }

    private suspend fun appendSourceIngress(
        source: SladkayaDatabase,
        value: SensorCoreEntityBundle,
    ) {
        val raw = value.raw
        val attemptId = raw.sourceIngressId.substringBeforeLast(':')
        val ordinal = raw.sourceIngressId.substringAfterLast(':').toLongOrNull()
            ?: raw.sequence.toLong()
        source.sensorPacketIngress().append(
            SensorPacketIngressEntity(
                ingressId = raw.sourceIngressId,
                sensorId = raw.sensorId,
                sensorFamily = raw.sensorFamily,
                bluetoothAddress = value.checkpoint.bluetoothAddress,
                attemptId = attemptId,
                ordinal = ordinal,
                receivedAtEpochMs = raw.phoneTimeEpochMs,
                encryptedPacket = raw.packet.copyOf(),
                packetSha256 = raw.packetSha256,
            ),
        )
    }

    private fun coreBundle(
        sequence: Int = 1,
        publishable: Boolean = true,
        sourceIngressId: String = "core-test:$sequence",
        packet: ByteArray = byteArrayOf(1, 2, 3),
        phoneTimeEpochMs: Long? = null,
    ): SensorCoreEntityBundle {
        val state = ByteArray(2_480) { index -> ((index + sequence) % 251).toByte() }
        val sensorTime = 1_700_000_000_000L + sequence * 60_000L
        val raw = RawSensorSampleEntity(
            eventId = "event-$sequence",
            sourceIngressId = sourceIngressId,
            sensorId = "sensor-a",
            sensorFamily = "sibionics_gs1",
            sequence = sequence,
            sensorTimeEpochMs = sensorTime,
            phoneTimeEpochMs = phoneTimeEpochMs ?: sensorTime + 1_000L,
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
            publishable = publishable,
            alarmEligible = publishable,
            algorithmErrorCode = if (publishable) null else "DIAGNOSTIC_ONLY",
            publicationApprovalId = physicalApprovalRecord().approvalId.takeIf { publishable },
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
            sensorStartTimeEpochMs = raw.sensorTimeEpochMs - raw.sequence * 60_000L,
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
            publicationApprovalId = physicalApprovalRecord().approvalId.takeIf { publishable },
        )
        val publicationContext = productPublicationContext().takeIf { publishable }
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
                publicationApprovalId = publicationContext?.approvalId,
                publicationBindingId = publicationContext?.publicationBindingId,
                remotePublicationBindingId = publicationContext?.remotePublicationBindingId,
                httpsOrigin = publicationContext?.httpsOrigin,
                backendBindingId = publicationContext?.backendBindingId,
                credentialId = publicationContext?.credentialId,
                credentialRevision = publicationContext?.credentialRevision,
                expectedPatientId = publicationContext?.expectedPatientId,
                expectedDeviceId = publicationContext?.expectedDeviceId,
            ).takeIf { publishable },
            publicationContext = publicationContext,
        )
    }

    private fun productBundle(
        sequence: Int,
        glucoseMgDl: Int,
        trendMgDlPerMinute: Double = 0.0,
    ): SensorCoreEntityBundle {
        val base = coreBundle(sequence = sequence, publishable = true)
        val glucoseMmolL = glucoseMgDl / 18.0
        return base.copy(
            result = base.result.copy(
                nativeGlucoseMmolL = glucoseMmolL,
                displayedGlucoseMmolL = glucoseMmolL,
            ),
            measurement = requireNotNull(base.measurement).copy(
                glucoseMgDl = glucoseMgDl,
                trendMgDlPerMinute = trendMgDlPerMinute,
            ),
        )
    }

    private fun alarmApplyRequest(
        effect: LeasedLocalReadingEffect,
        processedAt: Long,
        expectedPreviousThresholdFingerprint: String? = null,
    ) = LocalAlarmApplyRequest(
        effectId = effect.effect.effectId,
        eventId = effect.effect.eventId,
        leaseToken = checkNotNull(effect.effect.leaseToken),
        processedAtEpochMs = processedAt,
        monitoringStartedAtEpochMs = 1_700_000_000_000L,
        thresholds = AlarmThresholdSnapshot.from(com.sladkaya.core.model.AlarmThresholds()),
        expectedPreviousThresholdFingerprint = expectedPreviousThresholdFingerprint,
        repeatIntervalMs = 120_000L,
    )

    private fun protocolBinding() = SensorProtocolBindingEntity(
        sensorId = "sensor-a",
        bluetoothAddress = "AA:BB:CC:DD:EE:FF",
        sensorFamily = "sibionics_gs1",
        transportVariant = 0,
        sensitivityToken = "ABCDEFGH",
        wireProfile = "V120",
        transportProtocol = "GS1_V120",
        transportCodecId = "transport-codec-test",
        algorithmProfile = "V116A",
        sensitivityEncoding = "NORMAL",
        evidenceKind = "VALIDATED_V120_ENVELOPE",
        evidenceSha256 = "ab".repeat(32),
        schemaVersion = 1,
    )

    private fun physicalApprovalRecord() = PhysicalSensorApprovalRecord(
        sensorId = "sensor-a",
        bluetoothAddress = "AA:BB:CC:DD:EE:FF",
        sensorFamily = com.sladkaya.core.model.SensorFamily.SIBIONICS_GS1,
        transportVariant = 0,
        sensitivityToken = "ABCDEFGH",
        wireProfile = "V120",
        transportProtocol = "GS1_V120",
        transportCodecId = "transport-codec-test",
        algorithmProfile = "V116A",
        algorithmVersion = "1.1.6A",
        binarySetId = "algorithm-set",
        sensitivityTokenSource = "PACKAGE_CODE",
        sensitivityCoefficient = 1.42,
        sensitivityEncoding = "NORMAL",
        initializationMode = "STANDARD",
        displayOffsetMmolL = 0.0,
        protocolEvidenceKind = "VALIDATED_V120_ENVELOPE",
        protocolEvidenceSha256 = "ab".repeat(32),
        physicalValidationEvidenceSha256 = "cd".repeat(32),
        checkpointSchemaVersion = 1,
        approvedSequence = 1,
        approvedSensorTimeEpochMs = 1_700_000_060_000L,
        sensorStartTimeEpochMs = 1_700_000_000_000L,
        approvedCheckpointStateSha256 = ByteArray(2_480) { index ->
            ((index + 1) % 251).toByte()
        }.sha256(),
        nativeBinarySetSha256 = "12".repeat(32),
        nativeDatahandleBinarySetSha256 = "34".repeat(32),
        approvedAtEpochMs = 1_700_000_000_000L,
        schemaVersion = 1,
    )

    private fun physicalApproval() = physicalApprovalRecord().toEntity()

    private fun publicationBindingRecord() = ProductPublicationBindingRecord(
        approvalId = physicalApprovalRecord().approvalId,
        publicationBindingId = OFFLINE_BINDING_ID,
        httpsOrigin = "https://api.sladkaya.test",
        backendBindingId = "backend-binding-a",
        credentialId = "credential-a",
        credentialRevision = 3L,
        expectedPatientId = "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa",
        expectedDeviceId = "bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb",
        createdAtEpochMs = 1_700_000_001_000L,
    )

    private fun publicationBinding() = publicationBindingRecord().toEntity()

    private fun productPublicationContext() = ProductPublicationContext.verifiedRuntime(
        approval = physicalApprovalRecord(),
        publicationBinding = publicationBindingRecord(),
        nativeBinarySetSha256 = "12".repeat(32),
        nativeDatahandleBinarySetSha256 = "34".repeat(32),
    )

    private fun ingress(
        ingressId: String = "ingress-a",
        attemptId: String = "attempt-a",
        encryptedPacket: ByteArray = byteArrayOf(1, 2, 3),
        receivedAtEpochMs: Long = 1_700_000_000_000L,
    ): SensorPacketIngressEntity {
        return SensorPacketIngressEntity(
            ingressId = ingressId,
            sensorId = "sensor-a",
            sensorFamily = "sibionics_gs1",
            bluetoothAddress = "AA:BB:CC:DD:EE:FF",
            attemptId = attemptId,
            ordinal = 0,
            receivedAtEpochMs = receivedAtEpochMs,
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
