package com.sladkaya.app.service

import com.sladkaya.core.data.LocalAlarmDeliveryKind
import com.sladkaya.core.data.LocalAlarmDeliveryLeaseResult
import com.sladkaya.core.data.LocalAlarmDeliveryRecord
import com.sladkaya.core.data.LocalAlarmDeliveryState
import com.sladkaya.core.data.LocalAlarmDeliveryStore
import com.sladkaya.core.data.LocalAlarmDeliveryTransitionResult
import com.sladkaya.core.data.LocalAlarmStateReadResult
import com.sladkaya.core.data.LocalAlarmStateRecord
import com.sladkaya.core.data.LocalAlarmStore
import com.sladkaya.core.data.MONITORING_START_EFFECT_ID
import com.sladkaya.core.model.AlarmKind
import com.sladkaya.core.model.GlucoseReading
import java.security.MessageDigest
import kotlinx.coroutines.CancellationException

internal data class ProductMeasurement(
    val reading: GlucoseReading,
    val approvalId: String,
    val publicationBindingId: String,
) {
    init {
        require(SHA256.matches(approvalId))
        require(SHA256.matches(publicationBindingId))
        reading.requireProductPublication()
    }

    private companion object {
        val SHA256 = Regex("^[0-9a-f]{64}$")
    }
}

internal sealed interface ProductMeasurementReadResult {
    data class Exact(val measurement: ProductMeasurement) : ProductMeasurementReadResult
    data object Missing : ProductMeasurementReadResult
    data object TransientFailure : ProductMeasurementReadResult
    data object Conflict : ProductMeasurementReadResult
}

/**
 * Temporary narrow app port until core/data exposes an exact event + publication identity query.
 * Implementations must never fall back to an unbound "latest reading" lookup.
 */
internal fun interface ProductMeasurementSource {
    suspend fun readExact(
        eventId: String,
        approvalId: String,
        publicationBindingId: String,
    ): ProductMeasurementReadResult
}

internal data class ProductAlarmPresentation(
    val episodeId: String,
    val publicationBindingId: String,
    val generation: Long,
    val activeKinds: Set<AlarmKind>,
    val acknowledged: Boolean,
    val openedAtEpochMs: Long,
    val reading: GlucoseReading?,
) {
    init {
        require(SHA256.matches(episodeId))
        require(SHA256.matches(publicationBindingId))
        require(generation > 0)
        require(activeKinds.isNotEmpty())
        require(openedAtEpochMs > 0)
        if (reading == null) {
            require(activeKinds == setOf(AlarmKind.SIGNAL_LOSS))
        } else {
            reading.requireProductPublication()
        }
        require(episodeId == ProductAlarmEpisodeIdentity.derive(publicationBindingId, generation))
    }

    private companion object {
        val SHA256 = Regex("^[0-9a-f]{64}$")
    }
}

internal object ProductAlarmEpisodeIdentity {
    fun derive(publicationBindingId: String, generation: Long): String {
        require(SHA256.matches(publicationBindingId))
        require(generation > 0)
        return MessageDigest.getInstance("SHA-256")
            .digest(
                listOf(
                    SCHEMA,
                    publicationBindingId,
                    generation.toString(),
                ).joinToString("\u0000").toByteArray(Charsets.UTF_8),
            )
            .joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }
    }

    private const val SCHEMA = "product-local-alarm-episode-v1"
    private val SHA256 = Regex("^[0-9a-f]{64}$")
}

internal sealed interface ProductLocalDeliveryEffectResult {
    data object Applied : ProductLocalDeliveryEffectResult
    data object TransientFailure : ProductLocalDeliveryEffectResult
    data object Conflict : ProductLocalDeliveryEffectResult
}

internal interface ProductLocalDeliveryEffects {
    suspend fun show(presentation: ProductAlarmPresentation): ProductLocalDeliveryEffectResult
    suspend fun update(presentation: ProductAlarmPresentation): ProductLocalDeliveryEffectResult
    suspend fun repeat(presentation: ProductAlarmPresentation): ProductLocalDeliveryEffectResult
    suspend fun close(episodeId: String): ProductLocalDeliveryEffectResult
    suspend fun updateWidget(reading: GlucoseReading?): ProductLocalDeliveryEffectResult
}

internal data class ProductAlarmWatchdogMutationRequest(
    val delivery: LocalAlarmDeliveryRecord,
    val currentState: LocalAlarmStateRecord,
    val sourceMeasurement: ProductMeasurement?,
    val evaluatedAtEpochMs: Long,
) {
    init {
        require(delivery.kind == LocalAlarmDeliveryKind.WATCHDOG)
        require(delivery.publicationBindingId == currentState.publicationBindingId)
        require(delivery.approvalId == currentState.approvalId)
        if (delivery.sourceEffectId == MONITORING_START_EFFECT_ID) {
            require(sourceMeasurement == null)
        } else {
            val exactMeasurement = requireNotNull(sourceMeasurement)
            require(exactMeasurement.publicationBindingId == delivery.publicationBindingId)
            require(exactMeasurement.approvalId == delivery.approvalId)
            require(exactMeasurement.reading.eventId == delivery.sourceEventId)
        }
        require(evaluatedAtEpochMs > 0)
    }
}

internal sealed interface ProductAlarmWatchdogMutationResult {
    data object Applied : ProductAlarmWatchdogMutationResult
    data object NoChange : ProductAlarmWatchdogMutationResult
    data object TransientFailure : ProductAlarmWatchdogMutationResult
    data object Conflict : ProductAlarmWatchdogMutationResult
}

/** WATCHDOG is a durable core state mutation, never an app-side state imitation. */
internal fun interface ProductAlarmWatchdogMutationPort {
    suspend fun evaluate(
        request: ProductAlarmWatchdogMutationRequest,
    ): ProductAlarmWatchdogMutationResult
}

internal sealed interface ProductLocalDeliveryWakeResult {
    data object Scheduled : ProductLocalDeliveryWakeResult
    data object TransientFailure : ProductLocalDeliveryWakeResult
    data object Conflict : ProductLocalDeliveryWakeResult
}

internal interface ProductLocalDeliveryWakeScheduler {
    fun schedule(deadlineEpochMs: Long): ProductLocalDeliveryWakeResult
    fun cancel(): ProductLocalDeliveryWakeResult
}

internal sealed interface ProductLocalDeliveryRunResult {
    data class Drained(val processed: Int) : ProductLocalDeliveryRunResult
    data class Waiting(
        val processed: Int,
        val deadlineEpochMs: Long,
    ) : ProductLocalDeliveryRunResult
    data class Yielded(
        val processed: Int,
        val resumeAtEpochMs: Long,
    ) : ProductLocalDeliveryRunResult
    data class TransientFailure(
        val processed: Int,
        val retryAtEpochMs: Long,
    ) : ProductLocalDeliveryRunResult
    data class Degraded(
        val processed: Int,
        val quarantinedDeliveryIds: List<String>,
    ) : ProductLocalDeliveryRunResult {
        init {
            require(quarantinedDeliveryIds.isNotEmpty())
            require(quarantinedDeliveryIds.distinct().size == quarantinedDeliveryIds.size)
        }
    }
    data class Conflict(val processed: Int) : ProductLocalDeliveryRunResult
}

internal fun interface ProductLocalDeliveryDrain {
    suspend fun runBounded(): ProductLocalDeliveryRunResult
}

/**
 * Executes only the store's earliest delivery and remains bounded per wake. Every external effect
 * is idempotent by stable episode/widget identity, so a crash before the durable transition is safe.
 */
internal class ProductLocalDeliveryRunner(
    private val deliveryStore: LocalAlarmDeliveryStore,
    private val alarmStore: LocalAlarmStore,
    private val measurementSource: ProductMeasurementSource,
    private val effects: ProductLocalDeliveryEffects,
    private val watchdogMutation: ProductAlarmWatchdogMutationPort,
    private val wakeScheduler: ProductLocalDeliveryWakeScheduler,
    private val nowEpochMs: () -> Long = System::currentTimeMillis,
    private val nextLeaseToken: () -> String,
    private val maxDeliveriesPerRun: Int = DEFAULT_MAX_DELIVERIES_PER_RUN,
) : ProductLocalDeliveryDrain {
    init {
        require(maxDeliveriesPerRun in 1..MAX_DELIVERIES_PER_RUN)
    }

    override suspend fun runBounded(): ProductLocalDeliveryRunResult {
        var processed = 0
        val quarantinedDeliveryIds = mutableListOf<String>()
        while (processed < maxDeliveriesPerRun) {
            val now = nowEpochMs()
            if (now <= 0 || now > Long.MAX_VALUE - LEASE_MS) {
                return ProductLocalDeliveryRunResult.Conflict(processed)
            }
            val leaseExpiresAt = now + LEASE_MS
            val leaseToken = try {
                nextLeaseToken()
            } catch (_: RuntimeException) {
                return ProductLocalDeliveryRunResult.Conflict(processed)
            }
            val lease = try {
                deliveryStore.leaseDueEarliest(
                    nowEpochMs = now,
                    leaseToken = leaseToken,
                    leaseExpiresAtEpochMs = leaseExpiresAt,
                )
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: RuntimeException) {
                return scheduleTransient(processed, safeAdd(now, RETRY_INTERVAL_MS))
            }
            when (lease) {
                LocalAlarmDeliveryLeaseResult.Empty -> {
                    wakeScheduler.cancel()
                    return degradedOr(
                        ProductLocalDeliveryRunResult.Drained(processed),
                        processed,
                        quarantinedDeliveryIds,
                    )
                }
                is LocalAlarmDeliveryLeaseResult.NotDue ->
                    return degradedOr(
                        scheduleWaiting(processed, lease.notBeforeEpochMs),
                        processed,
                        quarantinedDeliveryIds,
                    )
                is LocalAlarmDeliveryLeaseResult.BlockedByActiveLease ->
                    return degradedOr(
                        scheduleWaiting(processed, lease.leaseExpiresAtEpochMs),
                        processed,
                        quarantinedDeliveryIds,
                    )
                is LocalAlarmDeliveryLeaseResult.Conflict ->
                    return ProductLocalDeliveryRunResult.Conflict(processed)
                is LocalAlarmDeliveryLeaseResult.Leased -> {
                    processed += 1
                    val outcome = processLease(
                        delivery = lease.value,
                        expectedLeaseToken = leaseToken,
                        expectedLeaseExpiry = leaseExpiresAt,
                        now = now,
                    )
                    when (outcome) {
                        LeaseOutcome.Continue -> Unit
                        is LeaseOutcome.Quarantined ->
                            quarantinedDeliveryIds += outcome.deliveryId
                        LeaseOutcome.Conflict ->
                            return outcome.toRunResult(processed, leaseExpiresAt)
                    }
                }
            }
        }
        val resumeAt = safeAdd(nowEpochMs().coerceAtLeast(1L), YIELD_DELAY_MS)
        val result = when (wakeScheduler.schedule(resumeAt)) {
            ProductLocalDeliveryWakeResult.Scheduled ->
                ProductLocalDeliveryRunResult.Yielded(processed, resumeAt)
            ProductLocalDeliveryWakeResult.TransientFailure,
            ProductLocalDeliveryWakeResult.Conflict,
            -> ProductLocalDeliveryRunResult.TransientFailure(processed, resumeAt)
        }
        return degradedOr(result, processed, quarantinedDeliveryIds)
    }

    private suspend fun processLease(
        delivery: LocalAlarmDeliveryRecord,
        expectedLeaseToken: String?,
        expectedLeaseExpiry: Long,
        now: Long,
    ): LeaseOutcome {
        val leaseToken = expectedLeaseToken ?: return LeaseOutcome.Conflict
        if (delivery.state != LocalAlarmDeliveryState.LEASED ||
            delivery.leaseToken != leaseToken ||
            delivery.leaseExpiresAtEpochMs != expectedLeaseExpiry ||
            now < delivery.notBeforeEpochMs || now >= expectedLeaseExpiry
        ) {
            return LeaseOutcome.Conflict
        }
        val state = when (val result = safeReadState(delivery.publicationBindingId)) {
            is AlarmStateRead.Exact -> result.state
            AlarmStateRead.PermanentConflict ->
                return quarantinePermanent(delivery, leaseToken, now)
            AlarmStateRead.TransientFailure -> return retry(delivery, leaseToken, now)
        }
        if (monitoringStartDeliveryIsObsolete(delivery, state)) {
            return markDelivered(delivery, leaseToken, now)
        }
        val measurement = if (delivery.sourceEffectId == MONITORING_START_EFFECT_ID) {
            null
        } else {
            when (val result = safeReadMeasurement(delivery)) {
                is ProductMeasurementReadResult.Exact -> result.measurement
                ProductMeasurementReadResult.TransientFailure ->
                    return retry(delivery, leaseToken, now)
                ProductMeasurementReadResult.Conflict,
                ProductMeasurementReadResult.Missing,
                -> return quarantinePermanent(delivery, leaseToken, now)
            }
        }
        if (!matchesDurableContext(delivery, state, measurement)) {
            return quarantinePermanent(delivery, leaseToken, now)
        }

        if (delivery.kind in VISIBLE_EPISODE_MUTATIONS &&
            !visibleEpisodeMutationIsCurrent(delivery, state)
        ) {
            return markDelivered(delivery, leaseToken, now)
        }

        if (delivery.kind == LocalAlarmDeliveryKind.REPEAT &&
            !repeatStillActive(delivery, state)
        ) {
            return markDelivered(delivery, leaseToken, now)
        }

        val effectResult = safeEffect {
            when (delivery.kind) {
                LocalAlarmDeliveryKind.SHOW -> effects.show(
                    presentation(delivery, measurement?.reading),
                )
                LocalAlarmDeliveryKind.UPDATE -> effects.update(
                    presentation(delivery, measurement?.reading),
                )
                LocalAlarmDeliveryKind.CLOSE -> effects.close(
                    ProductAlarmEpisodeIdentity.derive(
                        delivery.publicationBindingId,
                        delivery.episodeGeneration,
                    ),
                )
                LocalAlarmDeliveryKind.REPEAT -> effects.repeat(
                    presentation(
                        delivery = delivery,
                        reading = measurement?.reading,
                        activeKinds = state.policyState.active,
                        openedAtEpochMs = checkNotNull(state.episodeOpenedAtEpochMs),
                    ),
                )
                LocalAlarmDeliveryKind.WATCHDOG -> when (
                    watchdogMutation.evaluate(
                        ProductAlarmWatchdogMutationRequest(
                            delivery = delivery,
                            currentState = state,
                            sourceMeasurement = measurement,
                            evaluatedAtEpochMs = now,
                        ),
                    )
                ) {
                    ProductAlarmWatchdogMutationResult.Applied,
                    ProductAlarmWatchdogMutationResult.NoChange,
                    -> ProductLocalDeliveryEffectResult.Applied
                    ProductAlarmWatchdogMutationResult.TransientFailure ->
                        ProductLocalDeliveryEffectResult.TransientFailure
                    ProductAlarmWatchdogMutationResult.Conflict ->
                        ProductLocalDeliveryEffectResult.Conflict
                }
                LocalAlarmDeliveryKind.WIDGET -> effects.updateWidget(measurement?.reading)
            }
        }
        return when (effectResult) {
            ProductLocalDeliveryEffectResult.Applied -> if (
                delivery.kind == LocalAlarmDeliveryKind.REPEAT
            ) {
                retry(delivery, leaseToken, now)
            } else {
                markDelivered(delivery, leaseToken, now)
            }
            ProductLocalDeliveryEffectResult.TransientFailure ->
                retry(delivery, leaseToken, now)
            ProductLocalDeliveryEffectResult.Conflict ->
                quarantinePermanent(delivery, leaseToken, now)
        }
    }

    private suspend fun quarantinePermanent(
        delivery: LocalAlarmDeliveryRecord,
        leaseToken: String,
        now: Long,
    ): LeaseOutcome = try {
        when (deliveryStore.quarantine(delivery.deliveryId, leaseToken, now)) {
            LocalAlarmDeliveryTransitionResult.Applied,
            LocalAlarmDeliveryTransitionResult.AlreadyApplied,
            -> LeaseOutcome.Quarantined(delivery.deliveryId)
            is LocalAlarmDeliveryTransitionResult.Conflict -> LeaseOutcome.Conflict
        }
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: RuntimeException) {
        LeaseOutcome.Conflict
    }

    private suspend fun safeReadState(bindingId: String): AlarmStateRead = try {
        when (val result = alarmStore.readState(bindingId)) {
            is LocalAlarmStateReadResult.Exact -> AlarmStateRead.Exact(result.state)
            LocalAlarmStateReadResult.Empty,
            is LocalAlarmStateReadResult.Conflict,
            -> AlarmStateRead.PermanentConflict
        }
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: RuntimeException) {
        AlarmStateRead.TransientFailure
    }

    private suspend fun safeReadMeasurement(
        delivery: LocalAlarmDeliveryRecord,
    ): ProductMeasurementReadResult = try {
        measurementSource.readExact(
            eventId = delivery.sourceEventId,
            approvalId = delivery.approvalId,
            publicationBindingId = delivery.publicationBindingId,
        )
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: RuntimeException) {
        ProductMeasurementReadResult.TransientFailure
    }

    private suspend fun safeEffect(
        block: suspend () -> ProductLocalDeliveryEffectResult,
    ): ProductLocalDeliveryEffectResult = try {
        block()
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: RuntimeException) {
        ProductLocalDeliveryEffectResult.TransientFailure
    }

    private fun matchesDurableContext(
        delivery: LocalAlarmDeliveryRecord,
        state: LocalAlarmStateRecord,
        measurement: ProductMeasurement?,
    ): Boolean {
        return try {
            state.requireCanonical()
            if (delivery.sourceEffectId == MONITORING_START_EFFECT_ID) {
                delivery.publicationBindingId == state.publicationBindingId &&
                delivery.approvalId == state.approvalId &&
                measurement == null &&
                validDeliveryShape(delivery) &&
                if (delivery.kind == LocalAlarmDeliveryKind.WATCHDOG &&
                    delivery.resultingStateSha256 != state.stateSha256
                ) {
                    true
                } else {
                    state.lastEffectId == MONITORING_START_EFFECT_ID &&
                        delivery.sourceEventId == state.lastEventId &&
                        delivery.episodeGeneration <= state.episodeGeneration &&
                        exactStateOrAcknowledgedRepeat(delivery, state)
                }
            } else {
                val exactMeasurement = requireNotNull(measurement)
                exactMeasurement.reading.requireProductPublication()
                delivery.publicationBindingId == state.publicationBindingId &&
                    delivery.approvalId == state.approvalId &&
                    exactMeasurement.publicationBindingId == delivery.publicationBindingId &&
                    exactMeasurement.approvalId == delivery.approvalId &&
                    exactMeasurement.reading.eventId == delivery.sourceEventId &&
                    delivery.sourceEffectId <= state.lastEffectId &&
                    exactMeasurement.reading.sequence <= state.lastSequence &&
                    delivery.episodeGeneration <= state.episodeGeneration &&
                    validDeliveryShape(delivery) &&
                    if (delivery.sourceEffectId == state.lastEffectId) {
                        delivery.sourceEventId == state.lastEventId &&
                            exactMeasurement.reading.sequence == state.lastSequence &&
                            exactStateOrAcknowledgedRepeat(delivery, state)
                    } else {
                        true
                    }
            }
        } catch (_: IllegalArgumentException) {
            false
        }
    }

    private fun monitoringStartDeliveryIsObsolete(
        delivery: LocalAlarmDeliveryRecord,
        state: LocalAlarmStateRecord,
    ): Boolean = delivery.sourceEffectId == MONITORING_START_EFFECT_ID &&
        delivery.kind != LocalAlarmDeliveryKind.WATCHDOG &&
        (
            state.lastEffectId != MONITORING_START_EFFECT_ID ||
                state.lastEventId != delivery.sourceEventId ||
                state.episodeGeneration != delivery.episodeGeneration ||
                state.policyState.active != delivery.activeKinds ||
                state.episodeAcknowledged != delivery.episodeAcknowledged
            )

    private fun exactStateOrAcknowledgedRepeat(
        delivery: LocalAlarmDeliveryRecord,
        state: LocalAlarmStateRecord,
    ): Boolean {
        val exactReducerState = delivery.createdAtEpochMs == state.updatedAtEpochMs &&
            delivery.resultingStateSha256 == state.stateSha256 &&
            delivery.activeKinds == state.policyState.active &&
            delivery.episodeGeneration == state.episodeGeneration &&
            delivery.episodeAcknowledged == state.episodeAcknowledged
        if (exactReducerState) return true
        val acknowledgedAt = state.episodeAcknowledgedAtEpochMs
        return delivery.kind == LocalAlarmDeliveryKind.REPEAT &&
            !delivery.episodeAcknowledged && state.episodeAcknowledged &&
            state.episodeGeneration == delivery.episodeGeneration &&
            state.policyState.active == delivery.activeKinds &&
            acknowledgedAt != null && acknowledgedAt >= delivery.createdAtEpochMs
    }

    private fun validDeliveryShape(delivery: LocalAlarmDeliveryRecord): Boolean = when (
        delivery.kind
    ) {
        LocalAlarmDeliveryKind.SHOW,
        LocalAlarmDeliveryKind.UPDATE,
        LocalAlarmDeliveryKind.REPEAT,
        -> delivery.episodeGeneration > 0 && delivery.activeKinds.isNotEmpty()
        LocalAlarmDeliveryKind.CLOSE ->
            delivery.episodeGeneration > 0 && delivery.activeKinds.isEmpty()
        LocalAlarmDeliveryKind.WATCHDOG,
        LocalAlarmDeliveryKind.WIDGET,
        -> true
    }

    private fun repeatStillActive(
        delivery: LocalAlarmDeliveryRecord,
        state: LocalAlarmStateRecord,
    ): Boolean = state.episodeGeneration == delivery.episodeGeneration &&
        state.policyState.active.isNotEmpty() &&
        !state.episodeAcknowledged

    private fun visibleEpisodeMutationIsCurrent(
        delivery: LocalAlarmDeliveryRecord,
        state: LocalAlarmStateRecord,
    ): Boolean = state.episodeGeneration == delivery.episodeGeneration &&
        state.policyState.active.isNotEmpty() &&
        state.policyState.active == delivery.activeKinds &&
        state.episodeAcknowledged == delivery.episodeAcknowledged

    private fun presentation(
        delivery: LocalAlarmDeliveryRecord,
        reading: GlucoseReading?,
        activeKinds: Set<AlarmKind> = delivery.activeKinds,
        openedAtEpochMs: Long = delivery.createdAtEpochMs,
    ) = ProductAlarmPresentation(
        episodeId = ProductAlarmEpisodeIdentity.derive(
            delivery.publicationBindingId,
            delivery.episodeGeneration,
        ),
        publicationBindingId = delivery.publicationBindingId,
        generation = delivery.episodeGeneration,
        activeKinds = activeKinds,
        acknowledged = delivery.episodeAcknowledged,
        openedAtEpochMs = openedAtEpochMs,
        reading = reading,
    )

    private suspend fun markDelivered(
        delivery: LocalAlarmDeliveryRecord,
        leaseToken: String,
        now: Long,
    ): LeaseOutcome = try {
        when (deliveryStore.markDelivered(delivery.deliveryId, leaseToken, now)) {
            LocalAlarmDeliveryTransitionResult.Applied,
            LocalAlarmDeliveryTransitionResult.AlreadyApplied,
            -> LeaseOutcome.Continue
            is LocalAlarmDeliveryTransitionResult.Conflict -> LeaseOutcome.Conflict
        }
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: RuntimeException) {
        LeaseOutcome.Conflict
    }

    private suspend fun retry(
        delivery: LocalAlarmDeliveryRecord,
        leaseToken: String,
        now: Long,
    ): LeaseOutcome {
        val next = safeAdd(now, RETRY_INTERVAL_MS)
        if (next <= now) return LeaseOutcome.Conflict
        return try {
            when (deliveryStore.reschedule(
                deliveryId = delivery.deliveryId,
                leaseToken = leaseToken,
                rescheduledAtEpochMs = now,
                nextAttemptEpochMs = next,
            )) {
                LocalAlarmDeliveryTransitionResult.Applied,
                LocalAlarmDeliveryTransitionResult.AlreadyApplied,
                -> LeaseOutcome.Continue
                is LocalAlarmDeliveryTransitionResult.Conflict -> LeaseOutcome.Conflict
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: RuntimeException) {
            LeaseOutcome.Conflict
        }
    }

    private fun LeaseOutcome.toRunResult(
        processed: Int,
        leaseExpiresAtEpochMs: Long,
    ): ProductLocalDeliveryRunResult = when (this) {
        LeaseOutcome.Continue -> error("Continue is handled by the drain loop")
        is LeaseOutcome.Quarantined -> error("Quarantined is handled by the drain loop")
        LeaseOutcome.Conflict -> {
            wakeScheduler.schedule(leaseExpiresAtEpochMs)
            ProductLocalDeliveryRunResult.Conflict(processed)
        }
    }

    private fun scheduleWaiting(
        processed: Int,
        deadlineEpochMs: Long,
    ): ProductLocalDeliveryRunResult = when (wakeScheduler.schedule(deadlineEpochMs)) {
        ProductLocalDeliveryWakeResult.Scheduled ->
            ProductLocalDeliveryRunResult.Waiting(processed, deadlineEpochMs)
        ProductLocalDeliveryWakeResult.TransientFailure,
        ProductLocalDeliveryWakeResult.Conflict,
        -> ProductLocalDeliveryRunResult.TransientFailure(processed, deadlineEpochMs)
    }

    private fun scheduleTransient(
        processed: Int,
        retryAtEpochMs: Long,
    ): ProductLocalDeliveryRunResult {
        wakeScheduler.schedule(retryAtEpochMs)
        return ProductLocalDeliveryRunResult.TransientFailure(processed, retryAtEpochMs)
    }

    private fun degradedOr(
        result: ProductLocalDeliveryRunResult,
        processed: Int,
        quarantinedDeliveryIds: List<String>,
    ): ProductLocalDeliveryRunResult = if (quarantinedDeliveryIds.isEmpty()) {
        result
    } else {
        ProductLocalDeliveryRunResult.Degraded(
            processed = processed,
            quarantinedDeliveryIds = quarantinedDeliveryIds.toList(),
        )
    }

    private sealed interface LeaseOutcome {
        data object Continue : LeaseOutcome
        data class Quarantined(val deliveryId: String) : LeaseOutcome
        data object Conflict : LeaseOutcome
    }

    private sealed interface AlarmStateRead {
        data class Exact(val state: LocalAlarmStateRecord) : AlarmStateRead
        data object PermanentConflict : AlarmStateRead
        data object TransientFailure : AlarmStateRead
    }

    private companion object {
        const val LEASE_MS = 10_000L
        const val RETRY_INTERVAL_MS = 2 * 60_000L
        const val YIELD_DELAY_MS = 1_000L
        const val DEFAULT_MAX_DELIVERIES_PER_RUN = 32
        const val MAX_DELIVERIES_PER_RUN = 128
        val VISIBLE_EPISODE_MUTATIONS = setOf(
            LocalAlarmDeliveryKind.SHOW,
            LocalAlarmDeliveryKind.UPDATE,
        )

        fun safeAdd(value: Long, delta: Long): Long =
            if (value > Long.MAX_VALUE - delta) Long.MAX_VALUE else value + delta
    }
}
