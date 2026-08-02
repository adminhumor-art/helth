package com.sladkaya.sensor.sibionics

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class Gs1SessionWritePlanPolicyTest {
    @Test
    fun resetAcceptedDuringStreamingRefreshesTheBoundedSilenceDeadline() {
        val plan = Gs1SessionWritePlanPolicy.plan(
            streaming = true,
            action = SessionAction.Write(
                bytes = byteArrayOf(0x0b),
                refreshTransportSilenceDeadline = true,
            ),
        )

        assertTrue(plan.enqueue)
        assertTrue(plan.armTransportSilenceWatchdogAfterEnqueue)
    }

    @Test
    fun ordinaryHandshakeWriteCannotArmTheStreamingWatchdog() {
        val plan = Gs1SessionWritePlanPolicy.plan(
            streaming = false,
            action = SessionAction.Write(byteArrayOf(0x01)),
        )

        assertTrue(plan.enqueue)
        assertFalse(plan.armTransportSilenceWatchdogAfterEnqueue)
    }
}
