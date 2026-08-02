package com.sladkaya.app.service

import com.sladkaya.sensor.sibionics.Gs1PhysicalSensorActivationResult

internal sealed interface DiagnosticSensorPromotionDecision {
    data object StartProduct : DiagnosticSensorPromotionDecision
    data class Rejected(val message: String) : DiagnosticSensorPromotionDecision
}

internal object DiagnosticSensorPromotionPolicy {
    fun decide(
        result: Gs1PhysicalSensorActivationResult,
    ): DiagnosticSensorPromotionDecision = when (result) {
        is Gs1PhysicalSensorActivationResult.Activated,
        is Gs1PhysicalSensorActivationResult.AlreadyActive,
        -> DiagnosticSensorPromotionDecision.StartProduct
        Gs1PhysicalSensorActivationResult.EvidenceMissing -> rejected(
            "Точка проверки не найдена. Запустите диагностику ещё раз.",
        )
        Gs1PhysicalSensorActivationResult.EvidenceMismatch,
        Gs1PhysicalSensorActivationResult.ReadingNotEligible,
        is Gs1PhysicalSensorActivationResult.Conflict,
        -> rejected("Точка проверки изменилась. Запустите диагностику ещё раз.")
        is Gs1PhysicalSensorActivationResult.NativeRuntimeUnavailable -> rejected(
            "Ядро датчика недоступно на этом телефоне.",
        )
        is Gs1PhysicalSensorActivationResult.StorageUnavailable -> rejected(
            "Не удалось сохранить подключение датчика.",
        )
    }

    private fun rejected(message: String) =
        DiagnosticSensorPromotionDecision.Rejected(message)
}
