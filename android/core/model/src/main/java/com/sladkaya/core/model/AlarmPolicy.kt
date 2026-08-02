package com.sladkaya.core.model

class AlarmPolicy(
    private val thresholds: AlarmThresholds = AlarmThresholds(),
    private val monitoringStartedAtEpochMs: Long,
    initiallyOpen: Set<AlarmKind> = emptySet(),
    initialLatestFreshSensorTimeEpochMs: Long = 0L,
    initialLatestFreshPhoneTimeEpochMs: Long = 0L,
    initialState: AlarmPolicyState? = null,
) {
    private val open = linkedSetOf<AlarmKind>().apply {
        addAll(initialState?.active ?: initiallyOpen)
    }
    private var latestFreshSensorTime =
        initialState?.latestFreshSensorTimeEpochMs ?: initialLatestFreshSensorTimeEpochMs
    private var latestFreshPhoneTime =
        initialState?.latestFreshPhoneTimeEpochMs ?: initialLatestFreshPhoneTimeEpochMs
    private var phoneClockMovedBackwards = initialState?.phoneClockMovedBackwards ?: false

    init {
        require(monitoringStartedAtEpochMs > 0L)
        require(initialState == null || (
            initiallyOpen.isEmpty() &&
                initialLatestFreshSensorTimeEpochMs == 0L &&
                initialLatestFreshPhoneTimeEpochMs == 0L
            )) { "Restored state cannot be combined with legacy initial fields" }
        require(latestFreshSensorTime >= 0L)
        require(latestFreshPhoneTime >= 0L)
        require(
            (latestFreshSensorTime == 0L) == (latestFreshPhoneTime == 0L),
        )
    }

    fun snapshot(): AlarmPolicyState = AlarmPolicyState(
        active = open.toSet(),
        latestFreshSensorTimeEpochMs = latestFreshSensorTime,
        latestFreshPhoneTimeEpochMs = latestFreshPhoneTime,
        phoneClockMovedBackwards = phoneClockMovedBackwards,
    )

    fun evaluate(
        reading: GlucoseReading,
        nowEpochMs: Long = reading.phoneTimeEpochMs,
    ): AlarmChanges {
        val readingIsFresh = reading.quality == ReadingQuality.VALID &&
            isFresh(reading, nowEpochMs)
        val hasBaseline = latestFreshSensorTime != 0L && latestFreshPhoneTime != 0L
        val canChangeThresholdAlarms = when {
            !readingIsFresh -> false
            phoneClockMovedBackwards -> {
                phoneClockMovedBackwards = false
                latestFreshSensorTime = reading.sensorTimeEpochMs
                latestFreshPhoneTime = reading.phoneTimeEpochMs
                false
            }
            !hasBaseline -> {
                latestFreshSensorTime = reading.sensorTimeEpochMs
                latestFreshPhoneTime = reading.phoneTimeEpochMs
                true
            }
            reading.sensorTimeEpochMs > latestFreshSensorTime &&
                reading.phoneTimeEpochMs >= latestFreshPhoneTime -> {
                latestFreshSensorTime = reading.sensorTimeEpochMs
                latestFreshPhoneTime = reading.phoneTimeEpochMs
                true
            }
            else -> false
        }
        if (!canChangeThresholdAlarms) {
            return AlarmChanges(emptySet(), emptySet(), open.toSet())
        }
        val desired = if (readingIsFresh) {
            setOfNotNull(
                AlarmKind.LOW.takeIf { reading.glucoseMgDl <= thresholds.lowMgDl },
                AlarmKind.HIGH.takeIf { reading.glucoseMgDl >= thresholds.highMgDl },
                AlarmKind.RAPID_FALL.takeIf { reading.trendMgDlPerMinute <= thresholds.rapidFallMgDlPerMinute },
                AlarmKind.RAPID_RISE.takeIf { reading.trendMgDlPerMinute >= thresholds.rapidRiseMgDlPerMinute },
            )
        } else emptySet()

        val opened = desired.filterTo(linkedSetOf()) { it !in open }
        val closed = if (readingIsFresh) {
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
        val hasFreshReading = latestFreshSensorTime != 0L && latestFreshPhoneTime != 0L
        val sensorTime = latestFreshSensorTime.takeIf { hasFreshReading }
            ?: monitoringStartedAtEpochMs
        val phoneTime = latestFreshPhoneTime.takeIf { hasFreshReading }
            ?: monitoringStartedAtEpochMs
        val sensorAge = nowEpochMs - sensorTime
        val phoneAge = nowEpochMs - phoneTime
        val clockMismatch = sensorTime > nowEpochMs + MAX_SENSOR_FUTURE_SKEW_MS ||
            phoneTime > nowEpochMs
        if (clockMismatch) phoneClockMovedBackwards = true
        val stale = clockMismatch || sensorAge >= thresholds.staleAfterMs ||
            phoneAge >= thresholds.staleAfterMs
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

    private fun isFresh(reading: GlucoseReading, nowEpochMs: Long): Boolean {
        if (reading.sensorTimeEpochMs > nowEpochMs + MAX_SENSOR_FUTURE_SKEW_MS ||
            reading.phoneTimeEpochMs > nowEpochMs
        ) {
            return false
        }
        return nowEpochMs - reading.sensorTimeEpochMs < thresholds.staleAfterMs &&
            nowEpochMs - reading.phoneTimeEpochMs < thresholds.staleAfterMs
    }

    private companion object {
        const val MAX_SENSOR_FUTURE_SKEW_MS = 5 * 60_000L
    }
}

data class AlarmPolicyState(
    val active: Set<AlarmKind> = emptySet(),
    val latestFreshSensorTimeEpochMs: Long = 0L,
    val latestFreshPhoneTimeEpochMs: Long = 0L,
    val phoneClockMovedBackwards: Boolean = false,
) {
    init {
        require(latestFreshSensorTimeEpochMs >= 0L)
        require(latestFreshPhoneTimeEpochMs >= 0L)
        require(
            (latestFreshSensorTimeEpochMs == 0L) ==
                (latestFreshPhoneTimeEpochMs == 0L),
        )
    }
}

data class AlarmChanges(
    val opened: Set<AlarmKind>,
    val closed: Set<AlarmKind>,
    val active: Set<AlarmKind>,
)
