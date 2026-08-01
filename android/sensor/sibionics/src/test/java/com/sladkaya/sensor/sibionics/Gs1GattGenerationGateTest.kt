package com.sladkaya.sensor.sibionics

import com.sladkaya.core.model.SensorFamily
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class Gs1GattGenerationGateTest {
    @Test
    fun staleCallbackCannotBindAfterNewGenerationStarts() {
        val gate = Gs1GattGenerationGate<Any>()
        val old = gate.begin(profile())
        val current = gate.begin(profile())

        assertFalse(gate.accept(old, Any(), "AA:BB:CC:DD:EE:FF"))
        assertTrue(gate.accept(current, Any(), "AA:BB:CC:DD:EE:FF"))
    }

    @Test
    fun callbackBeforeConnectReturnBindsExactlyOneGattObject() {
        val gate = Gs1GattGenerationGate<Any>()
        val token = gate.begin(profile())
        val callbackGatt = Any()
        val differentReturnValue = Any()

        assertTrue(gate.accept(token, callbackGatt, "AA:BB:CC:DD:EE:FF"))
        assertTrue(gate.bindConnectResult(token, callbackGatt, "AA:BB:CC:DD:EE:FF"))
        assertFalse(gate.bindConnectResult(token, differentReturnValue, "AA:BB:CC:DD:EE:FF"))
        assertFalse(gate.accept(token, differentReturnValue, "AA:BB:CC:DD:EE:FF"))
    }

    @Test
    fun wrongMacAndCallbacksAfterStopAreRejected() {
        val gate = Gs1GattGenerationGate<Any>()
        val token = gate.begin(profile())
        val gatt = Any()

        assertFalse(gate.accept(token, gatt, "AA:BB:CC:DD:EE:00"))
        assertTrue(gate.accept(token, gatt, "aa:bb:cc:dd:ee:ff"))
        assertTrue(gate.stop(token))
        assertFalse(gate.accept(token, gatt, "AA:BB:CC:DD:EE:FF"))
    }

    private fun profile() = (Gs1DiagnosticActivationProfile.validate(
        sensorId = "sensor-a",
        family = SensorFamily.SIBIONICS_GS1,
        bluetoothAddress = "AA:BB:CC:DD:EE:FF",
        transportVariant = 0,
        packageCode = "ABCDEFGH",
    ) as Gs1DiagnosticActivationProfileValidation.Valid).profile
}
