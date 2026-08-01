package com.sladkaya.sensor.sibionics

import java.util.Collections
import java.util.IdentityHashMap
import java.util.concurrent.atomic.AtomicLong

internal data class Gs1GattGenerationToken(
    val generation: Long,
    val expectedBluetoothAddress: String,
)

/**
 * Owns the transport identity for one GATT generation.
 *
 * Binding and closing are serialized so a callback accepted concurrently with
 * stop cannot publish a GATT object after the close path has already looked for
 * it. Android operations are supplied by the caller, which keeps the lifecycle
 * itself deterministic in plain JVM tests.
 */
internal class Gs1GattTransportRegistry<Gatt : Any>(
    private val disconnect: (Gatt) -> Unit,
    private val close: (Gatt) -> Unit,
) {
    private val lock = Any()
    private val sequence = AtomicLong(1L)
    private var active: Gs1GattTransportLease<Gatt>? = null

    fun begin(profile: Gs1DiagnosticActivationProfile): Gs1GattTransportLease<Gatt> =
        synchronized(lock) {
            active?.accepting = false
            Gs1GattTransportLease<Gatt>(
                token = Gs1GattGenerationToken(
                    generation = sequence.getAndIncrement(),
                    expectedBluetoothAddress = profile.bluetoothAddress,
                ),
            ).also { active = it }
        }

    fun acceptCallback(
        lease: Gs1GattTransportLease<Gatt>,
        gatt: Gatt,
        reportedBluetoothAddress: String,
    ): Boolean = bindOrReleaseRejected(lease, gatt, reportedBluetoothAddress)

    /**
     * Transfers ownership of the object returned by connectGatt. A rejected
     * result that is not already owned by any live lease is released here, so
     * callers cannot accidentally leak the second object in the rare
     * callback-before-return identity-conflict path.
     */
    fun bindConnectResult(
        lease: Gs1GattTransportLease<Gatt>,
        gatt: Gatt,
        reportedBluetoothAddress: String,
    ): Boolean = bindOrReleaseRejected(lease, gatt, reportedBluetoothAddress)

    private fun bindOrReleaseRejected(
        lease: Gs1GattTransportLease<Gatt>,
        gatt: Gatt,
        reportedBluetoothAddress: String,
    ): Boolean {
        val outcome = synchronized(lock) {
            if (bindLocked(lease, gatt, reportedBluetoothAddress)) {
                BindOutcome(accepted = true, rejected = null)
            } else {
                BindOutcome(accepted = false, rejected = claimRejectedLocked(lease, gatt))
            }
        }
        outcome.rejected?.let(::release)
        return outcome.accepted
    }

    fun releaseIfUnowned(lease: Gs1GattTransportLease<Gatt>, gatt: Gatt) {
        synchronized(lock) {
            claimRejectedLocked(lease, gatt)
        }?.let(::release)
    }

    fun current(lease: Gs1GattTransportLease<Gatt>): Gatt? = synchronized(lock) {
        lease.gatt.takeIf {
            active === lease && lease.accepting && !lease.closed
        }
    }

    fun close(lease: Gs1GattTransportLease<Gatt>) {
        val owned = synchronized(lock) {
            if (lease.closed) return
            lease.closed = true
            lease.accepting = false
            if (active === lease) active = null
            val gatt = lease.gatt?.takeIf(lease.released::add)
            lease.gatt = null
            gatt
        }
        owned?.let(::release)
    }

    private fun bindLocked(
        lease: Gs1GattTransportLease<Gatt>,
        gatt: Gatt,
        reportedBluetoothAddress: String,
    ): Boolean {
        if (active !== lease || !lease.accepting || lease.closed) return false
        if (gatt in lease.released) return false
        if (!reportedBluetoothAddress.equals(
                lease.token.expectedBluetoothAddress,
                ignoreCase = true,
            )
        ) {
            return false
        }
        val bound = lease.gatt
        if (bound != null && bound !== gatt) return false
        if (bound == null) lease.gatt = gatt
        return active === lease && lease.accepting && !lease.closed && lease.gatt === gatt
    }

    private fun claimRejectedLocked(
        lease: Gs1GattTransportLease<Gatt>,
        gatt: Gatt,
    ): Gatt? {
        val currentOwnsGatt = active?.gatt === gatt
        val leaseOwnsGatt = lease.gatt === gatt
        return if (!currentOwnsGatt && !leaseOwnsGatt && lease.released.add(gatt)) {
            gatt
        } else {
            null
        }
    }

    private fun release(gatt: Gatt) {
        try {
            disconnect(gatt)
        } finally {
            close(gatt)
        }
    }

    private data class BindOutcome<Gatt : Any>(
        val accepted: Boolean,
        val rejected: Gatt?,
    )
}

internal class Gs1GattTransportLease<Gatt : Any> internal constructor(
    val token: Gs1GattGenerationToken,
) {
    internal var accepting: Boolean = true
    internal var closed: Boolean = false
    internal var gatt: Gatt? = null
    internal val released: MutableSet<Gatt> =
        Collections.newSetFromMap(IdentityHashMap<Gatt, Boolean>())
}
