package com.sladkaya.core.data

import com.sladkaya.core.model.GlucoseReading
import com.sladkaya.core.model.ReadingQuality
import com.sladkaya.core.model.SensorFamily
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class LocalReadingEffectRecordTest {
    @Test
    fun leasedEffectRequiresAnExactBoundedLease() {
        val pending = effect()

        assertThrows(IllegalArgumentException::class.java) {
            pending.copy(
                state = LocalReadingEffectState.LEASED,
                leaseToken = null,
                leaseExpiresAtEpochMs = NOW + 10_000L,
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            pending.copy(
                state = LocalReadingEffectState.LEASED,
                leaseToken = "short",
                leaseExpiresAtEpochMs = NOW + 10_000L,
            )
        }
    }

    @Test
    fun acknowledgedEffectCannotRetainAnActiveLease() {
        assertThrows(IllegalArgumentException::class.java) {
            effect().copy(
                state = LocalReadingEffectState.ACKNOWLEDGED,
                leaseToken = LEASE_TOKEN,
                leaseExpiresAtEpochMs = NOW + 10_000L,
                lastTransitionToken = LEASE_TOKEN,
                acknowledgedAtEpochMs = NOW + 1_000L,
            )
        }
    }

    @Test
    fun leasedEffectAndReadingMustHaveTheSameProductLineage() {
        val leased = effect().copy(
            state = LocalReadingEffectState.LEASED,
            attempts = 1,
            leaseToken = LEASE_TOKEN,
            leaseExpiresAtEpochMs = NOW + 10_000L,
        )

        assertEquals(
            "event-2",
            LeasedLocalReadingEffect(leased, reading()).reading.eventId,
        )
        assertThrows(IllegalArgumentException::class.java) {
            LeasedLocalReadingEffect(leased, reading().copy(eventId = "other-event"))
        }
    }

    private fun effect() = LocalReadingEffectRecord(
        effectId = 1L,
        eventId = "event-2",
        approvalId = "ab".repeat(32),
        publicationBindingId = "cd".repeat(32),
        state = LocalReadingEffectState.PENDING,
        attempts = 0,
        enqueuedAtEpochMs = NOW,
        leaseToken = null,
        leaseExpiresAtEpochMs = null,
        lastTransitionToken = null,
        acknowledgedAtEpochMs = null,
    )

    private fun reading() = GlucoseReading(
        eventId = "event-2",
        sensorId = "sensor-a",
        sensorFamily = SensorFamily.SIBIONICS_GS1,
        sensorTimeEpochMs = NOW - 1_000L,
        phoneTimeEpochMs = NOW,
        glucoseMgDl = 103,
        trendMgDlPerMinute = 0.0,
        quality = ReadingQuality.VALID,
        sequence = 2L,
    )

    private companion object {
        const val NOW = 1_700_000_121_000L
        const val LEASE_TOKEN = "local-effect-lease-a"
    }
}
