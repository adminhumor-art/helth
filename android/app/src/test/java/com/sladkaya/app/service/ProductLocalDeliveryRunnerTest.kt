package com.sladkaya.app.service

import com.sladkaya.core.data.AlarmThresholdSnapshot
import com.sladkaya.core.data.LeasedLocalReadingEffect
import com.sladkaya.core.data.LocalAlarmApplyRequest
import com.sladkaya.core.data.LocalAlarmApplyResult
import com.sladkaya.core.data.LocalAlarmDeliveryKind
import com.sladkaya.core.data.LocalAlarmDeliveryLeaseResult
import com.sladkaya.core.data.LocalAlarmDeliveryRecord
import com.sladkaya.core.data.LocalAlarmDeliveryState
import com.sladkaya.core.data.LocalAlarmDeliveryStore
import com.sladkaya.core.data.LocalAlarmDeliveryTransitionKind
import com.sladkaya.core.data.LocalAlarmDeliveryTransitionResult
import com.sladkaya.core.data.LocalAlarmEpisodeAcknowledgeResult
import com.sladkaya.core.data.LocalAlarmSettlementReadResult
import com.sladkaya.core.data.LocalAlarmStateReadResult
import com.sladkaya.core.data.LocalAlarmStateRecord
import com.sladkaya.core.data.LocalAlarmStore
import com.sladkaya.core.data.LocalAlarmWatchdogResult
import com.sladkaya.core.data.LocalReadingEffectLeaseResult
import com.sladkaya.core.model.AlarmKind
import com.sladkaya.core.model.AlarmPolicyState
import com.sladkaya.core.model.AlarmThresholds
import com.sladkaya.core.model.GlucoseReading
import com.sladkaya.core.model.ReadingQuality
import com.sladkaya.core.model.SensorFamily
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ProductLocalDeliveryRunnerTest {
    @Test
    fun drainsDueShowUpdateCloseAndWidgetInStoreFifoOrder() = runBlocking {
        val deliveries = listOf(
            pendingDelivery(1, LocalAlarmDeliveryKind.SHOW, setOf(AlarmKind.LOW)),
            pendingDelivery(2, LocalAlarmDeliveryKind.UPDATE, setOf(AlarmKind.LOW)),
            pendingDelivery(3, LocalAlarmDeliveryKind.CLOSE, emptySet()),
            pendingDelivery(4, LocalAlarmDeliveryKind.WIDGET, emptySet()),
        )
        val deliveryStore = FakeDeliveryStore(deliveries)
        val effects = RecordingEffects()
        val runner = runner(
            deliveryStore = deliveryStore,
            state = state(
                lastEffectId = 10,
                lastSequence = 10,
                active = setOf(AlarmKind.LOW),
                generation = 1,
            ),
            readings = deliveries.associate { it.sourceEventId to reading(it.sourceEffectId) },
            effects = effects,
        )

        assertEquals(ProductLocalDeliveryRunResult.Drained(4), runner.runBounded())
        assertEquals(
            listOf("SHOW:event-1", "UPDATE:event-2", "CLOSE", "WIDGET:event-4"),
            effects.actions,
        )
        assertEquals(deliveries.map(LocalAlarmDeliveryRecord::deliveryId), deliveryStore.delivered)
    }

    @Test
    fun repeatAlertsOnlyForSameActiveUnacknowledgedGenerationThenDurablyReschedules() =
        runBlocking {
            val current = state(
                lastEffectId = 7,
                lastSequence = 7,
                active = setOf(AlarmKind.LOW),
                generation = 3,
            )
            val repeat = pendingDelivery(
                effectId = 7,
                kind = LocalAlarmDeliveryKind.REPEAT,
                active = setOf(AlarmKind.LOW),
                generation = 3,
                eventId = current.lastEventId,
                resultingStateSha256 = current.stateSha256,
                notBeforeEpochMs = NOW,
            )
            val deliveryStore = FakeDeliveryStore(listOf(repeat))
            val effects = RecordingEffects()
            val wakes = RecordingWakeScheduler()

            val result = runner(
                deliveryStore = deliveryStore,
                state = current,
                readings = mapOf(repeat.sourceEventId to reading(7)),
                effects = effects,
                wakes = wakes,
            ).runBounded()

            assertEquals(
                ProductLocalDeliveryRunResult.Waiting(1, NOW + REPEAT_INTERVAL_MS),
                result,
            )
            assertEquals(listOf("REPEAT:${repeat.sourceEventId}"), effects.actions)
            assertTrue(deliveryStore.delivered.isEmpty())
            assertEquals(listOf(NOW + REPEAT_INTERVAL_MS), deliveryStore.rescheduled)
            assertEquals(listOf(NOW + REPEAT_INTERVAL_MS), wakes.scheduled)
        }

    @Test
    fun staleOrAcknowledgedRepeatIsMarkedDeliveredWithoutNotification() = runBlocking {
        listOf(
            state(lastEffectId = 2, lastSequence = 2, active = setOf(AlarmKind.LOW), generation = 2),
            state(active = setOf(AlarmKind.LOW), generation = 1, acknowledged = true),
            state(lastEffectId = 2, lastSequence = 2, active = emptySet(), generation = 1),
        ).forEach { current ->
            val repeat = pendingDelivery(
                effectId = 1,
                kind = LocalAlarmDeliveryKind.REPEAT,
                active = setOf(AlarmKind.LOW),
                generation = 1,
            )
            val deliveryStore = FakeDeliveryStore(listOf(repeat))
            val effects = RecordingEffects()

            assertEquals(
                ProductLocalDeliveryRunResult.Drained(1),
                runner(
                    deliveryStore = deliveryStore,
                    state = current,
                    readings = mapOf(repeat.sourceEventId to reading(1)),
                    effects = effects,
                ).runBounded(),
            )
            assertTrue(effects.actions.isEmpty())
            assertEquals(listOf(repeat.deliveryId), deliveryStore.delivered)
        }
    }

    @Test
    fun delayedShowCannotResurrectAnOlderOrAlreadyAcknowledgedEpisode() = runBlocking {
        val cases = listOf(
            state(
                lastEffectId = 2,
                lastSequence = 2,
                active = setOf(AlarmKind.HIGH),
                generation = 2,
            ),
            state(
                lastEffectId = 2,
                lastSequence = 2,
                active = emptySet(),
                generation = 1,
            ),
            state(
                lastEffectId = 2,
                lastSequence = 2,
                active = setOf(AlarmKind.LOW),
                generation = 1,
                acknowledged = true,
            ),
        )

        cases.forEach { current ->
            val delayed = pendingDelivery(
                effectId = 1,
                kind = LocalAlarmDeliveryKind.SHOW,
                active = setOf(AlarmKind.LOW),
                generation = 1,
            )
            val deliveryStore = FakeDeliveryStore(listOf(delayed))
            val effects = RecordingEffects()

            assertEquals(
                ProductLocalDeliveryRunResult.Drained(1),
                runner(
                    deliveryStore = deliveryStore,
                    state = current,
                    readings = mapOf(delayed.sourceEventId to reading(1)),
                    effects = effects,
                ).runBounded(),
            )
            assertTrue(effects.actions.isEmpty())
            assertEquals(listOf(delayed.deliveryId), deliveryStore.delivered)
        }
    }

    @Test
    fun delayedUpdateCannotRegressTheVisibleNotificationOfANewerEpisode() = runBlocking {
        val delayed = pendingDelivery(
            effectId = 1,
            kind = LocalAlarmDeliveryKind.UPDATE,
            active = setOf(AlarmKind.LOW),
            generation = 1,
        )
        val deliveryStore = FakeDeliveryStore(listOf(delayed))
        val effects = RecordingEffects()

        assertEquals(
            ProductLocalDeliveryRunResult.Drained(1),
            runner(
                deliveryStore = deliveryStore,
                state = state(
                    lastEffectId = 2,
                    lastSequence = 2,
                    active = setOf(AlarmKind.HIGH),
                    generation = 2,
                ),
                readings = mapOf(delayed.sourceEventId to reading(1)),
                effects = effects,
            ).runBounded(),
        )
        assertTrue(effects.actions.isEmpty())
        assertEquals(listOf(delayed.deliveryId), deliveryStore.delivered)
    }

    @Test
    fun notDueAndActiveLeaseWakeAtTheExactDurableDeadline() = runBlocking {
        val notDueStore = FakeDeliveryStore(
            listOf(pendingDelivery(1, LocalAlarmDeliveryKind.WIDGET, emptySet(), notBeforeEpochMs = NOW + 9_000)),
        )
        val notDueWake = RecordingWakeScheduler()
        assertEquals(
            ProductLocalDeliveryRunResult.Waiting(0, NOW + 9_000),
            runner(notDueStore, wakes = notDueWake).runBounded(),
        )
        assertEquals(listOf(NOW + 9_000), notDueWake.scheduled)

        val blockedStore = FakeDeliveryStore(emptyList()).apply {
            forcedLease = LocalAlarmDeliveryLeaseResult.BlockedByActiveLease(
                deliveryId = ID_A,
                leaseExpiresAtEpochMs = NOW + 8_000,
            )
        }
        val blockedWake = RecordingWakeScheduler()
        assertEquals(
            ProductLocalDeliveryRunResult.Waiting(0, NOW + 8_000),
            runner(blockedStore, wakes = blockedWake).runBounded(),
        )
        assertEquals(listOf(NOW + 8_000), blockedWake.scheduled)
    }

    @Test
    fun transientSideEffectFailureIsDurablyRetriedAfterTwoMinutes() = runBlocking {
        val delivery = pendingDelivery(1, LocalAlarmDeliveryKind.SHOW, setOf(AlarmKind.LOW))
        val deliveryStore = FakeDeliveryStore(listOf(delivery))
        val wakes = RecordingWakeScheduler()
        val effects = RecordingEffects().apply {
            nextResult = ProductLocalDeliveryEffectResult.TransientFailure
        }

        assertEquals(
            ProductLocalDeliveryRunResult.Waiting(1, NOW + REPEAT_INTERVAL_MS),
            runner(
                deliveryStore = deliveryStore,
                state = state(lastEffectId = 2, lastSequence = 2, active = setOf(AlarmKind.LOW)),
                readings = mapOf(delivery.sourceEventId to reading(1)),
                effects = effects,
                wakes = wakes,
            ).runBounded(),
        )
        assertTrue(deliveryStore.delivered.isEmpty())
        assertEquals(listOf(NOW + REPEAT_INTERVAL_MS), deliveryStore.rescheduled)
    }

    @Test
    fun transientAlarmStateReadFailureIsRetriedAndNeverQuarantined() = runBlocking {
        val delivery = pendingDelivery(1, LocalAlarmDeliveryKind.SHOW, setOf(AlarmKind.LOW))
        val deliveryStore = FakeDeliveryStore(listOf(delivery))

        assertEquals(
            ProductLocalDeliveryRunResult.Waiting(1, NOW + REPEAT_INTERVAL_MS),
            runner(
                deliveryStore = deliveryStore,
                stateReadFailure = IllegalStateException("database temporarily unavailable"),
            ).runBounded(),
        )
        assertEquals(listOf(NOW + REPEAT_INTERVAL_MS), deliveryStore.rescheduled)
        assertTrue(deliveryStore.quarantined.isEmpty())
    }

    @Test
    fun durableIdentityConflictQuarantinesTheExactPoisonedLease() = runBlocking {
        val delivery = pendingDelivery(5, LocalAlarmDeliveryKind.SHOW, setOf(AlarmKind.LOW))
        val deliveryStore = FakeDeliveryStore(listOf(delivery))
        val wakes = RecordingWakeScheduler()

        val result = runner(
            deliveryStore = deliveryStore,
            state = state(lastEffectId = 4, lastSequence = 4, active = setOf(AlarmKind.LOW)),
            readings = mapOf(delivery.sourceEventId to reading(5)),
            wakes = wakes,
        ).runBounded()

        assertEquals(
            ProductLocalDeliveryRunResult.Degraded(1, listOf(delivery.deliveryId)),
            result,
        )
        assertTrue(deliveryStore.delivered.isEmpty())
        assertTrue(deliveryStore.rescheduled.isEmpty())
        assertEquals(listOf(delivery.deliveryId), deliveryStore.quarantined)
        assertTrue(wakes.scheduled.isEmpty())
    }

    @Test
    fun sideEffectConflictQuarantinesWithoutMarkingOrRescheduling() = runBlocking {
        val delivery = pendingDelivery(1, LocalAlarmDeliveryKind.SHOW, setOf(AlarmKind.LOW))
        val deliveryStore = FakeDeliveryStore(listOf(delivery))
        val wakes = RecordingWakeScheduler()
        val effects = RecordingEffects().apply {
            nextResult = ProductLocalDeliveryEffectResult.Conflict
        }

        assertEquals(
            ProductLocalDeliveryRunResult.Degraded(1, listOf(delivery.deliveryId)),
            runner(
                deliveryStore = deliveryStore,
                state = state(
                    lastEffectId = 2,
                    lastSequence = 2,
                    active = setOf(AlarmKind.LOW),
                ),
                readings = mapOf(delivery.sourceEventId to reading(1)),
                effects = effects,
                wakes = wakes,
            ).runBounded(),
        )
        assertTrue(deliveryStore.delivered.isEmpty())
        assertTrue(deliveryStore.rescheduled.isEmpty())
        assertEquals(listOf(delivery.deliveryId), deliveryStore.quarantined)
        assertTrue(wakes.scheduled.isEmpty())
    }

    @Test
    fun permanentConflictQuarantinesExactLeaseContinuesFifoAndSignalsDegraded() = runBlocking {
        val poisoned = pendingDelivery(1, LocalAlarmDeliveryKind.SHOW, setOf(AlarmKind.LOW))
        val healthy = pendingDelivery(2, LocalAlarmDeliveryKind.WIDGET, emptySet())
        val deliveryStore = FakeDeliveryStore(listOf(poisoned, healthy))
        val effects = RecordingEffects().apply {
            results += ProductLocalDeliveryEffectResult.Conflict
            results += ProductLocalDeliveryEffectResult.Applied
        }

        assertEquals(
            ProductLocalDeliveryRunResult.Degraded(
                processed = 2,
                quarantinedDeliveryIds = listOf(poisoned.deliveryId),
            ),
            runner(
                deliveryStore = deliveryStore,
                state = state(
                    lastEffectId = 3,
                    lastSequence = 3,
                    active = setOf(AlarmKind.LOW),
                ),
                readings = mapOf(
                    poisoned.sourceEventId to reading(1),
                    healthy.sourceEventId to reading(2),
                ),
                effects = effects,
            ).runBounded(),
        )
        assertEquals(listOf(poisoned.deliveryId), deliveryStore.quarantined)
        assertEquals(listOf(healthy.deliveryId), deliveryStore.delivered)
        assertEquals(
            listOf("SHOW:${poisoned.sourceEventId}", "WIDGET:${healthy.sourceEventId}"),
            effects.actions,
        )
    }

    @Test
    fun missingExactSourceMeasurementIsQuarantinedAsPermanentConflict() = runBlocking {
        val delivery = pendingDelivery(1, LocalAlarmDeliveryKind.WIDGET, emptySet())
        val deliveryStore = FakeDeliveryStore(listOf(delivery))

        assertEquals(
            ProductLocalDeliveryRunResult.Degraded(1, listOf(delivery.deliveryId)),
            runner(deliveryStore, readings = emptyMap()).runBounded(),
        )
        assertTrue(deliveryStore.delivered.isEmpty())
        assertEquals(listOf(delivery.deliveryId), deliveryStore.quarantined)
    }

    @Test
    fun quarantineTransitionConflictKeepsExactLeaseAndReturnsConflict() = runBlocking {
        val delivery = pendingDelivery(1, LocalAlarmDeliveryKind.SHOW, setOf(AlarmKind.LOW))
        val deliveryStore = FakeDeliveryStore(listOf(delivery)).apply {
            quarantineResult = LocalAlarmDeliveryTransitionResult.Conflict("lease changed")
        }
        val wakes = RecordingWakeScheduler()
        val effects = RecordingEffects().apply {
            nextResult = ProductLocalDeliveryEffectResult.Conflict
        }

        assertEquals(
            ProductLocalDeliveryRunResult.Conflict(1),
            runner(
                deliveryStore = deliveryStore,
                state = state(
                    lastEffectId = 2,
                    lastSequence = 2,
                    active = setOf(AlarmKind.LOW),
                ),
                readings = mapOf(delivery.sourceEventId to reading(1)),
                effects = effects,
                wakes = wakes,
            ).runBounded(),
        )
        assertTrue(deliveryStore.quarantined.isEmpty())
        assertEquals(listOf(NOW + LEASE_MS), wakes.scheduled)
    }

    @Test
    fun watchdogUsesOnlyInjectedDurableMutationPort() = runBlocking {
        val current = state(
            lastEffectId = 1,
            lastSequence = 1,
            active = emptySet(),
        )
        val delivery = pendingDelivery(
            effectId = 1,
            kind = LocalAlarmDeliveryKind.WATCHDOG,
            active = emptySet(),
            eventId = current.lastEventId,
            resultingStateSha256 = current.stateSha256,
            generation = 0,
        )
        val deliveryStore = FakeDeliveryStore(listOf(delivery))
        val effects = RecordingEffects()
        val watchdog = RecordingWatchdogMutation()

        assertEquals(
            ProductLocalDeliveryRunResult.Drained(1),
            runner(
                deliveryStore = deliveryStore,
                state = current,
                readings = mapOf(delivery.sourceEventId to reading(1)),
                effects = effects,
                watchdog = watchdog,
            ).runBounded(),
        )
        assertTrue(effects.actions.isEmpty())
        assertEquals(listOf(delivery.deliveryId), watchdog.deliveryIds)
        assertEquals(listOf(delivery.deliveryId), deliveryStore.delivered)
    }

    @Test
    fun monitoringStartWatchdogRunsWithoutInventingAProductMeasurement() = runBlocking {
        val current = monitoringStartState(active = emptySet(), generation = 0)
        val watchdogDelivery = pendingDelivery(
            effectId = 0,
            kind = LocalAlarmDeliveryKind.WATCHDOG,
            active = emptySet(),
            generation = 0,
            eventId = MONITORING_START_ID,
            resultingStateSha256 = current.stateSha256,
        )
        val deliveryStore = FakeDeliveryStore(listOf(watchdogDelivery))
        val watchdog = RecordingWatchdogMutation()

        assertEquals(
            ProductLocalDeliveryRunResult.Drained(1),
            runner(
                deliveryStore = deliveryStore,
                state = current,
                readings = emptyMap(),
                watchdog = watchdog,
            ).runBounded(),
        )
        assertEquals(listOf(watchdogDelivery.deliveryId), watchdog.deliveryIds)
        assertEquals(listOf(watchdogDelivery.deliveryId), deliveryStore.delivered)
        assertTrue(deliveryStore.quarantined.isEmpty())
    }

    @Test
    fun firstVerifiedReadingMakesPendingMonitoringStartWatchdogHarmless() = runBlocking {
        val current = state(
            lastEffectId = 1,
            lastSequence = 42,
            active = emptySet(),
            generation = 0,
        )
        val startupWatchdog = pendingDelivery(
            effectId = 0,
            kind = LocalAlarmDeliveryKind.WATCHDOG,
            active = emptySet(),
            generation = 0,
            eventId = MONITORING_START_ID,
            resultingStateSha256 = STATE_HASH,
        )
        val deliveryStore = FakeDeliveryStore(listOf(startupWatchdog))
        val effects = RecordingEffects()
        val watchdog = RecordingWatchdogMutation().apply {
            nextResult = ProductAlarmWatchdogMutationResult.NoChange
        }

        assertEquals(
            ProductLocalDeliveryRunResult.Drained(1),
            runner(
                deliveryStore = deliveryStore,
                state = current,
                readings = emptyMap(),
                effects = effects,
                watchdog = watchdog,
            ).runBounded(),
        )
        assertEquals(listOf(startupWatchdog.deliveryId), watchdog.deliveryIds)
        assertEquals(listOf(startupWatchdog.deliveryId), deliveryStore.delivered)
        assertTrue(deliveryStore.quarantined.isEmpty())
        assertTrue(effects.actions.isEmpty())
    }

    @Test
    fun signalLossOpenedFromMonitoringStartAlertsAndUpdatesWidgetWithoutFakeValue() = runBlocking {
        val current = monitoringStartState(
            active = setOf(AlarmKind.SIGNAL_LOSS),
            generation = 1,
        )
        val deliveries = listOf(
            pendingDelivery(
                effectId = 0,
                kind = LocalAlarmDeliveryKind.SHOW,
                active = setOf(AlarmKind.SIGNAL_LOSS),
                generation = 1,
                eventId = MONITORING_START_ID,
                resultingStateSha256 = current.stateSha256,
            ),
            pendingDelivery(
                effectId = 0,
                kind = LocalAlarmDeliveryKind.WIDGET,
                active = setOf(AlarmKind.SIGNAL_LOSS),
                generation = 1,
                eventId = MONITORING_START_ID,
                resultingStateSha256 = current.stateSha256,
            ),
        )
        val effects = RecordingEffects()

        assertEquals(
            ProductLocalDeliveryRunResult.Drained(2),
            runner(
                deliveryStore = FakeDeliveryStore(deliveries),
                state = current,
                readings = emptyMap(),
                effects = effects,
            ).runBounded(),
        )
        assertEquals(listOf("SHOW:NO_READING", "WIDGET:NO_READING"), effects.actions)
    }

    @Test
    fun boundedRunYieldsAndSchedulesAnotherWakeWithoutTakingTheThirdLease() = runBlocking {
        val deliveries = (1L..3L).map {
            pendingDelivery(it, LocalAlarmDeliveryKind.WIDGET, emptySet())
        }
        val deliveryStore = FakeDeliveryStore(deliveries)
        val wakes = RecordingWakeScheduler()

        assertEquals(
            ProductLocalDeliveryRunResult.Yielded(2, NOW + YIELD_DELAY_MS),
            runner(
                deliveryStore = deliveryStore,
                state = state(lastEffectId = 4, lastSequence = 4, generation = 1),
                readings = deliveries.associate { it.sourceEventId to reading(it.sourceEffectId) },
                wakes = wakes,
                maxDeliveries = 2,
            ).runBounded(),
        )
        assertEquals(deliveries.take(2).map(LocalAlarmDeliveryRecord::deliveryId), deliveryStore.delivered)
        assertEquals(listOf(NOW + YIELD_DELAY_MS), wakes.scheduled)
    }

    @Test
    fun episodeIdentityIsStableForBindingAndGenerationOnly() {
        val first = ProductAlarmEpisodeIdentity.derive(BINDING, 7)
        val same = ProductAlarmEpisodeIdentity.derive(BINDING, 7)
        val next = ProductAlarmEpisodeIdentity.derive(BINDING, 8)

        assertEquals(first, same)
        assertNotEquals(first, next)
        assertTrue(Regex("^[0-9a-f]{64}$").matches(first))
    }

    private fun runner(
        deliveryStore: FakeDeliveryStore,
        state: LocalAlarmStateRecord = state(lastEffectId = 2, lastSequence = 2),
        readings: Map<String, GlucoseReading> = mapOf("event-1" to reading(1)),
        effects: RecordingEffects = RecordingEffects(),
        watchdog: RecordingWatchdogMutation = RecordingWatchdogMutation(),
        wakes: RecordingWakeScheduler = RecordingWakeScheduler(),
        maxDeliveries: Int = 32,
        stateReadFailure: RuntimeException? = null,
    ): ProductLocalDeliveryRunner {
        var token = 0
        return ProductLocalDeliveryRunner(
            deliveryStore = deliveryStore,
            alarmStore = FakeAlarmStore(state, stateReadFailure),
            measurementSource = ProductMeasurementSource { eventId, approvalId, bindingId ->
                readings[eventId]?.let {
                    ProductMeasurementReadResult.Exact(
                        ProductMeasurement(it, approvalId, bindingId),
                    )
                } ?: ProductMeasurementReadResult.Missing
            },
            effects = effects,
            watchdogMutation = watchdog,
            wakeScheduler = wakes,
            nowEpochMs = { NOW },
            nextLeaseToken = { "delivery-lease-${++token}" },
            maxDeliveriesPerRun = maxDeliveries,
        )
    }

    private fun state(
        lastEffectId: Long = 1,
        lastSequence: Long = 1,
        active: Set<AlarmKind> = emptySet(),
        generation: Long = if (active.isEmpty()) 0 else 1,
        acknowledged: Boolean = false,
    ) = LocalAlarmStateRecord(
        publicationBindingId = BINDING,
        approvalId = APPROVAL,
        monitoringStartedAtEpochMs = NOW - 60_000,
        policyState = AlarmPolicyState(
            active = active,
            latestFreshSensorTimeEpochMs = NOW - 2_000,
            latestFreshPhoneTimeEpochMs = NOW - 1_000,
            phoneClockMovedBackwards = false,
        ),
        lastEffectId = lastEffectId,
        lastEventId = "event-$lastEffectId",
        lastSequence = lastSequence,
        thresholds = AlarmThresholdSnapshot.from(AlarmThresholds()),
        episodeGeneration = generation,
        episodeAcknowledged = acknowledged,
        episodeAcknowledgedAtEpochMs = if (acknowledged) NOW - 500 else null,
        episodeOpenedAtEpochMs = active.takeIf(Set<AlarmKind>::isNotEmpty)?.let { NOW - 30_000 },
        updatedAtEpochMs = if (acknowledged) NOW - 500 else NOW - 1_000,
        stateSha256 = "",
    ).canonicalized()

    private fun monitoringStartState(
        active: Set<AlarmKind>,
        generation: Long,
    ) = LocalAlarmStateRecord(
        publicationBindingId = BINDING,
        approvalId = APPROVAL,
        monitoringStartedAtEpochMs = NOW - 60_000,
        policyState = AlarmPolicyState(
            active = active,
            latestFreshSensorTimeEpochMs = 0,
            latestFreshPhoneTimeEpochMs = 0,
            phoneClockMovedBackwards = false,
        ),
        lastEffectId = 0,
        lastEventId = MONITORING_START_ID,
        lastSequence = 41,
        thresholds = AlarmThresholdSnapshot.from(AlarmThresholds()),
        episodeGeneration = generation,
        episodeAcknowledged = false,
        episodeAcknowledgedAtEpochMs = null,
        episodeOpenedAtEpochMs = active.takeIf(Set<AlarmKind>::isNotEmpty)?.let { NOW - 1_000 },
        updatedAtEpochMs = NOW - 1_000,
        stateSha256 = "",
    ).canonicalized()

    private fun pendingDelivery(
        effectId: Long,
        kind: LocalAlarmDeliveryKind,
        active: Set<AlarmKind>,
        generation: Long = if (active.isEmpty()) 1 else 1,
        eventId: String = "event-$effectId",
        resultingStateSha256: String = STATE_HASH,
        notBeforeEpochMs: Long = NOW,
    ) = LocalAlarmDeliveryRecord(
        deliveryId = effectId.toString(16).padStart(64, '0').takeLast(64),
        sourceEffectId = effectId,
        sourceEventId = eventId,
        approvalId = APPROVAL,
        publicationBindingId = BINDING,
        kind = kind,
        activeKinds = active,
        episodeGeneration = generation,
        episodeAcknowledged = false,
        resultingStateSha256 = resultingStateSha256,
        createdAtEpochMs = NOW - 1_000,
        notBeforeEpochMs = notBeforeEpochMs,
        state = LocalAlarmDeliveryState.PENDING,
        attempts = 0,
        leaseToken = null,
        leaseExpiresAtEpochMs = null,
        lastTransitionToken = null,
        lastTransitionKind = null,
        lastTransitionAtEpochMs = null,
        deliveredAtEpochMs = null,
    )

    private fun reading(sequence: Long) = GlucoseReading(
        eventId = "event-$sequence",
        sensorId = "sensor-a",
        sensorFamily = SensorFamily.SIBIONICS_GS1,
        sensorTimeEpochMs = NOW - 2_000 + sequence,
        phoneTimeEpochMs = NOW - 1_000 + sequence,
        glucoseMgDl = 60,
        trendMgDlPerMinute = -1.0,
        quality = ReadingQuality.VALID,
        sequence = sequence,
    )

    private companion object {
        const val NOW = 1_700_000_000_000L
        const val LEASE_MS = 10_000L
        const val REPEAT_INTERVAL_MS = 120_000L
        const val YIELD_DELAY_MS = 1_000L
        val APPROVAL = "11".repeat(32)
        val BINDING = "22".repeat(32)
        val STATE_HASH = "33".repeat(32)
        val ID_A = "44".repeat(32)
        val MONITORING_START_ID = "55".repeat(32)
    }
}

private class FakeDeliveryStore(
    deliveries: List<LocalAlarmDeliveryRecord>,
) : LocalAlarmDeliveryStore {
    private val pending = ArrayDeque(deliveries)
    private var leased: LocalAlarmDeliveryRecord? = null
    var forcedLease: LocalAlarmDeliveryLeaseResult? = null
    var quarantineResult: LocalAlarmDeliveryTransitionResult? = null
    val delivered = mutableListOf<String>()
    val quarantined = mutableListOf<String>()
    val rescheduled = mutableListOf<Long>()

    override suspend fun leaseDueEarliest(
        nowEpochMs: Long,
        leaseToken: String,
        leaseExpiresAtEpochMs: Long,
    ): LocalAlarmDeliveryLeaseResult {
        forcedLease?.let { return it }
        leased?.let { active ->
            return LocalAlarmDeliveryLeaseResult.BlockedByActiveLease(
                active.deliveryId,
                checkNotNull(active.leaseExpiresAtEpochMs),
            )
        }
        val next = pending.firstOrNull() ?: return LocalAlarmDeliveryLeaseResult.Empty
        if (next.notBeforeEpochMs > nowEpochMs) {
            return LocalAlarmDeliveryLeaseResult.NotDue(next.deliveryId, next.notBeforeEpochMs)
        }
        pending.removeFirst()
        return LocalAlarmDeliveryLeaseResult.Leased(
            next.copy(
                state = LocalAlarmDeliveryState.LEASED,
                attempts = next.attempts + 1,
                leaseToken = leaseToken,
                leaseExpiresAtEpochMs = leaseExpiresAtEpochMs,
            ).also { leased = it },
        )
    }

    override suspend fun markDelivered(
        deliveryId: String,
        leaseToken: String,
        deliveredAtEpochMs: Long,
    ): LocalAlarmDeliveryTransitionResult {
        val active = leased ?: return LocalAlarmDeliveryTransitionResult.Conflict("missing lease")
        if (active.deliveryId != deliveryId || active.leaseToken != leaseToken) {
            return LocalAlarmDeliveryTransitionResult.Conflict("wrong lease")
        }
        delivered += deliveryId
        leased = null
        return LocalAlarmDeliveryTransitionResult.Applied
    }

    override suspend fun reschedule(
        deliveryId: String,
        leaseToken: String,
        rescheduledAtEpochMs: Long,
        nextAttemptEpochMs: Long,
    ): LocalAlarmDeliveryTransitionResult {
        val active = leased ?: return LocalAlarmDeliveryTransitionResult.Conflict("missing lease")
        if (active.deliveryId != deliveryId || active.leaseToken != leaseToken) {
            return LocalAlarmDeliveryTransitionResult.Conflict("wrong lease")
        }
        rescheduled += nextAttemptEpochMs
        pending.addLast(
            active.copy(
                state = LocalAlarmDeliveryState.PENDING,
                notBeforeEpochMs = nextAttemptEpochMs,
                leaseToken = null,
                leaseExpiresAtEpochMs = null,
                lastTransitionToken = leaseToken,
                lastTransitionKind = LocalAlarmDeliveryTransitionKind.RETRY,
                lastTransitionAtEpochMs = rescheduledAtEpochMs,
            ),
        )
        leased = null
        return LocalAlarmDeliveryTransitionResult.Applied
    }

    override suspend fun quarantine(
        deliveryId: String,
        leaseToken: String,
        quarantinedAtEpochMs: Long,
    ): LocalAlarmDeliveryTransitionResult {
        quarantineResult?.let { return it }
        val active = leased ?: return LocalAlarmDeliveryTransitionResult.Conflict("missing lease")
        if (active.deliveryId != deliveryId || active.leaseToken != leaseToken) {
            return LocalAlarmDeliveryTransitionResult.Conflict("wrong lease")
        }
        quarantined += deliveryId
        leased = null
        return LocalAlarmDeliveryTransitionResult.Applied
    }
}

private class FakeAlarmStore(
    private val state: LocalAlarmStateRecord,
    private val readFailure: RuntimeException? = null,
) : LocalAlarmStore {
    override suspend fun readState(publicationBindingId: String): LocalAlarmStateReadResult {
        readFailure?.let { throw it }
        return if (publicationBindingId == state.publicationBindingId) {
            LocalAlarmStateReadResult.Exact(state)
        } else {
            LocalAlarmStateReadResult.Conflict("wrong binding")
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

private class RecordingEffects : ProductLocalDeliveryEffects {
    val actions = mutableListOf<String>()
    var nextResult: ProductLocalDeliveryEffectResult = ProductLocalDeliveryEffectResult.Applied
    val results = ArrayDeque<ProductLocalDeliveryEffectResult>()

    override suspend fun show(presentation: ProductAlarmPresentation): ProductLocalDeliveryEffectResult =
        record("SHOW:${presentation.reading?.eventId ?: "NO_READING"}")

    override suspend fun update(presentation: ProductAlarmPresentation): ProductLocalDeliveryEffectResult =
        record("UPDATE:${presentation.reading?.eventId ?: "NO_READING"}")

    override suspend fun repeat(presentation: ProductAlarmPresentation): ProductLocalDeliveryEffectResult =
        record("REPEAT:${presentation.reading?.eventId ?: "NO_READING"}")

    override suspend fun close(episodeId: String): ProductLocalDeliveryEffectResult =
        record("CLOSE")

    override suspend fun updateWidget(reading: GlucoseReading?): ProductLocalDeliveryEffectResult =
        record("WIDGET:${reading?.eventId ?: "NO_READING"}")

    private fun record(action: String): ProductLocalDeliveryEffectResult {
        actions += action
        return results.removeFirstOrNull()
            ?: nextResult.also { nextResult = ProductLocalDeliveryEffectResult.Applied }
    }
}

private class RecordingWatchdogMutation : ProductAlarmWatchdogMutationPort {
    val deliveryIds = mutableListOf<String>()
    var nextResult: ProductAlarmWatchdogMutationResult = ProductAlarmWatchdogMutationResult.Applied

    override suspend fun evaluate(
        request: ProductAlarmWatchdogMutationRequest,
    ): ProductAlarmWatchdogMutationResult {
        deliveryIds += request.delivery.deliveryId
        return nextResult
    }
}

private class RecordingWakeScheduler : ProductLocalDeliveryWakeScheduler {
    val scheduled = mutableListOf<Long>()
    var cancelCalls = 0

    override fun schedule(deadlineEpochMs: Long): ProductLocalDeliveryWakeResult {
        scheduled += deadlineEpochMs
        return ProductLocalDeliveryWakeResult.Scheduled
    }

    override fun cancel(): ProductLocalDeliveryWakeResult {
        cancelCalls += 1
        return ProductLocalDeliveryWakeResult.Scheduled
    }
}
