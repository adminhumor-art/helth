package com.sladkaya.app.service

import com.sladkaya.core.model.GlucoseReading
import java.util.Locale

internal object SensorForegroundNotificationText {
    fun forReading(reading: GlucoseReading, demo: Boolean): String {
        val value = String.format(
            Locale.forLanguageTag("ru"),
            "%.1f ммоль/л",
            reading.glucoseMmolL,
        )
        return if (demo) {
            "ДЕМО · $value · тестовые данные"
        } else {
            "$value · данные датчика"
        }
    }

    fun waiting(demo: Boolean): String = if (demo) {
        "Демо: ожидание тестовых данных"
    } else {
        "Ожидание данных датчика"
    }
}
