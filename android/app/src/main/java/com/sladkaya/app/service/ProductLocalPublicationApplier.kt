package com.sladkaya.app.service

import com.sladkaya.core.data.AlarmThresholdSnapshot
import com.sladkaya.core.data.LocalAlarmApplyRequest
import com.sladkaya.core.data.LocalAlarmApplyResult
import com.sladkaya.core.data.LocalAlarmSettlement
import com.sladkaya.core.data.LocalAlarmSettlementReadResult
import com.sladkaya.core.data.LocalAlarmStateReadResult
import com.sladkaya.core.data.LocalAlarmStore
import com.sladkaya.core.data.LocalReadingEffectLeaseResult
import com.sladkaya.core.model.AlarmKind
import com.sladkaya.core.model.AlarmThresholds

/** Settles the exact durable local effects before a product batch may reach UI. */
internal class ProductLocalPublicationApplier(
    private val store: LocalAlarmStore,
    private val thresholds: () -> AlarmThresholds,
    private val monitoringStartedAtEpochMs: Long,
    private val nowEpochMs: () -> Long = System::currentTimeMillis,
    private val nextLeaseToken: () -> String,
    private val afterDurableBatch: () -> Unit = {},
) : ProductPublicationApplier {
    override suspend fun apply(
        configuration: ProductSensorConfiguration,
        publications: List<ProductCommittedReading>,
    ): ProductPublicationApplyResult {
        if (!ProductPublicationContract.accepts(configuration, publications)) {
            return ProductPublicationApplyResult.Conflict
        }
        var activeAlarms = emptySet<AlarmKind>()
        for (publication in publications) {
            when (
                val saved = store.readSettlement(
                    eventId = publication.reading.eventId,
                    approvalId = publication.approvalId,
                    publicationBindingId = publication.publicationBindingId,
                )
            ) {
                is LocalAlarmSettlementReadResult.Exact -> {
                    if (!saved.settlement.matches(publication)) {
                        return ProductPublicationApplyResult.Conflict
                    }
                    activeAlarms = saved.settlement.activeKinds
                    continue
                }
                LocalAlarmSettlementReadResult.Missing -> Unit
                is LocalAlarmSettlementReadResult.Conflict ->
                    return ProductPublicationApplyResult.Conflict
            }

            val previous = when (
                val state = store.readState(publication.publicationBindingId)
            ) {
                is LocalAlarmStateReadResult.Exact -> state.state
                LocalAlarmStateReadResult.Empty -> null
                is LocalAlarmStateReadResult.Conflict ->
                    return ProductPublicationApplyResult.Conflict
            }
            val now = nowEpochMs()
            val leaseExpiry = safeAdd(now, LOCAL_EFFECT_LEASE_MS)
            val lease = when (
                val result = store.leaseEarliest(
                    nowEpochMs = now,
                    leaseToken = nextLeaseToken(),
                    leaseExpiresAtEpochMs = leaseExpiry,
                )
            ) {
                is LocalReadingEffectLeaseResult.Leased -> result.value
                LocalReadingEffectLeaseResult.Empty,
                is LocalReadingEffectLeaseResult.BlockedByActiveLease,
                -> return ProductPublicationApplyResult.StorageUnavailable
                is LocalReadingEffectLeaseResult.Conflict ->
                    return ProductPublicationApplyResult.Conflict
            }
            if (lease.reading != publication.reading ||
                lease.effect.eventId != publication.reading.eventId ||
                lease.effect.approvalId != publication.approvalId ||
                lease.effect.publicationBindingId != publication.publicationBindingId
            ) {
                return ProductPublicationApplyResult.Conflict
            }
            val thresholdSnapshot = try {
                AlarmThresholdSnapshot.from(thresholds())
            } catch (_: IllegalArgumentException) {
                return ProductPublicationApplyResult.Conflict
            }
            val request = LocalAlarmApplyRequest(
                effectId = lease.effect.effectId,
                eventId = lease.effect.eventId,
                leaseToken = checkNotNull(lease.effect.leaseToken),
                processedAtEpochMs = now,
                monitoringStartedAtEpochMs = previous?.monitoringStartedAtEpochMs
                    ?: monitoringStartedAtEpochMs,
                thresholds = thresholdSnapshot,
                expectedPreviousThresholdFingerprint = previous?.thresholds?.fingerprint,
            )
            val settlement = when (val applied = store.applyLeased(request)) {
                is LocalAlarmApplyResult.Applied -> applied.settlement
                is LocalAlarmApplyResult.AlreadyApplied -> applied.settlement
                is LocalAlarmApplyResult.Conflict -> return ProductPublicationApplyResult.Conflict
            }
            if (!settlement.matches(publication)) {
                return ProductPublicationApplyResult.Conflict
            }
            activeAlarms = settlement.activeKinds
        }
        try {
            afterDurableBatch()
        } catch (_: RuntimeException) {
            // Local display and alarms never depend on the optional remote scheduler.
        }
        return ProductPublicationApplyResult.Applied(
            verifiedReadings = publications.map(ProductCommittedReading::reading),
            activeAlarms = activeAlarms,
        )
    }

    private fun LocalAlarmSettlement.matches(publication: ProductCommittedReading): Boolean =
        eventId == publication.reading.eventId &&
            approvalId == publication.approvalId &&
            publicationBindingId == publication.publicationBindingId

    private fun safeAdd(value: Long, delta: Long): Long =
        if (value > Long.MAX_VALUE - delta) Long.MAX_VALUE else value + delta

    private companion object {
        const val LOCAL_EFFECT_LEASE_MS = 10_000L
    }
}
