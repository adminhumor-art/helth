package com.sladkaya.sensor.sibionics

import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

internal data class Gs1GattGenerationToken(
    val generation: Long,
    val expectedBluetoothAddress: String,
)

/**
 * Identity-only guard for Android GATT callbacks. It has no Android dependency,
 * so callback-before-connect-return and stale-generation races are JVM tested.
 */
internal class Gs1GattGenerationGate<Gatt : Any> {
    private val sequence = AtomicLong(1L)
    private val active = AtomicReference<Lease<Gatt>?>(null)

    fun begin(profile: Gs1ActivationProfile): Gs1GattGenerationToken {
        val token = Gs1GattGenerationToken(
            generation = sequence.getAndIncrement(),
            expectedBluetoothAddress = profile.bluetoothAddress,
        )
        val next = Lease<Gatt>(token)
        active.getAndSet(next)?.accepting?.set(false)
        return token
    }

    /**
     * Accepts a callback only for the active generation, exact MAC and the one
     * GATT object bound to that generation. The first callback may perform the
     * binding when Android calls back before connectGatt returns.
     */
    fun accept(
        token: Gs1GattGenerationToken,
        gatt: Gatt,
        reportedBluetoothAddress: String,
    ): Boolean = bind(token, gatt, reportedBluetoothAddress)

    /** Binds the object returned by connectGatt, or verifies an earlier bind. */
    fun bindConnectResult(
        token: Gs1GattGenerationToken,
        gatt: Gatt,
        reportedBluetoothAddress: String,
    ): Boolean = bind(token, gatt, reportedBluetoothAddress)

    fun stop(token: Gs1GattGenerationToken): Boolean {
        val lease = active.get() ?: return false
        if (lease.token != token || !active.compareAndSet(lease, null)) return false
        lease.accepting.set(false)
        return true
    }

    private fun bind(
        token: Gs1GattGenerationToken,
        gatt: Gatt,
        reportedBluetoothAddress: String,
    ): Boolean {
        val lease = active.get() ?: return false
        if (lease.token != token || !lease.accepting.get()) return false
        if (!reportedBluetoothAddress.equals(token.expectedBluetoothAddress, ignoreCase = true)) {
            return false
        }
        val bound = lease.gatt.get()
        if (bound !== gatt) {
            if (bound != null || !lease.gatt.compareAndSet(null, gatt)) return false
        }
        return active.get() === lease && lease.accepting.get() && lease.gatt.get() === gatt
    }

    private class Lease<Gatt : Any>(
        val token: Gs1GattGenerationToken,
        val gatt: AtomicReference<Gatt?> = AtomicReference(null),
        val accepting: AtomicBoolean = AtomicBoolean(true),
    )
}
