package com.sladkaya.app

import com.sladkaya.core.model.GlucoseReading
import com.sladkaya.core.model.AlarmThresholds
import com.sladkaya.core.model.ReadingQuality

internal enum class ReadingFreshness {
    MISSING,
    FRESH,
    NOT_READY,
    STALE,
    CLOCK_MISMATCH,
}

internal object ReadingFreshnessPolicy {
    fun evaluate(
        latest: GlucoseReading?,
        nowEpochMs: Long,
        staleAfterMs: Long = DEFAULT_STALE_AFTER_MS,
    ): ReadingFreshness {
        require(staleAfterMs > 0L)
        if (latest == null) return ReadingFreshness.MISSING
        if (latest.sensorTimeEpochMs > nowEpochMs + MAX_SENSOR_FUTURE_SKEW_MS ||
            latest.phoneTimeEpochMs > nowEpochMs
        ) {
            return ReadingFreshness.CLOCK_MISMATCH
        }
        if (nowEpochMs - latest.sensorTimeEpochMs >= staleAfterMs ||
            nowEpochMs - latest.phoneTimeEpochMs >= staleAfterMs
        ) {
            return ReadingFreshness.STALE
        }
        return if (latest.quality == ReadingQuality.VALID) {
            ReadingFreshness.FRESH
        } else {
            ReadingFreshness.NOT_READY
        }
    }

    private const val DEFAULT_STALE_AFTER_MS = 10 * 60_000L
    private const val MAX_SENSOR_FUTURE_SKEW_MS = 5 * 60_000L
}

internal object CurrentGlucoseNumberPolicy {
    fun show(freshness: ReadingFreshness): Boolean = freshness == ReadingFreshness.FRESH
}

internal data class ChartPoint(
    val reading: GlucoseReading,
    val xFraction: Float,
    val outsideAlarmRange: Boolean,
)

internal data class ChartConnection(
    val fromIndex: Int,
    val toIndex: Int,
)

internal data class GlucoseChartSeries(
    val points: List<ChartPoint>,
    val connections: List<ChartConnection>,
)

internal data class GlucoseChartScale(
    val minMgDl: Float,
    val maxMgDl: Float,
)

internal object GlucoseChartScalePolicy {
    fun build(
        series: GlucoseChartSeries,
        thresholds: AlarmThresholds,
    ): GlucoseChartScale {
        val values = buildList {
            add(DEFAULT_MIN_MG_DL)
            add(DEFAULT_MAX_MG_DL)
            add(thresholds.lowMgDl.toFloat())
            add(thresholds.highMgDl.toFloat())
            series.points.forEach { add(it.reading.glucoseMgDl.toFloat()) }
        }
        return GlucoseChartScale(
            minMgDl = values.min(),
            maxMgDl = values.max(),
        )
    }

    private const val DEFAULT_MIN_MG_DL = 40f
    private const val DEFAULT_MAX_MG_DL = 300f
}

internal object GlucoseChartPolicy {
    fun build(
        history: List<GlucoseReading>,
        thresholds: AlarmThresholds = AlarmThresholds(),
    ): GlucoseChartSeries {
        val readings = history.asSequence()
            .filter { it.quality == ReadingQuality.VALID }
            .sortedBy(GlucoseReading::sensorTimeEpochMs)
            .distinctBy(GlucoseReading::sensorTimeEpochMs)
            .toList()
        if (readings.isEmpty()) return GlucoseChartSeries(emptyList(), emptyList())

        val firstTime = readings.first().sensorTimeEpochMs
        val span = readings.last().sensorTimeEpochMs - firstTime
        val points = readings.map { reading ->
            ChartPoint(
                reading = reading,
                xFraction = if (span == 0L) {
                    1f
                } else {
                    (reading.sensorTimeEpochMs - firstTime).toFloat() / span.toFloat()
                },
                outsideAlarmRange = reading.glucoseMgDl <= thresholds.lowMgDl ||
                    reading.glucoseMgDl >= thresholds.highMgDl,
            )
        }
        val connections = readings.zipWithNext().mapIndexedNotNull { index, (left, right) ->
            ChartConnection(index, index + 1)
                .takeIf { right.sensorTimeEpochMs - left.sensorTimeEpochMs <= MAX_CONNECTED_GAP_MS }
        }
        return GlucoseChartSeries(points, connections)
    }

    private const val MAX_CONNECTED_GAP_MS = 150_000L
}
