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
        get() = sensorFamily != SensorFamily.SIMULATOR

    fun requireProductPublication() {
        require(isEligibleForProductPublication) {
            "Simulated readings cannot enter product persistence or remote publication"
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
)
