package com.sladkaya.core.data

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import com.sladkaya.core.model.AlarmPolicyState

@Entity(
    tableName = "local_alarm_monitoring_starts",
    indices = [
        Index(value = ["publicationBindingId"], unique = true),
        Index(value = ["approvalId"]),
    ],
    foreignKeys = [
        ForeignKey(
            entity = PhysicalSensorApprovalEntity::class,
            parentColumns = ["approvalId"],
            childColumns = ["approvalId"],
            onDelete = ForeignKey.RESTRICT,
        ),
    ],
)
internal data class LocalAlarmMonitoringStartEntity(
    @PrimaryKey val startId: String,
    val publicationBindingId: String,
    val approvalId: String,
    val monitoringStartedAtEpochMs: Long,
    val approvedSequence: Long,
    val thresholdLowMgDl: Int,
    val thresholdHighMgDl: Int,
    val thresholdRapidFallMgDlPerMinute: Double,
    val thresholdRapidRiseMgDlPerMinute: Double,
    val thresholdRecoveryHysteresisMgDl: Int,
    val thresholdStaleAfterMs: Long,
    val thresholdFingerprint: String,
    val initialStateSha256: String,
    val watchdogDeliveryId: String,
    val watchdogDeadlineEpochMs: Long,
)

@Entity(
    tableName = "local_alarm_states",
    indices = [
        Index(value = ["approvalId"]),
        Index(value = ["lastEffectId"]),
    ],
    foreignKeys = [
        ForeignKey(
            entity = PhysicalSensorApprovalEntity::class,
            parentColumns = ["approvalId"],
            childColumns = ["approvalId"],
            onDelete = ForeignKey.RESTRICT,
        ),
    ],
)
internal data class LocalAlarmStateEntity(
    @PrimaryKey val publicationBindingId: String,
    val approvalId: String,
    val monitoringStartedAtEpochMs: Long,
    val activeKinds: String,
    val latestFreshSensorTimeEpochMs: Long,
    val latestFreshPhoneTimeEpochMs: Long,
    val phoneClockMovedBackwards: Boolean,
    val lastEffectId: Long,
    val lastEventId: String,
    val lastSequence: Long,
    val thresholdLowMgDl: Int,
    val thresholdHighMgDl: Int,
    val thresholdRapidFallMgDlPerMinute: Double,
    val thresholdRapidRiseMgDlPerMinute: Double,
    val thresholdRecoveryHysteresisMgDl: Int,
    val thresholdStaleAfterMs: Long,
    val thresholdFingerprint: String,
    val episodeGeneration: Long,
    val episodeAcknowledged: Boolean,
    val episodeAcknowledgedAtEpochMs: Long?,
    val episodeOpenedAtEpochMs: Long?,
    val updatedAtEpochMs: Long,
    val stateSha256: String,
)

@Entity(
    tableName = "local_alarm_applications",
    indices = [
        Index(value = ["eventId"], unique = true),
        Index(value = ["publicationBindingId", "effectId"]),
    ],
    foreignKeys = [
        ForeignKey(
            entity = LocalReadingEffectEntity::class,
            parentColumns = ["effectId"],
            childColumns = ["effectId"],
            onDelete = ForeignKey.RESTRICT,
        ),
    ],
)
internal data class LocalAlarmApplicationEntity(
    @PrimaryKey val effectId: Long,
    val eventId: String,
    val approvalId: String,
    val publicationBindingId: String,
    val previousEffectId: Long?,
    val leaseToken: String,
    val appliedAtEpochMs: Long,
    val monitoringStartedAtEpochMs: Long,
    val thresholdFingerprint: String,
    val expectedPreviousThresholdFingerprint: String?,
    val repeatIntervalMs: Long,
    val resultingStateSha256: String,
    val activeKinds: String,
    val episodeGeneration: Long,
    val episodeAcknowledged: Boolean,
    val deliverySetSha256: String,
    val deliveryCount: Int,
)

@Entity(
    tableName = "local_alarm_watchdog_applications",
    indices = [
        Index(
            value = ["publicationBindingId", "expectedStateSha256", "appliedAtEpochMs"],
            unique = true,
        ),
        Index(value = ["sourceEffectId"]),
    ],
)
internal data class LocalAlarmWatchdogApplicationEntity(
    @PrimaryKey val watchdogId: String,
    val publicationBindingId: String,
    val approvalId: String,
    val sourceEffectId: Long,
    val sourceEventId: String,
    val expectedStateSha256: String,
    val appliedAtEpochMs: Long,
    val resultingStateSha256: String,
    val activeKinds: String,
    val episodeGeneration: Long,
    val episodeAcknowledged: Boolean,
    val stateChanged: Boolean,
    val deliveryKinds: String,
    val deliverySetSha256: String,
    val deliveryCount: Int,
)

@Entity(
    tableName = "local_alarm_deliveries",
    indices = [
        Index(value = ["sourceEffectId", "kind"]),
        Index(value = ["state", "notBeforeEpochMs", "sourceEffectId", "kindOrder"]),
        Index(value = ["leaseToken"]),
        Index(value = ["lastTransitionToken"]),
    ],
)
internal data class LocalAlarmDeliveryEntity(
    @PrimaryKey val deliveryId: String,
    val sourceEffectId: Long,
    val sourceEventId: String,
    val approvalId: String,
    val publicationBindingId: String,
    val kind: String,
    val kindOrder: Int,
    val activeKinds: String,
    val episodeGeneration: Long,
    val episodeAcknowledged: Boolean,
    val resultingStateSha256: String,
    val createdAtEpochMs: Long,
    val notBeforeEpochMs: Long,
    val state: String,
    val attempts: Int,
    val leaseToken: String?,
    val leaseExpiresAtEpochMs: Long?,
    val lastTransitionToken: String?,
    val lastTransitionKind: String?,
    val lastTransitionAtEpochMs: Long?,
    val deliveredAtEpochMs: Long?,
)

internal fun LocalAlarmMonitoringStartRecord.toEntity(): LocalAlarmMonitoringStartEntity {
    val value = requireCanonical()
    return LocalAlarmMonitoringStartEntity(
        startId = value.startId,
        publicationBindingId = value.publicationBindingId,
        approvalId = value.approvalId,
        monitoringStartedAtEpochMs = value.monitoringStartedAtEpochMs,
        approvedSequence = value.approvedSequence,
        thresholdLowMgDl = value.thresholds.lowMgDl,
        thresholdHighMgDl = value.thresholds.highMgDl,
        thresholdRapidFallMgDlPerMinute = value.thresholds.rapidFallMgDlPerMinute,
        thresholdRapidRiseMgDlPerMinute = value.thresholds.rapidRiseMgDlPerMinute,
        thresholdRecoveryHysteresisMgDl = value.thresholds.recoveryHysteresisMgDl,
        thresholdStaleAfterMs = value.thresholds.staleAfterMs,
        thresholdFingerprint = value.thresholdFingerprint,
        initialStateSha256 = value.initialStateSha256,
        watchdogDeliveryId = value.watchdogDeliveryId,
        watchdogDeadlineEpochMs = value.watchdogDeadlineEpochMs,
    )
}

internal fun LocalAlarmMonitoringStartEntity.toRecord(): LocalAlarmMonitoringStartRecord {
    val thresholds = AlarmThresholdSnapshot(
        lowMgDl = thresholdLowMgDl,
        highMgDl = thresholdHighMgDl,
        rapidFallMgDlPerMinute = thresholdRapidFallMgDlPerMinute,
        rapidRiseMgDlPerMinute = thresholdRapidRiseMgDlPerMinute,
        recoveryHysteresisMgDl = thresholdRecoveryHysteresisMgDl,
        staleAfterMs = thresholdStaleAfterMs,
    )
    return LocalAlarmMonitoringStartRecord(
        startId = startId,
        publicationBindingId = publicationBindingId,
        approvalId = approvalId,
        monitoringStartedAtEpochMs = monitoringStartedAtEpochMs,
        approvedSequence = approvedSequence,
        thresholds = thresholds,
        thresholdFingerprint = thresholdFingerprint,
        initialStateSha256 = initialStateSha256,
        watchdogDeliveryId = watchdogDeliveryId,
        watchdogDeadlineEpochMs = watchdogDeadlineEpochMs,
    ).requireCanonical()
}

internal fun LocalAlarmStateRecord.toEntity(): LocalAlarmStateEntity {
    val value = requireCanonical()
    return LocalAlarmStateEntity(
        publicationBindingId = value.publicationBindingId,
        approvalId = value.approvalId,
        monitoringStartedAtEpochMs = value.monitoringStartedAtEpochMs,
        activeKinds = value.policyState.active.toAlarmKindsWire(),
        latestFreshSensorTimeEpochMs = value.policyState.latestFreshSensorTimeEpochMs,
        latestFreshPhoneTimeEpochMs = value.policyState.latestFreshPhoneTimeEpochMs,
        phoneClockMovedBackwards = value.policyState.phoneClockMovedBackwards,
        lastEffectId = value.lastEffectId,
        lastEventId = value.lastEventId,
        lastSequence = value.lastSequence,
        thresholdLowMgDl = value.thresholds.lowMgDl,
        thresholdHighMgDl = value.thresholds.highMgDl,
        thresholdRapidFallMgDlPerMinute = value.thresholds.rapidFallMgDlPerMinute,
        thresholdRapidRiseMgDlPerMinute = value.thresholds.rapidRiseMgDlPerMinute,
        thresholdRecoveryHysteresisMgDl = value.thresholds.recoveryHysteresisMgDl,
        thresholdStaleAfterMs = value.thresholds.staleAfterMs,
        thresholdFingerprint = value.thresholds.fingerprint,
        episodeGeneration = value.episodeGeneration,
        episodeAcknowledged = value.episodeAcknowledged,
        episodeAcknowledgedAtEpochMs = value.episodeAcknowledgedAtEpochMs,
        episodeOpenedAtEpochMs = value.episodeOpenedAtEpochMs,
        updatedAtEpochMs = value.updatedAtEpochMs,
        stateSha256 = value.stateSha256,
    )
}

internal fun LocalAlarmStateEntity.toRecord(): LocalAlarmStateRecord {
    val thresholds = AlarmThresholdSnapshot(
        lowMgDl = thresholdLowMgDl,
        highMgDl = thresholdHighMgDl,
        rapidFallMgDlPerMinute = thresholdRapidFallMgDlPerMinute,
        rapidRiseMgDlPerMinute = thresholdRapidRiseMgDlPerMinute,
        recoveryHysteresisMgDl = thresholdRecoveryHysteresisMgDl,
        staleAfterMs = thresholdStaleAfterMs,
    )
    require(thresholds.fingerprint == thresholdFingerprint)
    return LocalAlarmStateRecord(
        publicationBindingId = publicationBindingId,
        approvalId = approvalId,
        monitoringStartedAtEpochMs = monitoringStartedAtEpochMs,
        policyState = AlarmPolicyState(
            active = activeKinds.toAlarmKinds(),
            latestFreshSensorTimeEpochMs = latestFreshSensorTimeEpochMs,
            latestFreshPhoneTimeEpochMs = latestFreshPhoneTimeEpochMs,
            phoneClockMovedBackwards = phoneClockMovedBackwards,
        ),
        lastEffectId = lastEffectId,
        lastEventId = lastEventId,
        lastSequence = lastSequence,
        thresholds = thresholds,
        episodeGeneration = episodeGeneration,
        episodeAcknowledged = episodeAcknowledged,
        episodeAcknowledgedAtEpochMs = episodeAcknowledgedAtEpochMs,
        episodeOpenedAtEpochMs = episodeOpenedAtEpochMs,
        updatedAtEpochMs = updatedAtEpochMs,
        stateSha256 = stateSha256,
    ).requireCanonical()
}

internal fun LocalAlarmDeliveryDraft.toEntity() = LocalAlarmDeliveryEntity(
    deliveryId = deliveryId,
    sourceEffectId = sourceEffectId,
    sourceEventId = sourceEventId,
    approvalId = approvalId,
    publicationBindingId = publicationBindingId,
    kind = kind.wireName,
    kindOrder = kind.order,
    activeKinds = activeKinds.toAlarmKindsWire(),
    episodeGeneration = episodeGeneration,
    episodeAcknowledged = episodeAcknowledged,
    resultingStateSha256 = resultingStateSha256,
    createdAtEpochMs = createdAtEpochMs,
    notBeforeEpochMs = notBeforeEpochMs,
    state = LocalAlarmDeliveryState.PENDING.wireName,
    attempts = 0,
    leaseToken = null,
    leaseExpiresAtEpochMs = null,
    lastTransitionToken = null,
    lastTransitionKind = null,
    lastTransitionAtEpochMs = null,
    deliveredAtEpochMs = null,
)

internal fun LocalAlarmDeliveryEntity.toRecord() = LocalAlarmDeliveryRecord(
    deliveryId = deliveryId,
    sourceEffectId = sourceEffectId,
    sourceEventId = sourceEventId,
    approvalId = approvalId,
    publicationBindingId = publicationBindingId,
    kind = LocalAlarmDeliveryKind.entries.first { it.wireName == kind },
    activeKinds = activeKinds.toAlarmKinds(),
    episodeGeneration = episodeGeneration,
    episodeAcknowledged = episodeAcknowledged,
    resultingStateSha256 = resultingStateSha256,
    createdAtEpochMs = createdAtEpochMs,
    notBeforeEpochMs = notBeforeEpochMs,
    state = LocalAlarmDeliveryState.entries.first { it.wireName == state },
    attempts = attempts,
    leaseToken = leaseToken,
    leaseExpiresAtEpochMs = leaseExpiresAtEpochMs,
    lastTransitionToken = lastTransitionToken,
    lastTransitionKind = lastTransitionKind?.let { wire ->
        LocalAlarmDeliveryTransitionKind.entries.first { it.wireName == wire }
    },
    lastTransitionAtEpochMs = lastTransitionAtEpochMs,
    deliveredAtEpochMs = deliveredAtEpochMs,
)

internal fun LocalAlarmDeliveryEntity.hasSameImmutableIdentityAs(
    other: LocalAlarmDeliveryEntity,
): Boolean = deliveryId == other.deliveryId &&
    sourceEffectId == other.sourceEffectId &&
    sourceEventId == other.sourceEventId &&
    approvalId == other.approvalId &&
    publicationBindingId == other.publicationBindingId &&
    kind == other.kind &&
    kindOrder == other.kindOrder &&
    activeKinds == other.activeKinds &&
    episodeGeneration == other.episodeGeneration &&
    episodeAcknowledged == other.episodeAcknowledged &&
    resultingStateSha256 == other.resultingStateSha256 &&
    createdAtEpochMs == other.createdAtEpochMs &&
    notBeforeEpochMs == other.notBeforeEpochMs
