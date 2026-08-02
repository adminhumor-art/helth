package com.sladkaya.app

import com.sladkaya.core.model.ReadingQuality
import com.sladkaya.sensor.sibionics.Gs1DiagnosticGattState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DiagnosticGattPresentationPolicyTest {
    @Test
    fun streamingAndNotFreshStatesAreClearlyDifferent() {
        assertEquals(
            "Диагностический поток активен",
            DiagnosticGattPresentationPolicy.present(
                Gs1DiagnosticGattState.StreamingDiagnostic,
            ).label,
        )
        assertTrue(
            DiagnosticGattPresentationPolicy.present(
                Gs1DiagnosticGattState.StreamingDiagnostic,
            ).allowsReading,
        )
        assertEquals(
            "Получены данные, но они пока не готовы",
            DiagnosticGattPresentationPolicy.present(
                Gs1DiagnosticGattState.DiagnosticDataNotFresh(
                    sequence = 4L,
                    quality = ReadingQuality.WARMING_UP,
                ),
            ).label,
        )
        assertFalse(
            DiagnosticGattPresentationPolicy.present(
                Gs1DiagnosticGattState.DiagnosticDataNotFresh(
                    sequence = 4L,
                    quality = ReadingQuality.WARMING_UP,
                ),
            ).allowsReading,
        )
    }

    @Test
    fun platformFailureExposesSafeCodeButNeverRawDetail() {
        val presentation = DiagnosticGattPresentationPolicy.present(
            Gs1DiagnosticGattState.Failed(
                code = "GATT_CONNECT_FAILED",
                detail = "private code Ab1Zcd34 and AA:BB:CC:DD:EE:FF",
                retryable = true,
            ),
        )

        assertEquals("GATT_CONNECT_FAILED", presentation.technicalCode)
        assertTrue(presentation.retryable)
        assertFalse(presentation.label.contains("Ab1Zcd34"))
        assertFalse(presentation.label.contains("AA:BB"))
    }

    @Test
    fun disabledBluetoothHasAnActionableRussianMessage() {
        val presentation = DiagnosticGattPresentationPolicy.present(
            Gs1DiagnosticGattState.Failed(
                code = "BLUETOOTH_DISABLED",
                retryable = true,
            ),
        )

        assertEquals("Включите Bluetooth и повторите подключение", presentation.label)
    }
}
