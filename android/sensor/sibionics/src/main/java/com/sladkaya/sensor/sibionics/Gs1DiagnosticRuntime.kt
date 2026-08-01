package com.sladkaya.sensor.sibionics

import java.util.concurrent.CancellationException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull

internal interface Gs1RuntimeCoreLease : AutoCloseable {
    val initialNextIndex: Int
    suspend fun ingest(packet: ByteArray): Gs1PacketProcessingResult
    suspend fun retryPending(): Gs1PacketProcessingResult
}

internal fun interface Gs1RuntimeCoreOpener {
    suspend fun open(profile: Gs1DiagnosticActivationProfile): Gs1RuntimeCoreOpenResult
}

internal sealed interface Gs1RuntimeCoreOpenResult {
    data class Success(val lease: Gs1RuntimeCoreLease) : Gs1RuntimeCoreOpenResult
    data class Failure(val code: String, val detail: String? = null) : Gs1RuntimeCoreOpenResult
}

internal sealed interface Gs1RuntimeStartResult {
    data class Started(
        val generation: Long,
        val initialNextIndex: Int,
    ) : Gs1RuntimeStartResult

    data class Failed(val code: String, val detail: String? = null) : Gs1RuntimeStartResult
}

internal enum class Gs1RuntimeStopResult {
    DRAINED,
    PERSISTENCE_PENDING,
    STALE_GENERATION,
}

internal enum class Gs1RuntimeSubmission {
    ACCEPTED,
    STALE_GENERATION,
    CLOSED,
    OVERFLOW,
}

internal sealed interface Gs1RuntimeAwaitResult {
    data class Processed(val result: Gs1PacketProcessingResult) : Gs1RuntimeAwaitResult
    data object StaleGeneration : Gs1RuntimeAwaitResult
    data object Closed : Gs1RuntimeAwaitResult
}

internal enum class Gs1RuntimeIngressDisposition {
    CORE_COMMITTED,
    QUARANTINED,
    NON_DATA,
    UNRESOLVED,
}

internal sealed interface Gs1DiagnosticRuntimeEvent {
    data class Finalized(
        val generation: Long,
        val ingressId: String,
        val receivedAtEpochMs: Long,
        val disposition: Gs1RuntimeIngressDisposition,
        val detail: String? = null,
    ) : Gs1DiagnosticRuntimeEvent

    data class RetryingPersistence(
        val generation: Long,
        val attempt: Int,
    ) : Gs1DiagnosticRuntimeEvent

    data class Committed(
        val generation: Long,
        val samples: List<DecodedGs1RawSample>,
        val diagnostics: List<Gs1DiagnosticReading>,
        val issues: List<Gs1PacketProcessingResult.CommittedIssue> = emptyList(),
    ) : Gs1DiagnosticRuntimeEvent

    data class Failed(
        val generation: Long,
        val code: String,
        val detail: String? = null,
    ) : Gs1DiagnosticRuntimeEvent
}

/**
 * One diagnostic-only FIFO between BLE callbacks and the stateful core.
 * Bluetooth callbacks never suspend and never launch per-notification jobs.
 * Overflow closes the generation instead of dropping an unknown packet.
 */
internal class Gs1DiagnosticRuntime(
    private val scope: CoroutineScope,
    private val opener: Gs1RuntimeCoreOpener,
    private val eventSink: suspend (Gs1DiagnosticRuntimeEvent) -> Unit,
    private val mailboxCapacity: Int = DEFAULT_MAILBOX_CAPACITY,
    private val retryDelayMillis: Long = DEFAULT_RETRY_DELAY_MS,
    private val stopTimeoutMillis: Long = DEFAULT_STOP_TIMEOUT_MS,
) {
    private val lifecycle = Mutex()
    private var nextGeneration = 1L

    @Volatile
    private var current: Generation? = null

    init {
        require(mailboxCapacity > 0)
        require(retryDelayMillis >= 0)
        require(stopTimeoutMillis > 0)
    }

    suspend fun start(profile: Gs1DiagnosticActivationProfile): Gs1RuntimeStartResult =
        lifecycle.withLock {
            current?.let { previous ->
                if (stopAndJoin(previous) == Gs1RuntimeStopResult.PERSISTENCE_PENDING) {
                    return@withLock Gs1RuntimeStartResult.Failed(
                        code = "PERSISTENCE_PENDING",
                        detail = "The previous native state is still waiting for durable storage",
                    )
                }
                if (current === previous) current = null
            }

            val opened = try {
                opener.open(profile)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: LinkageError) {
                return@withLock Gs1RuntimeStartResult.Failed(
                    code = "CORE_OPEN_FAILED",
                    detail = failure.message,
                )
            } catch (failure: Exception) {
                return@withLock Gs1RuntimeStartResult.Failed(
                    code = "CORE_OPEN_FAILED",
                    detail = failure.message,
                )
            }
            if (opened is Gs1RuntimeCoreOpenResult.Failure) {
                return@withLock Gs1RuntimeStartResult.Failed(opened.code, opened.detail)
            }
            opened as Gs1RuntimeCoreOpenResult.Success

            val generation = Generation(
                id = nextGeneration++,
                lease = opened.lease,
                mailbox = Channel(mailboxCapacity),
            )
            generation.job = scope.launch { runGeneration(generation) }
            current = generation
            Gs1RuntimeStartResult.Started(
                generation = generation.id,
                initialNextIndex = generation.lease.initialNextIndex,
            )
        }

    /** Safe for a Bluetooth callback thread. The byte array is copied. */
    fun submit(generation: Long, packet: DurablyJournaledGs1Packet): Gs1RuntimeSubmission {
        val active = current ?: return Gs1RuntimeSubmission.CLOSED
        if (active.id != generation) return Gs1RuntimeSubmission.STALE_GENERATION
        if (!active.accepting.get()) return Gs1RuntimeSubmission.CLOSED

        val offered = active.mailbox.trySend(RuntimeSubmission(packet = packet))
        if (offered.isSuccess) return Gs1RuntimeSubmission.ACCEPTED
        if (offered.isClosed) return Gs1RuntimeSubmission.CLOSED

        if (active.accepting.compareAndSet(true, false)) {
            active.overflowed.set(true)
            active.mailbox.close()
        }
        return Gs1RuntimeSubmission.OVERFLOW
    }

    /**
     * Ordered, back-pressured admission used while replaying durable ingress.
     * It returns only after the exact packet has reached a terminal core result.
     */
    suspend fun submitAndAwait(
        generation: Long,
        packet: DurablyJournaledGs1Packet,
    ): Gs1RuntimeAwaitResult {
        val active = current ?: return Gs1RuntimeAwaitResult.Closed
        if (active.id != generation) return Gs1RuntimeAwaitResult.StaleGeneration
        if (!active.accepting.get()) return Gs1RuntimeAwaitResult.Closed

        val receipt = CompletableDeferred<Gs1RuntimeAwaitResult>()
        active.pendingReceipts += receipt
        return try {
            active.mailbox.send(RuntimeSubmission(packet, receipt))
            receipt.await()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            Gs1RuntimeAwaitResult.Closed
        } finally {
            active.pendingReceipts.remove(receipt)
        }
    }

    /**
     * Stops accepting callbacks, settles already accepted submissions and waits
     * for any exact persistence retry before the native coordinator is closed.
     * A tail behind a terminal result remains in durable ingress for recovery.
     */
    suspend fun stop(expectedGeneration: Long? = null): Gs1RuntimeStopResult =
        lifecycle.withLock {
            val active = current ?: return@withLock Gs1RuntimeStopResult.DRAINED
            if (expectedGeneration != null && active.id != expectedGeneration) {
                return@withLock Gs1RuntimeStopResult.STALE_GENERATION
            }
            val result = stopAndJoin(active)
            if (result == Gs1RuntimeStopResult.DRAINED && current === active) {
                current = null
            }
            result
        }

    private suspend fun stopAndJoin(generation: Generation): Gs1RuntimeStopResult {
        generation.accepting.set(false)
        generation.mailbox.close()
        val drained = withTimeoutOrNull(stopTimeoutMillis) {
            generation.job.join()
            true
        } == true
        return if (drained) {
            Gs1RuntimeStopResult.DRAINED
        } else {
            Gs1RuntimeStopResult.PERSISTENCE_PENDING
        }
    }

    private suspend fun runGeneration(generation: Generation) {
        try {
            var terminal = false
            for (submission in generation.mailbox) {
                if (terminal) {
                    // Every accepted packet is already durable in the ingress
                    // journal. After a terminal core result, leave the tail for
                    // a fresh generation instead of mutating a failed lease.
                    submission.receipt?.complete(Gs1RuntimeAwaitResult.Closed)
                    submission.receipt?.let(generation.pendingReceipts::remove)
                    continue
                }
                var result = generation.lease.ingest(submission.packet.encryptedPacketCopy())
                var retryAttempt = 0
                while (result is Gs1PacketProcessingResult.PersistenceUnavailable) {
                    retryAttempt += 1
                    eventSink(
                        Gs1DiagnosticRuntimeEvent.RetryingPersistence(
                            generation = generation.id,
                            attempt = retryAttempt,
                        ),
                    )
                    if (retryDelayMillis > 0) delay(retryDelayMillis)
                    result = generation.lease.retryPending()
                }
                eventSink(
                    Gs1DiagnosticRuntimeEvent.Finalized(
                        generation = generation.id,
                        ingressId = submission.packet.ingressId,
                        receivedAtEpochMs = submission.packet.receivedAtEpochMs,
                        disposition = result.ingressDisposition(),
                        detail = result.ingressDetail(),
                    ),
                )
                val continueAccepting = emitFinalResult(generation.id, result)
                submission.receipt?.complete(Gs1RuntimeAwaitResult.Processed(result))
                submission.receipt?.let(generation.pendingReceipts::remove)
                if (!continueAccepting) {
                    if (!terminal) {
                        terminal = true
                        generation.accepting.set(false)
                        generation.mailbox.close()
                    }
                }
            }
            if (generation.overflowed.get()) {
                eventSink(
                    Gs1DiagnosticRuntimeEvent.Failed(
                        generation = generation.id,
                        code = "MAILBOX_OVERFLOW",
                        detail = "Diagnostic BLE mailbox filled before the core could commit",
                    ),
                )
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: LinkageError) {
            eventSink(
                Gs1DiagnosticRuntimeEvent.Failed(
                    generation = generation.id,
                    code = "RUNTIME_LINKAGE_FAILED",
                    detail = failure.message,
                ),
            )
        } catch (failure: Exception) {
            eventSink(
                Gs1DiagnosticRuntimeEvent.Failed(
                    generation = generation.id,
                    code = "RUNTIME_FAILED",
                    detail = failure.message,
                ),
            )
        } finally {
            generation.accepting.set(false)
            generation.pendingReceipts.forEach { it.complete(Gs1RuntimeAwaitResult.Closed) }
            generation.pendingReceipts.clear()
            generation.mailbox.cancel()
            generation.lease.close()
        }
    }

    private suspend fun emitFinalResult(
        generation: Long,
        result: Gs1PacketProcessingResult,
    ): Boolean {
        return when (result) {
            is Gs1PacketProcessingResult.Completed -> {
                emitCommitted(
                    generation,
                    result.committedSamples,
                    result.diagnostics,
                    result.committedIssues,
                )
                true
            }

        is Gs1PacketProcessingResult.InvalidPacket -> {
            eventSink(
                Gs1DiagnosticRuntimeEvent.Failed(
                    generation = generation,
                    code = result.error.name,
                    detail = result.detail,
                ),
            )
            false
        }

        is Gs1PacketProcessingResult.Rejected -> {
            emitCommitted(
                generation,
                result.committedSamples,
                result.diagnostics,
                result.committedIssues,
            )
            eventSink(Gs1DiagnosticRuntimeEvent.Failed(generation, result.code, result.message))
            false
        }

        is Gs1PacketProcessingResult.StorageConflict -> {
            emitCommitted(
                generation,
                result.committedSamples,
                result.diagnostics,
                result.committedIssues,
            )
            eventSink(
                Gs1DiagnosticRuntimeEvent.Failed(generation, "STORAGE_CONFLICT", result.reason),
            )
            false
        }

        is Gs1PacketProcessingResult.Closed -> {
            emitCommitted(
                generation,
                result.committedSamples,
                result.diagnostics,
                result.committedIssues,
            )
            eventSink(Gs1DiagnosticRuntimeEvent.Failed(generation, "CORE_CLOSED", result.reason))
            false
        }

        Gs1PacketProcessingResult.NoPendingCommit -> {
            eventSink(
                Gs1DiagnosticRuntimeEvent.Failed(
                    generation,
                    "PENDING_COMMIT_LOST",
                    "The core returned no pending commit while retrying",
                ),
            )
            false
        }

        is Gs1PacketProcessingResult.PersistenceUnavailable -> error(
            "PersistenceUnavailable must be resolved before final dispatch",
        )
        }
    }

    private fun Gs1PacketProcessingResult.ingressDisposition(): Gs1RuntimeIngressDisposition {
        return when (this) {
            is Gs1PacketProcessingResult.Completed -> if (committedSamples.isEmpty()) {
                Gs1RuntimeIngressDisposition.NON_DATA
            } else {
                Gs1RuntimeIngressDisposition.CORE_COMMITTED
            }
            is Gs1PacketProcessingResult.InvalidPacket -> Gs1RuntimeIngressDisposition.QUARANTINED
            is Gs1PacketProcessingResult.Rejected,
            is Gs1PacketProcessingResult.StorageConflict,
            is Gs1PacketProcessingResult.Closed,
            is Gs1PacketProcessingResult.PersistenceUnavailable,
            Gs1PacketProcessingResult.NoPendingCommit,
            -> Gs1RuntimeIngressDisposition.UNRESOLVED
        }
    }

    private fun Gs1PacketProcessingResult.ingressDetail(): String? = when (this) {
        is Gs1PacketProcessingResult.InvalidPacket -> detail ?: error.name
        else -> null
    }

    private suspend fun emitCommitted(
        generation: Long,
        samples: List<DecodedGs1RawSample>,
        diagnostics: List<Gs1DiagnosticReading>,
        issues: List<Gs1PacketProcessingResult.CommittedIssue> = emptyList(),
    ) {
        if (samples.isEmpty() && diagnostics.isEmpty() && issues.isEmpty()) return
        eventSink(
            Gs1DiagnosticRuntimeEvent.Committed(
                generation = generation,
                samples = samples.toList(),
                diagnostics = diagnostics.toList(),
                issues = issues.toList(),
            ),
        )
    }

    private class Generation(
        val id: Long,
        val lease: Gs1RuntimeCoreLease,
        val mailbox: Channel<RuntimeSubmission>,
        val accepting: AtomicBoolean = AtomicBoolean(true),
        val overflowed: AtomicBoolean = AtomicBoolean(false),
        val pendingReceipts: MutableSet<CompletableDeferred<Gs1RuntimeAwaitResult>> =
            ConcurrentHashMap.newKeySet(),
    ) {
        lateinit var job: Job
    }

    private data class RuntimeSubmission(
        val packet: DurablyJournaledGs1Packet,
        val receipt: CompletableDeferred<Gs1RuntimeAwaitResult>? = null,
    )

    private companion object {
        const val DEFAULT_MAILBOX_CAPACITY = 64
        const val DEFAULT_RETRY_DELAY_MS = 500L
        const val DEFAULT_STOP_TIMEOUT_MS = 2_000L
    }
}

internal class FactoryGs1RuntimeCoreOpener(
    private val factory: Gs1CoreFactory,
) : Gs1RuntimeCoreOpener {
    override suspend fun open(profile: Gs1DiagnosticActivationProfile): Gs1RuntimeCoreOpenResult =
        when (val opened = factory.open(profile.coreConfiguration())) {
            is Gs1CoreOpenResult.Failure -> Gs1RuntimeCoreOpenResult.Failure(
                code = opened.error.name,
                detail = opened.detail,
            )

            is Gs1CoreOpenResult.Success -> Gs1RuntimeCoreOpenResult.Success(
                FactoryGs1RuntimeCoreLease(
                    coordinator = opened.coordinator,
                    initialNextIndex = opened.nextSensorIndex,
                ),
            )
        }
}

private class FactoryGs1RuntimeCoreLease(
    private val coordinator: Gs1ProcessingCoordinator,
    override val initialNextIndex: Int,
) : Gs1RuntimeCoreLease {
    private val processor = Gs1PacketProcessor(
        core = coordinator,
        initialExpectedIndex = initialNextIndex,
    )

    override suspend fun ingest(packet: ByteArray): Gs1PacketProcessingResult =
        processor.ingest(packet)

    override suspend fun retryPending(): Gs1PacketProcessingResult =
        processor.retryPending()

    override fun close() {
        coordinator.close()
    }
}
