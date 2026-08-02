package com.sladkaya.core.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Upsert

@Dao
internal abstract class SensorCoreDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    abstract suspend fun insertProtocolBinding(value: SensorProtocolBindingEntity): Long

    @Query("SELECT * FROM sensor_protocol_bindings WHERE sensorId = :sensorId LIMIT 1")
    abstract suspend fun protocolBinding(sensorId: String): SensorProtocolBindingEntity?

    @Query(
        "SELECT * FROM sensor_protocol_bindings " +
            "WHERE bluetoothAddress = :bluetoothAddress LIMIT 1",
    )
    abstract suspend fun protocolBindingByBluetoothAddress(
        bluetoothAddress: String,
    ): SensorProtocolBindingEntity?

    @Transaction
    open suspend fun bindProtocol(value: SensorProtocolBindingEntity): SensorCoreCommitDisposition {
        val physical = protocolBindingByBluetoothAddress(value.bluetoothAddress)
        if (physical != null && physical.sensorId != value.sensorId) {
            conflict("Bluetooth address is already bound to another protocol identity")
        }
        if (insertProtocolBinding(value) != INSERT_IGNORED) {
            return SensorCoreCommitDisposition.COMMITTED
        }
        if (protocolBinding(value.sensorId) == value) {
            return SensorCoreCommitDisposition.ALREADY_COMMITTED
        }
        conflict("Protocol binding is immutable and conflicts with stored evidence")
    }

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    abstract suspend fun insertPhysicalApproval(value: PhysicalSensorApprovalEntity): Long

    @Query("SELECT * FROM physical_sensor_approvals WHERE approvalId = :approvalId LIMIT 1")
    abstract suspend fun physicalApproval(approvalId: String): PhysicalSensorApprovalEntity?

    @Transaction
    open suspend fun approvePhysicalSensor(
        value: PhysicalSensorApprovalEntity,
    ): SensorCoreCommitDisposition {
        try {
            value.toRecord()
        } catch (_: IllegalArgumentException) {
            conflict("Physical approval is malformed")
        } catch (_: NoSuchElementException) {
            conflict("Physical approval contains an unsupported typed value")
        }
        val binding = protocolBinding(value.sensorId)
            ?: conflict("Physical approval requires a durable protocol binding")
        if (protocolBindingByBluetoothAddress(value.bluetoothAddress) != binding ||
            !binding.matchesApproval(value)
        ) {
            conflict("Physical approval does not match the durable protocol binding")
        }
        val checkpoint = checkpoint(value.sensorId)
            ?: conflict("Physical approval requires a durable algorithm checkpoint")
        if (checkpointByBluetoothAddress(value.bluetoothAddress)?.sameAs(checkpoint) != true ||
            !checkpoint.matchesApprovalAnchor(value)
        ) {
            conflict("Physical approval does not match checkpoint provenance")
        }
        if (insertPhysicalApproval(value) != INSERT_IGNORED) {
            return SensorCoreCommitDisposition.COMMITTED
        }
        if (physicalApproval(value.approvalId) == value) {
            return SensorCoreCommitDisposition.ALREADY_COMMITTED
        }
        conflict("Physical approval identity conflicts with different evidence")
    }

    /**
     * One owner-controlled local promotion: the exact diagnostic point, approval and
     * stable local binding either commit together or Room rolls every write back.
     */
    @Transaction
    open suspend fun approveAndActivatePhysicalSensor(
        diagnosticEventId: String,
        approval: PhysicalSensorApprovalEntity,
        publicationBindingId: String,
        expectedPreviousPublicationBindingId: String?,
    ): SensorCoreCommitDisposition {
        require(diagnosticEventId.isNotBlank() && diagnosticEventId.length <= 128)
        val raw = rawByEvent(diagnosticEventId)
            ?: conflict("Physical activation requires the exact diagnostic raw sample")
        val result = resultByEvent(diagnosticEventId)
            ?: conflict("Physical activation requires the exact diagnostic algorithm result")
        val protocol = protocolBinding(raw.sensorId)
            ?: conflict("Physical activation requires durable protocol evidence")
        val anchorCheckpoint = checkpoint(raw.sensorId)
            ?: conflict("Physical activation requires a durable diagnostic checkpoint")
        val anchor = try {
            PhysicalSensorDiagnosticAnchor(
                protocol = protocol.toRecord(),
                raw = raw.toPhysicalActivationRecord(),
                result = result.toPhysicalActivationRecord(),
                checkpoint = anchorCheckpoint.toRecord(),
            )
        } catch (_: IllegalArgumentException) {
            conflict("Physical activation diagnostic evidence is malformed")
        } catch (_: NoSuchElementException) {
            conflict("Physical activation diagnostic evidence contains an unsupported typed value")
        }
        if (!anchor.isEligibleForLocalPrototypeActivation()) {
            conflict("Physical activation requires one exact fresh VALID diagnostic point")
        }
        val supplied = try {
            approval.toRecord()
        } catch (_: IllegalArgumentException) {
            conflict("Physical activation approval is malformed")
        } catch (_: NoSuchElementException) {
            conflict("Physical activation approval contains an unsupported typed value")
        }
        val expected = try {
            PhysicalSensorActivationIdentity.localPrototypeApproval(
                anchor = anchor,
                nativeBinarySetSha256 = supplied.nativeBinarySetSha256,
                nativeDatahandleBinarySetSha256 = supplied.nativeDatahandleBinarySetSha256,
            )
        } catch (_: IllegalArgumentException) {
            conflict("Physical activation evidence cannot produce a canonical approval")
        }
        if (supplied != expected) {
            conflict("Physical activation approval does not match the exact diagnostic evidence")
        }
        val approvalDisposition = approvePhysicalSensor(approval)
        val bindingDisposition = activateLocalSensorBinding(
            approvalId = supplied.approvalId,
            publicationBindingId = publicationBindingId,
            expectedPreviousPublicationBindingId = expectedPreviousPublicationBindingId,
        )
        return if (approvalDisposition == SensorCoreCommitDisposition.COMMITTED ||
            bindingDisposition == SensorCoreCommitDisposition.COMMITTED
        ) {
            SensorCoreCommitDisposition.COMMITTED
        } else {
            SensorCoreCommitDisposition.ALREADY_COMMITTED
        }
    }

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    abstract suspend fun insertPublicationBinding(value: ProductPublicationBindingEntity): Long

    @Query(
        "SELECT * FROM product_publication_bindings " +
            "WHERE remotePublicationBindingId = :remotePublicationBindingId LIMIT 1",
    )
    abstract suspend fun publicationBinding(
        remotePublicationBindingId: String,
    ): ProductPublicationBindingEntity?

    @Query(
        "SELECT b.* FROM product_publication_bindings b " +
            "INNER JOIN active_remote_publication_binding r " +
            "ON r.remotePublicationBindingId = b.remotePublicationBindingId " +
            "INNER JOIN active_sensor_publication_binding a " +
            "ON a.publicationBindingId = r.publicationBindingId " +
            "AND a.approvalId = r.approvalId " +
            "WHERE a.activeSlot = $ACTIVE_PUBLICATION_BINDING_SLOT LIMIT 1",
    )
    abstract suspend fun activePublicationBinding(): ProductPublicationBindingEntity?

    @Query(
        "SELECT * FROM active_sensor_publication_binding " +
            "WHERE activeSlot = $ACTIVE_PUBLICATION_BINDING_SLOT LIMIT 1",
    )
    abstract suspend fun activeSensorBinding(): ActiveSensorPublicationBindingEntity?

    @Upsert
    abstract suspend fun replaceActivePublicationBinding(
        value: ActiveSensorPublicationBindingEntity,
    )

    @Query(
        "SELECT * FROM active_remote_publication_binding " +
            "WHERE publicationBindingId = :publicationBindingId LIMIT 1",
    )
    abstract suspend fun activeRemotePublicationBinding(
        publicationBindingId: String,
    ): ActiveRemotePublicationBindingEntity?

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    abstract suspend fun insertActiveRemotePublicationBinding(
        value: ActiveRemotePublicationBindingEntity,
    ): Long

    @Query(
        "UPDATE active_remote_publication_binding " +
            "SET remotePublicationBindingId = :remotePublicationBindingId " +
            "WHERE publicationBindingId = :publicationBindingId AND approvalId = :approvalId " +
            "AND remotePublicationBindingId = :expectedPreviousRemotePublicationBindingId",
    )
    abstract suspend fun updateActiveRemotePublicationBinding(
        publicationBindingId: String,
        approvalId: String,
        remotePublicationBindingId: String,
        expectedPreviousRemotePublicationBindingId: String,
    ): Int

    @Query(
        "DELETE FROM active_remote_publication_binding " +
            "WHERE publicationBindingId = :publicationBindingId",
    )
    abstract suspend fun deleteActiveRemotePublicationBinding(
        publicationBindingId: String,
    ): Int

    @Query(
        "DELETE FROM active_sensor_publication_binding " +
            "WHERE activeSlot = $ACTIVE_PUBLICATION_BINDING_SLOT " +
            "AND publicationBindingId = :expectedPublicationBindingId",
    )
    abstract suspend fun deleteActivePublicationBinding(
        expectedPublicationBindingId: String,
    ): Int

    @Transaction
    open suspend fun activatePublicationBinding(
        value: ProductPublicationBindingEntity,
        expectedPreviousRemotePublicationBindingId: String?,
    ): SensorCoreCommitDisposition {
        try {
            value.toRecord()
        } catch (_: IllegalArgumentException) {
            conflict("Publication binding is malformed")
        }
        val approval = physicalApproval(value.approvalId)
            ?: conflict("Publication binding requires durable physical approval")
        try {
            approval.toRecord()
        } catch (_: IllegalArgumentException) {
            conflict("Publication binding references malformed physical approval")
        }
        val protocol = protocolBinding(approval.sensorId)
            ?: conflict("Publication binding requires durable protocol provenance")
        if (!protocol.matchesApproval(approval)) {
            conflict("Publication binding approval no longer matches protocol provenance")
        }
        val currentCheckpoint = checkpoint(approval.sensorId)
            ?: conflict("Publication binding requires a durable checkpoint")
        when (currentCheckpoint.publicationApprovalId) {
            null -> if (!currentCheckpoint.matchesApprovalAnchor(approval)) {
                conflict("Publication binding requires the exact approved checkpoint anchor")
            }
            approval.approvalId -> if (!currentCheckpoint.hasApprovedImmutableProvenance(approval)) {
                conflict("Publication binding checkpoint changed approved provenance")
            }
            else -> conflict("Publication binding checkpoint belongs to another approval")
        }
        val active = activeSensorBinding()
            ?: conflict("Remote publication binding requires an active local sensor binding")
        if (active.publicationBindingId != value.publicationBindingId ||
            active.approvalId != value.approvalId
        ) {
            conflict("Remote publication binding does not match the active local sensor binding")
        }
        val activeRemote = activeRemotePublicationBinding(value.publicationBindingId)
        if (activeRemote?.remotePublicationBindingId == value.remotePublicationBindingId) {
            if (publicationBinding(value.remotePublicationBindingId) == value
            ) return SensorCoreCommitDisposition.ALREADY_COMMITTED
            conflict("Active remote publication binding identity conflicts with different contents")
        }
        if (activeRemote?.remotePublicationBindingId != expectedPreviousRemotePublicationBindingId) {
            conflict("Active remote publication binding changed")
        }
        if (insertPublicationBinding(value) == INSERT_IGNORED &&
            publicationBinding(value.remotePublicationBindingId) != value
        ) {
            conflict("Remote publication binding identity conflicts with different contents")
        }
        val activeRoute = ActiveRemotePublicationBindingEntity(
            publicationBindingId = value.publicationBindingId,
            approvalId = value.approvalId,
            remotePublicationBindingId = value.remotePublicationBindingId,
        )
        val changed = if (expectedPreviousRemotePublicationBindingId == null) {
            insertActiveRemotePublicationBinding(activeRoute) != INSERT_IGNORED
        } else {
            updateActiveRemotePublicationBinding(
                publicationBindingId = value.publicationBindingId,
                approvalId = value.approvalId,
                remotePublicationBindingId = value.remotePublicationBindingId,
                expectedPreviousRemotePublicationBindingId =
                    expectedPreviousRemotePublicationBindingId,
            ) == 1
        }
        if (!changed) conflict("Active local or remote publication binding changed")
        return SensorCoreCommitDisposition.COMMITTED
    }

    @Transaction
    open suspend fun activateLocalSensorBinding(
        approvalId: String,
        publicationBindingId: String,
        expectedPreviousPublicationBindingId: String?,
    ): SensorCoreCommitDisposition {
        require(SHA256_HEX.matches(approvalId))
        require(SHA256_HEX.matches(publicationBindingId))
        val approval = physicalApproval(approvalId)
            ?: conflict("Local sensor binding requires durable physical approval")
        try {
            approval.toRecord()
        } catch (_: IllegalArgumentException) {
            conflict("Local sensor binding references malformed physical approval")
        } catch (_: NoSuchElementException) {
            conflict("Local sensor binding approval contains unsupported typed data")
        }
        val protocol = protocolBinding(approval.sensorId)
            ?: conflict("Local sensor binding requires durable protocol provenance")
        val currentCheckpoint = checkpoint(approval.sensorId)
            ?: conflict("Local sensor binding requires a durable checkpoint")
        if (!protocol.matchesApproval(approval) ||
            !currentCheckpoint.hasApprovedImmutableProvenance(approval) ||
            currentCheckpoint.publicationApprovalId !in setOf(null, approvalId)
        ) {
            conflict("Local sensor binding does not match approved sensor provenance")
        }
        val active = activeSensorBinding()
        if (active?.publicationBindingId == publicationBindingId) {
            if (active.approvalId == approvalId) {
                return SensorCoreCommitDisposition.ALREADY_COMMITTED
            }
            conflict("Active local sensor binding identity belongs to another approval")
        }
        if (active?.publicationBindingId != expectedPreviousPublicationBindingId) {
            conflict("Active local sensor binding changed or was not explicitly ended")
        }
        active?.let { deleteActiveRemotePublicationBinding(it.publicationBindingId) }
        replaceActivePublicationBinding(
            ActiveSensorPublicationBindingEntity(
                activeSlot = ACTIVE_PUBLICATION_BINDING_SLOT,
                publicationBindingId = publicationBindingId,
                approvalId = approvalId,
            ),
        )
        return SensorCoreCommitDisposition.COMMITTED
    }

    @Transaction
    open suspend fun endActivePublicationBinding(
        expectedPublicationBindingId: String,
    ): SensorCoreCommitDisposition {
        val active = activeSensorBinding()
            ?: return SensorCoreCommitDisposition.ALREADY_COMMITTED
        if (active.publicationBindingId != expectedPublicationBindingId) {
            conflict("A different publication binding is active")
        }
        deleteActiveRemotePublicationBinding(expectedPublicationBindingId)
        if (deleteActivePublicationBinding(expectedPublicationBindingId) != 1) {
            conflict("Active publication binding changed while ending the session")
        }
        return SensorCoreCommitDisposition.COMMITTED
    }

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    abstract suspend fun insertRaw(value: RawSensorSampleEntity): Long

    @Query(
        "SELECT * FROM sensor_packet_ingress WHERE ingressId = :sourceIngressId " +
            "AND sensorId = :sensorId AND sensorFamily = :sensorFamily " +
            "AND bluetoothAddress = :bluetoothAddress AND receivedAtEpochMs = :receivedAtEpochMs " +
            "AND encryptedPacket = :encryptedPacket AND packetSha256 = :packetSha256 LIMIT 1",
    )
    abstract suspend fun exactSourceIngress(
        sourceIngressId: String,
        sensorId: String,
        sensorFamily: String,
        bluetoothAddress: String,
        receivedAtEpochMs: Long,
        encryptedPacket: ByteArray,
        packetSha256: String,
    ): SensorPacketIngressEntity?

    @Query("SELECT * FROM sensor_raw_samples WHERE eventId = :eventId LIMIT 1")
    abstract suspend fun rawByEvent(eventId: String): RawSensorSampleEntity?

    @Query("SELECT * FROM sensor_raw_samples WHERE sensorId = :sensorId AND sequence = :sequence LIMIT 1")
    abstract suspend fun rawBySequence(sensorId: String, sequence: Int): RawSensorSampleEntity?

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    abstract suspend fun insertResult(value: SensorAlgorithmResultEntity): Long

    @Query("SELECT * FROM sensor_algorithm_results WHERE eventId = :eventId LIMIT 1")
    abstract suspend fun resultByEvent(eventId: String): SensorAlgorithmResultEntity?

    @Query("SELECT * FROM sensor_algorithm_results WHERE sensorId = :sensorId AND sequence = :sequence LIMIT 1")
    abstract suspend fun resultBySequence(sensorId: String, sequence: Int): SensorAlgorithmResultEntity?

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    abstract suspend fun insertMeasurement(value: MeasurementEntity): Long

    @Query("SELECT * FROM measurements WHERE eventId = :eventId LIMIT 1")
    abstract suspend fun measurement(eventId: String): MeasurementEntity?

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    abstract suspend fun insertOutbox(value: UploadOutboxEntity): Long

    @Query("SELECT * FROM measurement_upload_outbox WHERE eventId = :eventId LIMIT 1")
    abstract suspend fun outboxByEvent(eventId: String): UploadOutboxEntity?

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    abstract suspend fun insertLocalEffect(value: LocalReadingEffectEntity): Long

    @Query("SELECT * FROM local_reading_effects WHERE eventId = :eventId LIMIT 1")
    abstract suspend fun localEffectByEvent(eventId: String): LocalReadingEffectEntity?

    @Query(
        "SELECT * FROM measurement_upload_outbox " +
            "WHERE leaseToken = :leaseToken ORDER BY outboxId ASC",
    )
    abstract suspend fun outboxByLeaseToken(leaseToken: String): List<UploadOutboxEntity>

    @Query(
        "SELECT * FROM measurement_upload_outbox " +
            "WHERE leaseToken = :token OR lastTransitionToken = :token ORDER BY outboxId ASC",
    )
    abstract suspend fun outboxByOperationToken(token: String): List<UploadOutboxEntity>

    @Query(
        "SELECT * FROM measurement_upload_outbox " +
            "WHERE state = 'PENDING' AND nextAttemptEpochMs <= :nowEpochMs " +
            "ORDER BY outboxId ASC LIMIT :limit",
    )
    abstract suspend fun dueOutbox(nowEpochMs: Long, limit: Int): List<UploadOutboxEntity>

    @Query(
        "UPDATE measurement_upload_outbox SET " +
            "state = 'PENDING', " +
            "nextAttemptEpochMs = CASE " +
            "WHEN nextAttemptEpochMs > :nowEpochMs THEN :nowEpochMs ELSE nextAttemptEpochMs END, " +
            "lastTransitionToken = leaseToken, leaseToken = NULL, leaseExpiresAtEpochMs = NULL, " +
            "sanitizedStatus = 'RETRYABLE_NETWORK', sanitizedDetail = 'LEASE_EXPIRED' " +
            "WHERE state = 'LEASED' AND leaseExpiresAtEpochMs <= :nowEpochMs",
    )
    abstract suspend fun recoverExpiredOutboxLeases(nowEpochMs: Long): Int

    @Query(
        "UPDATE measurement_upload_outbox SET " +
            "state = 'LEASED', attempts = attempts + 1, " +
            "leaseToken = :leaseToken, leaseExpiresAtEpochMs = :leaseExpiresAtEpochMs, " +
            "sanitizedStatus = NULL, sanitizedDetail = NULL " +
            "WHERE outboxId = :outboxId AND state = 'PENDING' AND nextAttemptEpochMs <= :nowEpochMs",
    )
    abstract suspend fun acquireOutboxLease(
        outboxId: Long,
        nowEpochMs: Long,
        leaseToken: String,
        leaseExpiresAtEpochMs: Long,
    ): Int

    @Query(
        "UPDATE measurement_upload_outbox SET state = 'SENT', " +
            "leaseToken = NULL, leaseExpiresAtEpochMs = NULL, lastTransitionToken = :leaseToken, " +
            "sanitizedStatus = :status, sanitizedDetail = :detail " +
            "WHERE eventId = :eventId AND state = 'LEASED' AND leaseToken = :leaseToken",
    )
    abstract suspend fun setOutboxSent(
        eventId: String,
        leaseToken: String,
        status: String,
        detail: String?,
    ): Int

    @Query(
        "UPDATE measurement_upload_outbox SET state = 'PENDING', " +
            "nextAttemptEpochMs = :nextAttemptEpochMs, leaseToken = NULL, " +
            "leaseExpiresAtEpochMs = NULL, lastTransitionToken = :leaseToken, " +
            "sanitizedStatus = :status, sanitizedDetail = :detail " +
            "WHERE eventId = :eventId AND state = 'LEASED' AND leaseToken = :leaseToken",
    )
    abstract suspend fun setOutboxPending(
        eventId: String,
        leaseToken: String,
        nextAttemptEpochMs: Long,
        status: String,
        detail: String?,
    ): Int

    @Query(
        "UPDATE measurement_upload_outbox SET state = 'BLOCKED', " +
            "leaseToken = NULL, leaseExpiresAtEpochMs = NULL, lastTransitionToken = :leaseToken, " +
            "sanitizedStatus = :status, sanitizedDetail = :detail " +
            "WHERE eventId = :eventId AND state = 'LEASED' AND leaseToken = :leaseToken",
    )
    abstract suspend fun setOutboxBlocked(
        eventId: String,
        leaseToken: String,
        status: String,
        detail: String?,
    ): Int

    @Transaction
    open suspend fun leaseDueOutbox(
        nowEpochMs: Long,
        leaseToken: String,
        leaseExpiresAtEpochMs: Long,
        limit: Int,
    ): List<UploadOutboxEntity> {
        require(nowEpochMs > 0)
        requireLeaseToken(leaseToken)
        require(leaseExpiresAtEpochMs > nowEpochMs)
        require(limit in 1..MAX_OUTBOX_LEASE_SIZE)

        recoverExpiredOutboxLeases(nowEpochMs)
        val tokenHistory = outboxByOperationToken(leaseToken)
        val exactRetry = tokenHistory.filter { it.leaseToken == leaseToken }
        if (exactRetry.isNotEmpty()) {
            if (exactRetry.all {
                    it.state == UploadOutboxState.LEASED.wireName &&
                        it.leaseExpiresAtEpochMs == leaseExpiresAtEpochMs
                }
            ) {
                return exactRetry
            }
            conflict("Upload lease token was already used for another operation")
        }
        if (tokenHistory.isNotEmpty()) {
            conflict("Upload lease token was already finalized")
        }

        val due = dueOutbox(nowEpochMs, limit)
        due.forEach { entry ->
            if (acquireOutboxLease(
                    outboxId = entry.outboxId,
                    nowEpochMs = nowEpochMs,
                    leaseToken = leaseToken,
                    leaseExpiresAtEpochMs = leaseExpiresAtEpochMs,
                ) != 1
            ) {
                conflict("Due upload entry changed while acquiring its lease")
            }
        }
        return outboxByLeaseToken(leaseToken)
    }

    @Transaction
    open suspend fun leaseDueUploads(
        nowEpochMs: Long,
        leaseToken: String,
        leaseExpiresAtEpochMs: Long,
        limit: Int,
    ): List<LeasedUploadEntityBundle> = leaseDueOutbox(
        nowEpochMs = nowEpochMs,
        leaseToken = leaseToken,
        leaseExpiresAtEpochMs = leaseExpiresAtEpochMs,
        limit = limit,
    ).map { outbox ->
        val savedMeasurement = measurement(outbox.eventId)
            ?: conflict("Upload entry has no atomic measurement payload")
        if (!outbox.matchesMeasurementLineage(savedMeasurement)) {
            conflict("Upload entry and measurement publication lineage differ")
        }
        LeasedUploadEntityBundle(outbox, savedMeasurement)
    }

    @Transaction
    open suspend fun markOutboxSent(
        eventId: String,
        leaseToken: String,
        report: UploadDeliveryReportEntity,
    ): SensorCoreCommitDisposition {
        validateDeliveryReport(report, setOf(UploadDeliveryStatus.ACCEPTED))
        requireLeaseToken(leaseToken)
        if (setOutboxSent(eventId, leaseToken, report.status, report.detail) == 1) {
            return SensorCoreCommitDisposition.COMMITTED
        }
        val saved = outboxByEvent(eventId)
        if (saved?.hasTerminalState(
                state = UploadOutboxState.SENT,
                leaseToken = leaseToken,
                report = report,
            ) == true
        ) {
            return SensorCoreCommitDisposition.ALREADY_COMMITTED
        }
        conflict("Upload SENT transition does not match the active lease")
    }

    @Transaction
    open suspend fun rescheduleOutbox(
        eventId: String,
        leaseToken: String,
        nextAttemptEpochMs: Long,
        report: UploadDeliveryReportEntity,
    ): SensorCoreCommitDisposition {
        require(nextAttemptEpochMs > 0)
        validateDeliveryReport(
            report,
            setOf(UploadDeliveryStatus.RETRYABLE_NETWORK, UploadDeliveryStatus.RETRYABLE_SERVER),
        )
        requireLeaseToken(leaseToken)
        if (setOutboxPending(
                eventId,
                leaseToken,
                nextAttemptEpochMs,
                report.status,
                report.detail,
            ) == 1
        ) {
            return SensorCoreCommitDisposition.COMMITTED
        }
        val saved = outboxByEvent(eventId)
        if (saved?.state == UploadOutboxState.PENDING.wireName &&
            saved.leaseToken == null &&
            saved.leaseExpiresAtEpochMs == null &&
            saved.lastTransitionToken == leaseToken &&
            saved.nextAttemptEpochMs == nextAttemptEpochMs &&
            saved.sanitizedStatus == report.status &&
            saved.sanitizedDetail == report.detail
        ) {
            return SensorCoreCommitDisposition.ALREADY_COMMITTED
        }
        conflict("Upload retry transition does not match the active lease")
    }

    @Transaction
    open suspend fun blockOutbox(
        eventId: String,
        leaseToken: String,
        report: UploadDeliveryReportEntity,
    ): SensorCoreCommitDisposition {
        validateDeliveryReport(
            report,
            UploadDeliveryStatus.blockingStatuses,
        )
        requireLeaseToken(leaseToken)
        if (setOutboxBlocked(eventId, leaseToken, report.status, report.detail) == 1) {
            return SensorCoreCommitDisposition.COMMITTED
        }
        val saved = outboxByEvent(eventId)
        if (saved?.hasTerminalState(
                state = UploadOutboxState.BLOCKED,
                leaseToken = leaseToken,
                report = report,
            ) == true
        ) {
            return SensorCoreCommitDisposition.ALREADY_COMMITTED
        }
        conflict("Upload BLOCKED transition does not match the active lease")
    }

    @Query(
        "UPDATE measurement_upload_outbox SET state = 'PENDING', " +
            "nextAttemptEpochMs = :nextAttemptEpochMs, sanitizedStatus = NULL, " +
            "sanitizedDetail = NULL " +
            "WHERE eventId = :eventId AND approvalId = :approvalId " +
            "AND publicationBindingId = :publicationBindingId " +
            "AND remotePublicationBindingId = :remotePublicationBindingId " +
            "AND httpsOrigin = :httpsOrigin " +
            "AND backendBindingId = :backendBindingId AND credentialId = :credentialId " +
            "AND credentialRevision = :credentialRevision AND state = 'BLOCKED' " +
            "AND expectedPatientId = :expectedPatientId AND expectedDeviceId = :expectedDeviceId " +
            "AND sanitizedStatus = :expectedStatus AND lastTransitionToken = :expectedOperationToken " +
            "AND leaseToken IS NULL AND leaseExpiresAtEpochMs IS NULL",
    )
    abstract suspend fun setBlockedOutboxPending(
        eventId: String,
        approvalId: String,
        publicationBindingId: String,
        remotePublicationBindingId: String,
        httpsOrigin: String,
        backendBindingId: String,
        credentialId: String,
        credentialRevision: Long,
        expectedPatientId: String,
        expectedDeviceId: String,
        expectedStatus: String,
        expectedOperationToken: String,
        nextAttemptEpochMs: Long,
    ): Int

    @Transaction
    open suspend fun requeueBlockedOutbox(
        key: UploadBlockedRecoveryKey,
        nextAttemptEpochMs: Long,
    ): SensorCoreCommitDisposition {
        require(nextAttemptEpochMs > 0L)
        if (setBlockedOutboxPending(
                eventId = key.eventId,
                approvalId = key.approvalId,
                publicationBindingId = key.publicationBindingId,
                remotePublicationBindingId = key.remotePublicationBindingId,
                httpsOrigin = key.httpsOrigin,
                backendBindingId = key.backendBindingId,
                credentialId = key.credentialId,
                credentialRevision = key.credentialRevision,
                expectedPatientId = key.expectedPatientId,
                expectedDeviceId = key.expectedDeviceId,
                expectedStatus = key.expectedBlockingStatus.wireName,
                expectedOperationToken = key.expectedOperationToken,
                nextAttemptEpochMs = nextAttemptEpochMs,
            ) == 1
        ) {
            return SensorCoreCommitDisposition.COMMITTED
        }
        val saved = outboxByEvent(key.eventId)
        if (saved?.state == UploadOutboxState.PENDING.wireName &&
            saved.approvalId == key.approvalId &&
            saved.publicationBindingId == key.publicationBindingId &&
            saved.remotePublicationBindingId == key.remotePublicationBindingId &&
            saved.httpsOrigin == key.httpsOrigin &&
            saved.backendBindingId == key.backendBindingId &&
            saved.credentialId == key.credentialId &&
            saved.credentialRevision == key.credentialRevision &&
            saved.expectedPatientId == key.expectedPatientId &&
            saved.expectedDeviceId == key.expectedDeviceId &&
            saved.lastTransitionToken == key.expectedOperationToken &&
            saved.leaseToken == null &&
            saved.leaseExpiresAtEpochMs == null &&
            saved.sanitizedStatus == null &&
            saved.sanitizedDetail == null &&
            saved.nextAttemptEpochMs == nextAttemptEpochMs
        ) {
            return SensorCoreCommitDisposition.ALREADY_COMMITTED
        }
        conflict("BLOCKED recovery does not match the exact publication/credential tuple")
    }

    @Upsert
    abstract suspend fun replaceCheckpoint(value: SensorAlgorithmCheckpointEntity)

    @Query("SELECT * FROM sensor_algorithm_checkpoints WHERE sensorId = :sensorId LIMIT 1")
    abstract suspend fun checkpoint(sensorId: String): SensorAlgorithmCheckpointEntity?

    @Query(
        "SELECT * FROM sensor_algorithm_checkpoints " +
            "WHERE bluetoothAddress = :bluetoothAddress LIMIT 1",
    )
    abstract suspend fun checkpointByBluetoothAddress(
        bluetoothAddress: String,
    ): SensorAlgorithmCheckpointEntity?

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    abstract suspend fun insertFailure(value: SensorIngestionFailureEntity): Long

    @Query("SELECT * FROM sensor_ingestion_failures WHERE failureId = :failureId LIMIT 1")
    abstract suspend fun failure(failureId: String): SensorIngestionFailureEntity?

    @Transaction
    open suspend fun recordFailure(value: SensorIngestionFailureEntity): SensorCoreCommitDisposition {
        if (insertFailure(value) != INSERT_IGNORED) return SensorCoreCommitDisposition.COMMITTED
        if (failure(value.failureId)?.hasSameCauseAs(value) == true) {
            return SensorCoreCommitDisposition.ALREADY_COMMITTED
        }
        conflict("Ingestion failure ID conflicts with different contents")
    }

    @Transaction
    open suspend fun commit(value: SensorCoreEntityBundle): SensorCoreCommitDisposition {
        val raw = value.raw
        if (exactSourceIngress(
                sourceIngressId = raw.sourceIngressId,
                sensorId = raw.sensorId,
                sensorFamily = raw.sensorFamily,
                bluetoothAddress = value.checkpoint.bluetoothAddress,
                receivedAtEpochMs = raw.phoneTimeEpochMs,
                encryptedPacket = raw.packet,
                packetSha256 = raw.packetSha256,
            ) == null
        ) {
            conflict("Core commit does not match its exact durable ingress evidence")
        }
        val protocolBinding = protocolBinding(value.checkpoint.sensorId)
            ?: conflict("Protocol must be durably bound before the first core commit")
        val physicalProtocolBinding = protocolBindingByBluetoothAddress(
            value.checkpoint.bluetoothAddress,
        )
        if (physicalProtocolBinding != protocolBinding ||
            !protocolBinding.matchesCheckpoint(value.checkpoint)
        ) {
            conflict("Core checkpoint does not match the durable protocol binding")
        }
        val incomingMeasurement = value.measurement
        val publicationContext = value.publicationContext
        val approvedCheckpointContext = value.approvedCheckpointContext
        if (value.result.publishable != (incomingMeasurement != null)) {
            conflict("Algorithm publication state and measurement presence differ")
        }
        if (incomingMeasurement != null && incomingMeasurement.quality != "valid") {
            conflict("Only a VALID reading may become a product measurement")
        }
        if (incomingMeasurement != null &&
            (!value.result.alarmEligible ||
                raw.historyDistance != 0 ||
                raw.phoneTimeEpochMs - raw.sensorTimeEpochMs !in 0 until MAX_REALTIME_AGE_MS)
        ) {
            conflict("A product measurement must be a fresh alarm-eligible sample")
        }
        if (publicationContext != null && incomingMeasurement == null) {
            conflict("Only a product measurement may carry publication context")
        }
        if (publicationContext != null &&
            publicationContext.approvedCheckpointContext() != approvedCheckpointContext
        ) {
            conflict("Product publication and approved checkpoint contexts differ")
        }

        val activeLocalBinding = activeSensorBinding()
        val approval = if (approvedCheckpointContext == null) {
            if (activeLocalBinding != null) {
                conflict("Diagnostic commit is forbidden while a product sensor session is active")
            }
            if (value.checkpoint.publicationApprovalId != null ||
                value.result.publicationApprovalId != null
            ) {
                conflict("Diagnostic state cannot carry product approval lineage")
            }
            null
        } else {
            val active = activeLocalBinding
                ?: conflict("Approved commit requires an active local sensor binding")
            if (active.publicationBindingId != approvedCheckpointContext.publicationBindingId ||
                active.approvalId != approvedCheckpointContext.approvalId
            ) {
                conflict("Approved checkpoint context does not match the active local binding")
            }
            val remoteContext = publicationContext?.remoteBindingOrNull()
            val activeRemote = activeRemotePublicationBinding(active.publicationBindingId)
            if (activeRemote != null && activeRemote.approvalId != active.approvalId) {
                conflict("Active remote route belongs to another local sensor approval")
            }
            if (activeRemote?.remotePublicationBindingId !=
                remoteContext?.remotePublicationBindingId
            ) {
                conflict("Product publication context does not match the active remote route")
            }
            if (remoteContext != null) {
                val remote = publicationBinding(remoteContext.remotePublicationBindingId)
                    ?: conflict("Publication context has no durable remote binding")
                if (!remote.matchesRemoteContext(remoteContext) ||
                    remote.approvalId != active.approvalId
                ) {
                    conflict("Publication context does not match the active local binding")
                }
            }
            val physicalApproval = physicalApproval(approvedCheckpointContext.approvalId)
                ?: conflict("Approved commit requires durable physical approval")
            try {
                physicalApproval.toRecord()
            } catch (_: IllegalArgumentException) {
                conflict("Physical approval is malformed")
            } catch (_: NoSuchElementException) {
                conflict("Physical approval contains an unsupported typed value")
            }
            if (!protocolBinding.matchesApproval(physicalApproval) ||
                !value.checkpoint.hasApprovedImmutableProvenance(physicalApproval)
            ) {
                conflict("Core checkpoint does not match physical approval provenance")
            }
            if (approvedCheckpointContext.nativeBinarySetSha256 !=
                physicalApproval.nativeBinarySetSha256 ||
                approvedCheckpointContext.nativeDatahandleBinarySetSha256 !=
                physicalApproval.nativeDatahandleBinarySetSha256
            ) {
                conflict("Runtime native binary-set hashes differ from physical approval")
            }
            if (value.checkpoint.publicationApprovalId != approvedCheckpointContext.approvalId ||
                value.result.publicationApprovalId != approvedCheckpointContext.approvalId
            ) {
                conflict("Checkpoint/result lineage does not match approved context")
            }
            if (incomingMeasurement != null) {
                if (incomingMeasurement.publicationApprovalId !=
                        approvedCheckpointContext.approvalId ||
                    incomingMeasurement.publicationBindingId !=
                        approvedCheckpointContext.publicationBindingId ||
                    !incomingMeasurement.matchesOptionalPublicationContext(publicationContext)
                ) {
                    conflict("Measurement lineage does not match local/remote product context")
                }
            }
            physicalApproval
        }
        val savedCheckpoint = checkpoint(value.checkpoint.sensorId)
        val physicalCheckpoint = checkpointByBluetoothAddress(value.checkpoint.bluetoothAddress)
        if (approvedCheckpointContext == null && savedCheckpoint?.publicationApprovalId != null) {
            conflict("Approved checkpoint lineage cannot be downgraded to diagnostic")
        }
        if (approvedCheckpointContext != null) {
            val previous = savedCheckpoint
                ?: conflict("First approved commit requires its exact diagnostic anchor")
            when (previous.publicationApprovalId) {
                null -> if (!previous.matchesApprovalAnchor(checkNotNull(approval))) {
                    conflict("First approved commit does not continue the exact approved anchor")
                }
                approvedCheckpointContext.approvalId -> Unit
                else -> conflict("Approved checkpoint lineage belongs to another approval")
            }
        }
        when {
            physicalCheckpoint != null && physicalCheckpoint.sensorId != value.checkpoint.sensorId ->
                conflict("Bluetooth address is already bound to another sensor")
            savedCheckpoint == null && value.checkpoint.sequence != FIRST_SENSOR_INDEX ->
                conflict("A new checkpoint must start at sensor index $FIRST_SENSOR_INDEX")
            savedCheckpoint == null -> Unit
            savedCheckpoint.sequence > value.checkpoint.sequence -> conflict("Checkpoint regression")
            savedCheckpoint.sequence == value.checkpoint.sequence && !savedCheckpoint.sameAs(value.checkpoint) ->
                conflict("Checkpoint contents differ at the same sequence")
            savedCheckpoint.sequence < value.checkpoint.sequence &&
                !savedCheckpoint.hasSameImmutableProvenanceAs(value.checkpoint) ->
                conflict("Checkpoint provenance changed within an active sensor session")
            savedCheckpoint.sequence < value.checkpoint.sequence &&
                value.checkpoint.sequence != savedCheckpoint.sequence + 1 ->
                conflict("Checkpoint sequence gap")
            savedCheckpoint.sequence < value.checkpoint.sequence &&
                !savedCheckpoint.acceptsNextSensorTime(value.checkpoint) ->
                conflict("Checkpoint sensor time violates its transport contract")
        }

        var wroteAnything = false
        if (insertRaw(value.raw) == INSERT_IGNORED) {
            val byEvent = rawByEvent(value.raw.eventId)
            val bySequence = rawBySequence(value.raw.sensorId, value.raw.sequence)
            if (byEvent?.sameAs(value.raw) != true || bySequence?.sameAs(value.raw) != true) {
                conflict("Raw sample conflicts with an existing identity")
            }
        } else {
            wroteAnything = true
        }

        if (insertResult(value.result) == INSERT_IGNORED) {
            val byEvent = resultByEvent(value.result.eventId)
            val bySequence = resultBySequence(value.result.sensorId, value.result.sequence)
            if (byEvent != value.result || bySequence != value.result) {
                conflict("Algorithm result conflicts with an existing identity")
            }
        } else {
            wroteAnything = true
        }

        if (incomingMeasurement == null) {
            if (measurement(value.raw.eventId) != null) {
                conflict("A non-publishable result conflicts with an existing measurement")
            }
            if (outboxByEvent(value.raw.eventId) != null) {
                conflict("A diagnostic result conflicts with an existing upload entry")
            }
            if (localEffectByEvent(value.raw.eventId) != null) {
                conflict("A diagnostic result conflicts with an existing local effect")
            }
        } else if (insertMeasurement(incomingMeasurement) == INSERT_IGNORED) {
            if (measurement(incomingMeasurement.eventId)?.hasSameMedicalDataAs(incomingMeasurement) != true) {
                conflict("Measurement conflicts with an existing event")
            }
        } else {
            wroteAnything = true
        }

        if (incomingMeasurement != null) {
            approval
                ?: conflict("A publishable measurement requires active physical approval")
            val localContext = approvedCheckpointContext
                ?: conflict("A publishable measurement requires approved local context")
            val remoteContext = publicationContext?.remoteBindingOrNull()
            if (remoteContext == null) {
                if (outboxByEvent(incomingMeasurement.eventId) != null) {
                    conflict("Offline product measurement conflicts with a remote upload entry")
                }
            } else {
                val pendingOutbox = UploadOutboxEntity.pending(
                    eventId = incomingMeasurement.eventId,
                    approvalId = remoteContext.approvalId,
                    publicationBindingId = remoteContext.publicationBindingId,
                    remotePublicationBindingId = remoteContext.remotePublicationBindingId,
                    httpsOrigin = remoteContext.httpsOrigin,
                    backendBindingId = remoteContext.backendBindingId,
                    credentialId = remoteContext.credentialId,
                    credentialRevision = remoteContext.credentialRevision,
                    expectedPatientId = remoteContext.expectedPatientId,
                    expectedDeviceId = remoteContext.expectedDeviceId,
                    enqueuedAtEpochMs = incomingMeasurement.phoneTimeEpochMs,
                )
                if (insertOutbox(pendingOutbox) == INSERT_IGNORED) {
                    if (outboxByEvent(incomingMeasurement.eventId)
                            ?.hasSameImmutableIdentityAs(pendingOutbox) != true
                    ) {
                        conflict("Upload entry conflicts with the approved backend identity")
                    }
                } else {
                    wroteAnything = true
                }
            }

            val pendingLocalEffect = LocalReadingEffectEntity.pending(
                eventId = incomingMeasurement.eventId,
                approvalId = localContext.approvalId,
                publicationBindingId = localContext.publicationBindingId,
                enqueuedAtEpochMs = incomingMeasurement.phoneTimeEpochMs,
            )
            if (insertLocalEffect(pendingLocalEffect) == INSERT_IGNORED) {
                if (localEffectByEvent(incomingMeasurement.eventId)
                        ?.hasSameImmutableIdentityAs(pendingLocalEffect) != true
                ) {
                    conflict("Local effect conflicts with the approved product identity")
                }
            } else {
                wroteAnything = true
            }
        }

        if (savedCheckpoint == null || savedCheckpoint.sequence < value.checkpoint.sequence) {
            replaceCheckpoint(value.checkpoint)
            wroteAnything = true
        }

        return if (wroteAnything) {
            SensorCoreCommitDisposition.COMMITTED
        } else {
            SensorCoreCommitDisposition.ALREADY_COMMITTED
        }
    }

    private fun conflict(message: String): Nothing = throw SensorCoreConflictException(message)

    private companion object {
        const val INSERT_IGNORED = -1L
        const val FIRST_SENSOR_INDEX = 1
        const val MILLIS_PER_SAMPLE = 60_000L
        const val MAX_OUTBOX_LEASE_SIZE = 100
        const val MAX_REALTIME_AGE_MS = 330_000L
    }
}

internal enum class SensorCoreCommitDisposition {
    COMMITTED,
    ALREADY_COMMITTED,
}

internal class SensorCoreConflictException(message: String) : IllegalStateException(message)

private fun RawSensorSampleEntity.sameAs(other: RawSensorSampleEntity): Boolean =
    eventId == other.eventId &&
        sensorId == other.sensorId &&
        sensorFamily == other.sensorFamily &&
        sequence == other.sequence &&
        sensorTimeEpochMs == other.sensorTimeEpochMs &&
        phoneTimeEpochMs == other.phoneTimeEpochMs &&
        packet.contentEquals(other.packet) &&
        packetSha256 == other.packetSha256 &&
        currentRaw == other.currentRaw &&
        temperatureRaw == other.temperatureRaw &&
        historyDistance == other.historyDistance &&
        transportVariant == other.transportVariant &&
        sensorTimeWasClamped == other.sensorTimeWasClamped &&
        addTimeSeconds == other.addTimeSeconds

private fun SensorAlgorithmCheckpointEntity.acceptsNextSensorTime(
    next: SensorAlgorithmCheckpointEntity,
): Boolean = if (transportProtocol == "GS1_V115") {
    next.sensorTimeEpochMs >= sensorTimeEpochMs
} else {
    next.sensorTimeEpochMs == sensorTimeEpochMs + 60_000L
}

private fun SensorAlgorithmCheckpointEntity.sameAs(other: SensorAlgorithmCheckpointEntity): Boolean =
    sensorId == other.sensorId &&
        bluetoothAddress == other.bluetoothAddress &&
        sensorFamily == other.sensorFamily &&
        transportVariant == other.transportVariant &&
        transportProtocol == other.transportProtocol &&
        transportCodecId == other.transportCodecId &&
        sequence == other.sequence &&
        sensorTimeEpochMs == other.sensorTimeEpochMs &&
        sensorStartTimeEpochMs == other.sensorStartTimeEpochMs &&
        algorithmProfile == other.algorithmProfile &&
        algorithmVersion == other.algorithmVersion &&
        binarySetId == other.binarySetId &&
        sensitivityToken == other.sensitivityToken &&
        sensitivityTokenSource == other.sensitivityTokenSource &&
        sensitivityCoefficient == other.sensitivityCoefficient &&
        sensitivityEncoding == other.sensitivityEncoding &&
        initializationMode == other.initializationMode &&
        state.contentEquals(other.state) &&
        stateSha256 == other.stateSha256 &&
        displayOffsetMmolL == other.displayOffsetMmolL &&
        schemaVersion == other.schemaVersion &&
        publicationApprovalId == other.publicationApprovalId

private fun SensorAlgorithmCheckpointEntity.hasSameImmutableProvenanceAs(
    other: SensorAlgorithmCheckpointEntity,
): Boolean =
    sensorId == other.sensorId &&
        bluetoothAddress == other.bluetoothAddress &&
        sensorFamily == other.sensorFamily &&
        transportVariant == other.transportVariant &&
        transportProtocol == other.transportProtocol &&
        transportCodecId == other.transportCodecId &&
        sensorStartTimeEpochMs == other.sensorStartTimeEpochMs &&
        algorithmProfile == other.algorithmProfile &&
        algorithmVersion == other.algorithmVersion &&
        binarySetId == other.binarySetId &&
        sensitivityToken == other.sensitivityToken &&
        sensitivityTokenSource == other.sensitivityTokenSource &&
        sensitivityCoefficient == other.sensitivityCoefficient &&
        sensitivityEncoding == other.sensitivityEncoding &&
        initializationMode == other.initializationMode &&
        schemaVersion == other.schemaVersion

private fun SensorProtocolBindingEntity.matchesCheckpoint(
    checkpoint: SensorAlgorithmCheckpointEntity,
): Boolean = sensorId == checkpoint.sensorId &&
    bluetoothAddress == checkpoint.bluetoothAddress &&
    sensorFamily == checkpoint.sensorFamily &&
    transportVariant == checkpoint.transportVariant &&
    sensitivityToken == checkpoint.sensitivityToken &&
    transportProtocol == checkpoint.transportProtocol &&
    transportCodecId == checkpoint.transportCodecId &&
    algorithmProfile == checkpoint.algorithmProfile &&
    sensitivityEncoding == checkpoint.sensitivityEncoding &&
    schemaVersion == checkpoint.schemaVersion

private fun SensorProtocolBindingEntity.matchesApproval(
    approval: PhysicalSensorApprovalEntity,
): Boolean = sensorId == approval.sensorId &&
    bluetoothAddress == approval.bluetoothAddress &&
    sensorFamily == approval.sensorFamily &&
    transportVariant == approval.transportVariant &&
    sensitivityToken == approval.sensitivityToken &&
    wireProfile == approval.wireProfile &&
    transportProtocol == approval.transportProtocol &&
    transportCodecId == approval.transportCodecId &&
    algorithmProfile == approval.algorithmProfile &&
    sensitivityEncoding == approval.sensitivityEncoding &&
    evidenceKind == approval.protocolEvidenceKind &&
    evidenceSha256 == approval.protocolEvidenceSha256 &&
    schemaVersion == approval.schemaVersion

private fun SensorAlgorithmCheckpointEntity.hasApprovedImmutableProvenance(
    approval: PhysicalSensorApprovalEntity,
): Boolean = sensorId == approval.sensorId &&
    bluetoothAddress == approval.bluetoothAddress &&
    sensorFamily == approval.sensorFamily &&
    transportVariant == approval.transportVariant &&
    transportProtocol == approval.transportProtocol &&
    transportCodecId == approval.transportCodecId &&
    algorithmProfile == approval.algorithmProfile &&
    algorithmVersion == approval.algorithmVersion &&
    binarySetId == approval.binarySetId &&
    sensitivityToken == approval.sensitivityToken &&
    sensitivityTokenSource == approval.sensitivityTokenSource &&
    sensitivityCoefficient == approval.sensitivityCoefficient &&
    sensitivityEncoding == approval.sensitivityEncoding &&
    initializationMode == approval.initializationMode &&
    sensorStartTimeEpochMs == approval.sensorStartTimeEpochMs &&
    schemaVersion == approval.checkpointSchemaVersion

private fun SensorAlgorithmCheckpointEntity.matchesApprovalAnchor(
    approval: PhysicalSensorApprovalEntity,
): Boolean = hasApprovedImmutableProvenance(approval) &&
    sequence == approval.approvedSequence &&
    sensorTimeEpochMs == approval.approvedSensorTimeEpochMs &&
    stateSha256 == approval.approvedCheckpointStateSha256 &&
    displayOffsetMmolL == approval.displayOffsetMmolL &&
    publicationApprovalId == null

private fun ProductPublicationBindingEntity.matchesRemoteContext(
    context: ProductRemoteBindingContext,
): Boolean = remotePublicationBindingId == context.remotePublicationBindingId &&
    publicationBindingId == context.publicationBindingId &&
    approvalId == context.approvalId &&
    httpsOrigin == context.httpsOrigin &&
    backendBindingId == context.backendBindingId &&
    credentialId == context.credentialId &&
    credentialRevision == context.credentialRevision &&
    expectedPatientId == context.expectedPatientId &&
    expectedDeviceId == context.expectedDeviceId

private fun ProductPublicationBindingEntity.matchesApprovedCheckpointContext(
    context: ApprovedCheckpointContext,
): Boolean = publicationBindingId == context.publicationBindingId &&
    approvalId == context.approvalId

private fun MeasurementEntity.matchesOptionalPublicationContext(
    context: ProductPublicationContext?,
): Boolean = if (context == null) {
    remotePublicationBindingId == null && httpsOrigin == null &&
        backendBindingId == null && credentialId == null &&
        credentialRevision == null && expectedPatientId == null && expectedDeviceId == null
} else {
    publicationApprovalId == context.approvalId &&
        publicationBindingId == context.publicationBindingId &&
        remotePublicationBindingId == context.remotePublicationBindingId &&
        httpsOrigin == context.httpsOrigin && backendBindingId == context.backendBindingId &&
        credentialId == context.credentialId && credentialRevision == context.credentialRevision &&
        expectedPatientId == context.expectedPatientId && expectedDeviceId == context.expectedDeviceId
}

private fun UploadOutboxEntity.hasSameImmutableIdentityAs(other: UploadOutboxEntity): Boolean =
    eventId == other.eventId &&
        approvalId == other.approvalId &&
        publicationBindingId == other.publicationBindingId &&
        remotePublicationBindingId == other.remotePublicationBindingId &&
        httpsOrigin == other.httpsOrigin &&
        backendBindingId == other.backendBindingId &&
        credentialId == other.credentialId &&
        credentialRevision == other.credentialRevision &&
        expectedPatientId == other.expectedPatientId &&
        expectedDeviceId == other.expectedDeviceId &&
        enqueuedAtEpochMs == other.enqueuedAtEpochMs

private fun UploadOutboxEntity.matchesMeasurementLineage(
    measurement: MeasurementEntity,
): Boolean = eventId == measurement.eventId &&
    approvalId == measurement.publicationApprovalId &&
    publicationBindingId == measurement.publicationBindingId &&
    remotePublicationBindingId == measurement.remotePublicationBindingId &&
    httpsOrigin == measurement.httpsOrigin &&
    backendBindingId == measurement.backendBindingId &&
    credentialId == measurement.credentialId &&
    credentialRevision == measurement.credentialRevision &&
    expectedPatientId == measurement.expectedPatientId &&
    expectedDeviceId == measurement.expectedDeviceId

private fun UploadOutboxEntity.hasTerminalState(
    state: UploadOutboxState,
    leaseToken: String,
    report: UploadDeliveryReportEntity,
): Boolean = this.state == state.wireName &&
    this.leaseToken == null &&
    leaseExpiresAtEpochMs == null &&
    lastTransitionToken == leaseToken &&
    sanitizedStatus == report.status &&
    sanitizedDetail == report.detail

private fun validateDeliveryReport(
    report: UploadDeliveryReportEntity,
    allowed: Set<UploadDeliveryStatus>,
) {
    val status = UploadDeliveryStatus.entries.firstOrNull { it.wireName == report.status }
        ?: throw IllegalArgumentException("Unsupported sanitized upload status")
    require(status in allowed)
    UploadDeliveryReport(status, report.detail)
}

private fun MeasurementEntity.hasSameMedicalDataAs(other: MeasurementEntity): Boolean =
    eventId == other.eventId &&
        sensorId == other.sensorId &&
        sensorFamily == other.sensorFamily &&
        sensorTimeEpochMs == other.sensorTimeEpochMs &&
        phoneTimeEpochMs == other.phoneTimeEpochMs &&
        glucoseMgDl == other.glucoseMgDl &&
        trendMgDlPerMinute == other.trendMgDlPerMinute &&
        quality == other.quality &&
        sequence == other.sequence &&
        publicationApprovalId == other.publicationApprovalId &&
        publicationBindingId == other.publicationBindingId &&
        remotePublicationBindingId == other.remotePublicationBindingId &&
        httpsOrigin == other.httpsOrigin &&
        backendBindingId == other.backendBindingId &&
        credentialId == other.credentialId &&
        credentialRevision == other.credentialRevision &&
        expectedPatientId == other.expectedPatientId &&
        expectedDeviceId == other.expectedDeviceId

private fun SensorIngestionFailureEntity.hasSameCauseAs(other: SensorIngestionFailureEntity): Boolean =
    failureId == other.failureId &&
        sensorId == other.sensorId &&
        sensorFamily == other.sensorFamily &&
        sequence == other.sequence &&
        reportedSensorTimeEpochSeconds == other.reportedSensorTimeEpochSeconds &&
        packet.contentEquals(other.packet) &&
        packetSha256 == other.packetSha256 &&
        currentRaw == other.currentRaw &&
        temperatureRaw == other.temperatureRaw &&
        historyDistance == other.historyDistance &&
        transportVariant == other.transportVariant &&
        failureCode == other.failureCode &&
        nativeStateMayHaveChanged == other.nativeStateMayHaveChanged

private val SHA256_HEX = Regex("^[0-9a-f]{64}$")
