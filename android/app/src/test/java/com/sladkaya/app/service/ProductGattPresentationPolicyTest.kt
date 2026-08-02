package com.sladkaya.app.service

import com.sladkaya.core.sensor.SensorDriverState
import com.sladkaya.sensor.sibionics.Gs1ProductGattState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ProductGattPresentationPolicyTest {
    @Test
    fun connectionAndPersistencePhasesNeverPretendThatProductValuesAreStreaming() {
        val nowEpochMs = 1_700_000_000_000L

        assertEquals(
            SensorDriverState.Connecting(null),
            ProductGattPresentationPolicy.present(
                Gs1ProductGattState.ConnectingForHistoryBackfill(
                    expectedIndex = 12,
                    firstPendingIndex = 9,
                    reason = "gap",
                ),
                nowEpochMs,
            ).state,
        )
        assertEquals(
            SensorDriverState.WaitingForData(nowEpochMs),
            ProductGattPresentationPolicy.present(
                Gs1ProductGattState.PersistencePending,
                nowEpochMs,
            ).state,
        )
        assertEquals(
            SensorDriverState.WaitingForData(nowEpochMs),
            ProductGattPresentationPolicy.present(
                Gs1ProductGattState.WaitingForPublishableReading(sequence = 14),
                nowEpochMs,
            ).state,
        )
    }

    @Test
    fun onlyTheVerifiedProductStreamingStateMapsToStreaming() {
        val presentation = ProductGattPresentationPolicy.present(
            Gs1ProductGattState.Streaming,
            nowEpochMs = 1_700_000_000_000L,
        )

        assertEquals(SensorDriverState.Streaming, presentation.state)
        assertTrue(presentation.label.contains("Датчик"))
    }

    @Test
    fun typedFailureKeepsItsRetryabilityWithoutLeakingTechnicalDetailIntoLabel() {
        val presentation = ProductGattPresentationPolicy.present(
            Gs1ProductGattState.Failed(
                code = "GATT_CONNECT_FAILED",
                detail = "AA:BB:CC:DD:EE:FF",
                retryable = true,
            ),
            nowEpochMs = 1_700_000_000_000L,
        )

        val failure = presentation.state as SensorDriverState.Failure
        assertTrue(failure.retryable)
        assertFalse(presentation.label.contains("AA:BB"))
    }
}
