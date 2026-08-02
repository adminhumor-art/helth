package com.sladkaya.app.service

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ServiceSessionRequestGateTest {
    @Test
    fun onlyTheLatestRequestedSessionMayStart() {
        val gate = ServiceSessionRequestGate()

        val diagnostic = gate.request()
        val demo = gate.request()

        assertFalse(gate.isCurrent(diagnostic))
        assertTrue(gate.isCurrent(demo))
    }

    @Test
    fun shutdownInvalidatesEveryQueuedSessionStart() {
        val gate = ServiceSessionRequestGate()
        val diagnostic = gate.request()

        gate.invalidate()

        assertFalse(gate.isCurrent(diagnostic))
    }

    @Test
    fun staleFailureCleanupMayNotStopANewerSession() {
        val gate = ServiceSessionRequestGate()

        val failedRequestCleanup = gate.invalidate()
        val newerValidStart = gate.request()

        assertFalse(gate.isCurrent(failedRequestCleanup))
        assertTrue(gate.isCurrent(newerValidStart))
    }
}
