package com.sladkaya.core.data

import com.sladkaya.core.model.AlarmPolicy

internal data class LocalAlarmWatchdogReduction(
    val state: LocalAlarmStateRecord,
    val deliveryKinds: Set<LocalAlarmDeliveryKind>,
    val changed: Boolean,
)

internal object LocalAlarmWatchdogReducer {
    fun reduce(
        previous: LocalAlarmStateRecord,
        nowEpochMs: Long,
    ): LocalAlarmWatchdogReduction {
        require(nowEpochMs > 0L)
        val canonicalPrevious = previous.requireCanonical()
        require(nowEpochMs >= canonicalPrevious.updatedAtEpochMs)

        val policy = AlarmPolicy(
            thresholds = canonicalPrevious.thresholds.toModel(),
            monitoringStartedAtEpochMs = canonicalPrevious.monitoringStartedAtEpochMs,
            initialState = canonicalPrevious.policyState,
        )
        val changes = policy.evaluateFreshness(nowEpochMs)
        val nextPolicyState = policy.snapshot()
        if (nextPolicyState == canonicalPrevious.policyState) {
            return LocalAlarmWatchdogReduction(
                state = canonicalPrevious,
                deliveryKinds = emptySet(),
                changed = false,
            )
        }

        val startsNewEpisode = nextPolicyState.active.isNotEmpty() && changes.opened.isNotEmpty()
        val episodeGeneration = if (startsNewEpisode) {
            Math.addExact(canonicalPrevious.episodeGeneration, 1L)
        } else {
            canonicalPrevious.episodeGeneration
        }
        val episodeAcknowledged = when {
            nextPolicyState.active.isEmpty() -> false
            startsNewEpisode -> false
            else -> canonicalPrevious.episodeAcknowledged
        }
        val episodeAcknowledgedAtEpochMs = when {
            !episodeAcknowledged -> null
            else -> canonicalPrevious.episodeAcknowledgedAtEpochMs
        }
        val episodeOpenedAtEpochMs = when {
            nextPolicyState.active.isEmpty() -> null
            startsNewEpisode -> nowEpochMs
            else -> canonicalPrevious.episodeOpenedAtEpochMs
        }
        val state = canonicalPrevious.copy(
            policyState = nextPolicyState,
            episodeGeneration = episodeGeneration,
            episodeAcknowledged = episodeAcknowledged,
            episodeAcknowledgedAtEpochMs = episodeAcknowledgedAtEpochMs,
            episodeOpenedAtEpochMs = episodeOpenedAtEpochMs,
            updatedAtEpochMs = nowEpochMs,
            stateSha256 = "",
        ).canonicalized()
        val deliveryKinds = when {
            startsNewEpisode -> linkedSetOf(
                LocalAlarmDeliveryKind.SHOW,
                LocalAlarmDeliveryKind.REPEAT,
                LocalAlarmDeliveryKind.WIDGET,
            )
            changes.closed.isNotEmpty() && nextPolicyState.active.isEmpty() -> linkedSetOf(
                LocalAlarmDeliveryKind.CLOSE,
                LocalAlarmDeliveryKind.WIDGET,
            )
            changes.opened.isNotEmpty() || changes.closed.isNotEmpty() -> linkedSetOf(
                LocalAlarmDeliveryKind.UPDATE,
                LocalAlarmDeliveryKind.WIDGET,
            )
            else -> linkedSetOf(LocalAlarmDeliveryKind.WIDGET)
        }
        return LocalAlarmWatchdogReduction(
            state = state,
            deliveryKinds = deliveryKinds,
            changed = true,
        )
    }
}

internal object LocalAlarmWatchdogDeliveryPlan {
    fun notBeforeEpochMs(
        kind: LocalAlarmDeliveryKind,
        appliedAtEpochMs: Long,
    ): Long {
        require(appliedAtEpochMs > 0L)
        return if (kind == LocalAlarmDeliveryKind.REPEAT) {
            if (appliedAtEpochMs > Long.MAX_VALUE - REPEAT_INTERVAL_MS) {
                Long.MAX_VALUE
            } else {
                appliedAtEpochMs + REPEAT_INTERVAL_MS
            }
        } else {
            appliedAtEpochMs
        }
    }

    private const val REPEAT_INTERVAL_MS = 120_000L
}
