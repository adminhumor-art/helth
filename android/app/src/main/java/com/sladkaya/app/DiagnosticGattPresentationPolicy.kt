package com.sladkaya.app

import com.sladkaya.sensor.sibionics.Gs1DiagnosticGattState

data class DiagnosticGattPresentation(
    val label: String,
    val technicalCode: String? = null,
    val retryable: Boolean = false,
    val allowsReading: Boolean = false,
)

/** Keeps private sensor identity and native details out of the user-facing UI. */
object DiagnosticGattPresentationPolicy {
    fun present(state: Gs1DiagnosticGattState): DiagnosticGattPresentation = when (state) {
        Gs1DiagnosticGattState.Idle -> DiagnosticGattPresentation("Диагностика остановлена")
        Gs1DiagnosticGattState.OpeningCore -> DiagnosticGattPresentation("Проверка ядра датчика")
        Gs1DiagnosticGattState.Connecting -> DiagnosticGattPresentation("Подключение к датчику")
        is Gs1DiagnosticGattState.ConnectingForHistoryBackfill ->
            DiagnosticGattPresentation("Подключение и восстановление пропущенных данных")
        Gs1DiagnosticGattState.DiscoveringServices ->
            DiagnosticGattPresentation("Проверка Bluetooth-служб датчика")
        Gs1DiagnosticGattState.Subscribing ->
            DiagnosticGattPresentation("Включение потока данных")
        Gs1DiagnosticGattState.Authenticating ->
            DiagnosticGattPresentation("Проверка связи с датчиком")
        is Gs1DiagnosticGattState.RetryingPersistence ->
            DiagnosticGattPresentation("Повтор безопасного сохранения состояния")
        is Gs1DiagnosticGattState.RetryingIngressPersistence ->
            DiagnosticGattPresentation("Повтор сохранения входного пакета")
        is Gs1DiagnosticGattState.Reconnecting ->
            DiagnosticGattPresentation("Связь потеряна, повторное подключение", retryable = true)
        Gs1DiagnosticGattState.PersistencePending ->
            DiagnosticGattPresentation(
                label = "Состояние датчика ещё не сохранено — поток остановлен",
                technicalCode = "PERSISTENCE_PENDING",
                retryable = true,
            )
        Gs1DiagnosticGattState.StreamingDiagnostic ->
            DiagnosticGattPresentation(
                label = "Диагностический поток активен",
                allowsReading = true,
            )
        is Gs1DiagnosticGattState.DiagnosticDataNotFresh ->
            DiagnosticGattPresentation("Получены данные, но они пока не готовы")
        is Gs1DiagnosticGattState.DiagnosticDataRejected ->
            DiagnosticGattPresentation(
                label = "Пакет датчика безопасно отклонён",
                technicalCode = state.code,
                retryable = true,
            )
        is Gs1DiagnosticGattState.Failed -> DiagnosticGattPresentation(
            label = failureMessage(state.code),
            technicalCode = state.code,
            retryable = state.retryable,
        )
    }

    private fun failureMessage(code: String): String = when (code) {
        "BLUETOOTH_PERMISSION_REQUIRED",
        "BLUETOOTH_PERMISSION_REVOKED",
        -> "Разрешите приложению поиск и подключение к Bluetooth-устройствам"
        "BLUETOOTH_UNAVAILABLE" -> "На этом телефоне недоступен Bluetooth"
        "BLUETOOTH_DISABLED" -> "Включите Bluetooth и повторите подключение"
        "INVALID_BLUETOOTH_ADDRESS",
        "GATT_IDENTITY_CONFLICT",
        -> "Найденный датчик не прошёл проверку личности"
        "STORAGE_UNAVAILABLE" -> "Не удалось открыть защищённое локальное состояние датчика"
        "GATT_CONNECT_FAILED" -> "Не удалось подключиться к датчику; повторите попытку"
        else -> "Диагностическое подключение остановлено безопасно"
    }
}
