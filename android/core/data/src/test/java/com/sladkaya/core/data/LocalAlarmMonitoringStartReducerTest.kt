package com.sladkaya.core.data

import com.sladkaya.core.model.AlarmKind
import com.sladkaya.core.model.AlarmThresholds
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Test

class LocalAlarmMonitoringStartReducerTest {
    @Test
    fun firstProductStartCreatesOnlyDurableSignalLossDeadlineWithoutFakeReading() {
        val thresholds = AlarmThresholdSnapshot.from(AlarmThresholds(staleAfterMs = STALE_AFTER_MS))
        val request = LocalAlarmMonitoringStartRequest(
            publicationBindingId = BINDING_ID,
            approvalId = APPROVAL_ID,
            monitoringStartedAtEpochMs = STARTED_AT,
            approvedSequence = APPROVED_SEQUENCE,
            thresholds = thresholds,
        )

        val reduction = LocalAlarmMonitoringStartReducer.reduce(request)

        assertEquals(emptySet<Any>(), reduction.state.policyState.active)
        assertEquals(0L, reduction.state.lastEffectId)
        assertEquals(APPROVED_SEQUENCE, reduction.state.lastSequence)
        assertEquals(reduction.start.startId, reduction.state.lastEventId)
        assertEquals(reduction.state.stateSha256, reduction.start.initialStateSha256)
        assertEquals(1, reduction.deliveries.size)
        val watchdog = reduction.deliveries.single()
        assertEquals(LocalAlarmDeliveryKind.WATCHDOG, watchdog.kind)
        assertEquals(0L, watchdog.sourceEffectId)
        assertEquals(reduction.start.startId, watchdog.sourceEventId)
        assertEquals(STARTED_AT + STALE_AFTER_MS, watchdog.notBeforeEpochMs)
        assertFalse(watchdog.activeKinds.isNotEmpty())
    }

    @Test
    fun monitoringStartIdentityChangesWhenExactApprovedLineageChanges() {
        val base = request()

        val first = LocalAlarmMonitoringStartReducer.reduce(base)
        val changedApproval = LocalAlarmMonitoringStartReducer.reduce(
            base.copy(approvalId = "c".repeat(64)),
        )
        val changedStart = LocalAlarmMonitoringStartReducer.reduce(
            base.copy(monitoringStartedAtEpochMs = STARTED_AT + 1L),
        )

        assertFalse(first.start.startId == changedApproval.start.startId)
        assertFalse(first.start.startId == changedStart.start.startId)
    }

    @Test
    fun processRestartRestoresOriginalDeadlineInsteadOfMovingItForward() {
        val first = LocalAlarmMonitoringStartReducer.reduce(request())
        val muchLaterRetry = request().copy(
            monitoringStartedAtEpochMs = STARTED_AT + 24 * 60 * 60_000L,
            thresholds = AlarmThresholdSnapshot.from(
                AlarmThresholds(staleAfterMs = STALE_AFTER_MS * 2),
            ),
        )

        val restored = requireNotNull(
            LocalAlarmMonitoringStartRetryPolicy.restore(
                existing = first.start,
                currentState = first.state,
                request = muchLaterRetry,
            ),
        )

        assertEquals(STARTED_AT, restored.start.monitoringStartedAtEpochMs)
        assertEquals(STARTED_AT + STALE_AFTER_MS, restored.watchdogDeadlineEpochMs)
        assertEquals(first.start.startId, restored.start.startId)
    }

    @Test
    fun reservedZeroSourceRequiresTheExactHashedMonitoringAnchor() {
        val reduction = LocalAlarmMonitoringStartReducer.reduce(request())

        LocalAlarmWatchdogSettlement(
            watchdogId = "d".repeat(64),
            publicationBindingId = BINDING_ID,
            approvalId = APPROVAL_ID,
            sourceEffectId = MONITORING_START_EFFECT_ID,
            sourceEventId = reduction.start.startId,
            expectedStateSha256 = reduction.state.stateSha256,
            resultingStateSha256 = "e".repeat(64),
            activeKinds = setOf(com.sladkaya.core.model.AlarmKind.SIGNAL_LOSS),
            episodeGeneration = 1L,
            episodeAcknowledged = false,
            appliedAtEpochMs = reduction.start.watchdogDeadlineEpochMs,
            stateChanged = true,
            deliveryIds = listOf("f".repeat(64)),
        )
        assertThrows(IllegalArgumentException::class.java) {
            LocalAlarmWatchdogSettlement(
                watchdogId = "d".repeat(64),
                publicationBindingId = BINDING_ID,
                approvalId = APPROVAL_ID,
                sourceEffectId = MONITORING_START_EFFECT_ID,
                sourceEventId = "not-a-monitoring-anchor",
                expectedStateSha256 = reduction.state.stateSha256,
                resultingStateSha256 = "e".repeat(64),
                activeKinds = setOf(com.sladkaya.core.model.AlarmKind.SIGNAL_LOSS),
                episodeGeneration = 1L,
                episodeAcknowledged = false,
                appliedAtEpochMs = reduction.start.watchdogDeadlineEpochMs,
                stateChanged = true,
                deliveryIds = listOf("f".repeat(64)),
            )
        }
    }

    @Test
    fun monitoringAnchorCannotClaimGlucoseAlarmsOrFreshMeasurementTimestamps() {
        val initial = LocalAlarmMonitoringStartReducer.reduce(request()).state

        assertThrows(IllegalArgumentException::class.java) {
            initial.copy(
                policyState = initial.policyState.copy(active = setOf(AlarmKind.LOW)),
                stateSha256 = "",
            ).canonicalized()
        }
        assertThrows(IllegalArgumentException::class.java) {
            initial.copy(
                policyState = initial.policyState.copy(
                    latestFreshSensorTimeEpochMs = STARTED_AT,
                    latestFreshPhoneTimeEpochMs = STARTED_AT,
                ),
                stateSha256 = "",
            ).canonicalized()
        }
    }

    private fun request() = LocalAlarmMonitoringStartRequest(
        publicationBindingId = BINDING_ID,
        approvalId = APPROVAL_ID,
        monitoringStartedAtEpochMs = STARTED_AT,
        approvedSequence = APPROVED_SEQUENCE,
        thresholds = AlarmThresholdSnapshot.from(AlarmThresholds(staleAfterMs = STALE_AFTER_MS)),
    )

    private companion object {
        val BINDING_ID = "a".repeat(64)
        val APPROVAL_ID = "b".repeat(64)
        const val STARTED_AT = 1_700_000_000_000L
        const val APPROVED_SEQUENCE = 41L
        const val STALE_AFTER_MS = 10 * 60_000L
    }
}
