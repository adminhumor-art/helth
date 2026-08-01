package com.sladkaya.sensor.sibionics

import com.sladkaya.sensor.sibionics.algorithm.AlgorithmInput

internal fun DecodedGs1RawSample.toAlgorithmInput() = AlgorithmInput(
    index = index,
    sensorTimeEpochSeconds = sensorTimeEpochSeconds,
    signal = current / GS1_RAW_SCALE,
    temperatureCelsius = temperature / GS1_RAW_SCALE,
    historyDistance = reindex,
)

private const val GS1_RAW_SCALE = 10.0
