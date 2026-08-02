package com.sladkaya.app

import com.sladkaya.core.model.ReadingQuality

/** Fail-closed visibility rule for quarantined diagnostic values. */
object DiagnosticReadingUiPolicy {
    fun canDisplay(
        reading: DiagnosticReadingUi,
        nowEpochMs: Long,
        staleAfterMs: Long = DEFAULT_STALE_AFTER_MS,
    ): Boolean {
        require(staleAfterMs > 0L)
        if (reading.quality != ReadingQuality.VALID) return false
        val sensorAge = nowEpochMs - reading.sensorTimeEpochMs
        val phoneAge = nowEpochMs - reading.phoneTimeEpochMs
        return sensorAge >= 0L && phoneAge >= 0L &&
            sensorAge < staleAfterMs && phoneAge < staleAfterMs
    }

    fun ageMinutes(reading: DiagnosticReadingUi, nowEpochMs: Long): Long =
        ((nowEpochMs - reading.phoneTimeEpochMs).coerceAtLeast(0L) / 60_000L)

    const val DEFAULT_STALE_AFTER_MS = 10 * 60_000L
}
