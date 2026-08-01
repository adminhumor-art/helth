package com.sladkaya.app.service

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ConfirmedSensorConfigurationPolicyTest {
    @Test
    fun acceptsOnlyTheCompleteVerifiedShape() {
        assertTrue(
            ConfirmedSensorConfigurationPolicy.isConfirmed(
                mapOf(
                    "schema_version" to 1,
                    "confirmed" to true,
                    "sensor_id" to "sensor-a",
                    "bluetooth_address" to "AA:BB:CC:DD:EE:FF",
                    "package_code" to "Ab12Cd34",
                    "transport_variant" to 0,
                ),
            ),
        )
    }

    @Test
    fun wrongPreferenceTypesFailClosedInsteadOfBeingCoerced() {
        val valid = mapOf<String, Any?>(
            "schema_version" to 1,
            "confirmed" to true,
            "sensor_id" to "sensor-a",
            "bluetooth_address" to "AA:BB:CC:DD:EE:FF",
            "package_code" to "Ab12Cd34",
            "transport_variant" to 0,
        )

        valid.keys.forEach { key ->
            assertFalse(
                "wrong type for $key was accepted",
                ConfirmedSensorConfigurationPolicy.isConfirmed(
                    valid + (key to listOf("corrupt")),
                ),
            )
        }
    }

    @Test
    fun missingOrMalformedIdentityFailsClosed() {
        val valid = mapOf<String, Any?>(
            "schema_version" to 1,
            "confirmed" to true,
            "sensor_id" to "sensor-a",
            "bluetooth_address" to "AA:BB:CC:DD:EE:FF",
            "package_code" to "Ab12Cd34",
            "transport_variant" to 0,
        )

        assertFalse(ConfirmedSensorConfigurationPolicy.isConfirmed(valid - "sensor_id"))
        assertFalse(
            ConfirmedSensorConfigurationPolicy.isConfirmed(
                valid + ("bluetooth_address" to "not-a-mac"),
            ),
        )
        assertFalse(
            ConfirmedSensorConfigurationPolicy.isConfirmed(
                valid + ("package_code" to "АБВГ1234"),
            ),
        )
    }
}
