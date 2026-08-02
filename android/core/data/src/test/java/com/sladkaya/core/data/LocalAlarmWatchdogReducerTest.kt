package com.sladkaya.core.data

import com.sladkaya.core.model.AlarmKind
import com.sladkaya.core.model.AlarmPolicyState
import com.sladkaya.core.model.AlarmThresholds
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalAlarmWatchdogReducerTest {
    @Test
    fun dueWatchdogOpensNewSignalLossGenerationAndPlansShowRepeatAndWidget() {
        val previous = state()

        val reduced = LocalAlarmWatchdogReducer.reduce(
            previous = previous,
            nowEpochMs = LATEST_SENSOR_TIME + previous.thresholds.staleAfterMs,
        )

        assertEquals(setOf(AlarmKind.SIGNAL_LOSS), reduced.state.policyState.active)
        assertEquals(2L, reduced.state.episodeGeneration)
        assertFalse(reduced.state.episodeAcknowledged)
        assertEquals(
            setOf(
                LocalAlarmDeliveryKind.SHOW,
                LocalAlarmDeliveryKind.REPEAT,
                LocalAlarmDeliveryKind.WIDGET,
            ),
            reduced.deliveryKinds,
        )
        assertEquals(
            LATEST_SENSOR_TIME + previous.thresholds.staleAfterMs + 120_000L,
            LocalAlarmWatchdogDeliveryPlan.notBeforeEpochMs(
                LocalAlarmDeliveryKind.REPEAT,
                LATEST_SENSOR_TIME + previous.thresholds.staleAfterMs,
            ),
        )
        assertTrue(reduced.changed)
    }

    @Test
    fun earlyWatchdogIsAStableNoOp() {
        val previous = state()

        val reduced = LocalAlarmWatchdogReducer.reduce(
            previous = previous,
            nowEpochMs = LATEST_SENSOR_TIME + previous.thresholds.staleAfterMs - 1L,
        )

        assertEquals(previous, reduced.state)
        assertTrue(reduced.deliveryKinds.isEmpty())
        assertFalse(reduced.changed)
    }

    private fun state() = LocalAlarmStateRecord(
        publicationBindingId = "cd".repeat(32),
        approvalId = "ab".repeat(32),
        monitoringStartedAtEpochMs = 1_700_000_000_000L,
        policyState = AlarmPolicyState(
            active = emptySet(),
            latestFreshSensorTimeEpochMs = LATEST_SENSOR_TIME,
            latestFreshPhoneTimeEpochMs = LATEST_PHONE_TIME,
            phoneClockMovedBackwards = false,
        ),
        lastEffectId = 7L,
        lastEventId = "event-7",
        lastSequence = 7L,
        thresholds = AlarmThresholdSnapshot.from(AlarmThresholds()),
        episodeGeneration = 1L,
        episodeAcknowledged = false,
        episodeAcknowledgedAtEpochMs = null,
        episodeOpenedAtEpochMs = null,
        updatedAtEpochMs = LATEST_PHONE_TIME,
        stateSha256 = "",
    ).canonicalized()

    private companion object {
        const val LATEST_PHONE_TIME = 1_700_000_100_000L
        const val LATEST_SENSOR_TIME = LATEST_PHONE_TIME - 1_000L
    }
}
