package com.sladkaya.sensor.sibionics

import java.lang.ref.ReferenceQueue
import java.lang.ref.WeakReference
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
    private val owners = IdentityHashMap<Gatt, Gs1GattTransportLease<Gatt>>()
    // A tombstone is needed only while the released object can still reappear
    // in a late callback; weak identity avoids retaining every historical GATT.
    private val released = HashSet<WeakIdentityReference<Gatt>>()
    private val releasedQueue = ReferenceQueue<Gatt>()
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
            val gatt = lease.gatt?.takeIf { owned ->
                if (owners[owned] !== lease) {
                    false
                } else {
                    owners.remove(owned)
                    markReleasedLocked(owned)
                }
            }
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
        if (isReleasedLocked(gatt)) return false
        val owner = owners[gatt]
        if (owner != null && owner !== lease) return false
        if (!reportedBluetoothAddress.equals(
                lease.token.expectedBluetoothAddress,
                ignoreCase = true,
            )
        ) {
            return false
        }
        val bound = lease.gatt
        if (bound != null && bound !== gatt) return false
        if (bound == null) {
            lease.gatt = gatt
            owners[gatt] = lease
        }
        return active === lease && lease.accepting && !lease.closed && lease.gatt === gatt
    }

    private fun claimRejectedLocked(
        lease: Gs1GattTransportLease<Gatt>,
        gatt: Gatt,
    ): Gatt? {
        val leaseOwnsGatt = lease.gatt === gatt
        return if (!leaseOwnsGatt && !owners.containsKey(gatt) && markReleasedLocked(gatt)) {
            gatt
        } else {
            null
        }
    }

    private fun isReleasedLocked(gatt: Gatt): Boolean {
        drainReleasedLocked()
        return released.contains(WeakIdentityReference(gatt))
    }

    private fun markReleasedLocked(gatt: Gatt): Boolean {
        drainReleasedLocked()
        return released.add(WeakIdentityReference(gatt, releasedQueue))
    }

    private fun drainReleasedLocked() {
        while (true) {
            val collected = releasedQueue.poll() ?: return
            released.remove(collected)
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

    private class WeakIdentityReference<Value : Any>(
        referent: Value,
        queue: ReferenceQueue<Value>? = null,
    ) : WeakReference<Value>(referent, queue) {
        private val identityHashCode = System.identityHashCode(referent)

        override fun hashCode(): Int = identityHashCode

        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is WeakIdentityReference<*>) return false
            val value = get() ?: return false
            return value === other.get()
        }
    }
}

internal class Gs1GattTransportLease<Gatt : Any> internal constructor(
    val token: Gs1GattGenerationToken,
) {
    internal var accepting: Boolean = true
    internal var closed: Boolean = false
    internal var gatt: Gatt? = null
}
