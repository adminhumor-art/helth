package com.sladkaya.app.service

import com.sladkaya.core.data.AlarmThresholdSnapshot
import com.sladkaya.core.data.LocalAlarmApplyRequest
import com.sladkaya.core.data.LocalAlarmApplyResult
import com.sladkaya.core.data.LocalAlarmEpisodeAcknowledgeResult
import com.sladkaya.core.data.LocalAlarmSettingsApplyRequest
import com.sladkaya.core.data.LocalAlarmSettingsApplyResult
import com.sladkaya.core.data.LocalAlarmSettingsSettlement
import com.sladkaya.core.data.LocalAlarmSettlementReadResult
import com.sladkaya.core.data.LocalAlarmStateReadResult
import com.sladkaya.core.data.LocalAlarmStateRecord
import com.sladkaya.core.data.LocalAlarmStore
import com.sladkaya.core.data.LocalAlarmWatchdogResult
import com.sladkaya.core.data.LocalReadingEffectLeaseResult
import com.sladkaya.core.model.AlarmPolicyState
import com.sladkaya.core.model.AlarmThresholds
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ProductAlarmSettingsReconcilerTest {
    @Test
    fun appliesSavedThresholdsAgainstTheExactCurrentState() = runBlocking {
        val old = state(AlarmThresholds())
        val desired = AlarmThresholdSnapshot.from(AlarmThresholds(lowMgDl = 80))
        val store = SettingsStore(
            states = ArrayDeque(listOf(old)),
            apply = { request -> LocalAlarmSettingsApplyResult.Applied(settlement(request)) },
        )

        val result = ProductAlarmSettingsReconciler(store) { NOW }
            .reconcile(BINDING, desired)

        assertTrue(result is ProductAlarmSettingsReconcileResult.Applied)
        assertEquals(old.stateSha256, store.requests.single().expectedStateSha256)
        assertEquals(desired, store.requests.single().thresholds)
    }

    @Test
    fun obsoleteConcurrentStateIsReadAgainAndRetriedWithoutChangingTheRequestedThresholds() =
        runBlocking {
            val first = state(AlarmThresholds())
            val concurrent = first.copy(
                updatedAtEpochMs = NOW - 1,
                stateSha256 = "",
            ).canonicalized()
            val desired = AlarmThresholdSnapshot.from(AlarmThresholds(highMgDl = 170))
            var calls = 0
            val store = SettingsStore(
                states = ArrayDeque(listOf(first, concurrent)),
                apply = { request ->
                    calls += 1
                    if (calls == 1) {
                        LocalAlarmSettingsApplyResult.Obsolete(concurrent.stateSha256)
                    } else {
                        LocalAlarmSettingsApplyResult.AlreadyApplied(settlement(request))
                    }
                },
            )

            val result = ProductAlarmSettingsReconciler(store) { NOW }
                .reconcile(BINDING, desired)

            assertTrue(result is ProductAlarmSettingsReconcileResult.Applied)
            assertEquals(2, store.requests.size)
            assertEquals(first.stateSha256, store.requests[0].expectedStateSha256)
            assertEquals(concurrent.stateSha256, store.requests[1].expectedStateSha256)
            assertTrue(store.requests.all { it.thresholds == desired })
        }

    @Test
    fun restartWithAlreadyCurrentThresholdsIsIdempotentAndDoesNotPublishAMeasurement() =
        runBlocking {
            val desired = AlarmThresholdSnapshot.from(AlarmThresholds(staleAfterMs = 5 * 60_000L))
            val current = state(desired.toModel())
            val store = SettingsStore(
                states = ArrayDeque(listOf(current)),
                apply = { error("must not mutate") },
            )

            val result = ProductAlarmSettingsReconciler(store) { NOW }
                .reconcile(BINDING, desired)

            assertEquals(ProductAlarmSettingsReconcileResult.Current(current), result)
            assertTrue(store.requests.isEmpty())
        }

    private fun settlement(request: LocalAlarmSettingsApplyRequest) =
        LocalAlarmSettingsSettlement(
            operationId = request.operationId,
            publicationBindingId = BINDING,
            approvalId = APPROVAL,
            sourceEffectId = 1L,
            sourceEventId = "reading",
            expectedStateSha256 = request.expectedStateSha256,
            resultingStateSha256 = "44".repeat(32),
            thresholdFingerprint = request.thresholds.fingerprint,
            activeKinds = emptySet(),
            episodeGeneration = 0L,
            episodeAcknowledged = false,
            appliedAtEpochMs = request.appliedAtEpochMs,
            stateChanged = true,
            deliveryIds = listOf("55".repeat(32)),
        )

    private fun state(thresholds: AlarmThresholds) = LocalAlarmStateRecord(
        publicationBindingId = BINDING,
        approvalId = APPROVAL,
        monitoringStartedAtEpochMs = NOW - 60_000L,
        policyState = AlarmPolicyState(
            latestFreshSensorTimeEpochMs = NOW - 1_000L,
            latestFreshPhoneTimeEpochMs = NOW - 1_000L,
        ),
        lastEffectId = 1L,
        lastEventId = "reading",
        lastSequence = 1L,
        thresholds = AlarmThresholdSnapshot.from(thresholds),
        episodeGeneration = 0L,
        episodeAcknowledged = false,
        episodeOpenedAtEpochMs = null,
        updatedAtEpochMs = NOW - 1_000L,
        stateSha256 = "",
    ).canonicalized()

    private companion object {
        const val NOW = 1_800_000_000_000L
        val BINDING = "11".repeat(32)
        val APPROVAL = "22".repeat(32)
    }
}

private class SettingsStore(
    private val states: ArrayDeque<LocalAlarmStateRecord>,
    private val apply: (LocalAlarmSettingsApplyRequest) -> LocalAlarmSettingsApplyResult,
) : LocalAlarmStore {
    val requests = mutableListOf<LocalAlarmSettingsApplyRequest>()

    override suspend fun readState(publicationBindingId: String): LocalAlarmStateReadResult =
        LocalAlarmStateReadResult.Exact(states.removeFirst())

    override suspend fun applySettings(
        request: LocalAlarmSettingsApplyRequest,
    ): LocalAlarmSettingsApplyResult {
        requests += request
        return apply(request)
    }

    override suspend fun leaseEarliest(
        nowEpochMs: Long,
        leaseToken: String,
        leaseExpiresAtEpochMs: Long,
    ): LocalReadingEffectLeaseResult = error("not used")

    override suspend fun applyLeased(request: LocalAlarmApplyRequest): LocalAlarmApplyResult =
        error("not used")

    override suspend fun readSettlement(
        eventId: String,
        approvalId: String,
        publicationBindingId: String,
    ): LocalAlarmSettlementReadResult = error("not used")

    override suspend fun acknowledgeEpisode(
        publicationBindingId: String,
        expectedEpisodeGeneration: Long,
        acknowledgedAtEpochMs: Long,
    ): LocalAlarmEpisodeAcknowledgeResult = error("not used")

    override suspend fun applyWatchdog(
        publicationBindingId: String,
        expectedStateSha256: String,
        nowEpochMs: Long,
    ): LocalAlarmWatchdogResult = error("not used")
}
