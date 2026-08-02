package com.sladkaya.app.service

import com.sladkaya.core.model.SensorFamily
import com.sladkaya.sensor.sibionics.Gs1MarketProfile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DiagnosticResumeIdentityTest {
    @Test
    fun sameExactProfileAlwaysProducesSameNonSecretFingerprint() {
        val first = DiagnosticResumeIdentity.fingerprint(
            sensorId = "gs1-AA:BB:CC:DD:EE:01",
            family = SensorFamily.SIBIONICS_GS1,
            marketProfile = Gs1MarketProfile.RUSSIAN,
            bluetoothAddress = "AA:BB:CC:DD:EE:01",
            transportVariant = 1,
            packageCode = "Ab1Zcd34",
        )
        val second = DiagnosticResumeIdentity.fingerprint(
            sensorId = "gs1-AA:BB:CC:DD:EE:01",
            family = SensorFamily.SIBIONICS_GS1,
            marketProfile = Gs1MarketProfile.RUSSIAN,
            bluetoothAddress = "AA:BB:CC:DD:EE:01",
            transportVariant = 1,
            packageCode = "Ab1Zcd34",
        )

        assertEquals(first, second)
        assertTrue(first.matches(Regex("[0-9a-f]{64}")))
        assertFalseContains(first, "Ab1Zcd34")
    }

    @Test
    fun anyIdentityChangeCannotReuseThePreviousResumeIntent() {
        val base = fingerprint()

        assertNotEquals(base, fingerprint(packageCode = "ZZZZ9999"))
        assertNotEquals(base, fingerprint(address = "AA:BB:CC:DD:EE:02"))
        assertNotEquals(base, fingerprint(family = SensorFamily.SIBIONICS_GS1SB))
        assertNotEquals(base, fingerprint(marketProfile = Gs1MarketProfile.CHINESE))
    }

    private fun fingerprint(
        packageCode: String = "Ab1Zcd34",
        address: String = "AA:BB:CC:DD:EE:01",
        family: SensorFamily = SensorFamily.SIBIONICS_GS1,
        marketProfile: Gs1MarketProfile = Gs1MarketProfile.RUSSIAN,
    ): String = DiagnosticResumeIdentity.fingerprint(
        sensorId = "gs1-$address",
        family = family,
        marketProfile = marketProfile,
        bluetoothAddress = address,
        transportVariant = 1,
        packageCode = packageCode,
    )

    private fun assertFalseContains(value: String, secret: String) {
        assertTrue(!value.contains(secret))
    }
}
