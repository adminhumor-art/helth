package com.sladkaya.app.service

import com.sladkaya.app.ReadingFreshness
import com.sladkaya.app.ReadingFreshnessPolicy
import com.sladkaya.core.model.AlarmChanges
import com.sladkaya.core.model.AlarmKind
import com.sladkaya.core.model.AlarmPolicy
import com.sladkaya.core.model.AlarmThresholds
import com.sladkaya.core.model.GlucoseReading

internal data class AlarmSettingsReloadResult(
    val policy: AlarmPolicy,
    val changes: AlarmChanges,
)

internal object AlarmSettingsReloadPolicy {
    fun replace(
        previousActive: Set<AlarmKind>,
        latest: GlucoseReading?,
        latestValidSensorTimeEpochMs: Long = 0L,
        latestValidPhoneTimeEpochMs: Long = 0L,
        thresholds: AlarmThresholds,
        monitoringStartedAtEpochMs: Long,
        nowEpochMs: Long,
    ): AlarmSettingsReloadResult {
        val replacement = AlarmPolicy(
            thresholds = thresholds,
            monitoringStartedAtEpochMs = monitoringStartedAtEpochMs,
            initiallyOpen = previousActive,
            initialLatestFreshSensorTimeEpochMs = latestValidSensorTimeEpochMs,
            initialLatestFreshPhoneTimeEpochMs = latestValidPhoneTimeEpochMs,
        )
        val fresh = ReadingFreshnessPolicy.evaluate(
            latest = latest,
            nowEpochMs = nowEpochMs,
            staleAfterMs = thresholds.staleAfterMs,
        ) == ReadingFreshness.FRESH
        if (fresh) {
            replacement.evaluate(requireNotNull(latest), nowEpochMs)
        }
        val active = replacement.evaluateFreshness(nowEpochMs).active
        return AlarmSettingsReloadResult(
            policy = replacement,
            changes = AlarmChanges(
                opened = active - previousActive,
                closed = previousActive - active,
                active = active,
            ),
        )
    }
}
