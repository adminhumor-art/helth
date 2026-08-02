package com.sladkaya.app.service

import com.sladkaya.core.sensor.SensorDriverState
import com.sladkaya.sensor.sibionics.Gs1ProductGattState

internal data class ProductGattPresentation(
    val state: SensorDriverState,
    val label: String,
)

/** Maps typed product transport state without exposing sensor identity or native details. */
internal object ProductGattPresentationPolicy {
    fun present(
        state: Gs1ProductGattState,
        nowEpochMs: Long = System.currentTimeMillis(),
    ): ProductGattPresentation = when (state) {
        Gs1ProductGattState.Idle -> ProductGattPresentation(
            SensorDriverState.Idle,
            "Датчик остановлен",
        )
        Gs1ProductGattState.OpeningCore -> ProductGattPresentation(
            SensorDriverState.Authenticating,
            "Подготовка ядра датчика",
        )
        Gs1ProductGattState.Connecting -> connecting("Подключение к датчику")
        is Gs1ProductGattState.ConnectingForHistoryBackfill -> connecting(
            "Подключение и восстановление пропущенных данных",
        )
        Gs1ProductGattState.DiscoveringServices -> connecting(
            "Проверка Bluetooth-служб датчика",
        )
        Gs1ProductGattState.Subscribing -> ProductGattPresentation(
            SensorDriverState.Authenticating,
            "Включение потока данных",
        )
        Gs1ProductGattState.Authenticating -> ProductGattPresentation(
            SensorDriverState.Authenticating,
            "Проверка связи с датчиком",
        )
        is Gs1ProductGattState.RetryingPersistence -> waiting(
            nowEpochMs,
            "Повтор безопасного сохранения данных",
        )
        is Gs1ProductGattState.RetryingIngressPersistence -> waiting(
            nowEpochMs,
            "Повтор сохранения входных данных",
        )
        is Gs1ProductGattState.Reconnecting -> connecting(
            "Связь потеряна, повторное подключение",
        )
        Gs1ProductGattState.PersistencePending -> waiting(
            nowEpochMs,
            "Данные ещё не сохранены — показ значения остановлен",
        )
        Gs1ProductGattState.Streaming -> ProductGattPresentation(
            SensorDriverState.Streaming,
            "Датчик подключён",
        )
        is Gs1ProductGattState.WaitingForPublishableReading -> waiting(
            nowEpochMs,
            "Ожидание проверенного значения",
        )
        is Gs1ProductGattState.DataRejected -> waiting(
            nowEpochMs,
            "Некорректный пакет датчика безопасно отклонён",
        )
        is Gs1ProductGattState.Failed -> {
            val label = failureLabel(state.code)
            ProductGattPresentation(
                SensorDriverState.Failure(label, state.retryable),
                label,
            )
        }
    }

    private fun connecting(label: String) = ProductGattPresentation(
        SensorDriverState.Connecting(deviceName = null),
        label,
    )

    private fun waiting(nowEpochMs: Long, label: String) = ProductGattPresentation(
        SensorDriverState.WaitingForData(nowEpochMs),
        label,
    )

    private fun failureLabel(code: String): String = when (code) {
        "BLUETOOTH_PERMISSION_REQUIRED",
        "BLUETOOTH_PERMISSION_REVOKED",
        -> "Разрешите приложению поиск и подключение к Bluetooth-устройствам"
        "BLUETOOTH_UNAVAILABLE" -> "На этом телефоне недоступен Bluetooth"
        "BLUETOOTH_DISABLED" -> "Включите Bluetooth"
        "INVALID_BLUETOOTH_ADDRESS",
        "GATT_IDENTITY_CONFLICT",
        -> "Датчик не прошёл проверку личности"
        "STORAGE_UNAVAILABLE" -> "Не удалось открыть защищённые данные датчика"
        "GATT_CONNECT_FAILED" -> "Не удалось подключиться к датчику"
        else -> "Работа с датчиком остановлена безопасно"
    }
}
