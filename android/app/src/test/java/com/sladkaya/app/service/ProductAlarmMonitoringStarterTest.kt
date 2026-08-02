package com.sladkaya.app.service

import com.sladkaya.core.data.AlarmThresholdSnapshot
import com.sladkaya.core.data.LocalAlarmApplyRequest
import com.sladkaya.core.data.LocalAlarmApplyResult
import com.sladkaya.core.data.LocalAlarmEpisodeAcknowledgeResult
import com.sladkaya.core.data.LocalAlarmMonitoringStartRecord
import com.sladkaya.core.data.LocalAlarmMonitoringStartRequest
import com.sladkaya.core.data.LocalAlarmMonitoringStartResult
import com.sladkaya.core.data.LocalAlarmMonitoringStartSettlement
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
import com.sladkaya.core.model.SensorFamily
import com.sladkaya.sensor.sibionics.Gs1DiagnosticActivationProfile
import com.sladkaya.sensor.sibionics.Gs1DiagnosticActivationProfileValidation
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ProductAlarmMonitoringStarterTest {
    @Test
    fun armPersistsExactApprovedLineageAndSchedulesTheDurableDeadline() = runBlocking {
        val thresholds = AlarmThresholds(
            lowMgDl = 71,
            highMgDl = 181,
            staleAfterMs = STALE_AFTER_MS,
        )
        val store = CapturingMonitoringStore { request ->
            LocalAlarmMonitoringStartResult.Initialized(settlement(request))
        }
        var drainCalls = 0
        val starter = RoomProductAlarmMonitoringStarter(
            store = store,
            thresholds = { thresholds },
            nowEpochMs = { NOW },
            drain = ProductLocalDeliveryDrain {
                drainCalls += 1
                ProductLocalDeliveryRunResult.Waiting(0, NOW + STALE_AFTER_MS)
            },
        )

        assertEquals(ProductAlarmMonitoringStartResult.Armed, starter.arm(configuration()))

        val request = requireNotNull(store.request)
        assertEquals(BINDING, request.publicationBindingId)
        assertEquals(APPROVAL, request.approvalId)
        assertEquals(APPROVED_SEQUENCE, request.approvedSequence)
        assertEquals(NOW, request.monitoringStartedAtEpochMs)
        assertEquals(AlarmThresholdSnapshot.from(thresholds), request.thresholds)
        assertEquals(1, drainCalls)
    }

    @Test
    fun durableStartConflictFailsClosedWithoutSchedulingOrStartingBluetooth() = runBlocking {
        val store = CapturingMonitoringStore {
            LocalAlarmMonitoringStartResult.Conflict("lineage mismatch")
        }
        var drainCalls = 0
        val starter = RoomProductAlarmMonitoringStarter(
            store = store,
            thresholds = { AlarmThresholds() },
            nowEpochMs = { NOW },
            drain = ProductLocalDeliveryDrain {
                drainCalls += 1
                ProductLocalDeliveryRunResult.Drained(0)
            },
        )

        assertEquals(
            ProductAlarmMonitoringStartResult.Conflict("lineage mismatch"),
            starter.arm(configuration()),
        )
        assertTrue(store.request != null)
        assertEquals(0, drainCalls)
    }

    @Test
    fun wakeSchedulingFailureFailsClosedAfterTheDurableAnchorWasCreated() = runBlocking {
        val store = CapturingMonitoringStore { request ->
            LocalAlarmMonitoringStartResult.AlreadyInitialized(settlement(request))
        }
        val starter = RoomProductAlarmMonitoringStarter(
            store = store,
            thresholds = { AlarmThresholds() },
            nowEpochMs = { NOW },
            drain = ProductLocalDeliveryDrain {
                ProductLocalDeliveryRunResult.TransientFailure(
                    processed = 0,
                    retryAtEpochMs = NOW + 1_000L,
                )
            },
        )

        val result = starter.arm(configuration())

        assertTrue(result is ProductAlarmMonitoringStartResult.StorageUnavailable)
        assertTrue(store.request != null)
    }

    @Test
    fun restartReconcilesSavedThresholdsDurablyBeforeDrainingAndBeforeBluetoothCanStart() =
        runBlocking {
            val desired = AlarmThresholds(staleAfterMs = 5 * 60_000L)
            val old = AlarmThresholds(staleAfterMs = 30 * 60_000L)
            val events = mutableListOf<String>()
            val store = CapturingMonitoringStore(
                initializeResult = { request ->
                    LocalAlarmMonitoringStartResult.AlreadyInitialized(
                        settlement(
                            request.copy(thresholds = AlarmThresholdSnapshot.from(old)),
                        ),
                    )
                },
                settingsResult = { request, state ->
                    events += "settings"
                    LocalAlarmSettingsApplyResult.Applied(
                        LocalAlarmSettingsSettlement(
                            operationId = request.operationId,
                            publicationBindingId = request.publicationBindingId,
                            approvalId = state.approvalId,
                            sourceEffectId = state.lastEffectId,
                            sourceEventId = state.lastEventId,
                            expectedStateSha256 = state.stateSha256,
                            resultingStateSha256 = "55".repeat(32),
                            thresholdFingerprint = request.thresholds.fingerprint,
                            activeKinds = emptySet(),
                            episodeGeneration = 0L,
                            episodeAcknowledged = false,
                            appliedAtEpochMs = request.appliedAtEpochMs,
                            stateChanged = true,
                            deliveryIds = listOf("66".repeat(32)),
                        ),
                    )
                },
            )
            val starter = RoomProductAlarmMonitoringStarter(
                store = store,
                thresholds = { desired },
                nowEpochMs = { NOW },
                drain = ProductLocalDeliveryDrain {
                    events += "drain"
                    ProductLocalDeliveryRunResult.Drained(1)
                },
            )

            assertEquals(ProductAlarmMonitoringStartResult.Armed, starter.arm(configuration()))
            assertEquals(listOf("settings", "drain"), events)
            assertEquals(
                AlarmThresholdSnapshot.from(desired),
                store.settingsRequests.single().thresholds,
            )
        }

    private fun settlement(
        request: LocalAlarmMonitoringStartRequest,
    ): LocalAlarmMonitoringStartSettlement {
        val startId = "33".repeat(32)
        val state = LocalAlarmStateRecord(
            publicationBindingId = request.publicationBindingId,
            approvalId = request.approvalId,
            monitoringStartedAtEpochMs = request.monitoringStartedAtEpochMs,
            policyState = AlarmPolicyState(),
            lastEffectId = 0L,
            lastEventId = startId,
            lastSequence = request.approvedSequence,
            thresholds = request.thresholds,
            episodeGeneration = 0L,
            episodeAcknowledged = false,
            episodeOpenedAtEpochMs = null,
            updatedAtEpochMs = request.monitoringStartedAtEpochMs,
            stateSha256 = "",
        ).canonicalized()
        val start = LocalAlarmMonitoringStartRecord(
            startId = startId,
            publicationBindingId = request.publicationBindingId,
            approvalId = request.approvalId,
            monitoringStartedAtEpochMs = request.monitoringStartedAtEpochMs,
            approvedSequence = request.approvedSequence,
            thresholds = request.thresholds,
            thresholdFingerprint = request.thresholds.fingerprint,
            initialStateSha256 = state.stateSha256,
            watchdogDeliveryId = "44".repeat(32),
            watchdogDeadlineEpochMs = request.monitoringStartedAtEpochMs +
                request.thresholds.staleAfterMs,
        )
        return LocalAlarmMonitoringStartSettlement(start, state)
    }

    private fun configuration(): ProductSensorConfiguration = ProductSensorConfiguration(
        profile = profile(),
        approvalId = APPROVAL,
        publicationBindingId = BINDING,
        approvedSequence = APPROVED_SEQUENCE,
    )

    private fun profile(): Gs1DiagnosticActivationProfile =
        (Gs1DiagnosticActivationProfile.validate(
            sensorId = "approved-sensor",
            family = SensorFamily.SIBIONICS_GS1,
            bluetoothAddress = "AA:BB:CC:DD:EE:33",
            transportVariant = 2,
            packageCode = "Ab12Cd34",
        ) as Gs1DiagnosticActivationProfileValidation.Valid).profile

    private companion object {
        const val NOW = 1_700_000_100_000L
        const val STALE_AFTER_MS = 600_000L
        const val APPROVED_SEQUENCE = 41L
        val APPROVAL = "11".repeat(32)
        val BINDING = "22".repeat(32)
    }
}

private class CapturingMonitoringStore(
    private val settingsResult: ((LocalAlarmSettingsApplyRequest, LocalAlarmStateRecord) ->
        LocalAlarmSettingsApplyResult)? = null,
    private val initializeResult: (LocalAlarmMonitoringStartRequest) ->
        LocalAlarmMonitoringStartResult,
) : LocalAlarmStore {
    var request: LocalAlarmMonitoringStartRequest? = null
    private var state: LocalAlarmStateRecord? = null
    val settingsRequests = mutableListOf<LocalAlarmSettingsApplyRequest>()

    override suspend fun initializeMonitoring(
        request: LocalAlarmMonitoringStartRequest,
    ): LocalAlarmMonitoringStartResult {
        this.request = request
        return initializeResult(request).also { result ->
            state = when (result) {
                is LocalAlarmMonitoringStartResult.Initialized -> result.settlement.state
                is LocalAlarmMonitoringStartResult.AlreadyInitialized -> result.settlement.state
                is LocalAlarmMonitoringStartResult.Conflict -> null
            }
        }
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

    override suspend fun readState(
        publicationBindingId: String,
    ): LocalAlarmStateReadResult = state?.let(LocalAlarmStateReadResult::Exact)
        ?: LocalAlarmStateReadResult.Empty

    override suspend fun applySettings(
        request: LocalAlarmSettingsApplyRequest,
    ): LocalAlarmSettingsApplyResult {
        settingsRequests += request
        return requireNotNull(settingsResult).invoke(request, requireNotNull(state))
    }

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
