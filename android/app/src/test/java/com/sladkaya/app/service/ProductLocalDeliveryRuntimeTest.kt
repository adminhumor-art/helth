package com.sladkaya.app.service

import com.sladkaya.core.data.AlarmThresholdSnapshot
import com.sladkaya.core.data.ExactProductMeasurementResult
import com.sladkaya.core.data.LocalAlarmDeliveryKind
import com.sladkaya.core.data.LocalAlarmDeliveryRecord
import com.sladkaya.core.data.LocalAlarmDeliveryState
import com.sladkaya.core.data.LocalAlarmEpisodeAcknowledgeResult
import com.sladkaya.core.data.LocalAlarmStateRecord
import com.sladkaya.core.data.LocalAlarmWatchdogResult
import com.sladkaya.core.data.LocalAlarmWatchdogSettlement
import com.sladkaya.core.model.AlarmKind
import com.sladkaya.core.model.AlarmPolicyState
import com.sladkaya.core.model.AlarmThresholds
import com.sladkaya.core.model.GlucoseReading
import com.sladkaya.core.model.ReadingQuality
import com.sladkaya.core.model.SensorFamily
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ProductLocalDeliveryRuntimeTest {
    @Test
    fun processGatePreventsAnOlderEmptyDrainFromCancellingANewerWake() = runBlocking {
        val gate = ProductLocalDeliveryDrainGate()
        val oldDrainEntered = CompletableDeferred<Unit>()
        val releaseOldDrain = CompletableDeferred<Unit>()
        val actions = mutableListOf<String>()
        val oldDrain = SerializedProductLocalDeliveryDrain(
            delegate = ProductLocalDeliveryDrain {
                oldDrainEntered.complete(Unit)
                releaseOldDrain.await()
                actions += "cancel"
                ProductLocalDeliveryRunResult.Drained(0)
            },
            gate = gate,
        )
        val newDrain = SerializedProductLocalDeliveryDrain(
            delegate = ProductLocalDeliveryDrain {
                actions += "schedule"
                ProductLocalDeliveryRunResult.Waiting(0, NOW + 120_000L)
            },
            gate = gate,
        )

        val oldResult = async(start = CoroutineStart.UNDISPATCHED) { oldDrain.runBounded() }
        oldDrainEntered.await()
        val newResult = async(start = CoroutineStart.UNDISPATCHED) { newDrain.runBounded() }
        yield()

        assertTrue(actions.isEmpty())
        releaseOldDrain.complete(Unit)
        assertEquals(ProductLocalDeliveryRunResult.Drained(0), oldResult.await())
        assertEquals(
            ProductLocalDeliveryRunResult.Waiting(0, NOW + 120_000L),
            newResult.await(),
        )
        assertEquals(listOf("cancel", "schedule"), actions)
    }

    @Test
    fun degradedDrainResultIsObservedAndPreservedForEveryCaller() = runBlocking {
        val degraded = ProductLocalDeliveryRunResult.Degraded(
            processed = 2,
            quarantinedDeliveryIds = listOf("66".repeat(32)),
        )
        var observed: ProductLocalDeliveryRunResult.Degraded? = null
        val drain = SignalingProductLocalDeliveryDrain(
            delegate = ProductLocalDeliveryDrain { degraded },
            observer = ProductLocalDeliveryDegradedObserver { observed = it },
        )

        assertEquals(degraded, drain.runBounded())
        assertEquals(degraded, observed)
    }

    @Test
    fun degradedObserverFailureNeverBreaksTheDurableDrain() = runBlocking {
        val degraded = ProductLocalDeliveryRunResult.Degraded(
            processed = 1,
            quarantinedDeliveryIds = listOf("66".repeat(32)),
        )
        val drain = SignalingProductLocalDeliveryDrain(
            delegate = ProductLocalDeliveryDrain { degraded },
            observer = ProductLocalDeliveryDegradedObserver { error("observer unavailable") },
        )

        assertEquals(degraded, drain.runBounded())
    }

    @Test
    fun exactMeasurementAdapterNeverFallsBackToLatestReading() = runBlocking {
        var requested: List<String>? = null
        val adapter = RoomProductMeasurementSource { eventId, approvalId, bindingId ->
            requested = listOf(eventId, approvalId, bindingId)
            ExactProductMeasurementResult.Exact(reading())
        }

        val result = adapter.readExact(EVENT, APPROVAL, BINDING)

        assertEquals(listOf(EVENT, APPROVAL, BINDING), requested)
        assertEquals(
            ProductMeasurementReadResult.Exact(
                ProductMeasurement(reading(), APPROVAL, BINDING),
            ),
            result,
        )
    }

    @Test
    fun missingAndConflictMeasurementsStayTyped() = runBlocking {
        assertEquals(
            ProductMeasurementReadResult.Missing,
            RoomProductMeasurementSource { _, _, _ -> ExactProductMeasurementResult.Missing }
                .readExact(EVENT, APPROVAL, BINDING),
        )
        assertEquals(
            ProductMeasurementReadResult.Conflict,
            RoomProductMeasurementSource { _, _, _ ->
                ExactProductMeasurementResult.Conflict("conflict")
            }.readExact(EVENT, APPROVAL, BINDING),
        )
    }

    @Test
    fun watchdogPassesExactOldStateHashAndTreatsObsoleteWakeAsSettled() = runBlocking {
        var requested: List<Any>? = null
        val adapter = RoomProductAlarmWatchdogMutation(
            mutation = ProductAlarmWatchdogStoreMutation { bindingId, stateSha, now ->
                requested = listOf(bindingId, stateSha, now)
                LocalAlarmWatchdogResult.Obsolete("55".repeat(32))
            },
        )
        val request = watchdogRequest()

        assertEquals(ProductAlarmWatchdogMutationResult.NoChange, adapter.evaluate(request))
        assertEquals(
            listOf(BINDING, request.delivery.resultingStateSha256, NOW),
            requested,
        )
    }

    @Test
    fun watchdogRejectsASettlementThatDoesNotMatchItsDurableDelivery() = runBlocking {
        val request = watchdogRequest()
        val adapter = RoomProductAlarmWatchdogMutation(
            mutation = ProductAlarmWatchdogStoreMutation { _, _, _ ->
                LocalAlarmWatchdogResult.Applied(
                    watchdogSettlement(request).copy(sourceEventId = "different-event"),
                )
            },
        )

        assertEquals(ProductAlarmWatchdogMutationResult.Conflict, adapter.evaluate(request))
    }

    @Test
    fun appliedWatchdogPublishesItsExactDurableAlarmStateToTheActiveUi() = runBlocking {
        val request = watchdogRequest()
        var observed: Pair<String, Set<AlarmKind>>? = null
        val adapter = RoomProductAlarmWatchdogMutation(
            mutation = ProductAlarmWatchdogStoreMutation { _, _, _ ->
                LocalAlarmWatchdogResult.Applied(watchdogSettlement(request))
            },
            alarmStateObserver = ProductAlarmStateDeliveryObserver { bindingId, alarms ->
                observed = bindingId to alarms
            },
        )

        assertEquals(ProductAlarmWatchdogMutationResult.Applied, adapter.evaluate(request))
        assertEquals(BINDING to setOf(AlarmKind.SIGNAL_LOSS), observed)
    }

    @Test
    fun acknowledgementMapsExactDurableGenerationAndRejectsMismatchedState() = runBlocking {
        val state = alarmState(acknowledged = true, updatedAtEpochMs = NOW)
        var requested: List<Any>? = null
        val adapter = RoomProductAlarmAcknowledgementMutation { bindingId, generation, at ->
            requested = listOf(bindingId, generation, at)
            LocalAlarmEpisodeAcknowledgeResult.Applied(state)
        }
        val request = ProductAlarmAcknowledgementMutationRequest(
            episodeId = ProductAlarmEpisodeIdentity.derive(BINDING, 1L),
            publicationBindingId = BINDING,
            generation = 1L,
            acknowledgedAtEpochMs = NOW,
        )

        assertEquals(ProductAlarmAcknowledgementMutationResult.Applied, adapter.acknowledge(request))
        assertEquals(listOf(BINDING, 1L, NOW), requested)

        val mismatched = RoomProductAlarmAcknowledgementMutation { _, _, _ ->
            LocalAlarmEpisodeAcknowledgeResult.Applied(
                state.copy(episodeGeneration = 2L, stateSha256 = "").canonicalized(),
            )
        }
        assertEquals(
            ProductAlarmAcknowledgementMutationResult.Conflict,
            mismatched.acknowledge(request),
        )
    }

    @Test
    fun acknowledgementKeepsStaleAndConflictDistinct() = runBlocking {
        val request = ProductAlarmAcknowledgementMutationRequest(
            episodeId = ProductAlarmEpisodeIdentity.derive(BINDING, 1L),
            publicationBindingId = BINDING,
            generation = 1L,
            acknowledgedAtEpochMs = NOW,
        )
        assertEquals(
            ProductAlarmAcknowledgementMutationResult.Stale,
            RoomProductAlarmAcknowledgementMutation { _, _, _ ->
                LocalAlarmEpisodeAcknowledgeResult.Stale(2L, "55".repeat(32))
            }.acknowledge(request),
        )
        assertEquals(
            ProductAlarmAcknowledgementMutationResult.Conflict,
            RoomProductAlarmAcknowledgementMutation { _, _, _ ->
                LocalAlarmEpisodeAcknowledgeResult.Conflict("conflict")
            }.acknowledge(request),
        )
    }

    private fun watchdogRequest(): ProductAlarmWatchdogMutationRequest {
        val state = alarmState(acknowledged = false, updatedAtEpochMs = NOW - 60_000L)
        return ProductAlarmWatchdogMutationRequest(
            delivery = LocalAlarmDeliveryRecord(
                deliveryId = "66".repeat(32),
                sourceEffectId = 7L,
                sourceEventId = EVENT,
                approvalId = APPROVAL,
                publicationBindingId = BINDING,
                kind = LocalAlarmDeliveryKind.WATCHDOG,
                activeKinds = emptySet(),
                episodeGeneration = 1L,
                episodeAcknowledged = false,
                resultingStateSha256 = state.stateSha256,
                createdAtEpochMs = NOW - 30_000L,
                notBeforeEpochMs = NOW - 1L,
                state = LocalAlarmDeliveryState.LEASED,
                attempts = 1,
                leaseToken = "lease-token-0001",
                leaseExpiresAtEpochMs = NOW + 10_000L,
                lastTransitionToken = null,
                lastTransitionKind = null,
                lastTransitionAtEpochMs = null,
                deliveredAtEpochMs = null,
            ),
            currentState = state,
            sourceMeasurement = ProductMeasurement(reading(), APPROVAL, BINDING),
            evaluatedAtEpochMs = NOW,
        )
    }

    private fun watchdogSettlement(
        request: ProductAlarmWatchdogMutationRequest,
    ) = LocalAlarmWatchdogSettlement(
        watchdogId = "77".repeat(32),
        publicationBindingId = BINDING,
        approvalId = APPROVAL,
        sourceEffectId = request.delivery.sourceEffectId,
        sourceEventId = EVENT,
        expectedStateSha256 = request.delivery.resultingStateSha256,
        resultingStateSha256 = "88".repeat(32),
        activeKinds = setOf(AlarmKind.SIGNAL_LOSS),
        episodeGeneration = 2L,
        episodeAcknowledged = false,
        appliedAtEpochMs = NOW,
        stateChanged = true,
        deliveryIds = listOf("99".repeat(32)),
    )

    private fun alarmState(
        acknowledged: Boolean,
        updatedAtEpochMs: Long,
    ) = LocalAlarmStateRecord(
        publicationBindingId = BINDING,
        approvalId = APPROVAL,
        monitoringStartedAtEpochMs = NOW - 600_000L,
        policyState = AlarmPolicyState(
            active = setOf(AlarmKind.LOW),
            latestFreshSensorTimeEpochMs = NOW - 120_000L,
            latestFreshPhoneTimeEpochMs = NOW - 120_000L,
            phoneClockMovedBackwards = false,
        ),
        lastEffectId = 7L,
        lastEventId = EVENT,
        lastSequence = 7L,
        thresholds = AlarmThresholdSnapshot.from(AlarmThresholds()),
        episodeGeneration = 1L,
        episodeAcknowledged = acknowledged,
        episodeAcknowledgedAtEpochMs = NOW.takeIf { acknowledged },
        episodeOpenedAtEpochMs = NOW - 90_000L,
        updatedAtEpochMs = updatedAtEpochMs,
        stateSha256 = "",
    ).canonicalized()

    private fun reading() = GlucoseReading(
        eventId = EVENT,
        sensorId = "sensor-approved",
        sensorFamily = SensorFamily.SIBIONICS_GS1,
        sensorTimeEpochMs = NOW - 60_000L,
        phoneTimeEpochMs = NOW - 59_000L,
        glucoseMgDl = 72,
        trendMgDlPerMinute = -1.0,
        quality = ReadingQuality.VALID,
        sequence = 7L,
    )

    private companion object {
        const val EVENT = "product-event-7"
        const val NOW = 1_700_000_600_000L
        const val APPROVAL =
            "1111111111111111111111111111111111111111111111111111111111111111"
        const val BINDING =
            "2222222222222222222222222222222222222222222222222222222222222222"
    }
}
