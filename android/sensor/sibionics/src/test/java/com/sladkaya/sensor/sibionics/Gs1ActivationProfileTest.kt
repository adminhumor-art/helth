package com.sladkaya.sensor.sibionics

import com.sladkaya.core.model.SensorFamily
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class Gs1DiagnosticActivationProfileTest {
    @Test
    fun validProfileCanonicalizesOnlyMacAndPreservesPackageCodeCase() {
        val result = Gs1DiagnosticActivationProfile.validate(
            sensorId = "mom-gs1-01",
            family = SensorFamily.SIBIONICS_GS1,
            bluetoothAddress = "aa:01:b2:03:c4:05",
            transportVariant = 0,
            packageCode = "aB12cd34",
        ) as Gs1DiagnosticActivationProfileValidation.Valid

        assertEquals("AA:01:B2:03:C4:05", result.profile.bluetoothAddress)
        assertEquals("aB12cd34", result.profile.packageCode)
        assertEquals(
            Gs1CoreConfiguration(
                sensorId = "mom-gs1-01",
                family = SensorFamily.SIBIONICS_GS1,
                bluetoothAddress = "AA:01:B2:03:C4:05",
                transportVariant = 0,
                packageCode = "aB12cd34",
                wireProfile = Gs1WireProfile.V120,
            ),
            result.profile.coreConfiguration(Gs1WireProfile.V120),
        )
    }

    @Test
    fun incompleteOrUnverifiedProfileCannotStartPhysicalRuntime() {
        assertInvalid(
            Gs1DiagnosticActivationProfile.validate(
                "sensor", SensorFamily.SIBIONICS_GS1, "", 0, "ABCDEFGH",
            ),
            Gs1DiagnosticActivationProfileError.INVALID_BLUETOOTH_ADDRESS,
        )
        assertInvalid(
            Gs1DiagnosticActivationProfile.validate(
                "sensor", SensorFamily.SIBIONICS_GS1, "AA:BB:CC:DD:EE:FF", 1, "ABCDEFGH",
            ),
            Gs1DiagnosticActivationProfileError.UNVERIFIED_TRANSPORT_VARIANT,
        )
        assertInvalid(
            Gs1DiagnosticActivationProfile.validate(
                "sensor", SensorFamily.SIBIONICS_GS1, "AA:BB:CC:DD:EE:FF", 0, "bad-code",
            ),
            Gs1DiagnosticActivationProfileError.INVALID_PACKAGE_CODE,
        )
        assertInvalid(
            Gs1DiagnosticActivationProfile.validate(
                "sensor", SensorFamily.SIBIONICS_GS3, "AA:BB:CC:DD:EE:FF", 0, "ABCDEFGH",
            ),
            Gs1DiagnosticActivationProfileError.UNSUPPORTED_FAMILY,
        )
    }

    private fun assertInvalid(
        result: Gs1DiagnosticActivationProfileValidation,
        expected: Gs1DiagnosticActivationProfileError,
    ) {
        assertTrue(result is Gs1DiagnosticActivationProfileValidation.Invalid)
        assertEquals(expected, (result as Gs1DiagnosticActivationProfileValidation.Invalid).error)
    }
}
