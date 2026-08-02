package com.sladkaya.core.data

import com.sladkaya.core.model.AlarmKind
import com.sladkaya.core.model.AlarmPolicyState
import com.sladkaya.core.model.AlarmThresholds
import java.security.MessageDigest

data class AlarmThresholdSnapshot(
    val lowMgDl: Int,
    val highMgDl: Int,
    val rapidFallMgDlPerMinute: Double,
    val rapidRiseMgDlPerMinute: Double,
    val recoveryHysteresisMgDl: Int,
    val staleAfterMs: Long,
) {
    init {
        toModel()
    }

    val fingerprint: String
        get() = listOf(
            THRESHOLD_SCHEMA,
            lowMgDl.toString(),
            highMgDl.toString(),
            rapidFallMgDlPerMinute.toRawBits().toString(),
            rapidRiseMgDlPerMinute.toRawBits().toString(),
            recoveryHysteresisMgDl.toString(),
            staleAfterMs.toString(),
        ).canonicalSha256()

    fun toModel() = AlarmThresholds(
        lowMgDl = lowMgDl,
        highMgDl = highMgDl,
        rapidFallMgDlPerMinute = rapidFallMgDlPerMinute,
        rapidRiseMgDlPerMinute = rapidRiseMgDlPerMinute,
        recoveryHysteresisMgDl = recoveryHysteresisMgDl,
        staleAfterMs = staleAfterMs,
    )

    companion object {
        fun from(value: AlarmThresholds) = AlarmThresholdSnapshot(
            lowMgDl = value.lowMgDl,
            highMgDl = value.highMgDl,
            rapidFallMgDlPerMinute = value.rapidFallMgDlPerMinute,
            rapidRiseMgDlPerMinute = value.rapidRiseMgDlPerMinute,
            recoveryHysteresisMgDl = value.recoveryHysteresisMgDl,
            staleAfterMs = value.staleAfterMs,
        )

        private const val THRESHOLD_SCHEMA = "local-alarm-thresholds-v1"
    }
}

data class LocalAlarmStateRecord(
    val publicationBindingId: String,
    val approvalId: String,
    val monitoringStartedAtEpochMs: Long,
    val policyState: AlarmPolicyState,
    val lastEffectId: Long,
    val lastEventId: String,
    val lastSequence: Long,
    val thresholds: AlarmThresholdSnapshot,
    val episodeGeneration: Long,
    val episodeAcknowledged: Boolean,
    val episodeAcknowledgedAtEpochMs: Long? = null,
    val episodeOpenedAtEpochMs: Long?,
    val updatedAtEpochMs: Long,
    val stateSha256: String,
) {
    fun canonicalized(): LocalAlarmStateRecord {
        require(SHA256.matches(publicationBindingId))
        require(SHA256.matches(approvalId))
        require(monitoringStartedAtEpochMs > 0)
        // Zero is reserved for the exact monitoring-start anchor. It is never a sensor reading.
        require(lastEffectId >= 0)
        require(lastEventId.isNotBlank())
        if (lastEffectId == MONITORING_START_EFFECT_ID) {
            require(SHA256.matches(lastEventId))
            require(policyState.latestFreshSensorTimeEpochMs == 0L)
            require(policyState.latestFreshPhoneTimeEpochMs == 0L)
            require(!policyState.phoneClockMovedBackwards)
            require(policyState.active.all { it == AlarmKind.SIGNAL_LOSS })
        }
        require(lastSequence >= 0)
        require(episodeGeneration >= 0)
        require(updatedAtEpochMs > 0)
        if (policyState.active.isEmpty()) {
            require(!episodeAcknowledged)
            require(episodeAcknowledgedAtEpochMs == null)
            require(episodeOpenedAtEpochMs == null)
        } else {
            require(episodeGeneration > 0)
            require(requireNotNull(episodeOpenedAtEpochMs) > 0)
            if (episodeAcknowledged) {
                require(requireNotNull(episodeAcknowledgedAtEpochMs) >= episodeOpenedAtEpochMs)
            } else {
                require(episodeAcknowledgedAtEpochMs == null)
            }
        }
        val canonicalHash = canonicalStateHash(
            publicationBindingId = publicationBindingId,
            approvalId = approvalId,
            monitoringStartedAtEpochMs = monitoringStartedAtEpochMs,
            policyState = policyState,
            lastEffectId = lastEffectId,
            lastEventId = lastEventId,
            lastSequence = lastSequence,
            thresholds = thresholds,
            episodeGeneration = episodeGeneration,
            episodeAcknowledged = episodeAcknowledged,
            episodeAcknowledgedAtEpochMs = episodeAcknowledgedAtEpochMs,
            episodeOpenedAtEpochMs = episodeOpenedAtEpochMs,
            updatedAtEpochMs = updatedAtEpochMs,
        )
        return copy(stateSha256 = canonicalHash)
    }

    fun requireCanonical(): LocalAlarmStateRecord {
        require(SHA256.matches(stateSha256))
        require(canonicalized().stateSha256 == stateSha256)
        return this
    }

    private companion object {
        val SHA256 = Regex("^[0-9a-f]{64}$")
    }
}

enum class LocalAlarmDeliveryKind(val wireName: String, internal val order: Int) {
    SHOW("SHOW", 0),
    UPDATE("UPDATE", 1),
    CLOSE("CLOSE", 2),
    REPEAT("REPEAT", 3),
    WATCHDOG("WATCHDOG", 4),
    WIDGET("WIDGET", 5),
}

enum class LocalAlarmDeliveryState(val wireName: String) {
    PENDING("PENDING"),
    LEASED("LEASED"),
    DELIVERED("DELIVERED"),
    CANCELLED("CANCELLED"),
}

enum class LocalAlarmDeliveryTransitionKind(val wireName: String) {
    EXPIRED("EXPIRED"),
    RETRY("RETRY"),
    DELIVERED("DELIVERED"),
    CANCELLED("CANCELLED"),
}

data class LocalAlarmDeliveryRecord(
    val deliveryId: String,
    val sourceEffectId: Long,
    val sourceEventId: String,
    val approvalId: String,
    val publicationBindingId: String,
    val kind: LocalAlarmDeliveryKind,
    val activeKinds: Set<AlarmKind>,
    val episodeGeneration: Long,
    val episodeAcknowledged: Boolean,
    val resultingStateSha256: String,
    val createdAtEpochMs: Long,
    val notBeforeEpochMs: Long,
    val state: LocalAlarmDeliveryState,
    val attempts: Int,
    val leaseToken: String?,
    val leaseExpiresAtEpochMs: Long?,
    val lastTransitionToken: String?,
    val lastTransitionKind: LocalAlarmDeliveryTransitionKind?,
    val lastTransitionAtEpochMs: Long?,
    val deliveredAtEpochMs: Long?,
) {
    init {
        require(SHA256.matches(deliveryId))
        require(sourceEffectId >= MONITORING_START_EFFECT_ID)
        require(sourceEventId.isNotBlank())
        if (sourceEffectId == MONITORING_START_EFFECT_ID) require(SHA256.matches(sourceEventId))
        require(SHA256.matches(approvalId))
        require(SHA256.matches(publicationBindingId))
        require(episodeGeneration >= 0)
        require(!episodeAcknowledged || activeKinds.isNotEmpty())
        require(SHA256.matches(resultingStateSha256))
        require(createdAtEpochMs > 0)
        require(notBeforeEpochMs > 0)
        require(attempts >= 0)
        if (leaseToken != null) requireLeaseToken(leaseToken)
        if (lastTransitionToken != null) requireLeaseToken(lastTransitionToken)
        require(
            listOf(lastTransitionToken, lastTransitionKind, lastTransitionAtEpochMs)
                .all { it == null } ||
                listOf(lastTransitionToken, lastTransitionKind, lastTransitionAtEpochMs)
                    .all { it != null },
        )
        when (state) {
            LocalAlarmDeliveryState.PENDING -> {
                require(leaseToken == null && leaseExpiresAtEpochMs == null)
                require(deliveredAtEpochMs == null)
                require(
                    attempts == 0 && lastTransitionKind == null ||
                        attempts > 0 && lastTransitionKind in setOf(
                            LocalAlarmDeliveryTransitionKind.EXPIRED,
                            LocalAlarmDeliveryTransitionKind.RETRY,
                        ),
                )
            }
            LocalAlarmDeliveryState.LEASED -> {
                require(attempts > 0)
                requireNotNull(leaseToken)
                require(requireNotNull(leaseExpiresAtEpochMs) > 0)
                require(deliveredAtEpochMs == null)
            }
            LocalAlarmDeliveryState.DELIVERED -> {
                require(attempts > 0)
                require(leaseToken == null && leaseExpiresAtEpochMs == null)
                require(lastTransitionKind == LocalAlarmDeliveryTransitionKind.DELIVERED)
                require(deliveredAtEpochMs == lastTransitionAtEpochMs)
                require(requireNotNull(deliveredAtEpochMs) >= notBeforeEpochMs)
            }
            LocalAlarmDeliveryState.CANCELLED -> {
                require(leaseToken == null && leaseExpiresAtEpochMs == null)
                require(lastTransitionKind == LocalAlarmDeliveryTransitionKind.CANCELLED)
                require(deliveredAtEpochMs == null)
            }
        }
    }

    private companion object {
        val SHA256 = Regex("^[0-9a-f]{64}$")
    }
}

sealed interface LocalAlarmDeliveryLeaseResult {
    data class Leased(val value: LocalAlarmDeliveryRecord) : LocalAlarmDeliveryLeaseResult
    data object Empty : LocalAlarmDeliveryLeaseResult
    data class NotDue(
        val deliveryId: String,
        val notBeforeEpochMs: Long,
    ) : LocalAlarmDeliveryLeaseResult
    data class BlockedByActiveLease(
        val deliveryId: String,
        val leaseExpiresAtEpochMs: Long,
    ) : LocalAlarmDeliveryLeaseResult
    data class Conflict(val reason: String) : LocalAlarmDeliveryLeaseResult
}

sealed interface LocalAlarmDeliveryTransitionResult {
    data object Applied : LocalAlarmDeliveryTransitionResult
    data object AlreadyApplied : LocalAlarmDeliveryTransitionResult
    data class Conflict(val reason: String) : LocalAlarmDeliveryTransitionResult
}

data class LocalAlarmDeliveryDraft(
    val deliveryId: String,
    val sourceEffectId: Long,
    val sourceEventId: String,
    val approvalId: String,
    val publicationBindingId: String,
    val kind: LocalAlarmDeliveryKind,
    val activeKinds: Set<AlarmKind>,
    val episodeGeneration: Long,
    val episodeAcknowledged: Boolean,
    val resultingStateSha256: String,
    val createdAtEpochMs: Long,
    val notBeforeEpochMs: Long,
) {
    init {
        require(SHA256.matches(deliveryId))
        require(sourceEffectId >= MONITORING_START_EFFECT_ID)
        require(sourceEventId.isNotBlank())
        if (sourceEffectId == MONITORING_START_EFFECT_ID) require(SHA256.matches(sourceEventId))
        require(SHA256.matches(approvalId))
        require(SHA256.matches(publicationBindingId))
        require(episodeGeneration >= 0)
        require(!episodeAcknowledged || activeKinds.isNotEmpty())
        require(SHA256.matches(resultingStateSha256))
        require(createdAtEpochMs > 0)
        require(notBeforeEpochMs > 0)
    }

    private companion object {
        val SHA256 = Regex("^[0-9a-f]{64}$")
    }
}

data class LocalAlarmApplyRequest(
    val effectId: Long,
    val eventId: String,
    val leaseToken: String,
    val processedAtEpochMs: Long,
    val monitoringStartedAtEpochMs: Long,
    val thresholds: AlarmThresholdSnapshot,
    val expectedPreviousThresholdFingerprint: String? = null,
    val repeatIntervalMs: Long = 120_000L,
) {
    init {
        require(effectId > 0)
        require(eventId.isNotBlank())
        requireLeaseToken(leaseToken)
        require(processedAtEpochMs > 0)
        require(monitoringStartedAtEpochMs > 0)
        require(expectedPreviousThresholdFingerprint == null ||
            SHA256.matches(expectedPreviousThresholdFingerprint))
        require(repeatIntervalMs in MIN_REPEAT_INTERVAL_MS..MAX_REPEAT_INTERVAL_MS)
    }

    private companion object {
        const val MIN_REPEAT_INTERVAL_MS = 30_000L
        const val MAX_REPEAT_INTERVAL_MS = 60 * 60_000L
        val SHA256 = Regex("^[0-9a-f]{64}$")
    }
}

data class LocalAlarmSettlement(
    val effectId: Long,
    val eventId: String,
    val approvalId: String,
    val publicationBindingId: String,
    val activeKinds: Set<AlarmKind>,
    val episodeGeneration: Long,
    val episodeAcknowledged: Boolean,
    val thresholdFingerprint: String,
    val resultingStateSha256: String,
    val appliedAtEpochMs: Long,
    val deliveryIds: List<String>,
) {
    init {
        require(effectId > 0)
        require(eventId.isNotBlank())
        require(SHA256.matches(approvalId))
        require(SHA256.matches(publicationBindingId))
        require(episodeGeneration >= 0)
        require(!episodeAcknowledged || activeKinds.isNotEmpty())
        require(SHA256.matches(thresholdFingerprint))
        require(SHA256.matches(resultingStateSha256))
        require(appliedAtEpochMs > 0)
        require(deliveryIds.isNotEmpty())
        require(deliveryIds.distinct().size == deliveryIds.size)
        require(deliveryIds.all(SHA256::matches))
    }

    private companion object {
        val SHA256 = Regex("^[0-9a-f]{64}$")
    }
}

sealed interface LocalAlarmApplyResult {
    data class Applied(val settlement: LocalAlarmSettlement) : LocalAlarmApplyResult
    data class AlreadyApplied(val settlement: LocalAlarmSettlement) : LocalAlarmApplyResult
    data class Conflict(val reason: String) : LocalAlarmApplyResult
}

sealed interface LocalAlarmSettlementReadResult {
    data class Exact(val settlement: LocalAlarmSettlement) : LocalAlarmSettlementReadResult
    data object Missing : LocalAlarmSettlementReadResult
    data class Conflict(val reason: String) : LocalAlarmSettlementReadResult
}

sealed interface LocalAlarmStateReadResult {
    data class Exact(val state: LocalAlarmStateRecord) : LocalAlarmStateReadResult
    data object Empty : LocalAlarmStateReadResult
    data class Conflict(val reason: String) : LocalAlarmStateReadResult
}

sealed interface LocalAlarmEpisodeAcknowledgeResult {
    data class Applied(val state: LocalAlarmStateRecord) : LocalAlarmEpisodeAcknowledgeResult
    data class AlreadyApplied(val state: LocalAlarmStateRecord) : LocalAlarmEpisodeAcknowledgeResult
    data class Stale(
        val currentEpisodeGeneration: Long,
        val currentStateSha256: String,
    ) : LocalAlarmEpisodeAcknowledgeResult
    data class Conflict(val reason: String) : LocalAlarmEpisodeAcknowledgeResult
}

data class LocalAlarmWatchdogSettlement(
    val watchdogId: String,
    val publicationBindingId: String,
    val approvalId: String,
    val sourceEffectId: Long,
    val sourceEventId: String,
    val expectedStateSha256: String,
    val resultingStateSha256: String,
    val activeKinds: Set<AlarmKind>,
    val episodeGeneration: Long,
    val episodeAcknowledged: Boolean,
    val appliedAtEpochMs: Long,
    val stateChanged: Boolean,
    val deliveryIds: List<String>,
) {
    init {
        require(SHA256.matches(watchdogId))
        require(SHA256.matches(publicationBindingId))
        require(SHA256.matches(approvalId))
        require(sourceEffectId >= MONITORING_START_EFFECT_ID)
        require(sourceEventId.isNotBlank())
        if (sourceEffectId == MONITORING_START_EFFECT_ID) require(SHA256.matches(sourceEventId))
        require(SHA256.matches(expectedStateSha256))
        require(SHA256.matches(resultingStateSha256))
        require(episodeGeneration >= 0L)
        require(!episodeAcknowledged || activeKinds.isNotEmpty())
        require(appliedAtEpochMs > 0L)
        require(deliveryIds.distinct().size == deliveryIds.size)
        require(deliveryIds.all(SHA256::matches))
        require(stateChanged || deliveryIds.isEmpty())
    }

    private companion object {
        val SHA256 = Regex("^[0-9a-f]{64}$")
    }
}

sealed interface LocalAlarmWatchdogResult {
    data class Applied(val settlement: LocalAlarmWatchdogSettlement) : LocalAlarmWatchdogResult
    data class AlreadyApplied(
        val settlement: LocalAlarmWatchdogSettlement,
    ) : LocalAlarmWatchdogResult
    data class Obsolete(val currentStateSha256: String) : LocalAlarmWatchdogResult
    data class Conflict(val reason: String) : LocalAlarmWatchdogResult
}

internal fun Set<AlarmKind>.toAlarmKindsWire(): String =
    sortedBy(AlarmKind::ordinal).joinToString(",", transform = AlarmKind::name)

internal fun String.toAlarmKinds(): Set<AlarmKind> = if (isEmpty()) {
    emptySet()
} else {
    split(',').mapTo(linkedSetOf(), AlarmKind::valueOf)
}

internal fun canonicalStateHash(
    publicationBindingId: String,
    approvalId: String,
    monitoringStartedAtEpochMs: Long,
    policyState: AlarmPolicyState,
    lastEffectId: Long,
    lastEventId: String,
    lastSequence: Long,
    thresholds: AlarmThresholdSnapshot,
    episodeGeneration: Long,
    episodeAcknowledged: Boolean,
    episodeAcknowledgedAtEpochMs: Long?,
    episodeOpenedAtEpochMs: Long?,
    updatedAtEpochMs: Long,
): String = listOf(
    "local-alarm-state-v1",
    publicationBindingId,
    approvalId,
    monitoringStartedAtEpochMs.toString(),
    policyState.active.toAlarmKindsWire(),
    policyState.latestFreshSensorTimeEpochMs.toString(),
    policyState.latestFreshPhoneTimeEpochMs.toString(),
    policyState.phoneClockMovedBackwards.toString(),
    lastEffectId.toString(),
    lastEventId,
    lastSequence.toString(),
    thresholds.fingerprint,
    episodeGeneration.toString(),
    episodeAcknowledged.toString(),
    episodeAcknowledgedAtEpochMs?.toString() ?: "-",
    episodeOpenedAtEpochMs?.toString() ?: "-",
    updatedAtEpochMs.toString(),
).canonicalSha256()

internal fun deterministicLocalAlarmDeliveryId(
    publicationBindingId: String,
    effectId: Long,
    eventId: String,
    episodeGeneration: Long,
    kind: LocalAlarmDeliveryKind,
): String = listOf(
    "local-alarm-delivery-v1",
    publicationBindingId,
    effectId.toString(),
    eventId,
    episodeGeneration.toString(),
    kind.wireName,
).canonicalSha256()

internal fun deterministicLocalAlarmWatchdogId(
    publicationBindingId: String,
    expectedStateSha256: String,
    nowEpochMs: Long,
): String = listOf(
    "local-alarm-watchdog-v1",
    publicationBindingId,
    expectedStateSha256,
    nowEpochMs.toString(),
).canonicalSha256()

internal fun deterministicLocalAlarmWatchdogDeliveryId(
    watchdogId: String,
    kind: LocalAlarmDeliveryKind,
): String = listOf(
    "local-alarm-watchdog-delivery-v1",
    watchdogId,
    kind.wireName,
).canonicalSha256()

internal fun deterministicLocalAlarmEpisodeAcknowledgementId(
    publicationBindingId: String,
    episodeGeneration: Long,
    acknowledgedAtEpochMs: Long,
): String = listOf(
    "local-alarm-episode-ack-v1",
    publicationBindingId,
    episodeGeneration.toString(),
    acknowledgedAtEpochMs.toString(),
).canonicalSha256()

internal fun deterministicLocalAlarmEpisodeAcknowledgementDeliveryId(
    acknowledgementId: String,
): String = listOf(
    "local-alarm-episode-ack-delivery-v1",
    acknowledgementId,
    LocalAlarmDeliveryKind.UPDATE.wireName,
).canonicalSha256()

internal fun Set<LocalAlarmDeliveryKind>.toLocalAlarmDeliveryKindsWire(): String =
    sortedBy(LocalAlarmDeliveryKind::order)
        .joinToString(",", transform = LocalAlarmDeliveryKind::wireName)

internal fun String.toLocalAlarmDeliveryKinds(): Set<LocalAlarmDeliveryKind> = if (isEmpty()) {
    emptySet()
} else {
    split(',').mapTo(linkedSetOf()) { wire ->
        LocalAlarmDeliveryKind.entries.first { it.wireName == wire }
    }
}

internal fun List<String>.canonicalSha256(): String = MessageDigest.getInstance("SHA-256")
    .digest(joinToString("\u0000").encodeToByteArray())
    .joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }
