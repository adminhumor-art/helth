package com.sladkaya.core.data

import com.sladkaya.core.model.AlarmPolicy

internal data class LocalAlarmReduction(
    val state: LocalAlarmStateRecord,
    val deliveries: List<LocalAlarmDeliveryDraft>,
)

internal object LocalAlarmReducer {
    fun reduce(
        previous: LocalAlarmStateRecord?,
        effect: LeasedLocalReadingEffect,
        request: LocalAlarmApplyRequest,
    ): LocalAlarmReduction {
        require(effect.effect.effectId == request.effectId)
        require(effect.effect.eventId == request.eventId)
        require(effect.effect.leaseToken == request.leaseToken)
        require(request.processedAtEpochMs < checkNotNull(effect.effect.leaseExpiresAtEpochMs))
        val canonicalPrevious = previous?.requireCanonical()
        if (canonicalPrevious == null) {
            require(request.expectedPreviousThresholdFingerprint == null)
        } else {
            require(canonicalPrevious.publicationBindingId == effect.effect.publicationBindingId)
            require(canonicalPrevious.approvalId == effect.effect.approvalId)
            require(canonicalPrevious.monitoringStartedAtEpochMs == request.monitoringStartedAtEpochMs)
            require(canonicalPrevious.lastEffectId < effect.effect.effectId)
            require(canonicalPrevious.lastEventId != effect.effect.eventId)
            require(canonicalPrevious.lastSequence < effect.reading.sequence)
            require(
                request.expectedPreviousThresholdFingerprint ==
                    canonicalPrevious.thresholds.fingerprint,
            )
        }

        val policy = AlarmPolicy(
            thresholds = request.thresholds.toModel(),
            monitoringStartedAtEpochMs = request.monitoringStartedAtEpochMs,
            initialState = canonicalPrevious?.policyState,
        )
        val readingChanges = policy.evaluate(effect.reading, request.processedAtEpochMs)
        val freshnessChanges = policy.evaluateFreshness(request.processedAtEpochMs)
        val opened = readingChanges.opened + freshnessChanges.opened
        val finalPolicyState = policy.snapshot()
        val previousActive = canonicalPrevious?.policyState?.active.orEmpty()
        val startsNewEpisode = finalPolicyState.active.isNotEmpty() &&
            (previousActive.isEmpty() || opened.isNotEmpty())
        val nextGeneration = when {
            startsNewEpisode -> Math.addExact(canonicalPrevious?.episodeGeneration ?: 0L, 1L)
            else -> canonicalPrevious?.episodeGeneration ?: 0L
        }
        val episodeAcknowledged = when {
            finalPolicyState.active.isEmpty() -> false
            startsNewEpisode -> false
            else -> canonicalPrevious?.episodeAcknowledged ?: false
        }
        val episodeOpenedAt = when {
            finalPolicyState.active.isEmpty() -> null
            startsNewEpisode -> request.processedAtEpochMs
            else -> canonicalPrevious?.episodeOpenedAtEpochMs
        }
        val episodeAcknowledgedAt = when {
            !episodeAcknowledged -> null
            else -> canonicalPrevious?.episodeAcknowledgedAtEpochMs
        }
        val state = LocalAlarmStateRecord(
            publicationBindingId = effect.effect.publicationBindingId,
            approvalId = effect.effect.approvalId,
            monitoringStartedAtEpochMs = request.monitoringStartedAtEpochMs,
            policyState = finalPolicyState,
            lastEffectId = effect.effect.effectId,
            lastEventId = effect.effect.eventId,
            lastSequence = effect.reading.sequence,
            thresholds = request.thresholds,
            episodeGeneration = nextGeneration,
            episodeAcknowledged = episodeAcknowledged,
            episodeAcknowledgedAtEpochMs = episodeAcknowledgedAt,
            episodeOpenedAtEpochMs = episodeOpenedAt,
            updatedAtEpochMs = request.processedAtEpochMs,
            stateSha256 = "",
        ).canonicalized()

        val changedActiveKinds = previousActive != finalPolicyState.active
        val transitionKinds = buildList {
            when {
                startsNewEpisode -> {
                    add(LocalAlarmDeliveryKind.SHOW)
                    add(LocalAlarmDeliveryKind.REPEAT)
                }
                previousActive.isNotEmpty() && finalPolicyState.active.isEmpty() ->
                    add(LocalAlarmDeliveryKind.CLOSE)
                changedActiveKinds -> add(LocalAlarmDeliveryKind.UPDATE)
            }
            add(LocalAlarmDeliveryKind.WATCHDOG)
            add(LocalAlarmDeliveryKind.WIDGET)
        }
        val watchdogAt = watchdogAt(state)
        val deliveries = transitionKinds.map { kind ->
            LocalAlarmDeliveryDraft(
                deliveryId = deliveryId(effect, state, kind),
                sourceEffectId = effect.effect.effectId,
                sourceEventId = effect.effect.eventId,
                approvalId = effect.effect.approvalId,
                publicationBindingId = effect.effect.publicationBindingId,
                kind = kind,
                activeKinds = state.policyState.active,
                episodeGeneration = state.episodeGeneration,
                episodeAcknowledged = state.episodeAcknowledged,
                resultingStateSha256 = state.stateSha256,
                createdAtEpochMs = request.processedAtEpochMs,
                notBeforeEpochMs = when (kind) {
                    LocalAlarmDeliveryKind.REPEAT -> safeAdd(
                        request.processedAtEpochMs,
                        request.repeatIntervalMs,
                    )
                    LocalAlarmDeliveryKind.WATCHDOG -> watchdogAt
                    else -> request.processedAtEpochMs
                },
            )
        }
        return LocalAlarmReduction(state, deliveries)
    }

    private fun deliveryId(
        effect: LeasedLocalReadingEffect,
        state: LocalAlarmStateRecord,
        kind: LocalAlarmDeliveryKind,
    ): String = deterministicLocalAlarmDeliveryId(
        publicationBindingId = effect.effect.publicationBindingId,
        effectId = effect.effect.effectId,
        eventId = effect.effect.eventId,
        episodeGeneration = state.episodeGeneration,
        kind = kind,
    )

    private fun watchdogAt(state: LocalAlarmStateRecord): Long {
        val policy = state.policyState
        val baseline = if (policy.latestFreshSensorTimeEpochMs == 0L) {
            state.monitoringStartedAtEpochMs
        } else {
            minOf(policy.latestFreshSensorTimeEpochMs, policy.latestFreshPhoneTimeEpochMs)
        }
        return safeAdd(baseline, state.thresholds.staleAfterMs)
    }

    private fun safeAdd(value: Long, delta: Long): Long =
        if (value > Long.MAX_VALUE - delta) Long.MAX_VALUE else value + delta
}
