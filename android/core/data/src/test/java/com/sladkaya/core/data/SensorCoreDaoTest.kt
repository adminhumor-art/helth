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
    fun coreCommitRejectsMissingExactSourceIngressBeforeAnyWrite() {
        val dao = RecordingSensorCoreDao(exactIngressAvailable = false)

        assertThrows(SensorCoreConflictException::class.java) {
            runBlocking { dao.commit(record().toEntityBundle()) }
        }

        assertEquals(emptyList<String>(), dao.calls)
    }

    @Test
    fun protocolBindingIsInsertOnlyIdempotentAndPhysicalIdentityUnique() = runBlocking {
        val dao = RecordingSensorCoreDao(implicitProtocolBinding = false)
        val first = protocolBinding()

        assertEquals(SensorCoreCommitDisposition.COMMITTED, dao.bindProtocol(first))
        assertEquals(SensorCoreCommitDisposition.ALREADY_COMMITTED, dao.bindProtocol(first))
        assertThrows(SensorCoreConflictException::class.java) {
            runBlocking { dao.bindProtocol(first.copy(wireProfile = "V115")) }
        }
        assertThrows(SensorCoreConflictException::class.java) {
            runBlocking { dao.bindProtocol(first.copy(sensorId = "sensor-b")) }
        }
        assertEquals(first, dao.protocolBinding("sensor-a"))
        Unit
    }

    @Test
    fun coreCommitCannotPrecedeOrContradictDurableProtocolBinding() = runBlocking {
        val dao = RecordingSensorCoreDao(implicitProtocolBinding = false)
        val bundle = record().toEntityBundle()

        assertThrows(SensorCoreConflictException::class.java) {
            runBlocking { dao.commit(bundle) }
        }
        dao.bindProtocol(protocolBinding())
        assertEquals(SensorCoreCommitDisposition.COMMITTED, dao.commit(bundle))
        assertThrows(SensorCoreConflictException::class.java) {
            runBlocking {
                dao.commit(
                    record(sequence = 2).copy(
                        checkpoint = record(sequence = 2).checkpoint.copy(
                            transportCodecId = "opposite-codec",
                        ),
                    ).toEntityBundle(),
                )
            }
        }
        Unit
    }

    @Test
    fun commitWritesEveryPartBeforeReplacingCheckpoint() = runBlocking {
        val dao = RecordingSensorCoreDao()

        val disposition = dao.commit(record().toEntityBundle())

        assertEquals(listOf("raw", "result", "measurement", "outbox", "checkpoint"), dao.calls)
        assertEquals(SensorCoreCommitDisposition.COMMITTED, disposition)
    }

    @Test
    fun nonPublishableStepStillAdvancesRawResultAndCheckpointAtomically() = runBlocking {
        val dao = RecordingSensorCoreDao(productReady = false)
        val withoutMeasurement = record(sequence = 1, publishable = false).toEntityBundle()

        dao.commit(withoutMeasurement)

        assertEquals(listOf("raw", "result", "checkpoint"), dao.calls)
    }

    @Test
    fun approvedNonPublishableStepAdvancesLineageWithoutMeasurementOrOutbox() = runBlocking {
        val dao = RecordingSensorCoreDao()
        val approvedContext = testPublicationContext().approvedCheckpointContext()
        val stateOnly = record(
            sequence = 2,
            publishable = false,
            approvedCheckpointContext = approvedContext,
        ).toEntityBundle()

        assertEquals(SensorCoreCommitDisposition.COMMITTED, dao.commit(stateOnly))
        assertEquals(listOf("raw", "result", "checkpoint"), dao.calls)
        assertEquals(approvedContext.approvalId, dao.savedCheckpoint?.publicationApprovalId)
        assertEquals(null, dao.measurement(stateOnly.raw.eventId))
        assertEquals(null, dao.outboxByEvent(stateOnly.raw.eventId))
    }

    @Test
    fun approvedStateOnlyStepRejectsAStalePublicationBindingBeforeAnyWrite() = runBlocking {
        val dao = RecordingSensorCoreDao()
        val stale = testPublicationContext().approvedCheckpointContext().copy(
            publicationBindingId = "ef".repeat(32),
        )
        val stateOnly = record(
            sequence = 2,
            publishable = false,
            approvedCheckpointContext = stale,
        ).toEntityBundle()

        assertThrows(SensorCoreConflictException::class.java) {
            runBlocking { dao.commit(stateOnly) }
        }
        assertEquals(emptyList<String>(), dao.calls)
    }

    @Test
    fun exactRetryIsAcceptedWithoutReplacingTheSameCheckpoint() = runBlocking {
        val dao = RecordingSensorCoreDao()
        val bundle = record().toEntityBundle()
        dao.commit(bundle)
        dao.calls.clear()

        val disposition = dao.commit(bundle)

        assertEquals(SensorCoreCommitDisposition.ALREADY_COMMITTED, disposition)
        assertEquals(listOf("raw", "result", "measurement", "outbox"), dao.calls)
        assertEquals(2, dao.savedCheckpoint?.sequence)
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
        assertEquals(2, dao.savedCheckpoint?.sequence)
    }

    @Test
    fun olderCheckpointCanNeverReplaceNewerState() = runBlocking {
        val dao = RecordingSensorCoreDao()
        dao.commit(record(sequence = 2).toEntityBundle())
        dao.commit(record(sequence = 3).toEntityBundle())
        dao.calls.clear()

        assertThrows(SensorCoreConflictException::class.java) {
            runBlocking { dao.commit(record(sequence = 2).toEntityBundle()) }
        }

        assertEquals(emptyList<String>(), dao.calls)
        assertEquals(3, dao.savedCheckpoint?.sequence)
    }

    @Test
    fun firstCheckpointCannotPretendAStartedSensorIsFresh() {
        val dao = RecordingSensorCoreDao(productReady = false)

        assertThrows(SensorCoreConflictException::class.java) {
            runBlocking { dao.commit(record(sequence = 2, publishable = false).toEntityBundle()) }
        }

        assertEquals(emptyList<String>(), dao.calls)
    }

    @Test
    fun checkpointGapIsRejectedBeforeAnyWrite() = runBlocking {
        val dao = RecordingSensorCoreDao(productReady = false)
        dao.commit(record(sequence = 1, publishable = false).toEntityBundle())
        dao.calls.clear()

        assertThrows(SensorCoreConflictException::class.java) {
            runBlocking { dao.commit(record(sequence = 3, publishable = false).toEntityBundle()) }
        }

        assertEquals(emptyList<String>(), dao.calls)
        assertEquals(1, dao.savedCheckpoint?.sequence)
    }

    @Test
    fun nextCheckpointMustRepresentExactlyOneMinute() = runBlocking {
        val dao = RecordingSensorCoreDao(productReady = false)
        val first = record(sequence = 1, publishable = false)
        dao.commit(first.toEntityBundle())
        dao.calls.clear()
        val wrongTime = first.raw.sensorTimeEpochMs + 60_001L

        assertThrows(SensorCoreConflictException::class.java) {
            runBlocking {
                dao.commit(
                    record(
                        sequence = 2,
                        sensorTime = wrongTime,
                        publishable = false,
                    ).toEntityBundle(),
                )
            }
        }

        assertEquals(emptyList<String>(), dao.calls)
        assertEquals(1, dao.savedCheckpoint?.sequence)
    }

    @Test
    fun v115AcceptsExactNonMinuteTimingAndPreservesReceiveTimeProvenanceOnRetry() = runBlocking {
        val dao = RecordingSensorCoreDao(
            implicitProtocolBinding = false,
            productReady = false,
        )
        dao.bindProtocol(
            protocolBinding(
                transportVariant = 2,
                wireProfile = "V115",
                transportProtocol = "GS1_V115",
                transportCodecId = "GS1_V115_WIRE_V1",
                algorithmProfile = "V115G",
            ),
        )
        val receivedAt = 1_700_000_060_000L
        val sensorStartTime = receivedAt - 90_000L
        val history = record(
            sequence = 1,
            sensorTime = receivedAt - 30_000L,
            phoneTime = receivedAt,
            historyDistance = 1,
            transportVariant = 2,
            transportProtocol = "GS1_V115",
            transportCodecId = "GS1_V115_WIRE_V1",
            algorithmProfile = "V115G",
            algorithmVersion = "1.1.5G",
            algorithmStateSize = 2_336,
            addTimeSeconds = 30,
            sensorStartTime = sensorStartTime,
            publishable = false,
        )
        val current = record(
            sequence = 2,
            sensorTime = receivedAt,
            phoneTime = receivedAt,
            historyDistance = 0,
            transportVariant = 2,
            transportProtocol = "GS1_V115",
            transportCodecId = "GS1_V115_WIRE_V1",
            algorithmProfile = "V115G",
            algorithmVersion = "1.1.5G",
            algorithmStateSize = 2_336,
            sensorTimeWasClamped = true,
            addTimeSeconds = 30,
            sensorStartTime = sensorStartTime,
            publishable = false,
        )

        assertEquals(SensorCoreCommitDisposition.COMMITTED, dao.commit(history.toEntityBundle()))
        val currentBundle = current.toEntityBundle()
        assertEquals(SensorCoreCommitDisposition.COMMITTED, dao.commit(currentBundle))
        assertEquals(SensorCoreCommitDisposition.ALREADY_COMMITTED, dao.commit(currentBundle))

        val savedHistory = checkNotNull(dao.rawByEvent(history.raw.eventId))
        val savedCurrent = checkNotNull(dao.rawByEvent(current.raw.eventId))
        assertEquals(receivedAt - 30_000L, savedHistory.sensorTimeEpochMs)
        assertEquals(receivedAt, savedHistory.phoneTimeEpochMs)
        assertEquals(30, savedHistory.addTimeSeconds)
        assertEquals(false, savedHistory.sensorTimeWasClamped)
        assertEquals(receivedAt, savedCurrent.sensorTimeEpochMs)
        assertEquals(receivedAt, savedCurrent.phoneTimeEpochMs)
        assertEquals(30, savedCurrent.addTimeSeconds)
        assertEquals(true, savedCurrent.sensorTimeWasClamped)
        assertEquals(receivedAt, dao.savedCheckpoint?.sensorTimeEpochMs)
    }

    @Test
    fun immutableCheckpointProvenanceCannotChangeMidSensor() = runBlocking {
        val dao = RecordingSensorCoreDao()
        dao.commit(record(sequence = 2).toEntityBundle())
        dao.calls.clear()
        val next = record(sequence = 3)
        val changed = next.copy(
            checkpoint = next.checkpoint.copy(transportProtocol = "GS1_UNKNOWN"),
        )

        assertThrows(SensorCoreConflictException::class.java) {
            runBlocking { dao.commit(changed.toEntityBundle()) }
        }

        assertEquals(emptyList<String>(), dao.calls)
        assertEquals(2, dao.savedCheckpoint?.sequence)
    }

    @Test
    fun durableSensorStartCannotChangeMidSensor() = runBlocking {
        val dao = RecordingSensorCoreDao(productReady = false)
        dao.commit(record(sequence = 1, publishable = false).toEntityBundle())
        dao.calls.clear()
        val next = record(sequence = 2, publishable = false).toEntityBundle()
        val changed = next.copy(
            checkpoint = next.checkpoint.copy(
                sensorStartTimeEpochMs = next.checkpoint.sensorStartTimeEpochMs + 60_000L,
            ),
        )

        assertThrows(SensorCoreConflictException::class.java) {
            runBlocking { dao.commit(changed) }
        }

        assertEquals(emptyList<String>(), dao.calls)
        assertEquals(1_700_000_000_000L, dao.savedCheckpoint?.sensorStartTimeEpochMs)
    }

    @Test
    fun physicalApprovalMustCarryTheExactCheckpointSensorStart() = runBlocking {
        val dao = RecordingSensorCoreDao(productReady = false)
        dao.commit(record(sequence = 1, publishable = false).toEntityBundle())
        val anchor = checkNotNull(dao.savedCheckpoint)
        val altered = testPhysicalApproval(anchor).copy(
            sensorStartTimeEpochMs = anchor.sensorStartTimeEpochMs - 60_000L,
        )

        assertThrows(SensorCoreConflictException::class.java) {
            runBlocking { dao.approvePhysicalSensor(altered.toEntity()) }
        }
        Unit
    }

    @Test
    fun physicalBluetoothIdentityCannotChangeMidSensor() = runBlocking {
        val dao = RecordingSensorCoreDao()
        dao.commit(record(sequence = 2).toEntityBundle())
        dao.calls.clear()
        val next = record(sequence = 3)
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
    fun approvedLineageAllowsStateAndDisplayOffsetToAdvance() = runBlocking {
        val dao = RecordingSensorCoreDao()
        val second = record(sequence = 2)
        val secondWithOffset = second.copy(
            checkpoint = second.checkpoint.copy(displayOffsetMmolL = 0.25),
        )
        val third = record(sequence = 3)
        val thirdWithOffset = third.copy(
            checkpoint = third.checkpoint.copy(displayOffsetMmolL = 0.5),
        )

        assertEquals(
            SensorCoreCommitDisposition.COMMITTED,
            dao.commit(secondWithOffset.toEntityBundle()),
        )
        assertEquals(
            SensorCoreCommitDisposition.COMMITTED,
            dao.commit(thirdWithOffset.toEntityBundle()),
        )
        assertEquals(0.5, dao.savedCheckpoint?.displayOffsetMmolL ?: -1.0, 0.0)
    }

    @Test
    fun activationFailsIfDiagnosticsAdvancedPastTheExactApprovalAnchor() = runBlocking {
        val dao = RecordingSensorCoreDao(productReady = false)
        dao.commit(record(sequence = 1, publishable = false).toEntityBundle())
        val approval = testPhysicalApproval(checkNotNull(dao.savedCheckpoint))
        dao.approvePhysicalSensor(approval.toEntity())
        dao.commit(record(sequence = 2, publishable = false).toEntityBundle())
        val binding = testPublicationBinding(approval).toEntity()

        assertThrows(SensorCoreConflictException::class.java) {
            runBlocking { dao.activatePublicationBinding(binding, null) }
        }
        assertEquals(null, dao.activePublicationBinding())
    }

    @Test
    fun credentialRotationKeepsPhysicalApprovalAndReplacesOnlyTheActiveBinding() = runBlocking {
        val dao = RecordingSensorCoreDao()
        dao.commit(record(sequence = 2).toEntityBundle())
        val previous = checkNotNull(dao.activePublicationBinding())
        val approval = checkNotNull(dao.physicalApproval(previous.approvalId)).toRecord()
        val rotated = testPublicationBinding(approval).copy(
            credentialId = "credential-b",
            credentialRevision = 4L,
            createdAtEpochMs = 1_700_000_063_000L,
        ).toEntity()

        assertEquals(
            SensorCoreCommitDisposition.COMMITTED,
            dao.activatePublicationBinding(rotated, previous.publicationBindingId),
        )
        assertEquals(rotated, dao.activePublicationBinding())
        assertEquals(approval.toEntity(), dao.physicalApproval(approval.approvalId))
    }

    @Test
    fun endedSensorCanBeReplacedWithoutMixingApprovalOrOutboxLineage() = runBlocking {
        val dao = RecordingSensorCoreDao()
        val sensorA = record(sequence = 2).toEntityBundle()
        dao.commit(sensorA)
        val bindingA = checkNotNull(dao.activePublicationBinding())
        val approvalA = checkNotNull(dao.physicalApproval(bindingA.approvalId))
        dao.endActivePublicationBinding(bindingA.publicationBindingId)

        val sensorBId = "sensor-b"
        val sensorBAddress = "AA:BB:CC:DD:EE:01"
        dao.bindProtocol(
            protocolBinding(sensorId = sensorBId, bluetoothAddress = sensorBAddress),
        )
        dao.commit(
            record(
                sequence = 1,
                sensorId = sensorBId,
                bluetoothAddress = sensorBAddress,
                publishable = false,
            ).toEntityBundle(),
        )
        val anchorB = checkNotNull(dao.checkpoint(sensorBId))
        val approvalB = testPhysicalApproval(anchorB)
        dao.approvePhysicalSensor(approvalB.toEntity())
        val bindingB = testPublicationBinding(approvalB).copy(
            credentialId = "credential-b",
            expectedDeviceId = "cccccccc-cccc-4ccc-8ccc-cccccccccccc",
            createdAtEpochMs = 1_700_000_064_000L,
        )
        dao.activatePublicationBinding(bindingB.toEntity(), null)
        val contextB = ProductPublicationContext.verifiedRuntime(
            approval = approvalB,
            publicationBinding = bindingB,
            nativeBinarySetSha256 = approvalB.nativeBinarySetSha256,
            nativeDatahandleBinarySetSha256 = approvalB.nativeDatahandleBinarySetSha256,
        )
        val sensorB = record(
            sequence = 2,
            sensorId = sensorBId,
            bluetoothAddress = sensorBAddress,
            productContext = contextB,
        ).toEntityBundle()

        assertEquals(SensorCoreCommitDisposition.COMMITTED, dao.commit(sensorB))
        assertEquals(approvalA, dao.physicalApproval(approvalA.approvalId))
        assertEquals(approvalB.toEntity(), dao.physicalApproval(approvalB.approvalId))
        assertEquals(bindingA.approvalId, dao.outboxByEvent(sensorA.raw.eventId)?.approvalId)
        assertEquals(approvalB.approvalId, dao.outboxByEvent(sensorB.raw.eventId)?.approvalId)
        org.junit.Assert.assertNotEquals(
            dao.outboxByEvent(sensorA.raw.eventId)?.publicationBindingId,
            dao.outboxByEvent(sensorB.raw.eventId)?.publicationBindingId,
        )
    }

    @Test
    fun approvedCheckpointCannotBeDowngradedToDiagnosticAfterSessionEnd() = runBlocking {
        val dao = RecordingSensorCoreDao()
        dao.commit(record(sequence = 2).toEntityBundle())
        val activeId = checkNotNull(dao.activePublicationBinding()).publicationBindingId
        dao.endActivePublicationBinding(activeId)

        assertThrows(SensorCoreConflictException::class.java) {
            runBlocking { dao.commit(record(sequence = 3, publishable = false).toEntityBundle()) }
        }
        assertEquals(2, dao.savedCheckpoint?.sequence)
    }

    @Test
    fun blockedRecoveryRequiresTheExactOriginalPublicationAndCredentialTuple() = runBlocking {
        val dao = RecordingSensorCoreDao()
        val product = record(sequence = 2).toEntityBundle()
        dao.commit(product)
        val now = product.raw.phoneTimeEpochMs + 1L
        val lease = dao.leaseDueOutbox(now, "lease-token-a", now + 10_000L, 1).single()
        val blockedReport = UploadDeliveryReportEntity("BLOCKED_CREDENTIAL", "KEYSTORE_MISSING")
        dao.blockOutbox(lease.eventId, "lease-token-a", blockedReport)
        val key = UploadBlockedRecoveryKey(
            eventId = lease.eventId,
            approvalId = lease.approvalId,
            publicationBindingId = lease.publicationBindingId,
            httpsOrigin = lease.httpsOrigin,
            backendBindingId = lease.backendBindingId,
            credentialId = lease.credentialId,
            credentialRevision = lease.credentialRevision,
            expectedPatientId = lease.expectedPatientId,
            expectedDeviceId = lease.expectedDeviceId,
            expectedBlockingStatus = UploadDeliveryStatus.BLOCKED_CREDENTIAL,
            expectedOperationToken = "lease-token-a",
        )

        assertEquals(
            SensorCoreCommitDisposition.COMMITTED,
            dao.requeueBlockedOutbox(key, now + 60_000L),
        )
        assertEquals(
            SensorCoreCommitDisposition.ALREADY_COMMITTED,
            dao.requeueBlockedOutbox(key, now + 60_000L),
        )
        assertThrows(SensorCoreConflictException::class.java) {
            runBlocking {
                dao.requeueBlockedOutbox(
                    key.copy(credentialRevision = key.credentialRevision + 1L),
                    now + 60_000L,
                )
            }
        }
        Unit
    }

    @Test
    fun unboundLogicalIdentityCannotClaimAnExistingPhysicalSensor() = runBlocking {
        val dao = RecordingSensorCoreDao()
        dao.commit(record(sensorId = "sensor-a").toEntityBundle())
        dao.calls.clear()

        val conflict = assertThrows(SensorCoreConflictException::class.java) {
            runBlocking { dao.commit(record(sensorId = "sensor-b").toEntityBundle()) }
        }

        assertEquals("Protocol must be durably bound before the first core commit", conflict.message)
        assertEquals(emptyList<String>(), dao.calls)
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
        sequence: Int = 2,
        sensorTime: Long = 1_700_000_000_000L + sequence * 60_000L,
        phoneTime: Long = sensorTime + 1_000L,
        sensorId: String = "sensor-a",
        bluetoothAddress: String = "AA:BB:CC:DD:EE:FF",
        historyDistance: Int = 0,
        transportVariant: Int = 0,
        transportProtocol: String = "GS1_V120",
        transportCodecId: String = "transport-codec-test",
        algorithmProfile: String = "V116A",
        algorithmVersion: String = "1.1.6A",
        algorithmStateSize: Int = 2_480,
        sensorStartTime: Long = sensorTime - sequence * 60_000L,
        sensorTimeWasClamped: Boolean = false,
        addTimeSeconds: Int? = null,
        publishable: Boolean = true,
        productContext: ProductPublicationContext? = testPublicationContext().takeIf { publishable },
        approvedCheckpointContext: ApprovedCheckpointContext? =
            productContext?.approvedCheckpointContext(),
    ): AtomicSensorCoreRecord {
        val packet = byteArrayOf(1, 2, sequence.toByte())
        val raw = RawSensorSampleRecord(
            eventId = "$sensorId:event-$sequence",
            sourceIngressId = "attempt-a:$sequence",
            sensorId = sensorId,
            sensorFamily = SensorFamily.SIBIONICS_GS1,
            sequence = sequence,
            sensorTimeEpochMs = sensorTime,
            phoneTimeEpochMs = phoneTime,
            packet = packet,
            packetSha256 = packet.sha256(),
            currentRaw = 53,
            temperatureRaw = 322,
            historyDistance = historyDistance,
            transportVariant = transportVariant,
            sensorTimeWasClamped = sensorTimeWasClamped,
            addTimeSeconds = addTimeSeconds,
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
            algorithmProfile = algorithmProfile,
            algorithmVersion = algorithmVersion,
            binarySetId = "set",
            sensitivityToken = "ABCDEFGH",
            sensitivityTokenSource = "PACKAGE_CODE",
            sensitivityCoefficient = 1.42,
            sensitivityEncoding = "NORMAL",
            initializationMode = "STANDARD",
            publishable = publishable,
            alarmEligible = publishable,
            algorithmErrorCode = if (publishable) null else "DIAGNOSTIC_ONLY",
            publicationApprovalId = approvedCheckpointContext?.approvalId,
        )
        val checkpoint = SensorAlgorithmCheckpointRecord(
            sensorId = raw.sensorId,
            bluetoothAddress = bluetoothAddress,
            sensorFamily = raw.sensorFamily,
            transportVariant = raw.transportVariant,
            transportProtocol = transportProtocol,
            transportCodecId = transportCodecId,
            sequence = raw.sequence,
            sensorTimeEpochMs = raw.sensorTimeEpochMs,
            sensorStartTimeEpochMs = sensorStartTime,
            algorithmProfile = algorithmProfile,
            algorithmVersion = algorithmVersion,
            binarySetId = "set",
            sensitivityToken = "ABCDEFGH",
            sensitivityTokenSource = "PACKAGE_CODE",
            sensitivityCoefficient = 1.42,
            sensitivityEncoding = "NORMAL",
            initializationMode = "STANDARD",
            state = ByteArray(algorithmStateSize),
            stateSha256 = ByteArray(algorithmStateSize).sha256(),
            displayOffsetMmolL = 0.0,
            schemaVersion = 1,
            publicationApprovalId = approvedCheckpointContext?.approvalId,
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
        return AtomicSensorCoreRecord(
            raw = raw,
            result = result,
            checkpoint = checkpoint,
            measurement = measurement.takeIf { publishable },
            publicationContext = productContext.takeIf { publishable },
            approvedCheckpointContext = approvedCheckpointContext,
        )
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

    private fun protocolBinding(
        sensorId: String = "sensor-a",
        bluetoothAddress: String = "AA:BB:CC:DD:EE:FF",
        transportVariant: Int = 0,
        wireProfile: String = "V120",
        transportProtocol: String = "GS1_V120",
        transportCodecId: String = "transport-codec-test",
        algorithmProfile: String = "V116A",
    ) = SensorProtocolBindingEntity(
        sensorId = sensorId,
        bluetoothAddress = bluetoothAddress,
        sensorFamily = SensorFamily.SIBIONICS_GS1.wireName,
        transportVariant = transportVariant,
        sensitivityToken = "ABCDEFGH",
        wireProfile = wireProfile,
        transportProtocol = transportProtocol,
        transportCodecId = transportCodecId,
        algorithmProfile = algorithmProfile,
        sensitivityEncoding = "NORMAL",
        evidenceKind = "TEST_EVIDENCE",
        evidenceSha256 = "ab".repeat(32),
        schemaVersion = 1,
    )

    private fun ByteArray.sha256(): String = MessageDigest.getInstance("SHA-256")
        .digest(this)
        .joinToString("") { "%02x".format(it) }
}

private class RecordingSensorCoreDao(
    private val failOnResult: Boolean = false,
    private val implicitProtocolBinding: Boolean = true,
    private val productReady: Boolean = true,
    private val exactIngressAvailable: Boolean = true,
) : SensorCoreDao() {
    val calls = mutableListOf<String>()
    private val rawByEvent = mutableMapOf<String, RawSensorSampleEntity>()
    private val resultByEvent = mutableMapOf<String, SensorAlgorithmResultEntity>()
    private val measurementByEvent = mutableMapOf<String, MeasurementEntity>()
    private val checkpoints = mutableMapOf<String, SensorAlgorithmCheckpointEntity>()
    private val protocolBindings = mutableMapOf<String, SensorProtocolBindingEntity>()
    private val approvals = mutableMapOf<String, PhysicalSensorApprovalEntity>()
    private val publicationBindings = mutableMapOf<String, ProductPublicationBindingEntity>()
    private var activePublication: ActiveSensorPublicationBindingEntity? = null
    private val outbox = mutableMapOf<String, UploadOutboxEntity>()
    private var nextOutboxId = 1L

    init {
        if (productReady) {
            val anchor = baselineCheckpoint()
            checkpoints[anchor.sensorId] = anchor
            val approval = testPhysicalApproval(anchor).toEntity()
            approvals[approval.approvalId] = approval
            val publication = testPublicationBinding(approval.toRecord()).toEntity()
            publicationBindings[publication.publicationBindingId] = publication
            activePublication = ActiveSensorPublicationBindingEntity(
                ACTIVE_PUBLICATION_BINDING_SLOT,
                publication.publicationBindingId,
            )
        }
    }
    val savedCheckpoint: SensorAlgorithmCheckpointEntity?
        get() = checkpoints.values.singleOrNull()

    override suspend fun insertProtocolBinding(value: SensorProtocolBindingEntity): Long {
        val conflict = protocolBindings.containsKey(value.sensorId) ||
            protocolBindings.values.any { it.bluetoothAddress == value.bluetoothAddress }
        if (conflict) return -1
        protocolBindings[value.sensorId] = value
        return 1
    }

    override suspend fun protocolBinding(sensorId: String): SensorProtocolBindingEntity? =
        protocolBindings[sensorId] ?: implicitBinding().takeIf { implicitProtocolBinding && it.sensorId == sensorId }

    override suspend fun protocolBindingByBluetoothAddress(
        bluetoothAddress: String,
    ): SensorProtocolBindingEntity? = protocolBindings.values.singleOrNull {
        it.bluetoothAddress == bluetoothAddress
    } ?: implicitBinding().takeIf {
        implicitProtocolBinding && it.bluetoothAddress == bluetoothAddress
    }

    override suspend fun insertPhysicalApproval(value: PhysicalSensorApprovalEntity): Long {
        if (approvals.containsKey(value.approvalId)) return -1L
        approvals[value.approvalId] = value
        return 1L
    }

    override suspend fun physicalApproval(approvalId: String): PhysicalSensorApprovalEntity? =
        approvals[approvalId]

    override suspend fun insertPublicationBinding(value: ProductPublicationBindingEntity): Long {
        if (publicationBindings.containsKey(value.publicationBindingId)) return -1L
        publicationBindings[value.publicationBindingId] = value
        return 1L
    }

    override suspend fun publicationBinding(
        publicationBindingId: String,
    ): ProductPublicationBindingEntity? = publicationBindings[publicationBindingId]

    override suspend fun activePublicationBinding(): ProductPublicationBindingEntity? =
        activePublication?.let { publicationBindings[it.publicationBindingId] }

    override suspend fun replaceActivePublicationBinding(value: ActiveSensorPublicationBindingEntity) {
        activePublication = value
    }

    override suspend fun deleteActivePublicationBinding(
        expectedPublicationBindingId: String,
    ): Int = if (activePublication?.publicationBindingId == expectedPublicationBindingId) {
        activePublication = null
        1
    } else {
        0
    }

    private fun implicitBinding() = SensorProtocolBindingEntity(
        sensorId = "sensor-a",
        bluetoothAddress = "AA:BB:CC:DD:EE:FF",
        sensorFamily = SensorFamily.SIBIONICS_GS1.wireName,
        transportVariant = 0,
        sensitivityToken = "ABCDEFGH",
        wireProfile = "V120",
        transportProtocol = "GS1_V120",
        transportCodecId = "transport-codec-test",
        algorithmProfile = "V116A",
        sensitivityEncoding = "NORMAL",
        evidenceKind = "TEST_EVIDENCE",
        evidenceSha256 = "ab".repeat(32),
        schemaVersion = 1,
    )

    override suspend fun insertRaw(value: RawSensorSampleEntity): Long {
        calls += "raw"
        val conflict = rawByEvent.values.any {
            it.eventId == value.eventId || it.sensorId == value.sensorId && it.sequence == value.sequence
        }
        if (conflict) return -1
        rawByEvent[value.eventId] = value
        return 1
    }

    override suspend fun exactSourceIngress(
        sourceIngressId: String,
        sensorId: String,
        sensorFamily: String,
        bluetoothAddress: String,
        receivedAtEpochMs: Long,
        encryptedPacket: ByteArray,
        packetSha256: String,
    ): SensorPacketIngressEntity? = if (exactIngressAvailable) {
        SensorPacketIngressEntity(
            ingressId = sourceIngressId,
            sensorId = sensorId,
            sensorFamily = sensorFamily,
            bluetoothAddress = bluetoothAddress,
            attemptId = sourceIngressId.substringBeforeLast(':'),
            ordinal = sourceIngressId.substringAfterLast(':').toLongOrNull() ?: 0L,
            receivedAtEpochMs = receivedAtEpochMs,
            encryptedPacket = encryptedPacket.copyOf(),
            packetSha256 = packetSha256,
        )
    } else {
        null
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

    override suspend fun insertOutbox(value: UploadOutboxEntity): Long {
        calls += "outbox"
        if (outbox.containsKey(value.eventId)) return -1L
        outbox[value.eventId] = value.copy(outboxId = nextOutboxId++)
        return nextOutboxId - 1L
    }

    override suspend fun outboxByEvent(eventId: String): UploadOutboxEntity? = outbox[eventId]

    override suspend fun outboxByLeaseToken(leaseToken: String): List<UploadOutboxEntity> =
        outbox.values.filter { it.leaseToken == leaseToken }.sortedBy { it.outboxId }

    override suspend fun outboxByOperationToken(token: String): List<UploadOutboxEntity> =
        outbox.values.filter { it.leaseToken == token || it.lastTransitionToken == token }
            .sortedBy { it.outboxId }

    override suspend fun dueOutbox(nowEpochMs: Long, limit: Int): List<UploadOutboxEntity> =
        outbox.values.filter { it.state == "PENDING" && it.nextAttemptEpochMs <= nowEpochMs }
            .sortedBy { it.outboxId }
            .take(limit)

    override suspend fun recoverExpiredOutboxLeases(nowEpochMs: Long): Int {
        val expired = outbox.values.filter {
            it.state == "LEASED" && checkNotNull(it.leaseExpiresAtEpochMs) <= nowEpochMs
        }
        expired.forEach { value ->
            outbox[value.eventId] = value.copy(
                state = "PENDING",
                nextAttemptEpochMs = minOf(value.nextAttemptEpochMs, nowEpochMs),
                leaseToken = null,
                leaseExpiresAtEpochMs = null,
                lastTransitionToken = value.leaseToken,
                sanitizedStatus = "RETRYABLE_NETWORK",
                sanitizedDetail = "LEASE_EXPIRED",
            )
        }
        return expired.size
    }

    override suspend fun acquireOutboxLease(
        outboxId: Long,
        nowEpochMs: Long,
        leaseToken: String,
        leaseExpiresAtEpochMs: Long,
    ): Int {
        val value = outbox.values.singleOrNull { it.outboxId == outboxId } ?: return 0
        if (value.state != "PENDING" || value.nextAttemptEpochMs > nowEpochMs) return 0
        outbox[value.eventId] = value.copy(
            state = "LEASED",
            attempts = value.attempts + 1,
            leaseToken = leaseToken,
            leaseExpiresAtEpochMs = leaseExpiresAtEpochMs,
            sanitizedStatus = null,
            sanitizedDetail = null,
        )
        return 1
    }

    override suspend fun setOutboxSent(
        eventId: String,
        leaseToken: String,
        status: String,
        detail: String?,
    ): Int = transitionLeased(eventId, leaseToken) {
        it.copy(
            state = "SENT",
            leaseToken = null,
            leaseExpiresAtEpochMs = null,
            lastTransitionToken = leaseToken,
            sanitizedStatus = status,
            sanitizedDetail = detail,
        )
    }

    override suspend fun setOutboxPending(
        eventId: String,
        leaseToken: String,
        nextAttemptEpochMs: Long,
        status: String,
        detail: String?,
    ): Int = transitionLeased(eventId, leaseToken) {
        it.copy(
            state = "PENDING",
            nextAttemptEpochMs = nextAttemptEpochMs,
            leaseToken = null,
            leaseExpiresAtEpochMs = null,
            lastTransitionToken = leaseToken,
            sanitizedStatus = status,
            sanitizedDetail = detail,
        )
    }

    override suspend fun setOutboxBlocked(
        eventId: String,
        leaseToken: String,
        status: String,
        detail: String?,
    ): Int = transitionLeased(eventId, leaseToken) {
        it.copy(
            state = "BLOCKED",
            leaseToken = null,
            leaseExpiresAtEpochMs = null,
            lastTransitionToken = leaseToken,
            sanitizedStatus = status,
            sanitizedDetail = detail,
        )
    }

    override suspend fun setBlockedOutboxPending(
        eventId: String,
        approvalId: String,
        publicationBindingId: String,
        httpsOrigin: String,
        backendBindingId: String,
        credentialId: String,
        credentialRevision: Long,
        expectedPatientId: String,
        expectedDeviceId: String,
        expectedStatus: String,
        expectedOperationToken: String,
        nextAttemptEpochMs: Long,
    ): Int {
        val value = outbox[eventId] ?: return 0
        if (value.state != "BLOCKED" || value.approvalId != approvalId ||
            value.publicationBindingId != publicationBindingId ||
            value.httpsOrigin != httpsOrigin ||
            value.backendBindingId != backendBindingId || value.credentialId != credentialId ||
            value.credentialRevision != credentialRevision ||
            value.expectedPatientId != expectedPatientId ||
            value.expectedDeviceId != expectedDeviceId ||
            value.sanitizedStatus != expectedStatus ||
            value.lastTransitionToken != expectedOperationToken
        ) return 0
        outbox[eventId] = value.copy(
            state = "PENDING",
            nextAttemptEpochMs = nextAttemptEpochMs,
            sanitizedStatus = null,
            sanitizedDetail = null,
        )
        return 1
    }

    private fun transitionLeased(
        eventId: String,
        leaseToken: String,
        transform: (UploadOutboxEntity) -> UploadOutboxEntity,
    ): Int {
        val value = outbox[eventId] ?: return 0
        if (value.state != "LEASED" || value.leaseToken != leaseToken) return 0
        outbox[eventId] = transform(value)
        return 1
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
    }?.let { detachedRoomRow ->
        // Independent Room queries materialize distinct ByteArray instances.
        detachedRoomRow.copy(state = detachedRoomRow.state.copyOf())
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

private fun baselineCheckpoint() = SensorAlgorithmCheckpointEntity(
    sensorId = "sensor-a",
    bluetoothAddress = "AA:BB:CC:DD:EE:FF",
    sensorFamily = SensorFamily.SIBIONICS_GS1.wireName,
    transportVariant = 0,
    transportProtocol = "GS1_V120",
    transportCodecId = "transport-codec-test",
    sequence = 1,
    sensorTimeEpochMs = 1_700_000_060_000L,
    sensorStartTimeEpochMs = 1_700_000_000_000L,
    algorithmProfile = "V116A",
    algorithmVersion = "1.1.6A",
    binarySetId = "set",
    sensitivityToken = "ABCDEFGH",
    sensitivityTokenSource = "PACKAGE_CODE",
    sensitivityCoefficient = 1.42,
    sensitivityEncoding = "NORMAL",
    initializationMode = "STANDARD",
    state = ByteArray(2_480),
    stateSha256 = ByteArray(2_480).sha256ForTest(),
    displayOffsetMmolL = 0.0,
    schemaVersion = 1,
)

private fun testPhysicalApproval(
    anchor: SensorAlgorithmCheckpointEntity = baselineCheckpoint(),
) = PhysicalSensorApprovalRecord(
    sensorId = anchor.sensorId,
    bluetoothAddress = anchor.bluetoothAddress,
    sensorFamily = SensorFamily.SIBIONICS_GS1,
    transportVariant = anchor.transportVariant,
    sensitivityToken = anchor.sensitivityToken,
    wireProfile = "V120",
    transportProtocol = anchor.transportProtocol,
    transportCodecId = anchor.transportCodecId,
    algorithmProfile = anchor.algorithmProfile,
    algorithmVersion = anchor.algorithmVersion,
    binarySetId = anchor.binarySetId,
    sensitivityTokenSource = anchor.sensitivityTokenSource,
    sensitivityCoefficient = anchor.sensitivityCoefficient,
    sensitivityEncoding = anchor.sensitivityEncoding,
    initializationMode = anchor.initializationMode,
    displayOffsetMmolL = anchor.displayOffsetMmolL,
    protocolEvidenceKind = "TEST_EVIDENCE",
    protocolEvidenceSha256 = "ab".repeat(32),
    physicalValidationEvidenceSha256 = "cd".repeat(32),
    checkpointSchemaVersion = anchor.schemaVersion,
    approvedSequence = anchor.sequence,
    approvedSensorTimeEpochMs = anchor.sensorTimeEpochMs,
    sensorStartTimeEpochMs = anchor.sensorStartTimeEpochMs,
    approvedCheckpointStateSha256 = anchor.stateSha256,
    nativeBinarySetSha256 = "12".repeat(32),
    nativeDatahandleBinarySetSha256 = "34".repeat(32),
    approvedAtEpochMs = 1_700_000_061_000L,
)

private fun testPublicationBinding(
    approval: PhysicalSensorApprovalRecord = testPhysicalApproval(),
) = ProductPublicationBindingRecord(
    approvalId = approval.approvalId,
    httpsOrigin = "https://api.sladkaya.test",
    backendBindingId = "backend-binding-a",
    credentialId = "credential-a",
    credentialRevision = 3L,
    expectedPatientId = "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa",
    expectedDeviceId = "bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb",
    createdAtEpochMs = 1_700_000_062_000L,
)

private fun testPublicationContext(): ProductPublicationContext {
    val approval = testPhysicalApproval()
    val binding = testPublicationBinding(approval)
    return ProductPublicationContext.verifiedRuntime(
        approval = approval,
        publicationBinding = binding,
        nativeBinarySetSha256 = approval.nativeBinarySetSha256,
        nativeDatahandleBinarySetSha256 = approval.nativeDatahandleBinarySetSha256,
    )
}

private fun ByteArray.sha256ForTest(): String = MessageDigest.getInstance("SHA-256")
    .digest(this)
    .joinToString("") { "%02x".format(it) }
