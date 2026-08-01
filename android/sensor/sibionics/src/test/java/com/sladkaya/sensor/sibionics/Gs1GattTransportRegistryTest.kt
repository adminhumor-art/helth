package com.sladkaya.sensor.sibionics

import com.sladkaya.core.model.SensorFamily
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class Gs1GattTransportRegistryTest {
    @Test
    fun closeInvalidatesLeaseBeforeAPlatformDisconnectCanBlock() {
        val disconnectEntered = CountDownLatch(1)
        val allowDisconnectToFinish = CountDownLatch(1)
        val closed = mutableListOf<FakeGatt>()
        val registry = Gs1GattTransportRegistry<FakeGatt>(
            disconnect = {
                disconnectEntered.countDown()
                assertTrue(allowDisconnectToFinish.await(2, TimeUnit.SECONDS))
            },
            close = closed::add,
        )
        val lease = registry.begin(profile())
        val gatt = FakeGatt("owned")
        assertTrue(registry.bindConnectResult(lease, gatt, "AA:BB:CC:DD:EE:FF"))

        val closer = thread(name = "fake-gatt-close") { registry.close(lease) }
        assertTrue(disconnectEntered.await(2, TimeUnit.SECONDS))

        assertNull(registry.current(lease))
        assertFalse(registry.acceptCallback(lease, gatt, "AA:BB:CC:DD:EE:FF"))

        allowDisconnectToFinish.countDown()
        closer.join(2_000L)
        assertFalse(closer.isAlive)
        assertEquals(listOf(gatt), closed)
    }

    @Test
    fun callbackBeforeConnectReturnBindsOneTransportAndCloseIsExactlyOnce() {
        val disconnected = mutableListOf<FakeGatt>()
        val closed = mutableListOf<FakeGatt>()
        val registry = Gs1GattTransportRegistry<FakeGatt>(
            disconnect = disconnected::add,
            close = closed::add,
        )
        val lease = registry.begin(profile())
        val callbackGatt = FakeGatt("callback")
        val differentConnectResult = FakeGatt("returned")

        assertTrue(registry.acceptCallback(lease, callbackGatt, "aa:bb:cc:dd:ee:ff"))
        assertTrue(registry.bindConnectResult(lease, callbackGatt, "AA:BB:CC:DD:EE:FF"))
        assertFalse(
            registry.bindConnectResult(
                lease,
                differentConnectResult,
                "AA:BB:CC:DD:EE:FF",
            ),
        )
        assertFalse(
            registry.bindConnectResult(
                lease,
                differentConnectResult,
                "AA:BB:CC:DD:EE:FF",
            ),
        )
        assertSame(callbackGatt, registry.current(lease))

        registry.close(lease)
        registry.close(lease)

        assertEquals(listOf(differentConnectResult, callbackGatt), disconnected)
        assertEquals(listOf(differentConnectResult, callbackGatt), closed)
        assertNull(registry.current(lease))
        assertFalse(registry.acceptCallback(lease, callbackGatt, "AA:BB:CC:DD:EE:FF"))
    }

    @Test
    fun staleGenerationCannotBindOrCloseTheCurrentTransport() {
        val disconnected = mutableListOf<FakeGatt>()
        val closed = mutableListOf<FakeGatt>()
        val registry = Gs1GattTransportRegistry<FakeGatt>(
            disconnect = disconnected::add,
            close = closed::add,
        )
        val stale = registry.begin(profile())
        val current = registry.begin(profile())
        val staleGatt = FakeGatt("stale")
        val wrongAddressGatt = FakeGatt("wrong-address")
        val currentGatt = FakeGatt("current")

        assertFalse(registry.acceptCallback(stale, staleGatt, "AA:BB:CC:DD:EE:FF"))
        assertFalse(registry.acceptCallback(current, wrongAddressGatt, "AA:BB:CC:DD:EE:00"))
        assertFalse(
            registry.bindConnectResult(
                current,
                wrongAddressGatt,
                "AA:BB:CC:DD:EE:FF",
            ),
        )
        assertTrue(registry.acceptCallback(current, currentGatt, "AA:BB:CC:DD:EE:FF"))

        assertNull(registry.current(stale))
        registry.close(stale)

        assertTrue(registry.acceptCallback(current, currentGatt, "AA:BB:CC:DD:EE:FF"))
        assertSame(currentGatt, registry.current(current))
        assertEquals(listOf(staleGatt, wrongAddressGatt), disconnected)
        assertEquals(listOf(staleGatt, wrongAddressGatt), closed)

        registry.close(current)

        assertEquals(listOf(staleGatt, wrongAddressGatt, currentGatt), disconnected)
        assertEquals(listOf(staleGatt, wrongAddressGatt, currentGatt), closed)
        assertNull(registry.current(current))
    }

    @Test
    fun closeBeforeFirstCallbackRejectsLateBindingWithoutTouchingAnotherLease() {
        val closed = mutableListOf<FakeGatt>()
        val registry = Gs1GattTransportRegistry<FakeGatt>(
            disconnect = {},
            close = closed::add,
        )
        val stopped = registry.begin(profile())

        registry.close(stopped)

        val lateGatt = FakeGatt("late")
        assertFalse(
            registry.acceptCallback(
                stopped,
                lateGatt,
                "AA:BB:CC:DD:EE:FF",
            ),
        )
        assertEquals(listOf(lateGatt), closed)
        assertFalse(
            registry.bindConnectResult(
                stopped,
                lateGatt,
                "AA:BB:CC:DD:EE:FF",
            ),
        )
        assertEquals(listOf(lateGatt), closed)

        val current = registry.begin(profile())
        val currentGatt = FakeGatt("current")
        assertTrue(registry.bindConnectResult(current, currentGatt, "AA:BB:CC:DD:EE:FF"))
        registry.close(current)
        assertEquals(listOf(lateGatt, currentGatt), closed)
    }

    private data class FakeGatt(val name: String)

    private fun profile() = (Gs1DiagnosticActivationProfile.validate(
        sensorId = "sensor-a",
        family = SensorFamily.SIBIONICS_GS1,
        bluetoothAddress = "AA:BB:CC:DD:EE:FF",
        transportVariant = 0,
        packageCode = "ABCDEFGH",
    ) as Gs1DiagnosticActivationProfileValidation.Valid).profile
}
