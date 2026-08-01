package com.sladkaya.app.widget

import com.sladkaya.core.model.GlucoseReading
import com.sladkaya.core.model.ReadingQuality
import com.sladkaya.core.model.SensorFamily
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WidgetReadingPersistencePolicyTest {
    @Test
    fun onlyConfirmedSensorFamiliesMayBePersistedByWidget() {
        assertFalse(WidgetReadingPersistencePolicy.canPersist(reading(SensorFamily.SIMULATOR)))
        assertTrue(WidgetReadingPersistencePolicy.canPersist(reading(SensorFamily.SIBIONICS_GS1)))
    }

    private fun reading(family: SensorFamily) = GlucoseReading(
        eventId = "event-${family.wireName}",
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
