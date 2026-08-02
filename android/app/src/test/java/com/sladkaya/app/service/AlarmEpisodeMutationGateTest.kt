package com.sladkaya.app.service

import java.util.Collections
import java.util.concurrent.CountDownLatch
import kotlin.concurrent.thread
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AlarmEpisodeMutationGateTest {
    @Test
    fun acknowledgementWaitsForSnapshotMutationAndCannotBeOverwritten() {
        val gate = AlarmEpisodeMutationGate()
        val snapshotLoaded = CountDownLatch(1)
        val releaseSnapshot = CountDownLatch(1)
        val acknowledgementStarted = CountDownLatch(1)
        val events = Collections.synchronizedList(mutableListOf<String>())
        var acknowledged = false

        val snapshotThread = thread {
            gate.runAtomically {
                val acknowledgementAtLoad = acknowledged
                snapshotLoaded.countDown()
                releaseSnapshot.await()
                acknowledged = acknowledgementAtLoad
                events += "snapshot"
            }
        }
        snapshotLoaded.await()
        val acknowledgementThread = thread {
            acknowledgementStarted.countDown()
            gate.runAtomically {
                acknowledged = true
                events += "acknowledgement"
            }
        }
        acknowledgementStarted.await()
        releaseSnapshot.countDown()
        snapshotThread.join()
        acknowledgementThread.join()

        assertTrue(acknowledged)
        assertEquals(listOf("snapshot", "acknowledgement"), events)
    }

    @Test
    fun newEpisodeCannotAppearBetweenStaleAcknowledgementDecisionAndSideEffects() {
        val gate = AlarmEpisodeMutationGate()
        val acknowledgementDecided = CountDownLatch(1)
        val releaseAcknowledgement = CountDownLatch(1)
        val newEpisodeStarted = CountDownLatch(1)
        val events = Collections.synchronizedList(mutableListOf<String>())
        var episodeId = "episode-old000001"

        val acknowledgementThread = thread {
            gate.runAtomically {
                assertEquals("episode-old000001", episodeId)
                acknowledgementDecided.countDown()
                releaseAcknowledgement.await()
                events += "ack-old-side-effects"
            }
        }
        acknowledgementDecided.await()
        val newEpisodeThread = thread {
            newEpisodeStarted.countDown()
            gate.runAtomically {
                episodeId = "episode-new000001"
                events += "publish-new"
            }
        }
        newEpisodeStarted.await()
        releaseAcknowledgement.countDown()
        acknowledgementThread.join()
        newEpisodeThread.join()

        assertEquals("episode-new000001", episodeId)
        assertEquals(listOf("ack-old-side-effects", "publish-new"), events)
    }
}
