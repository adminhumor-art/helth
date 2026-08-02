package com.sladkaya.app.service

internal sealed interface SensorLaunchDecision {
    data object StartProduct : SensorLaunchDecision
    data object OpenSetup : SensorLaunchDecision
    data class FailClosed(val message: String) : SensorLaunchDecision
}

internal object SensorLaunchPolicy {
    fun decide(result: ProductSensorConfigurationResult): SensorLaunchDecision = when (result) {
        is ProductSensorConfigurationResult.Available -> SensorLaunchDecision.StartProduct
        ProductSensorConfigurationResult.Missing -> SensorLaunchDecision.OpenSetup
        is ProductSensorConfigurationResult.Invalid -> SensorLaunchDecision.FailClosed(
            "Сохранённая настройка датчика повреждена: ${result.code}",
        )
        is ProductSensorConfigurationResult.StorageUnavailable ->
            SensorLaunchDecision.FailClosed("Не удалось прочитать настройку датчика")
    }
}
