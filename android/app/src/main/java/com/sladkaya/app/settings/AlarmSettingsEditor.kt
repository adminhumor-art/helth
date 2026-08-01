package com.sladkaya.app.settings

import com.sladkaya.core.model.AlarmThresholds

internal object AlarmSettingsEditor {
    val staleAfterMinuteOptions = listOf(5, 10, 15, 20, 30)

    fun adjustLow(thresholds: AlarmThresholds, direction: Int): AlarmThresholds =
        adjust(thresholds, direction) { delta -> thresholds.copy(lowMgDl = thresholds.lowMgDl + delta) }

    fun adjustHigh(thresholds: AlarmThresholds, direction: Int): AlarmThresholds =
        adjust(thresholds, direction) { delta -> thresholds.copy(highMgDl = thresholds.highMgDl + delta) }

    fun withStaleAfterMinutes(
        thresholds: AlarmThresholds,
        minutes: Int,
    ): AlarmThresholds = if (minutes in staleAfterMinuteOptions) {
        thresholds.copy(staleAfterMs = minutes * 60_000L)
    } else {
        thresholds
    }

    fun adjustStaleAfter(thresholds: AlarmThresholds, direction: Int): AlarmThresholds {
        if (direction != -1 && direction != 1) return thresholds
        val currentMinutes = (thresholds.staleAfterMs / 60_000L).toInt()
        val currentIndex = staleAfterMinuteOptions.indexOf(currentMinutes)
        if (currentIndex < 0) return thresholds
        val nextIndex = (currentIndex + direction).coerceIn(staleAfterMinuteOptions.indices)
        return withStaleAfterMinutes(thresholds, staleAfterMinuteOptions[nextIndex])
    }

    private inline fun adjust(
        original: AlarmThresholds,
        direction: Int,
        copy: (deltaMgDl: Int) -> AlarmThresholds,
    ): AlarmThresholds {
        if (direction != -1 && direction != 1) return original
        return runCatching { copy(direction * STEP_MG_DL) }.getOrElse { original }
    }

    private const val STEP_MG_DL = 5
}
