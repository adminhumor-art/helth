package com.sladkaya.core.data

import com.sladkaya.core.model.AlarmKind
import com.sladkaya.core.model.AlarmPolicyState
import com.sladkaya.core.model.AlarmThresholds
import com.sladkaya.core.model.GlucoseReading
import com.sladkaya.core.model.ReadingQuality
import com.sladkaya.core.model.SensorFamily
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalAlarmSettingsReducerTest {
    @Test
    fun reducingStaleAfterFromThirtyToFiveMinutesImmediatelyOpensSignalLoss() {
        val reading = reading(value = 110, time = NOW - 10 * MINUTE)
        val previous = state(
            reading = reading,
            thresholds = thresholds(staleAfterMinutes = 30),
        )

        val reduction = LocalAlarmSettingsReducer.reduce(
            previous = previous,
            latestVerifiedReading = reading,
            request = request(
                previous = previous,
                thresholds = thresholds(staleAfterMinutes = 5),
            ),
        )

        assertEquals(setOf(AlarmKind.SIGNAL_LOSS), reduction.state.policyState.active)
        assertEquals(1L, reduction.state.episodeGeneration)
        assertEquals(
            setOf(
                LocalAlarmDeliveryKind.SHOW,
                LocalAlarmDeliveryKind.REPEAT,
                LocalAlarmDeliveryKind.WATCHDOG,
                LocalAlarmDeliveryKind.WIDGET,
            ),
            reduction.deliveries.mapTo(linkedSetOf(), LocalAlarmDeliveryDraft::kind),
        )
        assertEquals(
            reading.sensorTimeEpochMs + 5 * MINUTE,
            reduction.deliveries.single { it.kind == LocalAlarmDeliveryKind.WATCHDOG }
                .notBeforeEpochMs,
        )
    }

    @Test
    fun thresholdChangesImmediatelyOpenAndCloseLowAndHighAgainstTheExactReading() {
        val reading = reading(value = 100, time = NOW - MINUTE)
        val baseline = state(reading, thresholds(low = 70, high = 180))

        val lowOpened = reduce(baseline, reading, thresholds(low = 105, high = 180))
        assertEquals(setOf(AlarmKind.LOW), lowOpened.state.policyState.active)
        assertEquals(LocalAlarmDeliveryKind.SHOW, lowOpened.deliveries.first().kind)

        val lowClosed = reduce(lowOpened.state, reading, thresholds(low = 80, high = 180))
        assertTrue(lowClosed.state.policyState.active.isEmpty())
        assertEquals(LocalAlarmDeliveryKind.CLOSE, lowClosed.deliveries.first().kind)

        val highOpened = reduce(lowClosed.state, reading, thresholds(low = 70, high = 95))
        assertEquals(setOf(AlarmKind.HIGH), highOpened.state.policyState.active)
        assertEquals(LocalAlarmDeliveryKind.SHOW, highOpened.deliveries.first().kind)

        val highClosed = reduce(highOpened.state, reading, thresholds(low = 70, high = 120))
        assertTrue(highClosed.state.policyState.active.isEmpty())
        assertEquals(LocalAlarmDeliveryKind.CLOSE, highClosed.deliveries.first().kind)
    }

    @Test
    fun sameExactRequestProducesTheSameStateAndDeliveryIdentitiesForProcessRetry() {
        val reading = reading(value = 60, time = NOW - MINUTE)
        val previous = state(reading, thresholds())
        val request = request(previous, thresholds(low = 65))

        val first = LocalAlarmSettingsReducer.reduce(previous, reading, request)
        val retry = LocalAlarmSettingsReducer.reduce(previous, reading, request)

        assertEquals(first, retry)
        assertEquals(
            first.deliveries.map(LocalAlarmDeliveryDraft::deliveryId),
            retry.deliveries.map(LocalAlarmDeliveryDraft::deliveryId),
        )
    }

    @Test
    fun monitoringStartWithoutMeasurementUsesItsOriginalAnchorAndDoesNotInventAReading() {
        val previous = LocalAlarmStateRecord(
            publicationBindingId = BINDING_ID,
            approvalId = APPROVAL_ID,
            monitoringStartedAtEpochMs = NOW - 10 * MINUTE,
            policyState = AlarmPolicyState(),
            lastEffectId = MONITORING_START_EFFECT_ID,
            lastEventId = START_ID,
            lastSequence = 1L,
            thresholds = AlarmThresholdSnapshot.from(thresholds(staleAfterMinutes = 30)),
            episodeGeneration = 0L,
            episodeAcknowledged = false,
            episodeOpenedAtEpochMs = null,
            updatedAtEpochMs = NOW - 10 * MINUTE,
            stateSha256 = "",
        ).canonicalized()

        val reduction = LocalAlarmSettingsReducer.reduce(
            previous = previous,
            latestVerifiedReading = null,
            request = request(previous, thresholds(staleAfterMinutes = 5)),
        )

        assertEquals(MONITORING_START_EFFECT_ID, reduction.state.lastEffectId)
        assertEquals(START_ID, reduction.state.lastEventId)
        assertEquals(setOf(AlarmKind.SIGNAL_LOSS), reduction.state.policyState.active)
    }

    private fun reduce(
        previous: LocalAlarmStateRecord,
        reading: GlucoseReading,
        thresholds: AlarmThresholds,
    ) = LocalAlarmSettingsReducer.reduce(
        previous = previous,
        latestVerifiedReading = reading,
        request = request(previous, thresholds),
    )

    private fun request(
        previous: LocalAlarmStateRecord,
        thresholds: AlarmThresholds,
    ) = LocalAlarmSettingsApplyRequest(
        publicationBindingId = BINDING_ID,
        expectedStateSha256 = previous.stateSha256,
        thresholds = AlarmThresholdSnapshot.from(thresholds),
        appliedAtEpochMs = NOW,
    )

    private fun state(
        reading: GlucoseReading,
        thresholds: AlarmThresholds,
    ) = LocalAlarmStateRecord(
        publicationBindingId = BINDING_ID,
        approvalId = APPROVAL_ID,
        monitoringStartedAtEpochMs = NOW - 60 * MINUTE,
        policyState = AlarmPolicyState(
            latestFreshSensorTimeEpochMs = reading.sensorTimeEpochMs,
            latestFreshPhoneTimeEpochMs = reading.phoneTimeEpochMs,
        ),
        lastEffectId = 7L,
        lastEventId = reading.eventId,
        lastSequence = reading.sequence,
        thresholds = AlarmThresholdSnapshot.from(thresholds),
        episodeGeneration = 0L,
        episodeAcknowledged = false,
        episodeOpenedAtEpochMs = null,
        updatedAtEpochMs = reading.phoneTimeEpochMs,
        stateSha256 = "",
    ).canonicalized()

    private fun reading(value: Int, time: Long) = GlucoseReading(
        eventId = "reading-$time",
        sensorId = "sensor",
        sensorFamily = SensorFamily.SIBIONICS_GS1,
        sensorTimeEpochMs = time,
        phoneTimeEpochMs = time,
        glucoseMgDl = value,
        trendMgDlPerMinute = 0.0,
        quality = ReadingQuality.VALID,
        sequence = 7L,
    )

    private fun thresholds(
        low: Int = 70,
        high: Int = 180,
        staleAfterMinutes: Long = 20,
    ) = AlarmThresholds(
        lowMgDl = low,
        highMgDl = high,
        staleAfterMs = staleAfterMinutes * MINUTE,
    )

    private companion object {
        const val MINUTE = 60_000L
        const val NOW = 1_800_000_000_000L
        const val BINDING_ID =
            "1111111111111111111111111111111111111111111111111111111111111111"
        const val APPROVAL_ID =
            "2222222222222222222222222222222222222222222222222222222222222222"
        const val START_ID =
            "3333333333333333333333333333333333333333333333333333333333333333"
    }
}
