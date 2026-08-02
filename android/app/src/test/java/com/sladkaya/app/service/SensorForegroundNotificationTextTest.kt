package com.sladkaya.app.service

import com.sladkaya.core.model.GlucoseReading
import com.sladkaya.core.model.ReadingQuality
import com.sladkaya.core.model.SensorFamily
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SensorForegroundNotificationTextTest {
    @Test
    fun productReadingIsNeverLabelledAsDemo() {
        val text = SensorForegroundNotificationText.forReading(reading(), demo = false)

        assertEquals("5,6 ммоль/л · данные датчика", text)
        assertFalse(text.contains("ДЕМО"))
    }

    @Test
    fun simulatorReadingRemainsExplicitlyLabelledAsDemo() {
        val text = SensorForegroundNotificationText.forReading(
            reading().copy(sensorFamily = SensorFamily.SIMULATOR),
            demo = true,
        )

        assertTrue(text.startsWith("ДЕМО · 5,6 ммоль/л"))
        assertTrue(text.endsWith("тестовые данные"))
    }

    private fun reading() = GlucoseReading(
        eventId = "event-1",
        sensorId = "sensor-1",
        sensorFamily = SensorFamily.SIBIONICS_GS1,
        sensorTimeEpochMs = 1_700_000_000_000,
        phoneTimeEpochMs = 1_700_000_001_000,
        glucoseMgDl = 101,
        trendMgDlPerMinute = 0.0,
        quality = ReadingQuality.VALID,
        sequence = 1,
    )
}
