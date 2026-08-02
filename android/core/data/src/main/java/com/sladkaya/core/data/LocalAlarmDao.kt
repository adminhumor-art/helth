package com.sladkaya.core.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update

internal sealed interface LocalAlarmApplyDecision {
    data class Applied(val settlement: LocalAlarmSettlement) : LocalAlarmApplyDecision
    data class AlreadyApplied(val settlement: LocalAlarmSettlement) : LocalAlarmApplyDecision
}

internal sealed interface LocalAlarmSettlementReadDecision {
    data class Exact(val settlement: LocalAlarmSettlement) : LocalAlarmSettlementReadDecision
    data object Missing : LocalAlarmSettlementReadDecision
    data class Mismatch(val reason: String) : LocalAlarmSettlementReadDecision
}

internal sealed interface LocalAlarmEpisodeAcknowledgeDecision {
    data class Applied(val state: LocalAlarmStateRecord) : LocalAlarmEpisodeAcknowledgeDecision
    data class AlreadyApplied(
        val state: LocalAlarmStateRecord,
    ) : LocalAlarmEpisodeAcknowledgeDecision
    data class Stale(
        val currentEpisodeGeneration: Long,
        val currentStateSha256: String,
    ) : LocalAlarmEpisodeAcknowledgeDecision
}

internal sealed interface LocalAlarmWatchdogDecision {
    data class Applied(val settlement: LocalAlarmWatchdogSettlement) : LocalAlarmWatchdogDecision
    data class AlreadyApplied(
        val settlement: LocalAlarmWatchdogSettlement,
    ) : LocalAlarmWatchdogDecision
    data class Obsolete(val currentStateSha256: String) : LocalAlarmWatchdogDecision
}

internal sealed interface LocalAlarmMonitoringStartDecision {
    data class Initialized(val settlement: LocalAlarmMonitoringStartSettlement) :
        LocalAlarmMonitoringStartDecision

    data class AlreadyInitialized(val settlement: LocalAlarmMonitoringStartSettlement) :
        LocalAlarmMonitoringStartDecision
}

internal sealed interface LocalAlarmSettingsApplyDecision {
    data class Applied(val settlement: LocalAlarmSettingsSettlement) :
        LocalAlarmSettingsApplyDecision

    data class AlreadyApplied(val settlement: LocalAlarmSettingsSettlement) :
        LocalAlarmSettingsApplyDecision

    data class Obsolete(val currentStateSha256: String) : LocalAlarmSettingsApplyDecision
}

@Dao
internal abstract class LocalAlarmDao {
    @Query(
        "SELECT * FROM local_alarm_monitoring_starts " +
            "WHERE startId = :startId LIMIT 1",
    )
    abstract suspend fun monitoringStart(startId: String): LocalAlarmMonitoringStartEntity?

    @Query(
        "SELECT * FROM local_alarm_monitoring_starts " +
            "WHERE publicationBindingId = :publicationBindingId LIMIT 1",
    )
    abstract suspend fun monitoringStartForBinding(
        publicationBindingId: String,
    ): LocalAlarmMonitoringStartEntity?

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    abstract suspend fun insertMonitoringStart(value: LocalAlarmMonitoringStartEntity): Long

    @Query("SELECT * FROM local_reading_effects WHERE effectId = :effectId LIMIT 1")
    abstract suspend fun localEffect(effectId: Long): LocalReadingEffectEntity?

    @Query("SELECT * FROM local_reading_effects WHERE eventId = :eventId LIMIT 1")
    abstract suspend fun localEffectByEvent(eventId: String): LocalReadingEffectEntity?

    @Query(
        "SELECT * FROM local_reading_effects " +
            "WHERE state != 'ACKNOWLEDGED' ORDER BY effectId ASC LIMIT 1",
    )
    abstract suspend fun earliestUnacknowledgedEffect(): LocalReadingEffectEntity?

    @Query("SELECT * FROM measurements WHERE eventId = :eventId LIMIT 1")
    abstract suspend fun measurement(eventId: String): MeasurementEntity?

    @Query("SELECT * FROM sensor_raw_samples WHERE eventId = :eventId LIMIT 1")
    abstract suspend fun raw(eventId: String): RawSensorSampleEntity?

    @Query("SELECT * FROM sensor_algorithm_results WHERE eventId = :eventId LIMIT 1")
    abstract suspend fun algorithmResult(eventId: String): SensorAlgorithmResultEntity?

    @Query(
        "SELECT * FROM active_sensor_publication_binding " +
            "WHERE activeSlot = $ACTIVE_PUBLICATION_BINDING_SLOT LIMIT 1",
    )
    abstract suspend fun activeSensorBinding(): ActiveSensorPublicationBindingEntity?

    @Query("SELECT * FROM physical_sensor_approvals WHERE approvalId = :approvalId LIMIT 1")
    abstract suspend fun physicalApproval(approvalId: String): PhysicalSensorApprovalEntity?

    @Query(
        "SELECT COUNT(*) FROM measurements " +
            "WHERE publicationBindingId = :publicationBindingId",
    )
    abstract suspend fun productMeasurementCount(publicationBindingId: String): Int

    @Query(
        "SELECT * FROM local_alarm_states WHERE publicationBindingId = :publicationBindingId LIMIT 1",
    )
    abstract suspend fun alarmState(publicationBindingId: String): LocalAlarmStateEntity?

    @Transaction
    open suspend fun verifiedAlarmState(
        publicationBindingId: String,
    ): LocalAlarmStateRecord? {
        require(SHA256.matches(publicationBindingId))
        val state = alarmState(publicationBindingId)?.validatedState() ?: return null
        requireStateSource(state)
        return state
    }

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    abstract suspend fun insertAlarmState(value: LocalAlarmStateEntity): Long

    @Update
    abstract suspend fun updateAlarmState(value: LocalAlarmStateEntity): Int

    @Query("SELECT * FROM local_alarm_applications WHERE effectId = :effectId LIMIT 1")
    abstract suspend fun alarmApplication(effectId: Long): LocalAlarmApplicationEntity?

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    abstract suspend fun insertAlarmApplication(value: LocalAlarmApplicationEntity): Long

    @Query(
        "SELECT * FROM local_alarm_watchdog_applications " +
            "WHERE watchdogId = :watchdogId LIMIT 1",
    )
    abstract suspend fun watchdogApplication(
        watchdogId: String,
    ): LocalAlarmWatchdogApplicationEntity?

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    abstract suspend fun insertWatchdogApplication(
        value: LocalAlarmWatchdogApplicationEntity,
    ): Long

    @Query(
        "SELECT * FROM local_alarm_deliveries " +
            "WHERE sourceEffectId = :effectId ORDER BY kindOrder ASC",
    )
    abstract suspend fun deliveriesForEffect(effectId: Long): List<LocalAlarmDeliveryEntity>

    @Query("SELECT * FROM local_alarm_deliveries WHERE deliveryId = :deliveryId LIMIT 1")
    abstract suspend fun alarmDelivery(deliveryId: String): LocalAlarmDeliveryEntity?

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    abstract suspend fun insertAlarmDelivery(value: LocalAlarmDeliveryEntity): Long

    @Query(
        "UPDATE local_alarm_deliveries SET state = 'CANCELLED', " +
            "leaseToken = NULL, leaseExpiresAtEpochMs = NULL, " +
            "lastTransitionToken = :operationToken, lastTransitionKind = 'CANCELLED', " +
            "lastTransitionAtEpochMs = :cancelledAtEpochMs, deliveredAtEpochMs = NULL " +
            "WHERE publicationBindingId = :publicationBindingId " +
            "AND kind IN ('REPEAT', 'WATCHDOG') " +
            "AND deliveryId NOT IN (:protectedStartWatchdogId, :replacementRepeatId, " +
            ":replacementWatchdogId) AND state IN ('PENDING', 'LEASED')",
    )
    abstract suspend fun cancelSupersededAlarmSchedules(
        publicationBindingId: String,
        protectedStartWatchdogId: String,
        replacementRepeatId: String,
        replacementWatchdogId: String,
        operationToken: String,
        cancelledAtEpochMs: Long,
    ): Int

    @Transaction
    open suspend fun initializeMonitoring(
        request: LocalAlarmMonitoringStartRequest,
    ): LocalAlarmMonitoringStartDecision {
        val active = activeSensorBinding()
            ?: conflict("Monitoring start requires an active local sensor binding")
        if (active.publicationBindingId != request.publicationBindingId ||
            active.approvalId != request.approvalId
        ) {
            conflict("Monitoring start differs from the active local sensor binding")
        }
        val approval = physicalApproval(request.approvalId)
            ?: conflict("Monitoring start has no durable physical approval")
        val approvalRecord = try {
            approval.toRecord()
        } catch (_: IllegalArgumentException) {
            conflict("Monitoring start physical approval is malformed")
        }
        if (approvalRecord.approvalId != request.approvalId ||
            approvalRecord.approvedSequence.toLong() != request.approvedSequence
        ) {
            conflict("Monitoring start differs from the approved sensor lineage")
        }

        val existingState = alarmState(request.publicationBindingId)
        val existingStart = monitoringStartForBinding(request.publicationBindingId)
        if (existingState != null || existingStart != null) {
            if (existingState == null || existingStart == null) {
                conflict("Monitoring start durable state is incomplete")
            }
            val start = existingStart.validatedMonitoringStart()
            val state = existingState.validatedState()
            LocalAlarmMonitoringStartRetryPolicy.restore(start, state, request)
                ?: conflict("Monitoring start retry differs from its durable lineage")
            requireStateSource(state)
            return LocalAlarmMonitoringStartDecision.AlreadyInitialized(
                monitoringStartSettlement(start, state),
            )
        }
        if (request.monitoringStartedAtEpochMs < approvalRecord.approvedAtEpochMs) {
            conflict("Monitoring start predates the approved sensor lineage")
        }
        if (productMeasurementCount(request.publicationBindingId) != 0) {
            conflict("Monitoring start cannot replace missing alarm state for existing history")
        }

        val reduction = LocalAlarmMonitoringStartReducer.reduce(request)
        if (insertMonitoringStart(reduction.start.toEntity()) == INSERT_IGNORED) {
            conflict("Monitoring start identity already exists")
        }
        if (insertAlarmState(reduction.state.toEntity()) == INSERT_IGNORED) {
            conflict("Local alarm state appeared while initializing monitoring")
        }
        val watchdog = reduction.deliveries.single()
        if (insertAlarmDelivery(watchdog.toEntity()) == INSERT_IGNORED) {
            conflict("Monitoring start watchdog identity already exists")
        }
        val storedStart = monitoringStart(reduction.start.startId)
            ?.validatedMonitoringStart()
            ?: conflict("Monitoring start proof was not persisted")
        val storedState = alarmState(request.publicationBindingId)?.validatedState()
            ?: conflict("Monitoring start alarm state was not persisted")
        val storedWatchdog = alarmDelivery(reduction.start.watchdogDeliveryId)
            ?: conflict("Monitoring start watchdog was not persisted")
        if (storedStart != reduction.start || storedState != reduction.state ||
            !storedWatchdog.matchesMonitoringStart(storedStart)
        ) {
            conflict("Monitoring start proof differs from the atomic request")
        }
        return LocalAlarmMonitoringStartDecision.Initialized(
            monitoringStartSettlement(storedStart, storedState),
        )
    }

    @Query(
        "UPDATE local_alarm_deliveries SET state = 'CANCELLED', " +
            "leaseToken = NULL, leaseExpiresAtEpochMs = NULL, " +
            "lastTransitionToken = :operationToken, lastTransitionKind = 'CANCELLED', " +
            "lastTransitionAtEpochMs = :cancelledAtEpochMs, deliveredAtEpochMs = NULL " +
            "WHERE publicationBindingId = :publicationBindingId " +
            "AND episodeGeneration = :episodeGeneration AND kind = 'REPEAT' " +
            "AND state IN ('PENDING', 'LEASED')",
    )
    abstract suspend fun cancelEpisodeRepeats(
        publicationBindingId: String,
        episodeGeneration: Long,
        operationToken: String,
        cancelledAtEpochMs: Long,
    ): Int

    @Query(
        "SELECT COUNT(*) FROM local_alarm_deliveries " +
            "WHERE publicationBindingId = :publicationBindingId " +
            "AND episodeGeneration = :episodeGeneration AND kind = 'REPEAT' " +
            "AND state IN ('PENDING', 'LEASED')",
    )
    abstract suspend fun nonTerminalEpisodeRepeatCount(
        publicationBindingId: String,
        episodeGeneration: Long,
    ): Int

    @Query(
        "UPDATE local_reading_effects SET state = 'ACKNOWLEDGED', " +
            "leaseToken = NULL, leaseExpiresAtEpochMs = NULL, " +
            "lastTransitionToken = :leaseToken, acknowledgedAtEpochMs = :acknowledgedAtEpochMs " +
            "WHERE effectId = :effectId AND eventId = :eventId " +
            "AND state = 'LEASED' AND leaseToken = :leaseToken",
    )
    abstract suspend fun acknowledgeEffect(
        effectId: Long,
        eventId: String,
        leaseToken: String,
        acknowledgedAtEpochMs: Long,
    ): Int

    @Transaction
    open suspend fun applyLeased(request: LocalAlarmApplyRequest): LocalAlarmApplyDecision {
        val effect = localEffect(request.effectId)
            ?: conflict("Local alarm apply effect does not exist")
        if (effect.eventId != request.eventId) {
            conflict("Local alarm apply event identity differs from the effect")
        }
        if (effect.state == LocalReadingEffectState.ACKNOWLEDGED.wireName) {
            val application = alarmApplication(effect.effectId)
                ?: conflict("Acknowledged local effect has no alarm application proof")
            if (!application.matchesRequest(request)) {
                conflict("Local alarm apply retry differs from the durable application")
            }
            return when (val proof = settlementFor(effect)) {
                is LocalAlarmSettlementReadDecision.Exact ->
                    LocalAlarmApplyDecision.AlreadyApplied(proof.settlement)
                LocalAlarmSettlementReadDecision.Missing ->
                    conflict("Acknowledged local effect settlement is missing")
                is LocalAlarmSettlementReadDecision.Mismatch -> conflict(proof.reason)
            }
        }
        if (effect.state != LocalReadingEffectState.LEASED.wireName ||
            effect.leaseToken != request.leaseToken
        ) {
            conflict("Local alarm apply requires the exact active effect lease")
        }
        if (earliestUnacknowledgedEffect()?.effectId != effect.effectId) {
            conflict("Only the earliest local reading effect may update alarm state")
        }
        val leased = validateLocalReadingEffect(
            effect = effect,
            measurement = measurement(effect.eventId)
                ?: conflict("Local alarm effect has no measurement"),
            raw = raw(effect.eventId)
                ?: conflict("Local alarm effect has no raw provenance"),
            result = algorithmResult(effect.eventId)
                ?: conflict("Local alarm effect has no algorithm provenance"),
            activeBinding = activeSensorBinding()
                ?: conflict("Local alarm effect has no active local sensor binding"),
            approval = physicalApproval(effect.approvalId)
                ?: conflict("Local alarm effect has no durable physical approval"),
        )
        val previousEntity = alarmState(effect.publicationBindingId)
        val previous = previousEntity?.let {
            try {
                it.toRecord()
            } catch (_: IllegalArgumentException) {
                conflict("Stored local alarm state is malformed")
            } catch (_: NoSuchElementException) {
                conflict("Stored local alarm state contains an unsupported alarm kind")
            }
        }
        previous?.let { requireStateSource(it) }
        val reduction = try {
            LocalAlarmReducer.reduce(previous, leased, request)
        } catch (_: IllegalArgumentException) {
            conflict("Local alarm apply request conflicts with the durable reducer state")
        } catch (_: ArithmeticException) {
            conflict("Local alarm episode generation overflow")
        }
        val stateEntity = reduction.state.toEntity()
        if (previousEntity == null) {
            if (insertAlarmState(stateEntity) == INSERT_IGNORED) {
                conflict("Local alarm state appeared while applying the earliest effect")
            }
        } else if (updateAlarmState(stateEntity) != 1) {
            conflict("Local alarm state changed while applying the earliest effect")
        }

        val deliveries = reduction.deliveries.map(LocalAlarmDeliveryDraft::toEntity)
        val deliveryIds = deliveries.map(LocalAlarmDeliveryEntity::deliveryId)
        val application = LocalAlarmApplicationEntity(
            effectId = effect.effectId,
            eventId = effect.eventId,
            approvalId = effect.approvalId,
            publicationBindingId = effect.publicationBindingId,
            previousEffectId = previous?.lastEffectId,
            leaseToken = request.leaseToken,
            appliedAtEpochMs = request.processedAtEpochMs,
            monitoringStartedAtEpochMs = request.monitoringStartedAtEpochMs,
            thresholdFingerprint = request.thresholds.fingerprint,
            expectedPreviousThresholdFingerprint =
                request.expectedPreviousThresholdFingerprint,
            repeatIntervalMs = request.repeatIntervalMs,
            resultingStateSha256 = reduction.state.stateSha256,
            activeKinds = reduction.state.policyState.active.toAlarmKindsWire(),
            episodeGeneration = reduction.state.episodeGeneration,
            episodeAcknowledged = reduction.state.episodeAcknowledged,
            deliverySetSha256 = deliveryIds.sorted().canonicalSha256(),
            deliveryCount = deliveryIds.size,
        )
        if (insertAlarmApplication(application) == INSERT_IGNORED) {
            conflict("Local alarm application already exists for an unacknowledged effect")
        }
        deliveries.forEach { delivery ->
            if (insertAlarmDelivery(delivery) == INSERT_IGNORED) {
                conflict("Local alarm delivery identity already exists")
            }
        }
        if (acknowledgeEffect(
                effectId = effect.effectId,
                eventId = effect.eventId,
                leaseToken = request.leaseToken,
                acknowledgedAtEpochMs = request.processedAtEpochMs,
            ) != 1
        ) {
            conflict("Local effect lease changed before atomic alarm acknowledgment")
        }
        val acknowledged = localEffect(effect.effectId)
            ?: conflict("Acknowledged local effect disappeared")
        return when (val proof = settlementFor(acknowledged)) {
            is LocalAlarmSettlementReadDecision.Exact ->
                LocalAlarmApplyDecision.Applied(proof.settlement)
            LocalAlarmSettlementReadDecision.Missing ->
                conflict("Atomic local alarm settlement was not persisted")
            is LocalAlarmSettlementReadDecision.Mismatch -> conflict(proof.reason)
        }
    }

    @Transaction
    open suspend fun applySettings(
        request: LocalAlarmSettingsApplyRequest,
    ): LocalAlarmSettingsApplyDecision {
        settingsSettlementFor(request)?.let {
            return LocalAlarmSettingsApplyDecision.AlreadyApplied(it)
        }
        val previous = alarmState(request.publicationBindingId)?.validatedState()
            ?: conflict("Local alarm settings state does not exist")
        requireStateSource(previous)
        if (previous.stateSha256 != request.expectedStateSha256) {
            return LocalAlarmSettingsApplyDecision.Obsolete(previous.stateSha256)
        }
        val latestVerifiedReading = if (previous.lastEffectId == MONITORING_START_EFFECT_ID) {
            null
        } else {
            validateExactLocalProductMeasurement(
                measurement = measurement(previous.lastEventId)
                    ?: conflict("Local alarm settings source measurement is missing"),
                raw = raw(previous.lastEventId)
                    ?: conflict("Local alarm settings source raw sample is missing"),
                result = algorithmResult(previous.lastEventId)
                    ?: conflict("Local alarm settings source algorithm result is missing"),
                activeBinding = activeSensorBinding()
                    ?: conflict("Local alarm settings have no active local binding"),
                approval = physicalApproval(previous.approvalId)
                    ?: conflict("Local alarm settings have no physical approval"),
                expectedEventId = previous.lastEventId,
                expectedApprovalId = previous.approvalId,
                expectedPublicationBindingId = previous.publicationBindingId,
            )
        }
        val reduction = try {
            LocalAlarmSettingsReducer.reduce(previous, latestVerifiedReading, request)
        } catch (_: IllegalArgumentException) {
            conflict("Local alarm settings request conflicts with the durable state")
        } catch (_: ArithmeticException) {
            conflict("Local alarm settings episode generation overflow")
        }
        if (!reduction.stateChanged) {
            return LocalAlarmSettingsApplyDecision.AlreadyApplied(
                LocalAlarmSettingsSettlement(
                    operationId = request.operationId,
                    publicationBindingId = previous.publicationBindingId,
                    approvalId = previous.approvalId,
                    sourceEffectId = previous.lastEffectId,
                    sourceEventId = previous.lastEventId,
                    expectedStateSha256 = request.expectedStateSha256,
                    resultingStateSha256 = previous.stateSha256,
                    thresholdFingerprint = request.thresholds.fingerprint,
                    activeKinds = previous.policyState.active,
                    episodeGeneration = previous.episodeGeneration,
                    episodeAcknowledged = previous.episodeAcknowledged,
                    appliedAtEpochMs = request.appliedAtEpochMs,
                    stateChanged = false,
                    deliveryIds = emptyList(),
                ),
            )
        }
        if (updateAlarmState(reduction.state.toEntity()) != 1) {
            conflict("Local alarm state changed while applying settings")
        }
        val protectedStartWatchdogId = monitoringStartForBinding(request.publicationBindingId)
            ?.validatedMonitoringStart()
            ?.watchdogDeliveryId
            ?: conflict("Local alarm settings have no monitoring start proof")
        cancelSupersededAlarmSchedules(
            publicationBindingId = request.publicationBindingId,
            protectedStartWatchdogId = protectedStartWatchdogId,
            replacementRepeatId = deterministicLocalAlarmSettingsDeliveryId(
                request.operationId,
                LocalAlarmDeliveryKind.REPEAT,
            ),
            replacementWatchdogId = deterministicLocalAlarmSettingsDeliveryId(
                request.operationId,
                LocalAlarmDeliveryKind.WATCHDOG,
            ),
            operationToken = request.operationId,
            cancelledAtEpochMs = request.appliedAtEpochMs,
        )
        reduction.deliveries.forEach { delivery ->
            if (insertAlarmDelivery(delivery.toEntity()) == INSERT_IGNORED) {
                conflict("Local alarm settings delivery identity already exists")
            }
        }
        val restored = alarmState(request.publicationBindingId)?.validatedState()
            ?: conflict("Local alarm settings state disappeared")
        if (restored != reduction.state) {
            conflict("Local alarm settings state differs from its atomic reduction")
        }
        val settlement = settingsSettlementFor(request)
            ?: conflict("Local alarm settings delivery proof is missing")
        if (settlement.resultingStateSha256 != restored.stateSha256) {
            conflict("Local alarm settings delivery proof differs from its state")
        }
        return LocalAlarmSettingsApplyDecision.Applied(settlement)
    }

    @Transaction
    open suspend fun readSettlement(
        eventId: String,
        approvalId: String,
        publicationBindingId: String,
    ): LocalAlarmSettlementReadDecision {
        require(eventId.isNotBlank())
        require(SHA256.matches(approvalId))
        require(SHA256.matches(publicationBindingId))
        val effect = localEffectByEvent(eventId)
            ?: return LocalAlarmSettlementReadDecision.Missing
        if (effect.approvalId != approvalId ||
            effect.publicationBindingId != publicationBindingId
        ) {
            return LocalAlarmSettlementReadDecision.Mismatch(
                "Local alarm settlement publication identity differs",
            )
        }
        return settlementFor(effect)
    }

    @Transaction
    open suspend fun acknowledgeEpisode(
        publicationBindingId: String,
        expectedEpisodeGeneration: Long,
        acknowledgedAtEpochMs: Long,
    ): LocalAlarmEpisodeAcknowledgeDecision {
        require(SHA256.matches(publicationBindingId))
        require(expectedEpisodeGeneration > 0L)
        require(acknowledgedAtEpochMs > 0L)
        val saved = alarmState(publicationBindingId)?.validatedState()
            ?: conflict("Local alarm episode does not exist")
        requireStateSource(saved)
        val acknowledgementId = deterministicLocalAlarmEpisodeAcknowledgementId(
            publicationBindingId,
            expectedEpisodeGeneration,
            acknowledgedAtEpochMs,
        )
        val acknowledgementDeliveryId =
            deterministicLocalAlarmEpisodeAcknowledgementDeliveryId(acknowledgementId)
        if (saved.episodeGeneration != expectedEpisodeGeneration ||
            saved.policyState.active.isEmpty()
        ) {
            return LocalAlarmEpisodeAcknowledgeDecision.Stale(
                currentEpisodeGeneration = saved.episodeGeneration,
                currentStateSha256 = saved.stateSha256,
            )
        }
        if (saved.episodeAcknowledged) {
            if (saved.episodeAcknowledgedAtEpochMs != acknowledgedAtEpochMs) {
                conflict("Local alarm episode acknowledgement differs from the durable operation")
            }
            if (nonTerminalEpisodeRepeatCount(
                    publicationBindingId,
                    expectedEpisodeGeneration,
                ) != 0
            ) {
                conflict("Acknowledged local alarm episode still has an active repeat")
            }
            val update = alarmDelivery(acknowledgementDeliveryId)
                ?: conflict("Acknowledged local alarm episode has no update delivery proof")
            if (!update.matchesEpisodeAcknowledgement(saved, acknowledgementDeliveryId)) {
                conflict("Local alarm episode update delivery proof is inconsistent")
            }
            requireDeliverySource(update)
            return LocalAlarmEpisodeAcknowledgeDecision.AlreadyApplied(saved)
        }
        val openedAt = saved.episodeOpenedAtEpochMs
            ?: conflict("Active local alarm episode has no opening time")
        if (acknowledgedAtEpochMs < openedAt || acknowledgedAtEpochMs < saved.updatedAtEpochMs) {
            conflict("Local alarm episode cannot be acknowledged before its current state")
        }
        val acknowledged = saved.copy(
            episodeAcknowledged = true,
            episodeAcknowledgedAtEpochMs = acknowledgedAtEpochMs,
            updatedAtEpochMs = acknowledgedAtEpochMs,
            stateSha256 = "",
        ).canonicalized()
        if (updateAlarmState(acknowledged.toEntity()) != 1) {
            conflict("Local alarm state changed while acknowledging its episode")
        }
        cancelEpisodeRepeats(
            publicationBindingId = publicationBindingId,
            episodeGeneration = expectedEpisodeGeneration,
            operationToken = acknowledgementId,
            cancelledAtEpochMs = acknowledgedAtEpochMs,
        )
        if (nonTerminalEpisodeRepeatCount(
                publicationBindingId,
                expectedEpisodeGeneration,
            ) != 0
        ) {
            conflict("Local alarm repeat cancellation was not durable")
        }
        val updateDelivery = LocalAlarmDeliveryDraft(
            deliveryId = acknowledgementDeliveryId,
            sourceEffectId = acknowledged.lastEffectId,
            sourceEventId = acknowledged.lastEventId,
            approvalId = acknowledged.approvalId,
            publicationBindingId = acknowledged.publicationBindingId,
            kind = LocalAlarmDeliveryKind.UPDATE,
            activeKinds = acknowledged.policyState.active,
            episodeGeneration = acknowledged.episodeGeneration,
            episodeAcknowledged = true,
            resultingStateSha256 = acknowledged.stateSha256,
            createdAtEpochMs = acknowledgedAtEpochMs,
            notBeforeEpochMs = acknowledgedAtEpochMs,
        ).toEntity()
        if (insertAlarmDelivery(updateDelivery) == INSERT_IGNORED) {
            conflict("Local alarm episode update delivery identity already exists")
        }
        val restored = alarmState(publicationBindingId)?.validatedState()
            ?: conflict("Acknowledged local alarm state disappeared")
        if (restored != acknowledged) {
            conflict("Acknowledged local alarm state differs from its durable proof")
        }
        val restoredUpdate = alarmDelivery(acknowledgementDeliveryId)
            ?: conflict("Local alarm episode update delivery was not durable")
        if (!restoredUpdate.matchesEpisodeAcknowledgement(
                restored,
                acknowledgementDeliveryId,
                requireExactState = true,
            )
        ) {
            conflict("Local alarm episode update delivery differs from its durable proof")
        }
        return LocalAlarmEpisodeAcknowledgeDecision.Applied(restored)
    }

    @Transaction
    open suspend fun applyWatchdog(
        publicationBindingId: String,
        expectedStateSha256: String,
        nowEpochMs: Long,
    ): LocalAlarmWatchdogDecision {
        require(SHA256.matches(publicationBindingId))
        require(SHA256.matches(expectedStateSha256))
        require(nowEpochMs > 0L)
        val watchdogId = deterministicLocalAlarmWatchdogId(
            publicationBindingId,
            expectedStateSha256,
            nowEpochMs,
        )
        watchdogApplication(watchdogId)?.let { application ->
            if (!application.matchesRequest(
                    watchdogId,
                    publicationBindingId,
                    expectedStateSha256,
                    nowEpochMs,
                )
            ) {
                conflict("Local alarm watchdog retry differs from its durable application")
            }
            return LocalAlarmWatchdogDecision.AlreadyApplied(
                watchdogSettlementFor(application),
            )
        }

        val previous = alarmState(publicationBindingId)?.validatedState()
            ?: conflict("Local alarm watchdog state does not exist")
        requireStateSource(previous)
        if (previous.stateSha256 != expectedStateSha256) {
            return LocalAlarmWatchdogDecision.Obsolete(previous.stateSha256)
        }
        val reduction = try {
            LocalAlarmWatchdogReducer.reduce(previous, nowEpochMs)
        } catch (_: IllegalArgumentException) {
            conflict("Local alarm watchdog request conflicts with the durable state")
        } catch (_: ArithmeticException) {
            conflict("Local alarm episode generation overflow")
        }
        if (reduction.changed && updateAlarmState(reduction.state.toEntity()) != 1) {
            conflict("Local alarm state changed while applying its watchdog")
        }
        val deliveries = reduction.deliveryKinds.map { kind ->
            LocalAlarmDeliveryDraft(
                deliveryId = deterministicLocalAlarmWatchdogDeliveryId(watchdogId, kind),
                sourceEffectId = reduction.state.lastEffectId,
                sourceEventId = reduction.state.lastEventId,
                approvalId = reduction.state.approvalId,
                publicationBindingId = reduction.state.publicationBindingId,
                kind = kind,
                activeKinds = reduction.state.policyState.active,
                episodeGeneration = reduction.state.episodeGeneration,
                episodeAcknowledged = reduction.state.episodeAcknowledged,
                resultingStateSha256 = reduction.state.stateSha256,
                createdAtEpochMs = nowEpochMs,
                notBeforeEpochMs = LocalAlarmWatchdogDeliveryPlan.notBeforeEpochMs(
                    kind,
                    nowEpochMs,
                ),
            ).toEntity()
        }
        val deliveryIds = deliveries.map(LocalAlarmDeliveryEntity::deliveryId)
        val application = LocalAlarmWatchdogApplicationEntity(
            watchdogId = watchdogId,
            publicationBindingId = publicationBindingId,
            approvalId = reduction.state.approvalId,
            sourceEffectId = reduction.state.lastEffectId,
            sourceEventId = reduction.state.lastEventId,
            expectedStateSha256 = expectedStateSha256,
            appliedAtEpochMs = nowEpochMs,
            resultingStateSha256 = reduction.state.stateSha256,
            activeKinds = reduction.state.policyState.active.toAlarmKindsWire(),
            episodeGeneration = reduction.state.episodeGeneration,
            episodeAcknowledged = reduction.state.episodeAcknowledged,
            stateChanged = reduction.changed,
            deliveryKinds = reduction.deliveryKinds.toLocalAlarmDeliveryKindsWire(),
            deliverySetSha256 = deliveryIds.sorted().canonicalSha256(),
            deliveryCount = deliveryIds.size,
        )
        if (insertWatchdogApplication(application) == INSERT_IGNORED) {
            conflict("Local alarm watchdog application identity already exists")
        }
        deliveries.forEach { delivery ->
            if (insertAlarmDelivery(delivery) == INSERT_IGNORED) {
                conflict("Local alarm watchdog delivery identity already exists")
            }
        }
        return LocalAlarmWatchdogDecision.Applied(watchdogSettlementFor(application))
    }

    private suspend fun settlementFor(
        effect: LocalReadingEffectEntity,
    ): LocalAlarmSettlementReadDecision {
        val effectRecord = try {
            effect.toRecord()
        } catch (_: IllegalArgumentException) {
            return LocalAlarmSettlementReadDecision.Mismatch(
                "Acknowledged local effect is malformed",
            )
        } catch (_: NoSuchElementException) {
            return LocalAlarmSettlementReadDecision.Mismatch(
                "Acknowledged local effect has an unsupported state",
            )
        }
        if (effect.state != LocalReadingEffectState.ACKNOWLEDGED.wireName ||
            effectRecord.state != LocalReadingEffectState.ACKNOWLEDGED ||
            effect.leaseToken != null || effect.leaseExpiresAtEpochMs != null ||
            effect.lastTransitionToken == null || effect.acknowledgedAtEpochMs == null
        ) {
            return LocalAlarmSettlementReadDecision.Mismatch(
                "Local effect is not durably acknowledged",
            )
        }
        val application = alarmApplication(effect.effectId)
            ?: return LocalAlarmSettlementReadDecision.Mismatch(
                "Local alarm application proof is missing",
            )
        val deliveries = deliveriesForEffect(effect.effectId)
            .filter { it.matchesApplication(application) }
        val deliveryIds = deliveries.map(LocalAlarmDeliveryEntity::deliveryId)
        val requiredKinds = setOf(
            LocalAlarmDeliveryKind.WATCHDOG.wireName,
            LocalAlarmDeliveryKind.WIDGET.wireName,
        )
        if (application.effectId != effect.effectId ||
            application.eventId != effect.eventId ||
            application.approvalId != effect.approvalId ||
            application.publicationBindingId != effect.publicationBindingId ||
            application.leaseToken != effect.lastTransitionToken ||
            application.appliedAtEpochMs != effect.acknowledgedAtEpochMs ||
            application.deliveryCount != deliveries.size ||
            application.deliveryCount <= 0 ||
            application.deliverySetSha256 != deliveryIds.sorted().canonicalSha256() ||
            !deliveries.mapTo(mutableSetOf(), LocalAlarmDeliveryEntity::kind)
                .containsAll(requiredKinds) ||
            deliveries.any { !it.matchesApplication(application) }
        ) {
            return LocalAlarmSettlementReadDecision.Mismatch(
                "Local alarm application proof is inconsistent",
            )
        }
        val activeKinds = try {
            application.activeKinds.toAlarmKinds()
        } catch (_: IllegalArgumentException) {
            return LocalAlarmSettlementReadDecision.Mismatch(
                "Local alarm application contains unsupported alarm kinds",
            )
        }
        return try {
            LocalAlarmSettlementReadDecision.Exact(
                LocalAlarmSettlement(
                    effectId = application.effectId,
                    eventId = application.eventId,
                    approvalId = application.approvalId,
                    publicationBindingId = application.publicationBindingId,
                    activeKinds = activeKinds,
                    episodeGeneration = application.episodeGeneration,
                    episodeAcknowledged = application.episodeAcknowledged,
                    thresholdFingerprint = application.thresholdFingerprint,
                    resultingStateSha256 = application.resultingStateSha256,
                    appliedAtEpochMs = application.appliedAtEpochMs,
                    deliveryIds = deliveryIds,
                ),
            )
        } catch (_: IllegalArgumentException) {
            LocalAlarmSettlementReadDecision.Mismatch(
                "Local alarm settlement proof is malformed",
            )
        }
    }

    private fun conflict(message: String): Nothing = throw SensorCoreConflictException(message)

    private suspend fun requireActiveState(state: LocalAlarmStateRecord) {
        val active = activeSensorBinding()
            ?: conflict("Local alarm state has no active local sensor binding")
        if (active.publicationBindingId != state.publicationBindingId ||
            active.approvalId != state.approvalId
        ) {
            conflict("Local alarm state differs from the active local sensor binding")
        }
        val approval = physicalApproval(state.approvalId)
            ?: conflict("Local alarm state has no durable physical approval")
        try {
            if (approval.toRecord().approvalId != state.approvalId) {
                conflict("Local alarm state physical approval identity is inconsistent")
            }
        } catch (_: IllegalArgumentException) {
            conflict("Local alarm state physical approval is malformed")
        }
    }

    private fun LocalAlarmStateEntity.validatedState(): LocalAlarmStateRecord = try {
        toRecord()
    } catch (_: IllegalArgumentException) {
        conflict("Stored local alarm state is malformed")
    } catch (_: NoSuchElementException) {
        conflict("Stored local alarm state contains unsupported alarm kinds")
    }

    private fun LocalAlarmMonitoringStartEntity.validatedMonitoringStart():
        LocalAlarmMonitoringStartRecord = try {
        toRecord()
    } catch (_: IllegalArgumentException) {
        conflict("Stored monitoring start proof is malformed")
    }

    private suspend fun requireStateSource(state: LocalAlarmStateRecord) {
        requireActiveState(state)
        if (state.lastEffectId == MONITORING_START_EFFECT_ID) {
            val start = monitoringStart(state.lastEventId)?.validatedMonitoringStart()
                ?: conflict("Local alarm state has no monitoring start proof")
            if (start.publicationBindingId != state.publicationBindingId ||
                start.approvalId != state.approvalId ||
                start.monitoringStartedAtEpochMs != state.monitoringStartedAtEpochMs ||
                start.approvedSequence != state.lastSequence
            ) {
                conflict("Local alarm state differs from its monitoring start proof")
            }
            return
        }
        val application = alarmApplication(state.lastEffectId)
            ?: conflict("Local alarm state has no reading application proof")
        if (application.eventId != state.lastEventId ||
            application.approvalId != state.approvalId ||
            application.publicationBindingId != state.publicationBindingId ||
            application.monitoringStartedAtEpochMs != state.monitoringStartedAtEpochMs
        ) {
            conflict("Local alarm state differs from its reading application proof")
        }
    }

    private suspend fun requireDeliverySource(delivery: LocalAlarmDeliveryEntity) {
        if (delivery.sourceEffectId == MONITORING_START_EFFECT_ID) {
            val start = monitoringStart(delivery.sourceEventId)?.validatedMonitoringStart()
                ?: conflict("Local alarm delivery has no monitoring start proof")
            if (start.publicationBindingId != delivery.publicationBindingId ||
                start.approvalId != delivery.approvalId
            ) {
                conflict("Local alarm delivery differs from its monitoring start proof")
            }
            return
        }
        val application = alarmApplication(delivery.sourceEffectId)
            ?: conflict("Local alarm delivery has no source application proof")
        if (application.eventId != delivery.sourceEventId ||
            application.approvalId != delivery.approvalId ||
            application.publicationBindingId != delivery.publicationBindingId
        ) {
            conflict("Local alarm delivery source application is inconsistent")
        }
    }

    private suspend fun requireWatchdogSource(
        application: LocalAlarmWatchdogApplicationEntity,
    ) {
        if (application.sourceEffectId == MONITORING_START_EFFECT_ID) {
            val start = monitoringStart(application.sourceEventId)?.validatedMonitoringStart()
                ?: conflict("Local alarm watchdog has no monitoring start proof")
            if (start.publicationBindingId != application.publicationBindingId ||
                start.approvalId != application.approvalId
            ) {
                conflict("Local alarm watchdog differs from its monitoring start proof")
            }
            return
        }
        val source = alarmApplication(application.sourceEffectId)
            ?: conflict("Local alarm watchdog has no reading application proof")
        if (source.eventId != application.sourceEventId ||
            source.approvalId != application.approvalId ||
            source.publicationBindingId != application.publicationBindingId
        ) {
            conflict("Local alarm watchdog differs from its reading application proof")
        }
    }

    private suspend fun monitoringStartSettlement(
        start: LocalAlarmMonitoringStartRecord,
        state: LocalAlarmStateRecord,
    ): LocalAlarmMonitoringStartSettlement {
        requireStateSource(state)
        val watchdog = alarmDelivery(start.watchdogDeliveryId)
            ?: conflict("Monitoring start watchdog proof is missing")
        if (!watchdog.matchesMonitoringStart(start)) {
            conflict("Monitoring start watchdog proof is inconsistent")
        }
        return LocalAlarmMonitoringStartSettlement(start, state)
    }

    private suspend fun watchdogSettlementFor(
        application: LocalAlarmWatchdogApplicationEntity,
    ): LocalAlarmWatchdogSettlement {
        requireWatchdogSource(application)
        val deliveryKinds = try {
            application.deliveryKinds.toLocalAlarmDeliveryKinds()
        } catch (_: IllegalArgumentException) {
            conflict("Local alarm watchdog application has unsupported delivery kinds")
        } catch (_: NoSuchElementException) {
            conflict("Local alarm watchdog application has unsupported delivery kinds")
        }
        val deliveries = deliveryKinds.map { kind ->
            val deliveryId = deterministicLocalAlarmWatchdogDeliveryId(
                application.watchdogId,
                kind,
            )
            alarmDelivery(deliveryId)
                ?: conflict("Local alarm watchdog delivery proof is missing")
        }
        val deliveryIds = deliveries.map(LocalAlarmDeliveryEntity::deliveryId)
        if (application.watchdogId != deterministicLocalAlarmWatchdogId(
                application.publicationBindingId,
                application.expectedStateSha256,
                application.appliedAtEpochMs,
            ) ||
            application.deliveryCount != deliveries.size ||
            application.deliveryCount != deliveryKinds.size ||
            application.deliverySetSha256 != deliveryIds.sorted().canonicalSha256() ||
            application.stateChanged != deliveryKinds.isNotEmpty() ||
            deliveries.any { !it.matchesWatchdogApplication(application) }
        ) {
            conflict("Local alarm watchdog application proof is inconsistent")
        }
        val activeKinds = try {
            application.activeKinds.toAlarmKinds()
        } catch (_: IllegalArgumentException) {
            conflict("Local alarm watchdog application has unsupported alarm kinds")
        }
        return try {
            LocalAlarmWatchdogSettlement(
                watchdogId = application.watchdogId,
                publicationBindingId = application.publicationBindingId,
                approvalId = application.approvalId,
                sourceEffectId = application.sourceEffectId,
                sourceEventId = application.sourceEventId,
                expectedStateSha256 = application.expectedStateSha256,
                resultingStateSha256 = application.resultingStateSha256,
                activeKinds = activeKinds,
                episodeGeneration = application.episodeGeneration,
                episodeAcknowledged = application.episodeAcknowledged,
                appliedAtEpochMs = application.appliedAtEpochMs,
                stateChanged = application.stateChanged,
                deliveryIds = deliveryIds,
            )
        } catch (_: IllegalArgumentException) {
            conflict("Local alarm watchdog settlement is malformed")
        }
    }

    private suspend fun settingsSettlementFor(
        request: LocalAlarmSettingsApplyRequest,
    ): LocalAlarmSettingsSettlement? {
        val operationId = request.operationId
        val found = LocalAlarmDeliveryKind.entries.mapNotNull { kind ->
            alarmDelivery(deterministicLocalAlarmSettingsDeliveryId(operationId, kind))
        }
        if (found.isEmpty()) return null
        val records = try {
            found.map(LocalAlarmDeliveryEntity::toRecord)
        } catch (_: IllegalArgumentException) {
            conflict("Local alarm settings delivery proof is malformed")
        } catch (_: NoSuchElementException) {
            conflict("Local alarm settings delivery proof has an unsupported kind")
        }
        val byKind = records.associateBy(LocalAlarmDeliveryRecord::kind)
        if (byKind.size != records.size ||
            LocalAlarmDeliveryKind.WATCHDOG !in byKind ||
            LocalAlarmDeliveryKind.WIDGET !in byKind
        ) {
            conflict("Local alarm settings delivery proof is incomplete")
        }
        val anchor = checkNotNull(byKind[LocalAlarmDeliveryKind.WATCHDOG])
        val sameReduction = records.all { delivery ->
            delivery.deliveryId == deterministicLocalAlarmSettingsDeliveryId(
                operationId,
                delivery.kind,
            ) &&
                delivery.publicationBindingId == request.publicationBindingId &&
                delivery.approvalId == anchor.approvalId &&
                delivery.sourceEffectId == anchor.sourceEffectId &&
                delivery.sourceEventId == anchor.sourceEventId &&
                delivery.activeKinds == anchor.activeKinds &&
                delivery.episodeGeneration == anchor.episodeGeneration &&
                delivery.episodeAcknowledged == anchor.episodeAcknowledged &&
                delivery.resultingStateSha256 == anchor.resultingStateSha256 &&
                delivery.createdAtEpochMs == request.appliedAtEpochMs
        }
        val visibleKinds = byKind.keys.intersect(
            setOf(
                LocalAlarmDeliveryKind.SHOW,
                LocalAlarmDeliveryKind.UPDATE,
                LocalAlarmDeliveryKind.CLOSE,
            ),
        )
        val coherentShape = when {
            anchor.activeKinds.isEmpty() ->
                LocalAlarmDeliveryKind.REPEAT !in byKind &&
                    LocalAlarmDeliveryKind.SHOW !in byKind &&
                    LocalAlarmDeliveryKind.UPDATE !in byKind &&
                    visibleKinds.size <= 1
            anchor.episodeAcknowledged ->
                LocalAlarmDeliveryKind.REPEAT !in byKind &&
                    LocalAlarmDeliveryKind.CLOSE !in byKind &&
                    visibleKinds.size <= 1
            else ->
                LocalAlarmDeliveryKind.REPEAT in byKind &&
                    LocalAlarmDeliveryKind.CLOSE !in byKind &&
                    visibleKinds.size <= 1
        }
        if (!sameReduction || !coherentShape) {
            conflict("Local alarm settings delivery proof is inconsistent")
        }
        return LocalAlarmSettingsSettlement(
            operationId = operationId,
            publicationBindingId = request.publicationBindingId,
            approvalId = anchor.approvalId,
            sourceEffectId = anchor.sourceEffectId,
            sourceEventId = anchor.sourceEventId,
            expectedStateSha256 = request.expectedStateSha256,
            resultingStateSha256 = anchor.resultingStateSha256,
            thresholdFingerprint = request.thresholds.fingerprint,
            activeKinds = anchor.activeKinds,
            episodeGeneration = anchor.episodeGeneration,
            episodeAcknowledged = anchor.episodeAcknowledged,
            appliedAtEpochMs = request.appliedAtEpochMs,
            stateChanged = true,
            deliveryIds = records.map(LocalAlarmDeliveryRecord::deliveryId),
        )
    }

    private companion object {
        const val INSERT_IGNORED = -1L
        val SHA256 = Regex("^[0-9a-f]{64}$")
    }
}

private fun LocalAlarmApplicationEntity.matchesRequest(
    request: LocalAlarmApplyRequest,
): Boolean = effectId == request.effectId &&
    eventId == request.eventId &&
    leaseToken == request.leaseToken &&
    appliedAtEpochMs == request.processedAtEpochMs &&
    monitoringStartedAtEpochMs == request.monitoringStartedAtEpochMs &&
    thresholdFingerprint == request.thresholds.fingerprint &&
    expectedPreviousThresholdFingerprint == request.expectedPreviousThresholdFingerprint &&
    repeatIntervalMs == request.repeatIntervalMs

private fun LocalAlarmWatchdogApplicationEntity.matchesRequest(
    requestedWatchdogId: String,
    requestedPublicationBindingId: String,
    requestedExpectedStateSha256: String,
    requestedAppliedAtEpochMs: Long,
): Boolean = watchdogId == requestedWatchdogId &&
    publicationBindingId == requestedPublicationBindingId &&
    expectedStateSha256 == requestedExpectedStateSha256 &&
    appliedAtEpochMs == requestedAppliedAtEpochMs

private fun LocalAlarmDeliveryEntity.matchesApplication(
    application: LocalAlarmApplicationEntity,
): Boolean {
    val typedKind = LocalAlarmDeliveryKind.entries.firstOrNull { it.wireName == kind }
        ?: return false
    val canonicalActiveKinds = runCatching { activeKinds.toAlarmKinds().toAlarmKindsWire() }
        .getOrNull() ?: return false
    return sourceEffectId == application.effectId &&
        sourceEventId == application.eventId &&
        approvalId == application.approvalId &&
        publicationBindingId == application.publicationBindingId &&
        activeKinds == application.activeKinds &&
        activeKinds == canonicalActiveKinds &&
        episodeGeneration == application.episodeGeneration &&
        episodeAcknowledged == application.episodeAcknowledged &&
        resultingStateSha256 == application.resultingStateSha256 &&
        createdAtEpochMs == application.appliedAtEpochMs &&
        kindOrder == typedKind.order &&
        deliveryId == deterministicLocalAlarmDeliveryId(
            publicationBindingId = publicationBindingId,
            effectId = sourceEffectId,
            eventId = sourceEventId,
            episodeGeneration = episodeGeneration,
            kind = typedKind,
        ) &&
        validDeliveryLifecycle()
}

private fun LocalAlarmDeliveryEntity.matchesWatchdogApplication(
    application: LocalAlarmWatchdogApplicationEntity,
): Boolean {
    val typedKind = LocalAlarmDeliveryKind.entries.firstOrNull { it.wireName == kind }
        ?: return false
    val applicationKinds = runCatching {
        application.deliveryKinds.toLocalAlarmDeliveryKinds()
    }.getOrNull() ?: return false
    val canonicalActiveKinds = runCatching { activeKinds.toAlarmKinds().toAlarmKindsWire() }
        .getOrNull() ?: return false
    return typedKind in applicationKinds &&
        sourceEffectId == application.sourceEffectId &&
        sourceEventId == application.sourceEventId &&
        approvalId == application.approvalId &&
        publicationBindingId == application.publicationBindingId &&
        activeKinds == application.activeKinds &&
        activeKinds == canonicalActiveKinds &&
        episodeGeneration == application.episodeGeneration &&
        episodeAcknowledged == application.episodeAcknowledged &&
        resultingStateSha256 == application.resultingStateSha256 &&
        createdAtEpochMs == application.appliedAtEpochMs &&
        notBeforeEpochMs == LocalAlarmWatchdogDeliveryPlan.notBeforeEpochMs(
            typedKind,
            application.appliedAtEpochMs,
        ) &&
        kindOrder == typedKind.order &&
        deliveryId == deterministicLocalAlarmWatchdogDeliveryId(
            application.watchdogId,
            typedKind,
        ) &&
        validDeliveryLifecycle()
}

private fun LocalAlarmDeliveryEntity.matchesMonitoringStart(
    start: LocalAlarmMonitoringStartRecord,
): Boolean = sourceEffectId == MONITORING_START_EFFECT_ID &&
    sourceEventId == start.startId &&
    approvalId == start.approvalId &&
    publicationBindingId == start.publicationBindingId &&
    kind == LocalAlarmDeliveryKind.WATCHDOG.wireName &&
    kindOrder == LocalAlarmDeliveryKind.WATCHDOG.order &&
    activeKinds.isEmpty() &&
    episodeGeneration == 0L &&
    !episodeAcknowledged &&
    resultingStateSha256 == start.initialStateSha256 &&
    createdAtEpochMs == start.monitoringStartedAtEpochMs &&
    notBeforeEpochMs == start.watchdogDeadlineEpochMs &&
    state != LocalAlarmDeliveryState.CANCELLED.wireName &&
    deliveryId == start.watchdogDeliveryId &&
    deliveryId == deterministicLocalAlarmDeliveryId(
        publicationBindingId = start.publicationBindingId,
        effectId = MONITORING_START_EFFECT_ID,
        eventId = start.startId,
        episodeGeneration = 0L,
        kind = LocalAlarmDeliveryKind.WATCHDOG,
    ) &&
    validDeliveryLifecycle()

private fun LocalAlarmDeliveryEntity.matchesEpisodeAcknowledgement(
    state: LocalAlarmStateRecord,
    expectedDeliveryId: String,
    requireExactState: Boolean = false,
): Boolean = deliveryId == expectedDeliveryId &&
    sourceEffectId in MONITORING_START_EFFECT_ID..state.lastEffectId &&
    sourceEventId.isNotBlank() &&
    approvalId == state.approvalId &&
    publicationBindingId == state.publicationBindingId &&
    kind == LocalAlarmDeliveryKind.UPDATE.wireName &&
    kindOrder == LocalAlarmDeliveryKind.UPDATE.order &&
    runCatching { activeKinds.toAlarmKinds() }.getOrNull()?.isNotEmpty() == true &&
    episodeGeneration == state.episodeGeneration &&
    episodeAcknowledged &&
    Regex("^[0-9a-f]{64}$").matches(resultingStateSha256) &&
    createdAtEpochMs == state.episodeAcknowledgedAtEpochMs &&
    notBeforeEpochMs == state.episodeAcknowledgedAtEpochMs &&
    if (requireExactState) {
        sourceEffectId == state.lastEffectId &&
            sourceEventId == state.lastEventId &&
            activeKinds == state.policyState.active.toAlarmKindsWire() &&
            resultingStateSha256 == state.stateSha256
    } else {
        true
    } &&
    validDeliveryLifecycle()

private fun LocalAlarmDeliveryEntity.validDeliveryLifecycle(): Boolean = try {
    toRecord()
    true
} catch (_: IllegalArgumentException) {
    false
} catch (_: NoSuchElementException) {
    false
}
