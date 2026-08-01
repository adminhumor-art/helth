package com.sladkaya.core.data

import com.sladkaya.core.model.GlucoseReading
import com.sladkaya.core.model.ReadingQuality
import com.sladkaya.core.model.SensorFamily
import org.junit.Assert.assertEquals
import org.junit.Test

class MeasurementEntityTest {
    @Test
    fun modelRoundTripPreservesUploadPayload() {
        val reading = GlucoseReading(
            eventId = "00000000-0000-4000-8000-000000000010",
            sensorId = "sim-1",
            sensorFamily = SensorFamily.SIMULATOR,
            sensorTimeEpochMs = 1_785_450_000_000,
            phoneTimeEpochMs = 1_785_450_001_000,
            glucoseMgDl = 58,
            trendMgDlPerMinute = -3.2,
            quality = ReadingQuality.VALID,
            sequence = 42,
        )
        assertEquals(reading, reading.toEntity().toModel())
    }
}
