package com.sladkaya.app.service

import com.sladkaya.core.model.SensorFamily
import com.sladkaya.sensor.sibionics.Gs1DiagnosticActivationProfile
import com.sladkaya.sensor.sibionics.Gs1DiagnosticActivationProfileValidation
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SensorLaunchPolicyTest {
    @Test
    fun activeLocalConfigurationStartsProductInsteadOfOpeningSetup() {
        val configuration = ProductSensorConfiguration(
            profile = profile(),
            approvalId = "ab".repeat(32),
            publicationBindingId = "cd".repeat(32),
        )

        assertEquals(
            SensorLaunchDecision.StartProduct,
            SensorLaunchPolicy.decide(ProductSensorConfigurationResult.Available(configuration)),
        )
    }

    @Test
    fun setupOpensOnlyWhenThereIsNoActiveLocalConfiguration() {
        assertEquals(
            SensorLaunchDecision.OpenSetup,
            SensorLaunchPolicy.decide(ProductSensorConfigurationResult.Missing),
        )
        assertTrue(
            SensorLaunchPolicy.decide(
                ProductSensorConfigurationResult.Invalid("BAD_BINDING"),
            ) is SensorLaunchDecision.FailClosed,
        )
        assertTrue(
            SensorLaunchPolicy.decide(
                ProductSensorConfigurationResult.StorageUnavailable(),
            ) is SensorLaunchDecision.FailClosed,
        )
    }

    private fun profile() =
        (Gs1DiagnosticActivationProfile.validate(
            sensorId = "sensor-a",
            family = SensorFamily.SIBIONICS_GS1,
            bluetoothAddress = "AA:BB:CC:DD:EE:FF",
            transportVariant = 0,
            packageCode = "ABCDEFGH",
        ) as Gs1DiagnosticActivationProfileValidation.Valid).profile
}
