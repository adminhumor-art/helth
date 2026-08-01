package com.sladkaya.sensor.sibionics

import java.util.concurrent.atomic.AtomicReference

/** Keeps the first terminal cause immutable across callback/actor races. */
internal class FirstTerminalCause<T : Any> {
    private val first = AtomicReference<T?>(null)

    fun current(): T? = first.get()

    fun offer(cause: T): T {
        first.compareAndSet(null, cause)
        return checkNotNull(first.get())
    }

    fun resolve(localCause: T?): T? = current() ?: localCause?.let(::offer)
}
