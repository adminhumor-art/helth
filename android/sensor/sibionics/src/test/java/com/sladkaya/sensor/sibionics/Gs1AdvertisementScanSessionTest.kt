package com.sladkaya.sensor.sibionics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class Gs1AdvertisementScanSessionTest {
    @Test
    fun completionReturnsEveryUniqueAdvertisementWithoutSelectingOne() {
        val session = Gs1AdvertisementScanSession(maxUniqueAdvertisements = 3)

        assertNull(session.record(deviceName = "GS-Ab1Z", bluetoothAddress = "aa:bb:cc:dd:ee:01"))
        assertNull(session.record(deviceName = "MED-Ab1Z", bluetoothAddress = "AA:BB:CC:DD:EE:02"))

        assertEquals(
            Gs1AdvertisementScanOutcome.Success(
                listOf(
                    Gs1DiscoveredAdvertisement("GS-Ab1Z", "AA:BB:CC:DD:EE:01"),
                    Gs1DiscoveredAdvertisement("MED-Ab1Z", "AA:BB:CC:DD:EE:02"),
                ),
            ),
            session.complete(),
        )
    }

    @Test
    fun canonicalMacDeduplicatesAndKeepsLatestNonBlankName() {
        val session = Gs1AdvertisementScanSession(maxUniqueAdvertisements = 2)

        assertNull(session.record(null, "aa:bb:cc:dd:ee:01"))
        assertNull(session.record("first", "AA:BB:CC:DD:EE:01"))
        assertNull(session.record("", "aa:bb:cc:dd:ee:01"))
        assertNull(session.record("   ", "AA:BB:CC:DD:EE:01"))
        assertNull(session.record("latest", "aa:bb:cc:dd:ee:01"))

        assertEquals(
            Gs1AdvertisementScanOutcome.Success(
                listOf(Gs1DiscoveredAdvertisement("latest", "AA:BB:CC:DD:EE:01")),
            ),
            session.complete(),
        )
    }

    @Test
    fun duplicateAtCapacityCanStillRefreshNameButNextUniqueMacOverflows() {
        val session = Gs1AdvertisementScanSession(maxUniqueAdvertisements = 2)

        assertNull(session.record("one", "AA:BB:CC:DD:EE:01"))
        assertNull(session.record("two", "AA:BB:CC:DD:EE:02"))
        assertNull(session.record("one-latest", "aa:bb:cc:dd:ee:01"))

        assertSame(
            Gs1AdvertisementScanOutcome.Overflow,
            session.record("three", "AA:BB:CC:DD:EE:03"),
        )
        assertSame(Gs1AdvertisementScanOutcome.Overflow, session.complete())
    }

    @Test
    fun productionCapacityAcceptsExactly256UniqueMacsAndFailsClosedOn257th() {
        val session = Gs1AdvertisementScanSession()

        repeat(GS1_MAX_DISCOVERED_ADVERTISEMENTS) { index ->
            assertNull(session.record("device-$index", mac(index)))
        }

        assertSame(
            Gs1AdvertisementScanOutcome.Overflow,
            session.record("overflow", "BB:00:00:00:00:00"),
        )
        assertSame(Gs1AdvertisementScanOutcome.Overflow, session.complete())
    }

    @Test
    fun configuredCapacityCanOnlyNarrowTheProductionBoundary() {
        listOf(0, -1, GS1_MAX_DISCOVERED_ADVERTISEMENTS + 1).forEach { invalid ->
            assertThrows(IllegalArgumentException::class.java) {
                Gs1AdvertisementScanSession(maxUniqueAdvertisements = invalid)
            }
        }
    }

    @Test
    fun malformedPlatformAddressIsAnExplicitPlatformFailure() {
        val session = Gs1AdvertisementScanSession()

        val failure = session.record("sensor", "not-a-mac")

        assertEquals(Gs1AdvertisementScanOutcome.PlatformScanFailure(errorCode = null), failure)
        assertEquals(failure, session.complete())
    }

    @Test
    fun firstTerminalOutcomeWinsAndLateCallbacksCannotMutateIt() {
        val session = Gs1AdvertisementScanSession()
        assertNull(session.record("before", "AA:BB:CC:DD:EE:01"))

        val failure = session.platformScanFailed(errorCode = 7)

        assertEquals(Gs1AdvertisementScanOutcome.PlatformScanFailure(7), failure)
        assertNull(session.record("late", "AA:BB:CC:DD:EE:02"))
        assertNull(session.platformScanFailed(errorCode = 9))
        assertEquals(failure, session.complete())
    }

    @Test
    fun permissionLossIsStoredBeforeTheAndroidContinuationIsResumed() {
        val session = Gs1AdvertisementScanSession()
        assertNull(session.record("before", "AA:BB:CC:DD:EE:01"))

        val permissionFailure = session.permissionDenied()

        assertSame(Gs1AdvertisementScanOutcome.PermissionDenied, permissionFailure)
        assertNull(session.platformScanFailed(errorCode = 7))
        assertSame(Gs1AdvertisementScanOutcome.PermissionDenied, session.complete())
    }

    @Test
    fun completionSnapshotDoesNotChangeAfterLateCallbacks() {
        val session = Gs1AdvertisementScanSession()
        assertNull(session.record("before", "AA:BB:CC:DD:EE:01"))

        val completed = session.complete()

        assertNull(session.record("late", "AA:BB:CC:DD:EE:02"))
        assertEquals(
            Gs1AdvertisementScanOutcome.Success(
                listOf(Gs1DiscoveredAdvertisement("before", "AA:BB:CC:DD:EE:01")),
            ),
            completed,
        )
        assertEquals(completed, session.complete())
    }

    @Test
    fun publicOutcomesKeepEveryPreflightFailureDistinct() {
        val outcomes: Set<Gs1AdvertisementScanOutcome> = setOf(
            Gs1AdvertisementScanOutcome.PermissionDenied,
            Gs1AdvertisementScanOutcome.BluetoothUnavailable,
            Gs1AdvertisementScanOutcome.BluetoothDisabled,
            Gs1AdvertisementScanOutcome.LocationServicesDisabled,
            Gs1AdvertisementScanOutcome.PlatformScanFailure(errorCode = 1),
            Gs1AdvertisementScanOutcome.Overflow,
        )

        assertEquals(6, outcomes.size)
    }

    @Test
    fun legacyAndroidNeedsLocationServicesButModernBluetoothDoesNot() {
        assertTrue(LegacyBleLocationPolicy.requiresEnabledLocationServices(sdkInt = 26))
        assertTrue(LegacyBleLocationPolicy.requiresEnabledLocationServices(sdkInt = 30))
        assertTrue(!LegacyBleLocationPolicy.requiresEnabledLocationServices(sdkInt = 31))
        assertTrue(!LegacyBleLocationPolicy.requiresEnabledLocationServices(sdkInt = 37))
    }

    @Test
    fun scannerUsesOneFixedBoundedDiscoveryWindow() {
        assertEquals(15_000L, GS1_ADVERTISEMENT_SCAN_TIMEOUT_MILLIS)
        assertTrue(GS1_ADVERTISEMENT_SCAN_TIMEOUT_MILLIS in 1L..60_000L)
    }

    private fun mac(index: Int): String =
        "AA:BB:CC:DD:%02X:%02X".format(index / 256, index % 256)
}
