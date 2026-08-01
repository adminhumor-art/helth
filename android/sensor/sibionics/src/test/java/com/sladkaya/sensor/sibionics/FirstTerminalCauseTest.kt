package com.sladkaya.sensor.sibionics

import java.util.concurrent.CountDownLatch
import kotlin.concurrent.thread
import org.junit.Assert.assertEquals
import org.junit.Test

class FirstTerminalCauseTest {
    @Test
    fun callbackFailureThatWinsDuringHandlingKeepsPriorityOverLocalArtifact() {
        val causes = FirstTerminalCause<String>()
        val handlerStarted = CountDownLatch(1)
        val callbackStored = CountDownLatch(1)
        var resolved: String? = null

        val actor = thread {
            assertEquals(null, causes.current())
            handlerStarted.countDown()
            callbackStored.await()
            resolved = causes.resolve("local-arbiter-closed")
        }
        handlerStarted.await()
        causes.offer("retryable-callback-failure")
        callbackStored.countDown()
        actor.join()

        assertEquals("retryable-callback-failure", resolved)
        assertEquals("retryable-callback-failure", causes.current())
    }

    @Test
    fun firstCauseIsImmutable() {
        val causes = FirstTerminalCause<String>()

        assertEquals("first", causes.offer("first"))
        assertEquals("first", causes.offer("second"))
        assertEquals("first", causes.resolve("third"))
    }
}
