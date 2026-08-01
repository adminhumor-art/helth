package com.sladkaya.app.sync

import com.sladkaya.core.model.GlucoseReading
import com.sladkaya.core.model.ReadingQuality
import com.sladkaya.core.model.SensorFamily
import org.junit.Assert.assertEquals
import org.junit.Test

class MeasurementUploadBatchPolicyTest {
    @Test
    fun legacySimulationIsDiscardedWithoutBlockingFollowingPhysicalReading() {
        val actions = listOf(
            reading("legacy-demo", SensorFamily.SIMULATOR),
            reading("physical", SensorFamily.SIBIONICS_GS1),
        ).map(MeasurementUploadBatchPolicy::action)

        assertEquals(
            listOf(
                MeasurementUploadAction.DiscardLegacySimulation("legacy-demo"),
                MeasurementUploadAction.Upload,
            ),
            actions,
        )
    }

    private fun reading(eventId: String, family: SensorFamily) = GlucoseReading(
        eventId = eventId,
        sensorId = "sensor-${family.wireName}",
        sensorFamily = family,
        sensorTimeEpochMs = 1_000L,
        phoneTimeEpochMs = 1_000L,
        glucoseMgDl = 100,
        trendMgDlPerMinute = 0.0,
        quality = ReadingQuality.VALID,
        sequence = 1L,
    )
}
