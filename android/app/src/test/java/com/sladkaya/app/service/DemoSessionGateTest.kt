package com.sladkaya.app.service

import java.util.Collections
import java.util.concurrent.CountDownLatch
import kotlin.concurrent.thread
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DemoSessionGateTest {
    @Test
    fun invalidatedGenerationCannotRunSideEffects() {
        val gate = DemoSessionGate()
        val generation = gate.activate()
        gate.invalidate()

        var called = false
        val accepted = gate.runIfCurrent(generation) { called = true }

        assertFalse(accepted)
        assertFalse(called)
    }

    @Test
    fun invalidationWaitsForInFlightSideEffectsBeforeReturning() {
        val gate = DemoSessionGate()
        val generation = gate.activate()
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val invalidatorStarted = CountDownLatch(1)
        val events = Collections.synchronizedList(mutableListOf<String>())

        val callback = thread {
            gate.runIfCurrent(generation) {
                events += "callback-start"
                entered.countDown()
                release.await()
                events += "callback-end"
            }
        }
        entered.await()
        val invalidator = thread {
            invalidatorStarted.countDown()
            gate.invalidate()
            events += "invalidated"
        }
        invalidatorStarted.await()
        release.countDown()
        callback.join()
        invalidator.join()

        assertEquals(listOf("callback-start", "callback-end", "invalidated"), events)
        assertFalse(gate.runIfCurrent(generation) { error("stale generation") })
    }

    @Test
    fun currentGenerationRunsSideEffectsExactlyOnce() {
        val gate = DemoSessionGate()
        val generation = gate.activate()
        var calls = 0

        assertTrue(gate.runIfCurrent(generation) { calls += 1 })
        assertEquals(1, calls)
    }

    @Test
    fun repeatedDemoStartIsIgnoredUntilCurrentStartOrSessionReleasesClaim() {
        val gate = DemoStartRequestGate()

        assertTrue(gate.claim())
        assertFalse(gate.claim())

        gate.release()
        assertTrue(gate.claim())
    }
}
