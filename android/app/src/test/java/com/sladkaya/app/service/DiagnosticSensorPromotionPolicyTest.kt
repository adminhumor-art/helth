package com.sladkaya.app.service

import com.sladkaya.sensor.sibionics.Gs1PhysicalSensorActivationResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DiagnosticSensorPromotionPolicyTest {
    @Test
    fun onlyDurablyActivatedOutcomesMayStartProductCollection() {
        val activated = DiagnosticSensorPromotionPolicy.decide(
            Gs1PhysicalSensorActivationResult.Activated("ab".repeat(32), "cd".repeat(32)),
        )
        val retry = DiagnosticSensorPromotionPolicy.decide(
            Gs1PhysicalSensorActivationResult.AlreadyActive("ab".repeat(32), "cd".repeat(32)),
        )
        val mismatch = DiagnosticSensorPromotionPolicy.decide(
            Gs1PhysicalSensorActivationResult.EvidenceMismatch,
        )

        assertEquals(DiagnosticSensorPromotionDecision.StartProduct, activated)
        assertEquals(DiagnosticSensorPromotionDecision.StartProduct, retry)
        assertTrue(mismatch is DiagnosticSensorPromotionDecision.Rejected)
    }

    @Test
    fun failuresHaveShortUserMessagesWithoutRawEvidence() {
        val conflict = DiagnosticSensorPromotionPolicy.decide(
            Gs1PhysicalSensorActivationResult.Conflict("raw checkpoint changed"),
        ) as DiagnosticSensorPromotionDecision.Rejected
        val missing = DiagnosticSensorPromotionPolicy.decide(
            Gs1PhysicalSensorActivationResult.EvidenceMissing,
        ) as DiagnosticSensorPromotionDecision.Rejected

        assertEquals("Точка проверки изменилась. Запустите диагностику ещё раз.", conflict.message)
        assertEquals("Точка проверки не найдена. Запустите диагностику ещё раз.", missing.message)
        assertTrue("raw checkpoint changed" !in conflict.message)
    }
}
