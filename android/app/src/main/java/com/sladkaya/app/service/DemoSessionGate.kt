package com.sladkaya.app.service

import java.util.concurrent.atomic.AtomicBoolean

internal class DemoStartRequestGate {
    private val claimed = AtomicBoolean(false)

    fun claim(): Boolean = claimed.compareAndSet(false, true)

    fun release() {
        claimed.set(false)
    }
}

/** Serializes demo side effects with invalidation so fail-closed always wins. */
internal class DemoSessionGate {
    private val lock = Any()
    private var nextGeneration = 0L
    private var activeGeneration: Long? = null

    fun activate(): Long = synchronized(lock) {
        nextGeneration += 1
        nextGeneration.also { activeGeneration = it }
    }

    fun invalidate() = synchronized(lock) {
        activeGeneration = null
        nextGeneration += 1
    }

    fun runIfCurrent(generation: Long, block: () -> Unit): Boolean = synchronized(lock) {
        if (activeGeneration != generation) return@synchronized false
        block()
        true
    }
}
