package com.sladkaya.app.service

import com.sladkaya.core.data.AlarmThresholdSnapshot
import com.sladkaya.core.data.LeasedLocalReadingEffect
import com.sladkaya.core.data.LocalAlarmApplyRequest
import com.sladkaya.core.data.LocalAlarmApplyResult
import com.sladkaya.core.data.LocalAlarmEpisodeAcknowledgeResult
import com.sladkaya.core.data.LocalAlarmSettlement
import com.sladkaya.core.data.LocalAlarmSettlementReadResult
import com.sladkaya.core.data.LocalAlarmStateReadResult
import com.sladkaya.core.data.LocalAlarmStore
import com.sladkaya.core.data.LocalAlarmWatchdogResult
import com.sladkaya.core.data.LocalReadingEffectLeaseResult
import com.sladkaya.core.data.LocalReadingEffectRecord
import com.sladkaya.core.data.LocalReadingEffectState
import com.sladkaya.core.model.AlarmKind
import com.sladkaya.core.model.AlarmThresholds
import com.sladkaya.core.model.GlucoseReading
import com.sladkaya.core.model.ReadingQuality
import com.sladkaya.core.model.SensorFamily
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ProductLocalPublicationApplierTest {
    @Test
    fun batchIsAppliedInExactFifoOrderAndReturnsFinalAlarmState() = runBlocking {
        val first = publication("event-1", sequence = 1, glucoseMgDl = 54)
        val second = publication("event-2", sequence = 2, glucoseMgDl = 53)
        val store = FakeLocalAlarmStore(listOf(first, second))
        var postCommitCalls = 0
        val applier = ProductLocalPublicationApplier(
            store = store,
            thresholds = { AlarmThresholds() },
            monitoringStartedAtEpochMs = 1_699_999_900_000L,
            nowEpochMs = sequenceClock(1_700_000_001_100L),
            nextLeaseToken = sequenceTokens(),
            afterDurableBatch = { postCommitCalls += 1 },
        )

        val result = applier.apply(configuration(), listOf(first, second))

        assertTrue(result is ProductPublicationApplyResult.Applied)
        result as ProductPublicationApplyResult.Applied
        assertEquals(listOf(first.reading, second.reading), result.verifiedReadings)
        assertEquals(setOf(AlarmKind.LOW), result.activeAlarms)
        assertEquals(listOf("event-1", "event-2"), store.applied.map { it.eventId })
        assertEquals(null, store.applied.first().expectedPreviousThresholdFingerprint)
        assertEquals(
            AlarmThresholdSnapshot.from(AlarmThresholds()).fingerprint,
            store.applied.last().expectedPreviousThresholdFingerprint,
        )
        assertEquals(1, postCommitCalls)
    }

    @Test
    fun retryAfterCrashUsesExactSettlementAndContinuesWithoutReapplyingIt() = runBlocking {
        val first = publication("event-1", sequence = 1, glucoseMgDl = 54)
        val second = publication("event-2", sequence = 2, glucoseMgDl = 53)
        val store = FakeLocalAlarmStore(listOf(second)).apply {
            settlements[first.reading.eventId] = settlement(first, setOf(AlarmKind.LOW), 1L)
        }
        val applier = ProductLocalPublicationApplier(
            store = store,
            thresholds = { AlarmThresholds() },
            monitoringStartedAtEpochMs = 1_699_999_900_000L,
            nowEpochMs = sequenceClock(1_700_000_001_100L),
            nextLeaseToken = sequenceTokens(),
        )

        val result = applier.apply(configuration(), listOf(first, second))

        assertTrue(result is ProductPublicationApplyResult.Applied)
        assertEquals(listOf("event-2"), store.applied.map { it.eventId })
        assertEquals(listOf(first.reading, second.reading),
            (result as ProductPublicationApplyResult.Applied).verifiedReadings)
    }

    @Test
    fun wrongEarliestEffectFailsWithoutApplyingAnotherReading() = runBlocking {
        val expected = publication("event-2", sequence = 2, glucoseMgDl = 100)
        val wrong = publication("event-1", sequence = 1, glucoseMgDl = 100)
        val store = FakeLocalAlarmStore(listOf(wrong))
        val applier = ProductLocalPublicationApplier(
            store = store,
            thresholds = { AlarmThresholds() },
            monitoringStartedAtEpochMs = 1_699_999_900_000L,
            nowEpochMs = { 1_700_000_001_100L },
            nextLeaseToken = { "lease-token-0001" },
        )

        assertEquals(
            ProductPublicationApplyResult.Conflict,
            applier.apply(configuration(), listOf(expected)),
        )
        assertTrue(store.applied.isEmpty())
    }

    @Test
    fun temporarilyUnavailableEffectStoreDoesNotPublishToUi() = runBlocking {
        val publication = publication("event-1", sequence = 1, glucoseMgDl = 100)
        val store = FakeLocalAlarmStore(emptyList())
        val applier = ProductLocalPublicationApplier(
            store = store,
            thresholds = { AlarmThresholds() },
            monitoringStartedAtEpochMs = 1_699_999_900_000L,
            nowEpochMs = { 1_700_000_001_100L },
            nextLeaseToken = { "lease-token-0001" },
        )

        assertEquals(
            ProductPublicationApplyResult.StorageUnavailable,
            applier.apply(configuration(), listOf(publication)),
        )
    }

    @Test
    fun remoteSchedulingFailureCannotBreakTheDurableLocalBatch() = runBlocking {
        val publication = publication("event-1", sequence = 1, glucoseMgDl = 100)
        val applier = ProductLocalPublicationApplier(
            store = FakeLocalAlarmStore(listOf(publication)),
            thresholds = { AlarmThresholds() },
            monitoringStartedAtEpochMs = 1_699_999_900_000L,
            nowEpochMs = { 1_700_000_001_100L },
            nextLeaseToken = { "lease-token-0001" },
            afterDurableBatch = { error("WorkManager unavailable") },
        )

        assertTrue(
            applier.apply(configuration(), listOf(publication)) is
                ProductPublicationApplyResult.Applied,
        )
    }

    private fun configuration(): ProductSensorConfiguration {
        val profile = com.sladkaya.sensor.sibionics.Gs1DiagnosticActivationProfile.validate(
            sensorId = "sensor-approved",
            family = SensorFamily.SIBIONICS_GS1,
            bluetoothAddress = "AA:BB:CC:DD:EE:22",
            transportVariant = 2,
            packageCode = "Ab12Cd34",
        ) as com.sladkaya.sensor.sibionics.Gs1DiagnosticActivationProfileValidation.Valid
        return ProductSensorConfiguration(
            profile = profile.profile,
            approvalId = APPROVAL,
            publicationBindingId = BINDING,
        )
    }

    private fun publication(
        eventId: String,
        sequence: Long,
        glucoseMgDl: Int,
    ) = ProductCommittedReading(
        reading = GlucoseReading(
            eventId = eventId,
            sensorId = "sensor-approved",
            sensorFamily = SensorFamily.SIBIONICS_GS1,
            sensorTimeEpochMs = 1_700_000_000_000L + sequence * 60_000L,
            phoneTimeEpochMs = 1_700_000_001_000L + sequence * 60_000L,
            glucoseMgDl = glucoseMgDl,
            trendMgDlPerMinute = 0.0,
            quality = ReadingQuality.VALID,
            sequence = sequence,
        ),
        approvalId = APPROVAL,
        publicationBindingId = BINDING,
    )

    private fun settlement(
        publication: ProductCommittedReading,
        active: Set<AlarmKind>,
        effectId: Long,
    ) = LocalAlarmSettlement(
        effectId = effectId,
        eventId = publication.reading.eventId,
        approvalId = publication.approvalId,
        publicationBindingId = publication.publicationBindingId,
        activeKinds = active,
        episodeGeneration = if (active.isEmpty()) 0 else 1,
        episodeAcknowledged = false,
        thresholdFingerprint = AlarmThresholdSnapshot.from(AlarmThresholds()).fingerprint,
        resultingStateSha256 = "33".repeat(32),
        appliedAtEpochMs = publication.reading.phoneTimeEpochMs + 100L,
        deliveryIds = listOf("44".repeat(32)),
    )

    private fun sequenceClock(start: Long): () -> Long {
        var value = start
        return { value.also { value += 1_000L } }
    }

    private fun sequenceTokens(): () -> String {
        var value = 0
        return { "lease-token-${++value}" }
    }

    private inner class FakeLocalAlarmStore(
        publications: List<ProductCommittedReading>,
    ) : LocalAlarmStore {
        private val pending = ArrayDeque(publications.mapIndexed { index, publication ->
            LeasedLocalReadingEffect(
                effect = LocalReadingEffectRecord(
                    effectId = (index + 1).toLong(),
                    eventId = publication.reading.eventId,
                    approvalId = publication.approvalId,
                    publicationBindingId = publication.publicationBindingId,
                    state = LocalReadingEffectState.LEASED,
                    attempts = 1,
                    enqueuedAtEpochMs = publication.reading.phoneTimeEpochMs,
                    leaseToken = "placeholder-token",
                    leaseExpiresAtEpochMs = Long.MAX_VALUE,
                    lastTransitionToken = null,
                    acknowledgedAtEpochMs = null,
                ),
                reading = publication.reading,
            )
        })
        val settlements = linkedMapOf<String, LocalAlarmSettlement>()
        val applied = mutableListOf<LocalAlarmApplyRequest>()

        override suspend fun leaseEarliest(
            nowEpochMs: Long,
            leaseToken: String,
            leaseExpiresAtEpochMs: Long,
        ): LocalReadingEffectLeaseResult {
            val next = pending.removeFirstOrNull() ?: return LocalReadingEffectLeaseResult.Empty
            return LocalReadingEffectLeaseResult.Leased(
                next.copy(
                    effect = next.effect.copy(
                        leaseToken = leaseToken,
                        leaseExpiresAtEpochMs = leaseExpiresAtEpochMs,
                    ),
                ),
            )
        }

        override suspend fun applyLeased(request: LocalAlarmApplyRequest): LocalAlarmApplyResult {
            applied += request
            val active = setOf(AlarmKind.LOW)
            val publication = ProductCommittedReading(
                reading = GlucoseReading(
                    eventId = request.eventId,
                    sensorId = "sensor-approved",
                    sensorFamily = SensorFamily.SIBIONICS_GS1,
                    sensorTimeEpochMs = request.processedAtEpochMs - 1_000L,
                    phoneTimeEpochMs = request.processedAtEpochMs,
                    glucoseMgDl = 54,
                    trendMgDlPerMinute = 0.0,
                    quality = ReadingQuality.VALID,
                    sequence = request.effectId,
                ),
                approvalId = APPROVAL,
                publicationBindingId = BINDING,
            )
            val settlement = settlement(publication, active, request.effectId)
            settlements[request.eventId] = settlement
            return LocalAlarmApplyResult.Applied(settlement)
        }

        override suspend fun readSettlement(
            eventId: String,
            approvalId: String,
            publicationBindingId: String,
        ): LocalAlarmSettlementReadResult = settlements[eventId]
            ?.let(LocalAlarmSettlementReadResult::Exact)
            ?: LocalAlarmSettlementReadResult.Missing

        override suspend fun readState(
            publicationBindingId: String,
        ): LocalAlarmStateReadResult {
            val last = settlements.values.lastOrNull() ?: return LocalAlarmStateReadResult.Empty
            return LocalAlarmStateReadResult.Exact(
                com.sladkaya.core.data.LocalAlarmStateRecord(
                    publicationBindingId = BINDING,
                    approvalId = APPROVAL,
                    monitoringStartedAtEpochMs = 1_699_999_900_000L,
                    policyState = com.sladkaya.core.model.AlarmPolicyState(
                        active = last.activeKinds,
                        latestFreshSensorTimeEpochMs = last.appliedAtEpochMs - 1_000L,
                        latestFreshPhoneTimeEpochMs = last.appliedAtEpochMs,
                        phoneClockMovedBackwards = false,
                    ),
                    lastEffectId = last.effectId,
                    lastEventId = last.eventId,
                    lastSequence = last.effectId,
                    thresholds = AlarmThresholdSnapshot.from(AlarmThresholds()),
                    episodeGeneration = last.episodeGeneration,
                    episodeAcknowledged = false,
                    episodeOpenedAtEpochMs = last.appliedAtEpochMs,
                    updatedAtEpochMs = last.appliedAtEpochMs,
                    stateSha256 = "",
                ).canonicalized(),
            )
        }

        override suspend fun acknowledgeEpisode(
            publicationBindingId: String,
            expectedEpisodeGeneration: Long,
            acknowledgedAtEpochMs: Long,
        ): LocalAlarmEpisodeAcknowledgeResult = error("Not used by publication applier tests")

        override suspend fun applyWatchdog(
            publicationBindingId: String,
            expectedStateSha256: String,
            nowEpochMs: Long,
        ): LocalAlarmWatchdogResult = error("Not used by publication applier tests")
    }

    private companion object {
        const val APPROVAL =
            "1111111111111111111111111111111111111111111111111111111111111111"
        const val BINDING =
            "2222222222222222222222222222222222222222222222222222222222222222"
    }
}
