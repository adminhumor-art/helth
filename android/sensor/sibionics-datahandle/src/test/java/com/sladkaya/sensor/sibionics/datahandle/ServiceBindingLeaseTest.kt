package com.sladkaya.sensor.sibionics.datahandle

import org.junit.Assert.assertEquals
import org.junit.Test

class ServiceBindingLeaseTest {
    @Test
    fun closeBeforeBindReturnsStillUnbindsAfterAcceptance() {
        var unbinds = 0
        val lease = ServiceBindingLease { unbinds += 1 }

        lease.close()
        assertEquals(0, unbinds)
        lease.accepted()

        assertEquals(1, unbinds)
        lease.close()
        assertEquals(1, unbinds)
    }
}
