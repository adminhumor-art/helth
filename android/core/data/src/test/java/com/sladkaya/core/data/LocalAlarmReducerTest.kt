package com.sladkaya.core.data

import com.sladkaya.core.model.AlarmKind
import com.sladkaya.core.model.AlarmThresholds
import com.sladkaya.core.model.GlucoseReading
import com.sladkaya.core.model.ReadingQuality
import com.sladkaya.core.model.SensorFamily
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalAlarmReducerTest {
    @Test
    fun firstLowReadingPersistsCompleteStateAndDeterministicDeliveryPlan() {
        val effect = leasedEffect(effectId = 11L, eventId = "event-11", sequence = 11L, glucose = 60)
        val request = request(effect)

        val result = LocalAlarmReducer.reduce(previous = null, effect = effect, request = request)

        assertEquals(setOf(AlarmKind.LOW), result.state.policyState.active)
        assertEquals(effect.reading.sensorTimeEpochMs, result.state.policyState.latestFreshSensorTimeEpochMs)
        assertEquals(effect.reading.phoneTimeEpochMs, result.state.policyState.latestFreshPhoneTimeEpochMs)
        assertFalse(result.state.policyState.phoneClockMovedBackwards)
        assertEquals(MONITORING_STARTED_AT, result.state.monitoringStartedAtEpochMs)
        assertEquals(effect.effect.effectId, result.state.lastEffectId)
        assertEquals(effect.effect.eventId, result.state.lastEventId)
        assertEquals(effect.reading.sequence, result.state.lastSequence)
        assertEquals(request.thresholds, result.state.thresholds)
        assertEquals(1L, result.state.episodeGeneration)
        assertFalse(result.state.episodeAcknowledged)
        assertEquals(PROCESSED_AT, result.state.episodeOpenedAtEpochMs)
        assertEquals(
            setOf(
                LocalAlarmDeliveryKind.SHOW,
                LocalAlarmDeliveryKind.REPEAT,
                LocalAlarmDeliveryKind.WATCHDOG,
                LocalAlarmDeliveryKind.WIDGET,
            ),
            result.deliveries.mapTo(linkedSetOf(), LocalAlarmDeliveryDraft::kind),
        )
        assertEquals(
            result.deliveries.map(LocalAlarmDeliveryDraft::deliveryId).distinct().size,
            result.deliveries.size,
        )
        assertTrue(result.deliveries.all { it.resultingStateSha256 == result.state.stateSha256 })
    }

    @Test
    fun unchangedEpisodeKeepsGenerationWhileRecoveryClosesIt() {
        val firstEffect = leasedEffect(11L, "event-11", 11L, glucose = 60)
        val first = LocalAlarmReducer.reduce(null, firstEffect, request(firstEffect))
        val stillLowEffect = leasedEffect(12L, "event-12", 12L, glucose = 62)

        val stillLow = LocalAlarmReducer.reduce(
            previous = first.state,
            effect = stillLowEffect,
            request = request(
                stillLowEffect,
                expectedPreviousThresholdFingerprint = first.state.thresholds.fingerprint,
                processedAt = PROCESSED_AT + 60_000L,
            ),
        )

        assertEquals(1L, stillLow.state.episodeGeneration)
        assertEquals(
            setOf(LocalAlarmDeliveryKind.WATCHDOG, LocalAlarmDeliveryKind.WIDGET),
            stillLow.deliveries.mapTo(linkedSetOf(), LocalAlarmDeliveryDraft::kind),
        )

        val recoveredEffect = leasedEffect(13L, "event-13", 13L, glucose = 100)
        val recovered = LocalAlarmReducer.reduce(
            previous = stillLow.state,
            effect = recoveredEffect,
            request = request(
                recoveredEffect,
                expectedPreviousThresholdFingerprint = stillLow.state.thresholds.fingerprint,
                processedAt = PROCESSED_AT + 120_000L,
            ),
        )

        assertTrue(recovered.state.policyState.active.isEmpty())
        assertEquals(1L, recovered.state.episodeGeneration)
        assertFalse(recovered.state.episodeAcknowledged)
        assertNull(recovered.state.episodeOpenedAtEpochMs)
        assertEquals(
            setOf(
                LocalAlarmDeliveryKind.CLOSE,
                LocalAlarmDeliveryKind.WATCHDOG,
                LocalAlarmDeliveryKind.WIDGET,
            ),
            recovered.deliveries.mapTo(linkedSetOf(), LocalAlarmDeliveryDraft::kind),
        )
    }

    @Test
    fun newlyOpenedKindStartsANewUnacknowledgedGeneration() {
        val firstEffect = leasedEffect(11L, "event-11", 11L, glucose = 60)
        val first = LocalAlarmReducer.reduce(null, firstEffect, request(firstEffect))
        val acknowledged = first.state.copy(
            episodeAcknowledged = true,
            episodeAcknowledgedAtEpochMs = first.state.updatedAtEpochMs + 1L,
        ).canonicalized()
        val rapidRise = leasedEffect(
            effectId = 12L,
            eventId = "event-12",
            sequence = 12L,
            glucose = 61,
            trend = 4.0,
        )

        val result = LocalAlarmReducer.reduce(
            previous = acknowledged,
            effect = rapidRise,
            request = request(
                rapidRise,
                expectedPreviousThresholdFingerprint = acknowledged.thresholds.fingerprint,
                processedAt = PROCESSED_AT + 60_000L,
            ),
        )

        assertEquals(setOf(AlarmKind.LOW, AlarmKind.RAPID_RISE), result.state.policyState.active)
        assertEquals(2L, result.state.episodeGeneration)
        assertFalse(result.state.episodeAcknowledged)
        assertTrue(result.deliveries.any { it.kind == LocalAlarmDeliveryKind.SHOW })
        assertTrue(result.deliveries.any { it.kind == LocalAlarmDeliveryKind.REPEAT })
    }

    @Test
    fun firstVerifiedReadingClosesStartupSignalLossWithoutOpeningDuplicateEpisode() {
        val thresholds = AlarmThresholdSnapshot.from(AlarmThresholds())
        val startup = LocalAlarmMonitoringStartReducer.reduce(
            LocalAlarmMonitoringStartRequest(
                publicationBindingId = PUBLICATION_BINDING_ID,
                approvalId = APPROVAL_ID,
                monitoringStartedAtEpochMs = MONITORING_STARTED_AT,
                approvedSequence = 10L,
                thresholds = thresholds,
            ),
        )
        val signalLoss = LocalAlarmWatchdogReducer.reduce(
            startup.state,
            MONITORING_STARTED_AT + thresholds.staleAfterMs + 1L,
        ).state
        val firstReading = leasedEffect(11L, "event-11", 11L, glucose = 100)

        val recovered = LocalAlarmReducer.reduce(
            previous = signalLoss,
            effect = firstReading,
            request = request(
                firstReading,
                expectedPreviousThresholdFingerprint = signalLoss.thresholds.fingerprint,
            ),
        )

        assertTrue(recovered.state.policyState.active.isEmpty())
        assertEquals(1L, recovered.state.episodeGeneration)
        assertEquals(
            setOf(
                LocalAlarmDeliveryKind.CLOSE,
                LocalAlarmDeliveryKind.WATCHDOG,
                LocalAlarmDeliveryKind.WIDGET,
            ),
            recovered.deliveries.mapTo(linkedSetOf(), LocalAlarmDeliveryDraft::kind),
        )
        assertFalse(recovered.deliveries.any { it.kind == LocalAlarmDeliveryKind.SHOW })
    }

    @Test
    fun thresholdSnapshotFingerprintIsExactAndRoundTrips() {
        val base = AlarmThresholdSnapshot.from(AlarmThresholds())
        val changed = AlarmThresholdSnapshot.from(AlarmThresholds(lowMgDl = 69))

        assertEquals(AlarmThresholds(), base.toModel())
        assertNotEquals(base.fingerprint, changed.fingerprint)
        assertEquals(base.fingerprint, AlarmThresholdSnapshot.from(base.toModel()).fingerprint)
    }

    private fun request(
        effect: LeasedLocalReadingEffect,
        expectedPreviousThresholdFingerprint: String? = null,
        processedAt: Long = PROCESSED_AT,
    ) = LocalAlarmApplyRequest(
        effectId = effect.effect.effectId,
        eventId = effect.effect.eventId,
        leaseToken = checkNotNull(effect.effect.leaseToken),
        processedAtEpochMs = processedAt,
        monitoringStartedAtEpochMs = MONITORING_STARTED_AT,
        thresholds = AlarmThresholdSnapshot.from(AlarmThresholds()),
        expectedPreviousThresholdFingerprint = expectedPreviousThresholdFingerprint,
        repeatIntervalMs = 120_000L,
    )

    private fun leasedEffect(
        effectId: Long,
        eventId: String,
        sequence: Long,
        glucose: Int,
        trend: Double = 0.0,
    ): LeasedLocalReadingEffect {
        val phoneTime = PROCESSED_AT - (13L - sequence) * 60_000L
        return LeasedLocalReadingEffect(
            effect = LocalReadingEffectRecord(
                effectId = effectId,
                eventId = eventId,
                approvalId = APPROVAL_ID,
                publicationBindingId = PUBLICATION_BINDING_ID,
                state = LocalReadingEffectState.LEASED,
                attempts = 1,
                enqueuedAtEpochMs = phoneTime,
                leaseToken = "alarm-effect-lease-$effectId",
                leaseExpiresAtEpochMs = PROCESSED_AT + 600_000L,
                lastTransitionToken = null,
                acknowledgedAtEpochMs = null,
            ),
            reading = GlucoseReading(
                eventId = eventId,
                sensorId = "sensor-a",
                sensorFamily = SensorFamily.SIBIONICS_GS1,
                sensorTimeEpochMs = phoneTime - 1_000L,
                phoneTimeEpochMs = phoneTime,
                glucoseMgDl = glucose,
                trendMgDlPerMinute = trend,
                quality = ReadingQuality.VALID,
                sequence = sequence,
            ),
        )
    }

    private companion object {
        val APPROVAL_ID = "ab".repeat(32)
        val PUBLICATION_BINDING_ID = "cd".repeat(32)
        const val MONITORING_STARTED_AT = 1_700_000_000_000L
        const val PROCESSED_AT = 1_700_001_000_000L
    }
}
