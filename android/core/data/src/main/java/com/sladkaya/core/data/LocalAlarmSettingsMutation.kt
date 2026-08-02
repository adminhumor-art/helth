package com.sladkaya.core.data

import com.sladkaya.core.model.AlarmKind
import com.sladkaya.core.model.AlarmPolicy
import com.sladkaya.core.model.GlucoseReading

data class LocalAlarmSettingsApplyRequest(
    val publicationBindingId: String,
    val expectedStateSha256: String,
    val thresholds: AlarmThresholdSnapshot,
    val appliedAtEpochMs: Long,
    val repeatIntervalMs: Long = 120_000L,
) {
    init {
        require(SHA256.matches(publicationBindingId))
        require(SHA256.matches(expectedStateSha256))
        require(appliedAtEpochMs > 0L)
        require(repeatIntervalMs in MIN_REPEAT_INTERVAL_MS..MAX_REPEAT_INTERVAL_MS)
    }

    val operationId: String
        get() = deterministicLocalAlarmSettingsOperationId(this)

    private companion object {
        const val MIN_REPEAT_INTERVAL_MS = 30_000L
        const val MAX_REPEAT_INTERVAL_MS = 60 * 60_000L
        val SHA256 = Regex("^[0-9a-f]{64}$")
    }
}

data class LocalAlarmSettingsSettlement(
    val operationId: String,
    val publicationBindingId: String,
    val approvalId: String,
    val sourceEffectId: Long,
    val sourceEventId: String,
    val expectedStateSha256: String,
    val resultingStateSha256: String,
    val thresholdFingerprint: String,
    val activeKinds: Set<AlarmKind>,
    val episodeGeneration: Long,
    val episodeAcknowledged: Boolean,
    val appliedAtEpochMs: Long,
    val stateChanged: Boolean,
    val deliveryIds: List<String>,
) {
    init {
        require(SHA256.matches(operationId))
        require(SHA256.matches(publicationBindingId))
        require(SHA256.matches(approvalId))
        require(sourceEffectId >= MONITORING_START_EFFECT_ID)
        require(sourceEventId.isNotBlank())
        if (sourceEffectId == MONITORING_START_EFFECT_ID) require(SHA256.matches(sourceEventId))
        require(SHA256.matches(expectedStateSha256))
        require(SHA256.matches(resultingStateSha256))
        require(SHA256.matches(thresholdFingerprint))
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

sealed interface LocalAlarmSettingsApplyResult {
    data class Applied(val settlement: LocalAlarmSettingsSettlement) :
        LocalAlarmSettingsApplyResult

    data class AlreadyApplied(val settlement: LocalAlarmSettingsSettlement) :
        LocalAlarmSettingsApplyResult

    data class Obsolete(val currentStateSha256: String) : LocalAlarmSettingsApplyResult
    data class Conflict(val reason: String) : LocalAlarmSettingsApplyResult
}

internal data class LocalAlarmSettingsReduction(
    val state: LocalAlarmStateRecord,
    val deliveries: List<LocalAlarmDeliveryDraft>,
    val stateChanged: Boolean,
)

internal object LocalAlarmSettingsReducer {
    fun reduce(
        previous: LocalAlarmStateRecord,
        latestVerifiedReading: GlucoseReading?,
        request: LocalAlarmSettingsApplyRequest,
    ): LocalAlarmSettingsReduction {
        val canonicalPrevious = previous.requireCanonical()
        require(request.publicationBindingId == canonicalPrevious.publicationBindingId)
        require(request.expectedStateSha256 == canonicalPrevious.stateSha256)
        require(request.appliedAtEpochMs >= canonicalPrevious.updatedAtEpochMs)
        require(
            (canonicalPrevious.lastEffectId == MONITORING_START_EFFECT_ID) ==
                (latestVerifiedReading == null),
        )
        latestVerifiedReading?.let { reading ->
            require(reading.eventId == canonicalPrevious.lastEventId)
            require(reading.sequence == canonicalPrevious.lastSequence)
        }

        if (canonicalPrevious.thresholds.fingerprint == request.thresholds.fingerprint) {
            return LocalAlarmSettingsReduction(
                state = canonicalPrevious,
                deliveries = emptyList(),
                stateChanged = false,
            )
        }

        val previousActive = canonicalPrevious.policyState.active
        val valuePolicy = AlarmPolicy(
            thresholds = request.thresholds.toModel(),
            monitoringStartedAtEpochMs = canonicalPrevious.monitoringStartedAtEpochMs,
            initiallyOpen = previousActive,
        )
        latestVerifiedReading?.let { valuePolicy.evaluate(it, request.appliedAtEpochMs) }
        val valueState = valuePolicy.snapshot()
        val freshnessBaseline = if (valueState.latestFreshSensorTimeEpochMs != 0L) {
            valueState
        } else {
            canonicalPrevious.policyState.copy(active = valueState.active)
        }
        val policy = AlarmPolicy(
            thresholds = request.thresholds.toModel(),
            monitoringStartedAtEpochMs = canonicalPrevious.monitoringStartedAtEpochMs,
            initialState = freshnessBaseline,
        )
        val freshnessChanges = policy.evaluateFreshness(request.appliedAtEpochMs)
        val nextPolicyState = policy.snapshot()
        val opened = nextPolicyState.active - previousActive
        val closed = previousActive - nextPolicyState.active
        val startsNewEpisode = nextPolicyState.active.isNotEmpty() &&
            (previousActive.isEmpty() || opened.isNotEmpty())
        val nextGeneration = if (startsNewEpisode) {
            Math.addExact(canonicalPrevious.episodeGeneration, 1L)
        } else {
            canonicalPrevious.episodeGeneration
        }
        val episodeAcknowledged = when {
            nextPolicyState.active.isEmpty() -> false
            startsNewEpisode -> false
            else -> canonicalPrevious.episodeAcknowledged
        }
        val episodeOpenedAtEpochMs = when {
            nextPolicyState.active.isEmpty() -> null
            startsNewEpisode -> request.appliedAtEpochMs
            else -> canonicalPrevious.episodeOpenedAtEpochMs
        }
        val episodeAcknowledgedAtEpochMs = if (episodeAcknowledged) {
            canonicalPrevious.episodeAcknowledgedAtEpochMs
        } else {
            null
        }
        val state = canonicalPrevious.copy(
            policyState = nextPolicyState,
            thresholds = request.thresholds,
            episodeGeneration = nextGeneration,
            episodeAcknowledged = episodeAcknowledged,
            episodeAcknowledgedAtEpochMs = episodeAcknowledgedAtEpochMs,
            episodeOpenedAtEpochMs = episodeOpenedAtEpochMs,
            updatedAtEpochMs = request.appliedAtEpochMs,
            stateSha256 = "",
        ).canonicalized()
        val changedActiveKinds = previousActive != nextPolicyState.active
        val transitionKinds = buildList {
            when {
                startsNewEpisode -> add(LocalAlarmDeliveryKind.SHOW)
                previousActive.isNotEmpty() && nextPolicyState.active.isEmpty() ->
                    add(LocalAlarmDeliveryKind.CLOSE)
                changedActiveKinds -> add(LocalAlarmDeliveryKind.UPDATE)
            }
            if (nextPolicyState.active.isNotEmpty() && !episodeAcknowledged) {
                add(LocalAlarmDeliveryKind.REPEAT)
            }
            add(LocalAlarmDeliveryKind.WATCHDOG)
            add(LocalAlarmDeliveryKind.WIDGET)
        }
        val operationId = request.operationId
        val watchdogDeadline = watchdogDeadline(state)
        val deliveries = transitionKinds.map { kind ->
            LocalAlarmDeliveryDraft(
                deliveryId = deterministicLocalAlarmSettingsDeliveryId(operationId, kind),
                sourceEffectId = state.lastEffectId,
                sourceEventId = state.lastEventId,
                approvalId = state.approvalId,
                publicationBindingId = state.publicationBindingId,
                kind = kind,
                activeKinds = state.policyState.active,
                episodeGeneration = state.episodeGeneration,
                episodeAcknowledged = state.episodeAcknowledged,
                resultingStateSha256 = state.stateSha256,
                createdAtEpochMs = request.appliedAtEpochMs,
                notBeforeEpochMs = when (kind) {
                    LocalAlarmDeliveryKind.REPEAT -> safeAdd(
                        request.appliedAtEpochMs,
                        request.repeatIntervalMs,
                    )
                    LocalAlarmDeliveryKind.WATCHDOG -> watchdogDeadline
                    else -> request.appliedAtEpochMs
                },
            )
        }
        // Keep the explicit calculation in the reducer: settings changes may open freshness
        // even when no reading arrives, and may independently open/close value alarms.
        require(freshnessChanges.active == nextPolicyState.active)
        require(opened.isNotEmpty() || closed.isNotEmpty() || state.stateSha256 != previous.stateSha256)
        return LocalAlarmSettingsReduction(state, deliveries, stateChanged = true)
    }

    private fun watchdogDeadline(state: LocalAlarmStateRecord): Long {
        val policy = state.policyState
        val baseline = if (policy.latestFreshSensorTimeEpochMs == 0L) {
            state.monitoringStartedAtEpochMs
        } else {
            minOf(policy.latestFreshSensorTimeEpochMs, policy.latestFreshPhoneTimeEpochMs)
        }
        return safeAdd(baseline, state.thresholds.staleAfterMs)
    }
}

internal fun deterministicLocalAlarmSettingsOperationId(
    request: LocalAlarmSettingsApplyRequest,
): String = listOf(
    "local-alarm-settings-operation-v1",
    request.publicationBindingId,
    request.expectedStateSha256,
    request.thresholds.fingerprint,
    request.appliedAtEpochMs.toString(),
    request.repeatIntervalMs.toString(),
).canonicalSha256()

internal fun deterministicLocalAlarmSettingsDeliveryId(
    operationId: String,
    kind: LocalAlarmDeliveryKind,
): String = listOf(
    "local-alarm-settings-delivery-v1",
    operationId,
    kind.wireName,
).canonicalSha256()

private fun safeAdd(value: Long, delta: Long): Long =
    if (value > Long.MAX_VALUE - delta) Long.MAX_VALUE else value + delta
