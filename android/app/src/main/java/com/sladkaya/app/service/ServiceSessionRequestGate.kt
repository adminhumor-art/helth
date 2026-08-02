package com.sladkaya.app.service

/** Makes a queued service transition stale as soon as a newer mode is requested. */
internal class ServiceSessionRequestGate {
    private val lock = Any()
    private var generation = 0L

    fun request(): Long = synchronized(lock) {
        generation = nextGeneration(generation)
        generation
    }

    fun invalidate(): Long = synchronized(lock) {
        generation = nextGeneration(generation)
        generation
    }

    fun isCurrent(request: Long): Boolean = synchronized(lock) {
        generation == request
    }

    private fun nextGeneration(current: Long): Long =
        if (current == Long.MAX_VALUE) 1L else current + 1L
}
