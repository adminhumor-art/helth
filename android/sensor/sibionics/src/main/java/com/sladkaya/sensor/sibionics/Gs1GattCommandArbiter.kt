package com.sladkaya.sensor.sibionics

/**
 * Serializes protocol commands onto Android's single in-flight GATT write slot.
 * The owner must execute every [Gs1GattCommandArbiterResult.StartWrite] and
 * report its completion through [onWriteCallback].
 */
internal class Gs1GattCommandArbiter {
    private val pending = ArrayDeque<ByteArray>()
    private var current: ByteArray? = null
    private var closed = false

    @Synchronized
    fun enqueue(bytes: ByteArray): Gs1GattCommandArbiterResult {
        if (closed) {
            return Gs1GattCommandArbiterResult.Rejected("ARBITER_CLOSED")
        }

        val command = bytes.copyOf()
        if (current != null) {
            pending.addLast(command)
            return Gs1GattCommandArbiterResult.Queued
        }

        current = command
        return Gs1GattCommandArbiterResult.StartWrite(command.copyOf())
    }

    @Synchronized
    fun onWriteCallback(success: Boolean): Gs1GattCommandArbiterResult {
        if (closed) {
            return Gs1GattCommandArbiterResult.Rejected("ARBITER_CLOSED")
        }
        if (current == null) {
            return failClosed("UNEXPECTED_WRITE_CALLBACK")
        }
        if (!success) {
            current = null
            return failClosed("WRITE_FAILED")
        }

        current = null
        val next = pending.removeFirstOrNull()
            ?: return Gs1GattCommandArbiterResult.Idle
        current = next
        return Gs1GattCommandArbiterResult.StartWrite(next.copyOf())
    }

    @Synchronized
    fun reset(): Gs1GattCommandArbiterResult.Cleared {
        val cleared = clearCommands()
        closed = false
        return Gs1GattCommandArbiterResult.Cleared(
            clearedCommands = cleared,
            closed = false,
        )
    }

    @Synchronized
    fun close(): Gs1GattCommandArbiterResult.Cleared {
        val cleared = clearCommands()
        closed = true
        return Gs1GattCommandArbiterResult.Cleared(
            clearedCommands = cleared,
            closed = true,
        )
    }

    private fun failClosed(code: String): Gs1GattCommandArbiterResult.Failed {
        val cleared = clearCommands()
        closed = true
        return Gs1GattCommandArbiterResult.Failed(
            code = code,
            clearedCommands = cleared,
        )
    }

    private fun clearCommands(): Int {
        val cleared = pending.size + if (current == null) 0 else 1
        pending.clear()
        current = null
        return cleared
    }
}

internal sealed interface Gs1GattCommandArbiterResult {
    data class StartWrite(val bytes: ByteArray) : Gs1GattCommandArbiterResult
    data object Queued : Gs1GattCommandArbiterResult
    data object Idle : Gs1GattCommandArbiterResult
    data class Failed(
        val code: String,
        val clearedCommands: Int,
    ) : Gs1GattCommandArbiterResult
    data class Rejected(val code: String) : Gs1GattCommandArbiterResult
    data class Cleared(
        val clearedCommands: Int,
        val closed: Boolean,
    ) : Gs1GattCommandArbiterResult
}
