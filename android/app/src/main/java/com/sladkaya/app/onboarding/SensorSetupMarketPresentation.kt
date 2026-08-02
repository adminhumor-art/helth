package com.sladkaya.app.onboarding

import com.sladkaya.sensor.sibionics.Gs1MarketProfile

internal fun Gs1MarketProfile.userLabel(): String = when (this) {
    Gs1MarketProfile.GLOBAL -> "Международный"
    Gs1MarketProfile.RUSSIAN -> "Российский / Hematonix"
    Gs1MarketProfile.CHINESE -> "Китайский"
    Gs1MarketProfile.ECO_SPLIT -> "Sibionics 2 / Split"
}

internal fun Gs1MarketProfile.diagnosticAvailabilityMessage(): String = when (this) {
    Gs1MarketProfile.GLOBAL,
    Gs1MarketProfile.CHINESE,
    -> "Диагностическое подключение доступно. Приложение само определит внутренний способ обмена по ответу датчика."

    Gs1MarketProfile.RUSSIAN ->
        "Диагностическое подключение для этого региона пока не запускается."
    Gs1MarketProfile.ECO_SPLIT ->
        "Этот вариант требует отдельного сценария с двумя кодами; подключение пока не запускается."
}
