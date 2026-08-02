package com.sladkaya.sensor.sibionics

import com.sladkaya.core.data.SensorPacketIngressRecord
import com.sladkaya.core.model.ReadingQuality
import com.sladkaya.core.model.SensorFamily
import com.sladkaya.core.model.GlucoseReading
import java.security.MessageDigest
import java.util.Collections
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class Gs1DiagnosticRuntimeTest {
    @Test
    fun stopRejectsUnsettledCommittedEventAndLeavesIngressPending() = runBlocking {
        val events = Collections.synchronizedList(mutableListOf<Gs1DiagnosticRuntimeEvent>())
        val committed = CompletableDeferred<Gs1DiagnosticRuntimeEvent.Committed>()
        val lease = ScriptedRuntimeLease(ingest = { completed(1) })
        val runtime = Gs1DiagnosticRuntime(
            scope = this,
            opener = Gs1RuntimeCoreOpener { Gs1RuntimeCoreOpenResult.Success(lease) },
            eventSink = { event ->
                events += event
                if (event is Gs1DiagnosticRuntimeEvent.Committed) committed.complete(event)
            },
            stopTimeoutMillis = 10_000,
        )
        val generation = (runtime.start(profile()) as Gs1RuntimeStartResult.Started).generation
        assertEquals(Gs1RuntimeSubmission.ACCEPTED, runtime.submit(generation, journaled(1)))
        val unsettled = withTimeout(1_000) { committed.await() }

        val stopped = withTimeout(500) { runtime.stop(expectedGeneration = generation) }

        assertEquals(Gs1RuntimeStopResult.PERSISTENCE_PENDING, stopped)
        assertTrue(!unsettled.acknowledgeDurablySettled())
        assertTrue(events.none { it is Gs1DiagnosticRuntimeEvent.Finalized })
        assertTrue(lease.closed.isCompleted)
        assertEquals(1, lease.closeCalls)
    }

    @Test
    fun stopBeforeSettlementRegistrationRejectsTheLateCommittedEvent() = runBlocking {
        val ingestStarted = CompletableDeferred<Unit>()
        val releaseIngest = CompletableDeferred<Unit>()
        val committed = CompletableDeferred<Gs1DiagnosticRuntimeEvent.Committed>()
        val events = Collections.synchronizedList(mutableListOf<Gs1DiagnosticRuntimeEvent>())
        val lease = ScriptedRuntimeLease(
            ingest = {
                ingestStarted.complete(Unit)
                releaseIngest.await()
                completed(1)
            },
        )
        val runtime = Gs1DiagnosticRuntime(
            scope = this,
            opener = Gs1RuntimeCoreOpener { Gs1RuntimeCoreOpenResult.Success(lease) },
            eventSink = { event ->
                events += event
                if (event is Gs1DiagnosticRuntimeEvent.Committed) committed.complete(event)
            },
            stopTimeoutMillis = 10_000,
        )
        val generation = (runtime.start(profile()) as Gs1RuntimeStartResult.Started).generation
        assertEquals(Gs1RuntimeSubmission.ACCEPTED, runtime.submit(generation, journaled(1)))
        withTimeout(1_000) { ingestStarted.await() }

        val stopping = async(start = CoroutineStart.UNDISPATCHED) {
            runtime.stop(expectedGeneration = generation)
        }
        releaseIngest.complete(Unit)

        assertEquals(Gs1RuntimeStopResult.PERSISTENCE_PENDING, withTimeout(500) { stopping.await() })
        val late = withTimeout(500) { committed.await() }
        assertTrue(!late.acknowledgeDurablySettled())
        assertTrue(events.none { it is Gs1DiagnosticRuntimeEvent.Finalized })
        assertTrue(lease.closed.isCompleted)
        assertEquals(1, lease.closeCalls)
    }

    @Test
    fun nextLivePacketCannotEnterTheCoreBeforePreviousCommittedEventIsSettled() = runBlocking {
        val lease = ScriptedRuntimeLease(
            ingest = { bytes ->
                val index = bytes.single().toInt()
                Gs1PacketProcessingResult.Completed(committedSamples = listOf(sample(index)))
            },
        )
        val firstCommitted = CompletableDeferred<Gs1DiagnosticRuntimeEvent.Committed>()
        val finalizedIngresses = Collections.synchronizedList(mutableListOf<String>())
        val runtime = Gs1DiagnosticRuntime(
            scope = this,
            opener = Gs1RuntimeCoreOpener { Gs1RuntimeCoreOpenResult.Success(lease) },
            eventSink = { event ->
                when (event) {
                    is Gs1DiagnosticRuntimeEvent.Committed -> {
                        if (!firstCommitted.isCompleted) {
                            firstCommitted.complete(event)
                        } else {
                            event.acknowledgeDurablySettled()
                        }
                    }
                    is Gs1DiagnosticRuntimeEvent.Finalized -> finalizedIngresses += event.ingressId
                    else -> Unit
                }
            },
            retryDelayMillis = 0,
        )
        val started = runtime.start(profile()) as Gs1RuntimeStartResult.Started

        assertEquals(Gs1RuntimeSubmission.ACCEPTED, runtime.submit(started.generation, journaled(1)))
        assertEquals(Gs1RuntimeSubmission.ACCEPTED, runtime.submit(started.generation, journaled(2)))
        val withheld = withTimeout(1_000L) { firstCommitted.await() }
        kotlinx.coroutines.delay(50L)
        assertEquals(1, lease.packets.size)
        assertTrue(finalizedIngresses.isEmpty())

        withheld.acknowledgeDurablySettled()
        withTimeout(1_000L) {
            while (lease.packets.size < 2 || "test-ingress-1" !in finalizedIngresses) {
                kotlinx.coroutines.delay(1L)
            }
        }
        assertEquals(2, lease.packets.size)
        assertTrue("test-ingress-1" in finalizedIngresses)
        runtime.stop()
        Unit
    }

    @Test
    fun productPublicationsSurviveCompletedAndEveryTerminalRuntimeResult() = runBlocking {
        val publication = productPublication(1)
        val results = listOf<Gs1PacketProcessingResult>(
            Gs1PacketProcessingResult.Completed(
                committedSamples = listOf(sample(1)),
                publications = listOf(publication),
            ),
            Gs1PacketProcessingResult.Rejected(
                code = "TEST_REJECTED",
                message = "terminal after committed prefix",
                committedSamples = listOf(sample(1)),
                publications = listOf(publication),
            ),
            Gs1PacketProcessingResult.StorageConflict(
                reason = "terminal after committed prefix",
                committedSamples = listOf(sample(1)),
                publications = listOf(publication),
            ),
            Gs1PacketProcessingResult.Closed(
                reason = "terminal after committed prefix",
                committedSamples = listOf(sample(1)),
                publications = listOf(publication),
            ),
        )

        results.forEach { scripted ->
            val committed = CompletableDeferred<Gs1DiagnosticRuntimeEvent.Committed>()
            val runtime = Gs1DiagnosticRuntime(
                scope = this,
                opener = Gs1RuntimeCoreOpener {
                    Gs1RuntimeCoreOpenResult.Success(
                        ScriptedRuntimeLease(ingest = { scripted }),
                    )
                },
                eventSink = { event ->
                    if (event is Gs1DiagnosticRuntimeEvent.Committed) committed.complete(event)
                },
            )
            val generation = (runtime.start(profile()) as Gs1RuntimeStartResult.Started).generation

            runtime.submit(generation, journaled(1))
            val event = withTimeout(1_000) { committed.await() }

            assertEquals(listOf(publication), event.publications)
            assertTrue(event.acknowledgeDurablySettled())
            runtime.stop(expectedGeneration = generation)
        }
    }

    @Test
    fun validatedEmptyEnvelopePublishesTransportProgressWithoutMedicalData() = runBlocking {
        val committed = CompletableDeferred<Gs1DiagnosticRuntimeEvent.Committed>()
        val runtime = Gs1DiagnosticRuntime(
            scope = this,
            opener = Gs1RuntimeCoreOpener {
                Gs1RuntimeCoreOpenResult.Success(
                    ScriptedRuntimeLease(
                        ingest = {
                            Gs1PacketProcessingResult.Completed(
                                committedSamples = emptyList(),
                                validatedTransportEnvelope = true,
                            )
                        },
                    ),
                )
            },
            eventSink = { if (it is Gs1DiagnosticRuntimeEvent.Committed) committed.complete(it) },
        )
        val generation = (runtime.start(profile()) as Gs1RuntimeStartResult.Started).generation

        runtime.submit(generation, journaled(1))
        val event = withTimeout(1_000) { committed.await() }

        assertTrue(event.validatedTransportEnvelope)
        assertTrue(event.samples.isEmpty())
        assertTrue(event.diagnostics.isEmpty())
        assertTrue(event.acknowledgeDurablySettled())
        runtime.stop(generation)
        Unit
    }

    @Test
    fun committedDeliveryPrecedesIngressFinalizationAndKeepsReceiptIdentity() = runBlocking {
        val events = Collections.synchronizedList(mutableListOf<Gs1DiagnosticRuntimeEvent>())
        val runtime = Gs1DiagnosticRuntime(
            scope = this,
            opener = Gs1RuntimeCoreOpener {
                Gs1RuntimeCoreOpenResult.Success(
                    ScriptedRuntimeLease(ingest = { completed(8) }),
                )
            },
            eventSink = { event ->
                events += event
                if (event is Gs1DiagnosticRuntimeEvent.Committed) {
                    event.acknowledgeDurablySettled()
                }
            },
        )
        val generation = (runtime.start(profile()) as Gs1RuntimeStartResult.Started).generation

        runtime.submit(generation, journaled(8))
        withTimeout(1_000) {
            while (events.none { it is Gs1DiagnosticRuntimeEvent.Finalized }) {
                kotlinx.coroutines.yield()
            }
        }

        val finalizedIndex = events.indexOfFirst { it is Gs1DiagnosticRuntimeEvent.Finalized }
        val committedIndex = events.indexOfFirst { it is Gs1DiagnosticRuntimeEvent.Committed }
        val finalized = events[finalizedIndex] as Gs1DiagnosticRuntimeEvent.Finalized
        assertTrue(committedIndex in 0 until finalizedIndex)
        assertEquals("test-ingress-8", finalized.ingressId)
        assertEquals(1_700_000_000_008L, finalized.receivedAtEpochMs)
        assertEquals(Gs1RuntimeIngressDisposition.CORE_COMMITTED, finalized.disposition)
        runtime.stop(expectedGeneration = generation)
        Unit
    }

    @Test
    fun committedDeliveryFailureDoesNotFinalizeRecoverableIngress() = runBlocking {
        val events = Collections.synchronizedList(mutableListOf<Gs1DiagnosticRuntimeEvent>())
        val failed = CompletableDeferred<Unit>()
        val runtime = Gs1DiagnosticRuntime(
            scope = this,
            opener = Gs1RuntimeCoreOpener {
                Gs1RuntimeCoreOpenResult.Success(
                    ScriptedRuntimeLease(ingest = { completed(8) }),
                )
            },
            eventSink = { event ->
                events += event
                if (event is Gs1DiagnosticRuntimeEvent.Committed) {
                    throw IllegalStateException("downstream delivery failed")
                }
                if (event is Gs1DiagnosticRuntimeEvent.Failed) failed.complete(Unit)
            },
        )
        val generation = (runtime.start(profile()) as Gs1RuntimeStartResult.Started).generation

        runtime.submit(generation, journaled(8))
        withTimeout(1_000) { failed.await() }

        assertTrue(events.any { it is Gs1DiagnosticRuntimeEvent.Committed })
        assertTrue(events.none { it is Gs1DiagnosticRuntimeEvent.Finalized })
        runtime.stop(expectedGeneration = generation)
        Unit
    }

    @Test
    fun committedDeliveryCancellationDoesNotFinalizeRecoverableIngress() = runBlocking {
        val events = Collections.synchronizedList(mutableListOf<Gs1DiagnosticRuntimeEvent>())
        val lease = ScriptedRuntimeLease(ingest = { completed(8) })
        val runtime = Gs1DiagnosticRuntime(
            scope = this,
            opener = Gs1RuntimeCoreOpener { Gs1RuntimeCoreOpenResult.Success(lease) },
            eventSink = { event ->
                events += event
                if (event is Gs1DiagnosticRuntimeEvent.Committed) {
                    throw CancellationException("downstream delivery cancelled")
                }
            },
        )
        val generation = (runtime.start(profile()) as Gs1RuntimeStartResult.Started).generation

        runtime.submit(generation, journaled(8))
        withTimeout(1_000) { lease.closed.await() }

        assertTrue(events.any { it is Gs1DiagnosticRuntimeEvent.Committed })
        assertTrue(events.none { it is Gs1DiagnosticRuntimeEvent.Finalized })
        runtime.stop(expectedGeneration = generation)
        Unit
    }

    @Test
    fun recoverySubmissionCompletesOnlyAfterExactPersistenceRetryCommits() = runBlocking {
        val allowRetry = CompletableDeferred<Unit>()
        val lease = ScriptedRuntimeLease(
            ingest = { Gs1PacketProcessingResult.PersistenceUnavailable("uncertain commit") },
            retry = {
                allowRetry.await()
                completed(4)
            },
        )
        val runtime = Gs1DiagnosticRuntime(
            scope = this,
            opener = Gs1RuntimeCoreOpener { Gs1RuntimeCoreOpenResult.Success(lease) },
            eventSink = { event ->
                if (event is Gs1DiagnosticRuntimeEvent.Committed) {
                    event.acknowledgeDurablySettled()
                }
            },
            retryDelayMillis = 0,
        )
        val generation = (runtime.start(profile()) as Gs1RuntimeStartResult.Started).generation

        val awaiting = async { runtime.submitAndAwait(generation, journaled(4)) }
        while (lease.retryCalls == 0) kotlinx.coroutines.yield()
        assertTrue(!awaiting.isCompleted)

        allowRetry.complete(Unit)
        val outcome = withTimeout(1_000) { awaiting.await() }
        assertTrue(outcome is Gs1RuntimeAwaitResult.Processed)
        outcome as Gs1RuntimeAwaitResult.Processed
        assertTrue(outcome.result is Gs1PacketProcessingResult.Completed)
        val completed = outcome.result as Gs1PacketProcessingResult.Completed
        assertEquals(listOf(4), completed.committedSamples.map { it.index })
        runtime.stop(expectedGeneration = generation)
        Unit
    }

    @Test
    fun recoverySubmissionNeverPublishesIntoTheLiveGattEventStream() = runBlocking {
        val results = listOf<Gs1PacketProcessingResult>(
            completed(4),
            Gs1PacketProcessingResult.Rejected(
                code = "TERMINAL_AFTER_PREFIX",
                message = "recovery caller owns exact delivery",
                committedSamples = listOf(sample(4)),
            ),
        )

        results.forEach { scripted ->
            val events = Collections.synchronizedList(mutableListOf<Gs1DiagnosticRuntimeEvent>())
            val runtime = Gs1DiagnosticRuntime(
                scope = this,
                opener = Gs1RuntimeCoreOpener {
                    Gs1RuntimeCoreOpenResult.Success(
                        ScriptedRuntimeLease(ingest = { scripted }),
                    )
                },
                eventSink = events::add,
            )
            val generation = (runtime.start(profile()) as Gs1RuntimeStartResult.Started).generation

            val result = runtime.submitAndAwait(generation, journaled(4))

            assertEquals(Gs1RuntimeAwaitResult.Processed(scripted), result)
            assertTrue(events.isEmpty())
            runtime.stop(expectedGeneration = generation)
        }
        Unit
    }

    @Test
    fun recoverySubmissionForOldGenerationIsRejectedBeforeCore() = runBlocking {
        val first = ScriptedRuntimeLease()
        val second = ScriptedRuntimeLease()
        val leases = ArrayDeque(listOf(first, second))
        val runtime = Gs1DiagnosticRuntime(
            scope = this,
            opener = Gs1RuntimeCoreOpener {
                Gs1RuntimeCoreOpenResult.Success(leases.removeFirst())
            },
            eventSink = { event ->
                if (event is Gs1DiagnosticRuntimeEvent.Committed) {
                    event.acknowledgeDurablySettled()
                }
            },
        )
        val old = (runtime.start(profile()) as Gs1RuntimeStartResult.Started).generation
        val current = (runtime.start(profile()) as Gs1RuntimeStartResult.Started).generation

        assertEquals(
            Gs1RuntimeAwaitResult.StaleGeneration,
            runtime.submitAndAwait(old, journaled(1)),
        )
        assertTrue(second.packets.isEmpty())
        runtime.stop(expectedGeneration = current)
        Unit
    }

    @Test
    fun callbackFromPreviousGenerationCannotReachNewCoreLease() = runBlocking {
        val first = ScriptedRuntimeLease()
        val secondProcessed = CompletableDeferred<Unit>()
        val second = ScriptedRuntimeLease(
            ingest = { packet ->
                secondProcessed.complete(Unit)
                completed(packet.first().toInt())
            },
        )
        val leases = ArrayDeque(listOf(first, second))
        val runtime = Gs1DiagnosticRuntime(
            scope = this,
            opener = Gs1RuntimeCoreOpener {
                Gs1RuntimeCoreOpenResult.Success(leases.removeFirst())
            },
            eventSink = { event ->
                if (event is Gs1DiagnosticRuntimeEvent.Committed) {
                    event.acknowledgeDurablySettled()
                }
            },
        )

        val generationA = (runtime.start(profile()) as Gs1RuntimeStartResult.Started).generation
        val generationB = (runtime.start(profile()) as Gs1RuntimeStartResult.Started).generation
        val stale = runtime.submit(generationA, journaled(1))
        val accepted = runtime.submit(generationB, journaled(2))

        withTimeout(1_000) { secondProcessed.await() }
        runtime.stop()
        assertEquals(Gs1RuntimeSubmission.STALE_GENERATION, stale)
        assertEquals(Gs1RuntimeSubmission.ACCEPTED, accepted)
        assertTrue(first.packets.isEmpty())
        assertEquals(listOf(2), second.packets.map { it.first().toInt() })
        assertTrue(first.closed.isCompleted)
        assertTrue(second.closed.isCompleted)
    }

    @Test
    fun lateStopFromPreviousGattAttemptCannotCloseCurrentCoreGeneration() = runBlocking {
        val secondProcessed = CompletableDeferred<Unit>()
        val leases = ArrayDeque(
            listOf(
                ScriptedRuntimeLease(),
                ScriptedRuntimeLease(ingest = { packet ->
                    secondProcessed.complete(Unit)
                    completed(packet.first().toInt())
                }),
            ),
        )
        val runtime = Gs1DiagnosticRuntime(
            scope = this,
            opener = Gs1RuntimeCoreOpener {
                Gs1RuntimeCoreOpenResult.Success(leases.removeFirst())
            },
            eventSink = { event ->
                if (event is Gs1DiagnosticRuntimeEvent.Committed) {
                    event.acknowledgeDurablySettled()
                }
            },
        )
        val old = (runtime.start(profile()) as Gs1RuntimeStartResult.Started).generation
        val current = (runtime.start(profile()) as Gs1RuntimeStartResult.Started).generation

        val lateStop = runtime.stop(expectedGeneration = old)
        val submission = runtime.submit(current, journaled(7))

        assertEquals(Gs1RuntimeStopResult.STALE_GENERATION, lateStop)
        assertEquals(Gs1RuntimeSubmission.ACCEPTED, submission)
        withTimeout(1_000) { secondProcessed.await() }
        runtime.stop(expectedGeneration = current)
        Unit
    }

    @Test
    fun uncertainCommitIsRetriedBeforeQueuedNotificationCanRun() = runBlocking {
        val allowRetry = CompletableDeferred<Unit>()
        val events = Collections.synchronizedList(mutableListOf<Gs1DiagnosticRuntimeEvent>())
        val lease = ScriptedRuntimeLease(
            ingest = { packet ->
                if (packet.first().toInt() == 1) {
                    Gs1PacketProcessingResult.PersistenceUnavailable("lost database response")
                } else {
                    completed(2)
                }
            },
            retry = {
                allowRetry.await()
                completed(1)
            },
        )
        val runtime = Gs1DiagnosticRuntime(
            scope = this,
            opener = Gs1RuntimeCoreOpener { Gs1RuntimeCoreOpenResult.Success(lease) },
            eventSink = { event ->
                events += event
                if (event is Gs1DiagnosticRuntimeEvent.Committed) {
                    event.acknowledgeDurablySettled()
                }
            },
            retryDelayMillis = 0,
        )
        val generation = (runtime.start(profile()) as Gs1RuntimeStartResult.Started).generation

        assertEquals(Gs1RuntimeSubmission.ACCEPTED, runtime.submit(generation, journaled(1)))
        assertEquals(Gs1RuntimeSubmission.ACCEPTED, runtime.submit(generation, journaled(2)))
        while (lease.retryCalls == 0) kotlinx.coroutines.yield()
        assertEquals(listOf(1), lease.packets.map { it.first().toInt() })

        allowRetry.complete(Unit)
        withTimeout(1_000) {
            while (events.filterIsInstance<Gs1DiagnosticRuntimeEvent.Committed>().size < 2) {
                kotlinx.coroutines.yield()
            }
        }
        runtime.stop()

        assertEquals(listOf(1, 2), lease.packets.map { it.first().toInt() })
        assertEquals(1, lease.retryCalls)
        assertEquals(
            listOf(1, 2),
            events.filterIsInstance<Gs1DiagnosticRuntimeEvent.Committed>()
                .flatMap { event -> event.samples.map { it.index } },
        )
    }

    @Test
    fun committedAlgorithmIssueIsNotReportedAsHealthyDiagnosticData() = runBlocking {
        val committed = CompletableDeferred<Gs1DiagnosticRuntimeEvent.Committed>()
        val lease = ScriptedRuntimeLease(
            ingest = {
                Gs1PacketProcessingResult.Completed(
                    committedSamples = listOf(sample(1)),
                    committedIssues = listOf(
                        Gs1PacketProcessingResult.CommittedIssue(
                            sequence = 1,
                            code = "INVALID_GLUCOSE",
                            message = "diagnostic persisted",
                        ),
                    ),
                )
            },
        )
        val runtime = Gs1DiagnosticRuntime(
            scope = this,
            opener = Gs1RuntimeCoreOpener { Gs1RuntimeCoreOpenResult.Success(lease) },
            eventSink = { if (it is Gs1DiagnosticRuntimeEvent.Committed) committed.complete(it) },
        )
        val generation = (runtime.start(profile()) as Gs1RuntimeStartResult.Started).generation

        assertEquals(Gs1RuntimeSubmission.ACCEPTED, runtime.submit(generation, journaled(1)))
        val event = withTimeout(1_000) { committed.await() }

        assertTrue(event.diagnostics.isEmpty())
        assertEquals(listOf("INVALID_GLUCOSE"), event.issues.map { it.code })
        assertTrue(event.acknowledgeDurablySettled())
        runtime.stop()
        Unit
    }

    @Test
    fun terminalHeadLeavesAlreadyJournaledTailForNextGenerationRecovery() = runBlocking {
        val firstStarted = CompletableDeferred<Unit>()
        val releaseFirst = CompletableDeferred<Unit>()
        val lease = ScriptedRuntimeLease(
            ingest = { packet ->
                if (packet.first().toInt() == 1) {
                    firstStarted.complete(Unit)
                    releaseFirst.await()
                    Gs1PacketProcessingResult.InvalidPacket(
                        Gs1VerifiedPacketError.WIRE_PACKET_INVALID,
                        "bad head",
                    )
                } else {
                    completed(2)
                }
            },
        )
        val runtime = Gs1DiagnosticRuntime(
            scope = this,
            opener = Gs1RuntimeCoreOpener { Gs1RuntimeCoreOpenResult.Success(lease) },
            eventSink = { event ->
                if (event is Gs1DiagnosticRuntimeEvent.Committed) {
                    event.acknowledgeDurablySettled()
                }
            },
        )
        val generation = (runtime.start(profile()) as Gs1RuntimeStartResult.Started).generation
        assertEquals(Gs1RuntimeSubmission.ACCEPTED, runtime.submit(generation, journaled(1)))
        withTimeout(1_000) { firstStarted.await() }
        val tail = async { runtime.submitAndAwait(generation, journaled(2)) }
        kotlinx.coroutines.yield()

        releaseFirst.complete(Unit)
        assertEquals(Gs1RuntimeAwaitResult.Closed, withTimeout(1_000) { tail.await() })
        runtime.stop()

        assertEquals(listOf(1), lease.packets.map { it.first().toInt() })
    }

    @Test
    fun stopCancelsPendingRetryClosesLeaseOnceAndLeavesDurableIngressForRecovery() = runBlocking {
        val retryStarted = CompletableDeferred<Unit>()
        val allowRetry = CompletableDeferred<Unit>()
        val lease = ScriptedRuntimeLease(
            ingest = { Gs1PacketProcessingResult.PersistenceUnavailable("commit pending") },
            retry = {
                retryStarted.complete(Unit)
                allowRetry.await()
                completed(1)
            },
        )
        val runtime = Gs1DiagnosticRuntime(
            scope = this,
            opener = Gs1RuntimeCoreOpener { Gs1RuntimeCoreOpenResult.Success(lease) },
            eventSink = { event ->
                if (event is Gs1DiagnosticRuntimeEvent.Committed) {
                    event.acknowledgeDurablySettled()
                }
            },
            retryDelayMillis = 0,
        )
        val generation = (runtime.start(profile()) as Gs1RuntimeStartResult.Started).generation
        runtime.submit(generation, journaled(1))
        withTimeout(1_000) { retryStarted.await() }

        val stopped = withTimeout(1_000) { runtime.stop() }

        assertEquals(Gs1RuntimeStopResult.PERSISTENCE_PENDING, stopped)
        assertTrue(lease.closed.isCompleted)
        assertEquals(1, lease.closeCalls)
        assertTrue(!allowRetry.isCompleted)
    }

    @Test
    fun persistencePendingStopDoesNotBlockAReplayGeneration() = runBlocking {
        val retryStarted = CompletableDeferred<Unit>()
        val allowRetry = CompletableDeferred<Unit>()
        val firstLease = ScriptedRuntimeLease(
            ingest = { Gs1PacketProcessingResult.PersistenceUnavailable("commit pending") },
            retry = {
                retryStarted.complete(Unit)
                allowRetry.await()
                completed(1)
            },
        )
        val replayLease = ScriptedRuntimeLease(ingest = { completed(1) })
        val replayFinalized = CompletableDeferred<Unit>()
        val leases = ArrayDeque(listOf(firstLease, replayLease))
        val runtime = Gs1DiagnosticRuntime(
            scope = this,
            opener = Gs1RuntimeCoreOpener {
                Gs1RuntimeCoreOpenResult.Success(leases.removeFirst())
            },
            eventSink = { event ->
                if (event is Gs1DiagnosticRuntimeEvent.Committed) {
                    event.acknowledgeDurablySettled()
                }
                if (event is Gs1DiagnosticRuntimeEvent.Finalized) {
                    replayFinalized.complete(Unit)
                }
            },
            retryDelayMillis = 0,
            stopTimeoutMillis = 25,
        )
        val generation = (runtime.start(profile()) as Gs1RuntimeStartResult.Started).generation
        runtime.submit(generation, journaled(1))
        withTimeout(1_000) { retryStarted.await() }

        val firstStop = withTimeout(500) { runtime.stop() }

        assertEquals(Gs1RuntimeStopResult.PERSISTENCE_PENDING, firstStop)
        assertTrue(firstLease.closed.isCompleted)
        assertEquals(1, firstLease.closeCalls)
        val replay = runtime.start(profile()) as Gs1RuntimeStartResult.Started
        assertEquals(
            Gs1RuntimeSubmission.ACCEPTED,
            runtime.submit(replay.generation, journaled(1)),
        )
        withTimeout(1_000) { replayFinalized.await() }
        val secondStop = withTimeout(1_000) { runtime.stop(replay.generation) }
        assertEquals(Gs1RuntimeStopResult.DRAINED, secondStop)
        assertTrue(replayLease.closed.isCompleted)
        assertEquals(1, replayLease.closeCalls)
        assertTrue(!allowRetry.isCompleted)
    }

    @Test
    fun mailboxOverflowClosesGenerationAfterDrainingEveryAcceptedPacket() = runBlocking {
        val firstStarted = CompletableDeferred<Unit>()
        val releaseFirst = CompletableDeferred<Unit>()
        val overflowFailure = CompletableDeferred<Gs1DiagnosticRuntimeEvent.Failed>()
        val lease = ScriptedRuntimeLease(
            ingest = { packet ->
                val index = packet.first().toInt()
                if (index == 1) {
                    firstStarted.complete(Unit)
                    releaseFirst.await()
                }
                completed(index)
            },
        )
        val runtime = Gs1DiagnosticRuntime(
            scope = this,
            opener = Gs1RuntimeCoreOpener { Gs1RuntimeCoreOpenResult.Success(lease) },
            eventSink = { event ->
                if (event is Gs1DiagnosticRuntimeEvent.Committed) {
                    event.acknowledgeDurablySettled()
                }
                if (event is Gs1DiagnosticRuntimeEvent.Failed && event.code == "MAILBOX_OVERFLOW") {
                    overflowFailure.complete(event)
                }
            },
            mailboxCapacity = 1,
        )
        val generation = (runtime.start(profile()) as Gs1RuntimeStartResult.Started).generation
        runtime.submit(generation, journaled(1))
        withTimeout(1_000) { firstStarted.await() }

        assertEquals(Gs1RuntimeSubmission.ACCEPTED, runtime.submit(generation, journaled(2)))
        assertEquals(Gs1RuntimeSubmission.OVERFLOW, runtime.submit(generation, journaled(3)))
        releaseFirst.complete(Unit)
        withTimeout(1_000) { overflowFailure.await() }
        runtime.stop()

        assertEquals(listOf(1, 2), lease.packets.map { it.first().toInt() })
        assertTrue(lease.closed.isCompleted)
    }

    private fun profile(): Gs1DiagnosticActivationProfile =
        (Gs1DiagnosticActivationProfile.validate(
            sensorId = "sensor-a",
            family = SensorFamily.SIBIONICS_GS1,
            bluetoothAddress = "AA:BB:CC:DD:EE:FF",
            transportVariant = 0,
            packageCode = "aB12cd34",
        ) as Gs1DiagnosticActivationProfileValidation.Valid).profile

    private fun completed(index: Int) = Gs1PacketProcessingResult.Completed(
        committedSamples = listOf(sample(index)),
        diagnostics = listOf(
            Gs1DiagnosticReading(
                eventId = "diagnostic-$index",
                sensorId = "sensor-a",
                sensorFamily = SensorFamily.SIBIONICS_GS1,
                sensorTimeEpochMs = sample(index).sensorTimeEpochSeconds * 1_000L,
                phoneTimeEpochMs = sample(index).sensorTimeEpochSeconds * 1_000L + 1_000L,
                glucoseMgDl = 100 + index,
                trendMgDlPerMinute = 0.0,
                quality = ReadingQuality.VALID,
                sequence = index.toLong(),
            ),
        ),
    )

    private fun productPublication(index: Int) = Gs1ProductPublication(
        reading = GlucoseReading(
            eventId = "product-$index",
            sensorId = "sensor-a",
            sensorFamily = SensorFamily.SIBIONICS_GS1,
            sensorTimeEpochMs = sample(index).sensorTimeEpochSeconds * 1_000L,
            phoneTimeEpochMs = sample(index).sensorTimeEpochSeconds * 1_000L + 1_000L,
            glucoseMgDl = 100 + index,
            trendMgDlPerMinute = 0.0,
            quality = ReadingQuality.VALID,
            sequence = index.toLong(),
        ),
        approvalId = "ab".repeat(32),
        publicationBindingId = "cd".repeat(32),
    )

    private fun sample(index: Int) = DecodedGs1RawSample(
        index = index,
        sensorTimeEpochSeconds = 1_700_000_000L + index * 60L,
        current = 50,
        temperature = 321,
        reindex = 0,
    )

    private fun journaled(index: Int): DurablyJournaledGs1Packet {
        val packet = byteArrayOf(index.toByte())
        return DurablyJournaledGs1Packet(
            SensorPacketIngressRecord(
                ingressId = "test-ingress-$index",
                sensorId = "sensor-a",
                sensorFamily = SensorFamily.SIBIONICS_GS1,
                bluetoothAddress = "AA:BB:CC:DD:EE:FF",
                attemptId = "runtime-test",
                ordinal = index.toLong(),
                receivedAtEpochMs = 1_700_000_000_000L + index,
                encryptedPacket = packet,
                packetSha256 = MessageDigest.getInstance("SHA-256")
                    .digest(packet)
                    .joinToString("") { byte -> "%02x".format(byte) },
            ),
        )
    }

}

private class ScriptedRuntimeLease(
    override val initialNextIndex: Int = 1,
    override val wireProfile: Gs1WireProfile = Gs1WireProfile.V120,
    private val ingest: suspend (ByteArray) -> Gs1PacketProcessingResult = {
        Gs1PacketProcessingResult.InvalidPacket(Gs1VerifiedPacketError.WIRE_PACKET_INVALID, null)
    },
    private val retry: suspend () -> Gs1PacketProcessingResult = {
        Gs1PacketProcessingResult.NoPendingCommit
    },
) : Gs1RuntimeCoreLease {
    val packets = Collections.synchronizedList(mutableListOf<ByteArray>())
    val closed = CompletableDeferred<Unit>()
    @Volatile var retryCalls = 0
    @Volatile var closeCalls = 0

    override suspend fun ingest(packet: DurablyJournaledGs1Packet): Gs1PacketProcessingResult {
        val bytes = packet.encryptedPacketCopy()
        packets += bytes
        return ingest.invoke(bytes)
    }

    override suspend fun retryPending(): Gs1PacketProcessingResult {
        retryCalls += 1
        return retry.invoke()
    }

    override fun close() {
        closeCalls += 1
        closed.complete(Unit)
    }
}
