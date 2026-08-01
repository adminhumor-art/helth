package com.sladkaya.core.model

class AlarmPolicy(private val thresholds: AlarmThresholds = AlarmThresholds()) {
    private val open = linkedSetOf<AlarmKind>()
    private var latestFreshPhoneTime = 0L

    fun evaluate(reading: GlucoseReading): AlarmChanges {
        if (reading.quality == ReadingQuality.VALID) {
            latestFreshPhoneTime = maxOf(latestFreshPhoneTime, reading.phoneTimeEpochMs)
        }
        val desired = if (reading.quality == ReadingQuality.VALID) {
            setOfNotNull(
                AlarmKind.LOW.takeIf { reading.glucoseMgDl <= thresholds.lowMgDl },
                AlarmKind.HIGH.takeIf { reading.glucoseMgDl >= thresholds.highMgDl },
                AlarmKind.RAPID_FALL.takeIf { reading.trendMgDlPerMinute <= thresholds.rapidFallMgDlPerMinute },
                AlarmKind.RAPID_RISE.takeIf { reading.trendMgDlPerMinute >= thresholds.rapidRiseMgDlPerMinute },
            )
        } else emptySet()

        val opened = desired.filterTo(linkedSetOf()) { it !in open }
        val closed = if (reading.quality == ReadingQuality.VALID) {
            open.filterTo(linkedSetOf()) { kind ->
                kind != AlarmKind.SIGNAL_LOSS && recovered(kind, reading)
            }
        } else {
            emptySet()
        }
        open += opened
        open -= closed
        return AlarmChanges(opened, closed, open.toSet())
    }

    fun evaluateFreshness(nowEpochMs: Long): AlarmChanges {
        val stale = latestFreshPhoneTime == 0L ||
            nowEpochMs - latestFreshPhoneTime >= thresholds.staleAfterMs
        val opened = if (stale && AlarmKind.SIGNAL_LOSS !in open) setOf(AlarmKind.SIGNAL_LOSS) else emptySet()
        val closed = if (!stale && AlarmKind.SIGNAL_LOSS in open) setOf(AlarmKind.SIGNAL_LOSS) else emptySet()
        open += opened
        open -= closed
        return AlarmChanges(opened, closed, open.toSet())
    }

    private fun recovered(kind: AlarmKind, reading: GlucoseReading): Boolean = when (kind) {
        AlarmKind.LOW -> reading.glucoseMgDl >= thresholds.lowMgDl + thresholds.recoveryHysteresisMgDl
        AlarmKind.HIGH -> reading.glucoseMgDl <= thresholds.highMgDl - thresholds.recoveryHysteresisMgDl
        AlarmKind.RAPID_FALL -> reading.trendMgDlPerMinute > thresholds.rapidFallMgDlPerMinute
        AlarmKind.RAPID_RISE -> reading.trendMgDlPerMinute < thresholds.rapidRiseMgDlPerMinute
        AlarmKind.SIGNAL_LOSS -> false
    }
}

data class AlarmChanges(
    val opened: Set<AlarmKind>,
    val closed: Set<AlarmKind>,
    val active: Set<AlarmKind>,
)
