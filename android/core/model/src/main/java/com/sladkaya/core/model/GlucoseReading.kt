package com.sladkaya.core.model

enum class SensorFamily(val wireName: String) {
    SIBIONICS_GS1("sibionics_gs1"),
    SIBIONICS_GS1SB("sibionics_gs1sb"),
    SIBIONICS_GS3("sibionics_gs3"),
    SIMULATOR("simulator"),
}

enum class ReadingQuality(val wireName: String) {
    VALID("valid"),
    WARMING_UP("warming_up"),
    DEGRADED("degraded"),
}

data class GlucoseReading(
    val eventId: String,
    val sensorId: String,
    val sensorFamily: SensorFamily,
    val sensorTimeEpochMs: Long,
    val phoneTimeEpochMs: Long,
    val glucoseMgDl: Int,
    val trendMgDlPerMinute: Double,
    val quality: ReadingQuality,
    val sequence: Long,
) {
    init {
        require(eventId.isNotBlank())
        require(sensorId.isNotBlank() && sensorId.length <= 128)
        require(glucoseMgDl in 20..600)
        require(trendMgDlPerMinute in -20.0..20.0)
        require(sensorTimeEpochMs > 0)
        require(phoneTimeEpochMs > 0)
        require(sequence >= 0)
    }

    val glucoseMmolL: Double get() = glucoseMgDl / 18.0

    val isEligibleForProductPublication: Boolean
        get() = sensorFamily != SensorFamily.SIMULATOR && quality == ReadingQuality.VALID

    fun requireProductPublication() {
        require(isEligibleForProductPublication) {
            "Only VALID physical readings may enter product persistence or remote publication"
        }
    }
}

enum class AlarmKind {
    LOW,
    HIGH,
    RAPID_FALL,
    RAPID_RISE,
    SIGNAL_LOSS,
}

data class AlarmThresholds(
    val lowMgDl: Int = 70,
    val highMgDl: Int = 250,
    val rapidFallMgDlPerMinute: Double = -3.0,
    val rapidRiseMgDlPerMinute: Double = 3.0,
    val recoveryHysteresisMgDl: Int = 5,
    val staleAfterMs: Long = 10 * 60 * 1000L,
) {
    init {
        require(lowMgDl in MIN_GLUCOSE_MG_DL..MAX_GLUCOSE_MG_DL)
        require(highMgDl in MIN_GLUCOSE_MG_DL..MAX_GLUCOSE_MG_DL)
        require(lowMgDl < highMgDl)
        require(rapidFallMgDlPerMinute in -MAX_ABSOLUTE_TREND..<0.0)
        require(rapidRiseMgDlPerMinute in 0.0..MAX_ABSOLUTE_TREND && rapidRiseMgDlPerMinute > 0.0)
        require(recoveryHysteresisMgDl > 0)
        require(recoveryHysteresisMgDl < highMgDl - lowMgDl)
        require(staleAfterMs in MIN_STALE_AFTER_MS..MAX_STALE_AFTER_MS)
    }

    private companion object {
        const val MIN_GLUCOSE_MG_DL = 20
        const val MAX_GLUCOSE_MG_DL = 600
        const val MAX_ABSOLUTE_TREND = 20.0
        const val MIN_STALE_AFTER_MS = 60_000L
        const val MAX_STALE_AFTER_MS = 60 * 60_000L
    }
}
