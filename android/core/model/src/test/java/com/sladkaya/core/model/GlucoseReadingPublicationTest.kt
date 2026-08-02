package com.sladkaya.core.model

import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class GlucoseReadingPublicationTest {
    @Test
    fun simulatorCanNeverEnterProductPersistenceOrRemotePublication() {
        val simulated = reading(SensorFamily.SIMULATOR)
        val physical = reading(SensorFamily.SIBIONICS_GS1)

        assertFalse(simulated.isEligibleForProductPublication)
        assertTrue(physical.isEligibleForProductPublication)
        assertThrows(IllegalArgumentException::class.java) {
            simulated.requireProductPublication()
        }
        physical.requireProductPublication()
    }

    @Test
    fun onlyValidatedPhysicalQualityCanEnterProductPublication() {
        val valid = reading(SensorFamily.SIBIONICS_GS1)
        val warming = valid.copy(quality = ReadingQuality.WARMING_UP)
        val degraded = valid.copy(quality = ReadingQuality.DEGRADED)

        assertTrue(valid.isEligibleForProductPublication)
        assertFalse(warming.isEligibleForProductPublication)
        assertFalse(degraded.isEligibleForProductPublication)
        assertThrows(IllegalArgumentException::class.java) {
            warming.requireProductPublication()
        }
        assertThrows(IllegalArgumentException::class.java) {
            degraded.requireProductPublication()
        }
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
