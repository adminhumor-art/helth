package com.sladkaya.sensor.sibionics

import java.lang.reflect.Modifier
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class Gs1GattCommandArbiterTest {
    @Test
    fun everyStateTransitionIsSerializedAcrossActorAndBluetoothThreads() {
        val methodNames = setOf("enqueue", "onWriteCallback", "reset", "close")
        val methods = Gs1GattCommandArbiter::class.java.declaredMethods
            .filter { it.name in methodNames }

        assertEquals(methodNames, methods.map { it.name }.toSet())
        methods.forEach { method ->
            assertTrue("${method.name} must be synchronized", Modifier.isSynchronized(method.modifiers))
        }
    }

    @Test
    fun ackBeforeAuthWriteCallbackQueuesActivationUntilPhysicalWriteCompletes() {
        val arbiter = Gs1GattCommandArbiter()
        val physicalStarts = mutableListOf<ByteArray>()

        recordStart(arbiter.enqueue(byteArrayOf(0x01)), physicalStarts)
        assertEquals(Gs1GattCommandArbiterResult.Queued, arbiter.enqueue(byteArrayOf(0x02)))
        assertEquals(1, physicalStarts.size)

        recordStart(arbiter.onWriteCallback(success = true), physicalStarts)

        assertEquals(2, physicalStarts.size)
        assertArrayEquals(byteArrayOf(0x02), physicalStarts[1])
        assertEquals(Gs1GattCommandArbiterResult.Idle, arbiter.onWriteCallback(success = true))
    }

    @Test
    fun writeFailureClearsQueueAndNeverStartsNextCommand() {
        val arbiter = Gs1GattCommandArbiter()
        arbiter.enqueue(byteArrayOf(0x01))
        arbiter.enqueue(byteArrayOf(0x02))

        val result = arbiter.onWriteCallback(success = false)

        assertEquals(
            Gs1GattCommandArbiterResult.Failed(
                code = "WRITE_FAILED",
                clearedCommands = 1,
            ),
            result,
        )
        assertTrue(arbiter.enqueue(byteArrayOf(0x03)) is Gs1GattCommandArbiterResult.Rejected)
    }

    @Test
    fun commandBytesAreDefensivelyCopiedOnInputAndOutput() {
        val arbiter = Gs1GattCommandArbiter()
        val input = byteArrayOf(0x11)

        val first = arbiter.enqueue(input) as Gs1GattCommandArbiterResult.StartWrite
        input[0] = 0x22
        assertArrayEquals(byteArrayOf(0x11), first.bytes)

        first.bytes[0] = 0x33
        arbiter.enqueue(byteArrayOf(0x44))
        val second = arbiter.onWriteCallback(success = true) as Gs1GattCommandArbiterResult.StartWrite
        assertArrayEquals(byteArrayOf(0x44), second.bytes)
    }

    @Test
    fun unexpectedCallbackFailsClosedAndClearsPendingState() {
        val arbiter = Gs1GattCommandArbiter()

        assertEquals(
            Gs1GattCommandArbiterResult.Failed(
                code = "UNEXPECTED_WRITE_CALLBACK",
                clearedCommands = 0,
            ),
            arbiter.onWriteCallback(success = true),
        )
        assertTrue(arbiter.enqueue(byteArrayOf(0x01)) is Gs1GattCommandArbiterResult.Rejected)
    }

    @Test
    fun closeAndResetReportExactlyHowManyCommandsWereCleared() {
        val arbiter = Gs1GattCommandArbiter()
        arbiter.enqueue(byteArrayOf(0x01))
        arbiter.enqueue(byteArrayOf(0x02))

        assertEquals(
            Gs1GattCommandArbiterResult.Cleared(clearedCommands = 2, closed = true),
            arbiter.close(),
        )
        assertTrue(arbiter.enqueue(byteArrayOf(0x03)) is Gs1GattCommandArbiterResult.Rejected)
        assertEquals(
            Gs1GattCommandArbiterResult.Cleared(clearedCommands = 0, closed = false),
            arbiter.reset(),
        )
        assertTrue(arbiter.enqueue(byteArrayOf(0x04)) is Gs1GattCommandArbiterResult.StartWrite)
    }

    private fun recordStart(
        result: Gs1GattCommandArbiterResult,
        starts: MutableList<ByteArray>,
    ) {
        if (result is Gs1GattCommandArbiterResult.StartWrite) {
            starts += result.bytes
        }
    }
}
