package com.sladkaya.sensor.sibionics

import com.sladkaya.core.model.GlucoseReading
import com.sladkaya.core.model.ReadingQuality
import com.sladkaya.core.model.SensorFamily
import java.util.Collections
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class Gs1DiagnosticRuntimeTest {
    @Test
    fun terminalIngressOutcomePrecedesDiagnosticPublicationAndKeepsReceiptIdentity() = runBlocking {
        val events = Collections.synchronizedList(mutableListOf<Gs1DiagnosticRuntimeEvent>())
        val runtime = Gs1DiagnosticRuntime(
            scope = this,
            opener = Gs1RuntimeCoreOpener {
                Gs1RuntimeCoreOpenResult.Success(
                    ScriptedRuntimeLease(ingest = { completed(8) }),
                )
            },
            eventSink = { events += it },
        )
        val generation = (runtime.start(profile()) as Gs1RuntimeStartResult.Started).generation

        runtime.submit(generation, journaled(8))
        withTimeout(1_000) {
            while (events.none { it is Gs1DiagnosticRuntimeEvent.Committed }) {
                kotlinx.coroutines.yield()
            }
        }

        val finalizedIndex = events.indexOfFirst { it is Gs1DiagnosticRuntimeEvent.Finalized }
        val committedIndex = events.indexOfFirst { it is Gs1DiagnosticRuntimeEvent.Committed }
        val finalized = events[finalizedIndex] as Gs1DiagnosticRuntimeEvent.Finalized
        assertTrue(finalizedIndex in 0 until committedIndex)
        assertEquals("test-ingress-8", finalized.ingressId)
        assertEquals(1_700_000_000_008L, finalized.receivedAtEpochMs)
        assertEquals(Gs1RuntimeIngressDisposition.CORE_COMMITTED, finalized.disposition)
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
            eventSink = {},
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
    fun recoverySubmissionForOldGenerationIsRejectedBeforeCore() = runBlocking {
        val first = ScriptedRuntimeLease()
        val second = ScriptedRuntimeLease()
        val leases = ArrayDeque(listOf(first, second))
        val runtime = Gs1DiagnosticRuntime(
            scope = this,
            opener = Gs1RuntimeCoreOpener {
                Gs1RuntimeCoreOpenResult.Success(leases.removeFirst())
            },
            eventSink = {},
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
            eventSink = {},
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
            eventSink = {},
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
            eventSink = { events += it },
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
    fun anyProductReadingFromDiagnosticLeaseFailsClosed() = runBlocking {
        val terminal = CompletableDeferred<Gs1DiagnosticRuntimeEvent.Failed>()
        val lease = ScriptedRuntimeLease(
            ingest = {
                Gs1PacketProcessingResult.Completed(
                    readings = listOf(
                        Gs1PacketProcessingResult.PublishedReading(
                            reading = reading(1),
                            alarmEligible = true,
                        ),
                    ),
                    committedSamples = listOf(sample(1)),
                )
            },
        )
        val runtime = Gs1DiagnosticRuntime(
            scope = this,
            opener = Gs1RuntimeCoreOpener { Gs1RuntimeCoreOpenResult.Success(lease) },
            eventSink = { if (it is Gs1DiagnosticRuntimeEvent.Failed) terminal.complete(it) },
        )
        val generation = (runtime.start(profile()) as Gs1RuntimeStartResult.Started).generation

        runtime.submit(generation, journaled(1))
        val failure = withTimeout(1_000) { terminal.await() }
        runtime.stop()

        assertEquals("PRODUCT_PUBLICATION_BYPASS", failure.code)
        assertTrue(lease.closed.isCompleted)
    }

    @Test
    fun productReadingInCommittedPrefixOfRejectedBatchAlsoFailsClosed() = runBlocking {
        val terminal = CompletableDeferred<Gs1DiagnosticRuntimeEvent.Failed>()
        val lease = ScriptedRuntimeLease(
            ingest = {
                Gs1PacketProcessingResult.Rejected(
                    code = "LATER_SAMPLE_REJECTED",
                    message = "partial prefix",
                    readings = listOf(
                        Gs1PacketProcessingResult.PublishedReading(reading(1), alarmEligible = true),
                    ),
                    committedSamples = listOf(sample(1)),
                )
            },
        )
        val runtime = Gs1DiagnosticRuntime(
            scope = this,
            opener = Gs1RuntimeCoreOpener { Gs1RuntimeCoreOpenResult.Success(lease) },
            eventSink = { if (it is Gs1DiagnosticRuntimeEvent.Failed) terminal.complete(it) },
        )
        val generation = (runtime.start(profile()) as Gs1RuntimeStartResult.Started).generation

        runtime.submit(generation, journaled(1))
        val failure = withTimeout(1_000) { terminal.await() }
        runtime.stop()

        assertEquals("PRODUCT_PUBLICATION_BYPASS", failure.code)
    }

    @Test
    fun committedAlgorithmIssueIsNotReportedAsHealthyDiagnosticData() = runBlocking {
        val committed = CompletableDeferred<Gs1DiagnosticRuntimeEvent.Committed>()
        val lease = ScriptedRuntimeLease(
            ingest = {
                Gs1PacketProcessingResult.Completed(
                    readings = emptyList(),
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
        runtime.stop()

        assertTrue(event.diagnostics.isEmpty())
        assertEquals(listOf("INVALID_GLUCOSE"), event.issues.map { it.code })
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
            eventSink = {},
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
    fun stopWaitsForExactPendingRetryBeforeClosingNativeLease() = runBlocking {
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
            eventSink = {},
            retryDelayMillis = 0,
        )
        val generation = (runtime.start(profile()) as Gs1RuntimeStartResult.Started).generation
        runtime.submit(generation, journaled(1))
        withTimeout(1_000) { retryStarted.await() }

        val stopping = async { runtime.stop() }
        kotlinx.coroutines.yield()
        assertTrue(!stopping.isCompleted)
        assertTrue(!lease.closed.isCompleted)

        allowRetry.complete(Unit)
        withTimeout(1_000) { stopping.await() }
        assertTrue(lease.closed.isCompleted)
    }

    @Test
    fun stopReturnsPersistencePendingInsteadOfHangingOrClosingMutatedCore() = runBlocking {
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
            eventSink = {},
            retryDelayMillis = 0,
            stopTimeoutMillis = 25,
        )
        val generation = (runtime.start(profile()) as Gs1RuntimeStartResult.Started).generation
        runtime.submit(generation, journaled(1))
        withTimeout(1_000) { retryStarted.await() }

        val firstStop = withTimeout(500) { runtime.stop() }

        assertEquals(Gs1RuntimeStopResult.PERSISTENCE_PENDING, firstStop)
        assertTrue(!lease.closed.isCompleted)
        val blockedStart = runtime.start(profile()) as Gs1RuntimeStartResult.Failed
        assertEquals("PERSISTENCE_PENDING", blockedStart.code)

        allowRetry.complete(Unit)
        val secondStop = withTimeout(1_000) { runtime.stop() }
        assertEquals(Gs1RuntimeStopResult.DRAINED, secondStop)
        assertTrue(lease.closed.isCompleted)
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
            eventSink = {
                if (it is Gs1DiagnosticRuntimeEvent.Failed && it.code == "MAILBOX_OVERFLOW") {
                    overflowFailure.complete(it)
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

    private fun profile(): Gs1ActivationProfile =
        (Gs1ActivationProfile.validate(
            sensorId = "sensor-a",
            family = SensorFamily.SIBIONICS_GS1,
            bluetoothAddress = "AA:BB:CC:DD:EE:FF",
            transportVariant = 0,
            packageCode = "aB12cd34",
        ) as Gs1ActivationProfileValidation.Valid).profile

    private fun completed(index: Int) = Gs1PacketProcessingResult.Completed(
        readings = emptyList(),
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

    private fun sample(index: Int) = DecodedGs1RawSample(
        index = index,
        sensorTimeEpochSeconds = 1_700_000_000L + index * 60L,
        current = 50,
        temperature = 321,
        reindex = 0,
    )

    private fun journaled(index: Int) = DurablyJournaledGs1Packet(
        ingressId = "test-ingress-$index",
        receivedAtEpochMs = 1_700_000_000_000L + index,
        encryptedPacket = byteArrayOf(index.toByte()),
    )

    private fun reading(index: Int) = GlucoseReading(
        eventId = "event-$index",
        sensorId = "sensor-a",
        sensorFamily = SensorFamily.SIBIONICS_GS1,
        sensorTimeEpochMs = sample(index).sensorTimeEpochSeconds * 1_000L,
        phoneTimeEpochMs = sample(index).sensorTimeEpochSeconds * 1_000L + 1_000L,
        glucoseMgDl = 100 + index,
        trendMgDlPerMinute = 0.0,
        quality = ReadingQuality.VALID,
        sequence = index.toLong(),
    )
}

private class ScriptedRuntimeLease(
    override val initialNextIndex: Int = 1,
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

    override suspend fun ingest(packet: ByteArray): Gs1PacketProcessingResult {
        packets += packet.copyOf()
        return ingest.invoke(packet)
    }

    override suspend fun retryPending(): Gs1PacketProcessingResult {
        retryCalls += 1
        return retry.invoke()
    }

    override fun close() {
        closed.complete(Unit)
    }
}
