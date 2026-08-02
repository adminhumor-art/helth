package com.sladkaya.core.data

import com.sladkaya.core.model.AlarmPolicyState

data class LocalAlarmMonitoringStartRequest(
    val publicationBindingId: String,
    val approvalId: String,
    val monitoringStartedAtEpochMs: Long,
    val approvedSequence: Long,
    val thresholds: AlarmThresholdSnapshot,
) {
    init {
        require(SHA256.matches(publicationBindingId))
        require(SHA256.matches(approvalId))
        require(monitoringStartedAtEpochMs > 0L)
        require(approvedSequence >= 0L)
    }

    private companion object {
        val SHA256 = Regex("^[0-9a-f]{64}$")
    }
}

data class LocalAlarmMonitoringStartRecord(
    val startId: String,
    val publicationBindingId: String,
    val approvalId: String,
    val monitoringStartedAtEpochMs: Long,
    val approvedSequence: Long,
    val thresholds: AlarmThresholdSnapshot,
    val thresholdFingerprint: String,
    val initialStateSha256: String,
    val watchdogDeliveryId: String,
    val watchdogDeadlineEpochMs: Long,
) {
    init {
        require(SHA256.matches(startId))
        require(SHA256.matches(publicationBindingId))
        require(SHA256.matches(approvalId))
        require(monitoringStartedAtEpochMs > 0L)
        require(approvedSequence >= 0L)
        require(SHA256.matches(thresholdFingerprint))
        require(thresholdFingerprint == thresholds.fingerprint)
        require(SHA256.matches(initialStateSha256))
        require(SHA256.matches(watchdogDeliveryId))
        require(watchdogDeadlineEpochMs > monitoringStartedAtEpochMs)
    }

    fun initialState(): LocalAlarmStateRecord = LocalAlarmStateRecord(
        publicationBindingId = publicationBindingId,
        approvalId = approvalId,
        monitoringStartedAtEpochMs = monitoringStartedAtEpochMs,
        policyState = AlarmPolicyState(
            active = emptySet(),
            latestFreshSensorTimeEpochMs = 0L,
            latestFreshPhoneTimeEpochMs = 0L,
            phoneClockMovedBackwards = false,
        ),
        lastEffectId = MONITORING_START_EFFECT_ID,
        lastEventId = startId,
        lastSequence = approvedSequence,
        thresholds = thresholds,
        episodeGeneration = 0L,
        episodeAcknowledged = false,
        episodeAcknowledgedAtEpochMs = null,
        episodeOpenedAtEpochMs = null,
        updatedAtEpochMs = monitoringStartedAtEpochMs,
        stateSha256 = "",
    ).canonicalized()

    fun requireCanonical(): LocalAlarmMonitoringStartRecord {
        require(
            startId == deterministicLocalAlarmMonitoringStartId(
                publicationBindingId = publicationBindingId,
                approvalId = approvalId,
                monitoringStartedAtEpochMs = monitoringStartedAtEpochMs,
                approvedSequence = approvedSequence,
                thresholdFingerprint = thresholdFingerprint,
            ),
        )
        require(initialState().stateSha256 == initialStateSha256)
        require(
            watchdogDeliveryId == deterministicLocalAlarmDeliveryId(
                publicationBindingId = publicationBindingId,
                effectId = MONITORING_START_EFFECT_ID,
                eventId = startId,
                episodeGeneration = 0L,
                kind = LocalAlarmDeliveryKind.WATCHDOG,
            ),
        )
        require(watchdogDeadlineEpochMs == safeAdd(monitoringStartedAtEpochMs, thresholds.staleAfterMs))
        return this
    }

    private companion object {
        val SHA256 = Regex("^[0-9a-f]{64}$")

        fun safeAdd(value: Long, delta: Long): Long =
            if (value > Long.MAX_VALUE - delta) Long.MAX_VALUE else value + delta
    }
}

internal data class LocalAlarmMonitoringStartReduction(
    val start: LocalAlarmMonitoringStartRecord,
    val state: LocalAlarmStateRecord,
    val deliveries: List<LocalAlarmDeliveryDraft>,
)

data class LocalAlarmMonitoringStartSettlement(
    val start: LocalAlarmMonitoringStartRecord,
    val state: LocalAlarmStateRecord,
    val watchdogDeadlineEpochMs: Long = start.watchdogDeadlineEpochMs,
) {
    init {
        require(watchdogDeadlineEpochMs > 0L)
        require(start.publicationBindingId == state.publicationBindingId)
        require(start.approvalId == state.approvalId)
    }
}

sealed interface LocalAlarmMonitoringStartResult {
    data class Initialized(val settlement: LocalAlarmMonitoringStartSettlement) :
        LocalAlarmMonitoringStartResult

    data class AlreadyInitialized(val settlement: LocalAlarmMonitoringStartSettlement) :
        LocalAlarmMonitoringStartResult

    data class Conflict(val reason: String) : LocalAlarmMonitoringStartResult
}

internal object LocalAlarmMonitoringStartRetryPolicy {
    fun restore(
        existing: LocalAlarmMonitoringStartRecord,
        currentState: LocalAlarmStateRecord,
        request: LocalAlarmMonitoringStartRequest,
    ): LocalAlarmMonitoringStartSettlement? = runCatching {
        val start = existing.requireCanonical()
        val state = currentState.requireCanonical()
        require(start.publicationBindingId == request.publicationBindingId)
        require(start.approvalId == request.approvalId)
        require(start.approvedSequence == request.approvedSequence)
        require(state.publicationBindingId == start.publicationBindingId)
        require(state.approvalId == start.approvalId)
        require(state.monitoringStartedAtEpochMs == start.monitoringStartedAtEpochMs)
        LocalAlarmMonitoringStartSettlement(start, state)
    }.getOrNull()
}

internal object LocalAlarmMonitoringStartReducer {
    fun reduce(request: LocalAlarmMonitoringStartRequest): LocalAlarmMonitoringStartReduction {
        val startId = deterministicLocalAlarmMonitoringStartId(request)
        val initialStart = LocalAlarmMonitoringStartRecord(
            startId = startId,
            publicationBindingId = request.publicationBindingId,
            approvalId = request.approvalId,
            monitoringStartedAtEpochMs = request.monitoringStartedAtEpochMs,
            approvedSequence = request.approvedSequence,
            thresholds = request.thresholds,
            thresholdFingerprint = request.thresholds.fingerprint,
            initialStateSha256 = "0".repeat(64),
            watchdogDeliveryId = "0".repeat(64),
            watchdogDeadlineEpochMs = safeAdd(
                request.monitoringStartedAtEpochMs,
                request.thresholds.staleAfterMs,
            ),
        )
        val state = initialStart.initialState()
        val watchdogDeliveryId = deterministicLocalAlarmDeliveryId(
            publicationBindingId = request.publicationBindingId,
            effectId = MONITORING_START_EFFECT_ID,
            eventId = startId,
            episodeGeneration = 0L,
            kind = LocalAlarmDeliveryKind.WATCHDOG,
        )
        val watchdog = LocalAlarmDeliveryDraft(
            deliveryId = watchdogDeliveryId,
            sourceEffectId = MONITORING_START_EFFECT_ID,
            sourceEventId = startId,
            approvalId = request.approvalId,
            publicationBindingId = request.publicationBindingId,
            kind = LocalAlarmDeliveryKind.WATCHDOG,
            activeKinds = emptySet(),
            episodeGeneration = 0L,
            episodeAcknowledged = false,
            resultingStateSha256 = state.stateSha256,
            createdAtEpochMs = request.monitoringStartedAtEpochMs,
            notBeforeEpochMs = safeAdd(
                request.monitoringStartedAtEpochMs,
                request.thresholds.staleAfterMs,
            ),
        )
        return LocalAlarmMonitoringStartReduction(
            start = initialStart.copy(
                initialStateSha256 = state.stateSha256,
                watchdogDeliveryId = watchdogDeliveryId,
                watchdogDeadlineEpochMs = watchdog.notBeforeEpochMs,
            ).requireCanonical(),
            state = state,
            deliveries = listOf(watchdog),
        )
    }

    private fun safeAdd(value: Long, delta: Long): Long =
        if (value > Long.MAX_VALUE - delta) Long.MAX_VALUE else value + delta
}

internal fun deterministicLocalAlarmMonitoringStartId(
    request: LocalAlarmMonitoringStartRequest,
): String = deterministicLocalAlarmMonitoringStartId(
    publicationBindingId = request.publicationBindingId,
    approvalId = request.approvalId,
    monitoringStartedAtEpochMs = request.monitoringStartedAtEpochMs,
    approvedSequence = request.approvedSequence,
    thresholdFingerprint = request.thresholds.fingerprint,
)

internal fun deterministicLocalAlarmMonitoringStartId(
    publicationBindingId: String,
    approvalId: String,
    monitoringStartedAtEpochMs: Long,
    approvedSequence: Long,
    thresholdFingerprint: String,
): String = listOf(
    "local-alarm-monitoring-start-v1",
    publicationBindingId,
    approvalId,
    monitoringStartedAtEpochMs.toString(),
    approvedSequence.toString(),
    thresholdFingerprint,
).canonicalSha256()

const val MONITORING_START_EFFECT_ID = 0L
