package com.sladkaya.sensor.sibionics

import com.sladkaya.core.data.SensorPacketIngressRecord
import java.util.concurrent.CancellationException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull

internal interface Gs1RuntimeCoreLease : AutoCloseable {
    val initialNextIndex: Int
    val wireProfile: Gs1WireProfile
    suspend fun ingest(packet: DurablyJournaledGs1Packet): Gs1PacketProcessingResult
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
        val wireProfile: Gs1WireProfile,
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
        val ingress: SensorPacketIngressRecord,
        val samples: List<DecodedGs1RawSample>,
        val diagnostics: List<Gs1DiagnosticReading>,
        val publications: List<Gs1ProductPublication> = emptyList(),
        val issues: List<Gs1PacketProcessingResult.CommittedIssue> = emptyList(),
        val validatedTransportEnvelope: Boolean = false,
        private val settlement: Gs1CommittedEventSettlement? = null,
    ) : Gs1DiagnosticRuntimeEvent {
        fun acknowledgeDurablySettled(): Boolean = settlement?.accept() == true

        internal fun rejectSettlement(code: String, detail: String? = null): Boolean =
            settlement?.reject(code, detail) == true

        internal suspend fun awaitSettlement() {
            settlement?.await()
        }
    }

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
                stopAndJoin(previous)
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
                wireProfile = generation.lease.wireProfile,
            )
        }

    /** Safe for a Bluetooth callback thread. The byte array is copied. */
    fun submit(generation: Long, packet: DurablyJournaledGs1Packet): Gs1RuntimeSubmission {
        val active = current ?: return Gs1RuntimeSubmission.CLOSED
        if (active.id != generation) return Gs1RuntimeSubmission.STALE_GENERATION
        if (!active.accepting.get()) return Gs1RuntimeSubmission.CLOSED

        val offered = active.mailbox.trySend(
            RuntimeSubmission(packet = packet, dispatchLiveEvents = true),
        )
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
            active.mailbox.send(
                RuntimeSubmission(
                    packet = packet,
                    receipt = receipt,
                    dispatchLiveEvents = false,
                ),
            )
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
            if (current === active) {
                current = null
            }
            result
        }

    private suspend fun stopAndJoin(generation: Generation): Gs1RuntimeStopResult {
        generation.accepting.set(false)
        generation.stopRequested.set(true)
        generation.activeSettlement.get()?.let { settlement ->
            if (
                settlement.reject(
                    code = "APPLICATION_STOPPED",
                    detail = "Runtime stopped before local effects were durably settled",
                )
            ) {
                generation.persistenceAbandoned.set(true)
            }
        }
        generation.mailbox.close()
        if (generation.retryingPersistence.get()) {
            generation.persistenceAbandoned.set(true)
            generation.job.cancel(
                CancellationException(
                    "Core persistence retry stopped; durable ingress remains pending",
                ),
            )
        }
        val drained = withTimeoutOrNull(stopTimeoutMillis) {
            generation.job.join()
            true
        } == true
        if (!drained) {
            if (generation.retryingPersistence.get()) {
                generation.persistenceAbandoned.set(true)
            }
            generation.job.cancel()
            generation.job.join()
        }
        return if (generation.persistenceAbandoned.get()) {
            Gs1RuntimeStopResult.PERSISTENCE_PENDING
        } else Gs1RuntimeStopResult.DRAINED
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
                var result = generation.lease.ingest(submission.packet)
                var retryAttempt = 0
                while (result is Gs1PacketProcessingResult.PersistenceUnavailable) {
                    if (generation.stopRequested.get()) {
                        generation.persistenceAbandoned.set(true)
                        throw CancellationException(
                            "Core persistence retry stopped; durable ingress remains pending",
                        )
                    }
                    generation.retryingPersistence.set(true)
                    retryAttempt += 1
                    if (submission.dispatchLiveEvents) {
                        eventSink(
                            Gs1DiagnosticRuntimeEvent.RetryingPersistence(
                                generation = generation.id,
                                attempt = retryAttempt,
                            ),
                        )
                    }
                    if (retryDelayMillis > 0) delay(retryDelayMillis)
                    kotlinx.coroutines.currentCoroutineContext().ensureActive()
                    if (generation.stopRequested.get()) {
                        generation.persistenceAbandoned.set(true)
                        throw CancellationException(
                            "Core persistence retry stopped; durable ingress remains pending",
                        )
                    }
                    result = generation.lease.retryPending()
                }
                generation.retryingPersistence.set(false)
                val continueAccepting = if (submission.dispatchLiveEvents) {
                    // A durable commit must reach the ordered downstream actor before
                    // the ingress can be finalized. If delivery fails or is cancelled,
                    // the missing outcome deliberately leaves the ingress recoverable.
                    emitCommittedResult(generation, submission.packet.ingress, result)
                    eventSink(
                        Gs1DiagnosticRuntimeEvent.Finalized(
                            generation = generation.id,
                            ingressId = submission.packet.ingressId,
                            receivedAtEpochMs = submission.packet.receivedAtEpochMs,
                            disposition = result.ingressDisposition(),
                            detail = result.ingressDetail(),
                        ),
                    )
                    emitFinalResult(generation.id, result)
                } else {
                    // Recovery owns exact validation, output delivery and ingress
                    // finalization. Emitting the live stream here would duplicate it
                    // before the GATT attempt even exists.
                    result is Gs1PacketProcessingResult.Completed
                }
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
            is Gs1PacketProcessingResult.Completed -> true

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
            eventSink(Gs1DiagnosticRuntimeEvent.Failed(generation, result.code, result.message))
            false
        }

        is Gs1PacketProcessingResult.StorageConflict -> {
            eventSink(
                Gs1DiagnosticRuntimeEvent.Failed(generation, "STORAGE_CONFLICT", result.reason),
            )
            false
        }

        is Gs1PacketProcessingResult.Closed -> {
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

    private suspend fun emitCommittedResult(
        generation: Generation,
        ingress: SensorPacketIngressRecord,
        result: Gs1PacketProcessingResult,
    ) {
        when (result) {
            is Gs1PacketProcessingResult.Completed -> emitCommitted(
                generation = generation,
                ingress = ingress,
                samples = result.committedSamples,
                diagnostics = result.diagnostics,
                publications = result.publications,
                issues = result.committedIssues,
                validatedTransportEnvelope = result.validatedTransportEnvelope,
            )

            is Gs1PacketProcessingResult.Rejected -> emitCommitted(
                generation = generation,
                ingress = ingress,
                samples = result.committedSamples,
                diagnostics = result.diagnostics,
                publications = result.publications,
                issues = result.committedIssues,
            )

            is Gs1PacketProcessingResult.StorageConflict -> emitCommitted(
                generation = generation,
                ingress = ingress,
                samples = result.committedSamples,
                diagnostics = result.diagnostics,
                publications = result.publications,
                issues = result.committedIssues,
            )

            is Gs1PacketProcessingResult.Closed -> emitCommitted(
                generation = generation,
                ingress = ingress,
                samples = result.committedSamples,
                diagnostics = result.diagnostics,
                publications = result.publications,
                issues = result.committedIssues,
            )

            is Gs1PacketProcessingResult.InvalidPacket,
            is Gs1PacketProcessingResult.PersistenceUnavailable,
            Gs1PacketProcessingResult.NoPendingCommit,
            -> Unit
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
        generation: Generation,
        ingress: SensorPacketIngressRecord,
        samples: List<DecodedGs1RawSample>,
        diagnostics: List<Gs1DiagnosticReading>,
        publications: List<Gs1ProductPublication> = emptyList(),
        issues: List<Gs1PacketProcessingResult.CommittedIssue> = emptyList(),
        validatedTransportEnvelope: Boolean = false,
    ) {
        if (samples.isEmpty() && diagnostics.isEmpty() && publications.isEmpty() && issues.isEmpty() &&
            !validatedTransportEnvelope
        ) return
        val settlement = Gs1CommittedEventSettlement()
        val committed = Gs1DiagnosticRuntimeEvent.Committed(
            generation = generation.id,
            ingress = ingress,
            samples = samples.toList(),
            diagnostics = diagnostics.toList(),
            publications = publications.toList(),
            issues = issues.toList(),
            validatedTransportEnvelope = validatedTransportEnvelope,
            settlement = settlement,
        )
        check(generation.activeSettlement.compareAndSet(null, settlement)) {
            "A runtime generation cannot have more than one active committed settlement"
        }
        try {
            if (
                generation.stopRequested.get() &&
                settlement.reject(
                    code = "APPLICATION_STOPPED",
                    detail = "Runtime stopped before local effects were durably settled",
                )
            ) {
                generation.persistenceAbandoned.set(true)
            }
            eventSink(committed)
            // The next native ingest and this ingress' Finalized event are forbidden
            // until Room validation, local effects and durable cursor settlement finish.
            committed.awaitSettlement()
        } finally {
            generation.activeSettlement.compareAndSet(settlement, null)
        }
    }

    private class Generation(
        val id: Long,
        val lease: Gs1RuntimeCoreLease,
        val mailbox: Channel<RuntimeSubmission>,
        val accepting: AtomicBoolean = AtomicBoolean(true),
        val overflowed: AtomicBoolean = AtomicBoolean(false),
        val stopRequested: AtomicBoolean = AtomicBoolean(false),
        val retryingPersistence: AtomicBoolean = AtomicBoolean(false),
        val persistenceAbandoned: AtomicBoolean = AtomicBoolean(false),
        val activeSettlement: AtomicReference<Gs1CommittedEventSettlement?> =
            AtomicReference(null),
        val pendingReceipts: MutableSet<CompletableDeferred<Gs1RuntimeAwaitResult>> =
            ConcurrentHashMap.newKeySet(),
    ) {
        lateinit var job: Job
    }

    private data class RuntimeSubmission(
        val packet: DurablyJournaledGs1Packet,
        val receipt: CompletableDeferred<Gs1RuntimeAwaitResult>? = null,
        val dispatchLiveEvents: Boolean,
    )

    private companion object {
        const val DEFAULT_MAILBOX_CAPACITY = 64
        const val DEFAULT_RETRY_DELAY_MS = 500L
        const val DEFAULT_STOP_TIMEOUT_MS = 2_000L
    }
}

internal class Gs1CommittedEventSettlement {
    private val result = CompletableDeferred<Unit>()

    fun accept(): Boolean = result.complete(Unit)

    fun reject(code: String, detail: String?): Boolean = result.completeExceptionally(
        Gs1CommittedSettlementRejectedException(code, detail),
    )

    suspend fun await() = result.await()
}

private class Gs1CommittedSettlementRejectedException(
    code: String,
    detail: String?,
) : IllegalStateException(listOfNotNull(code, detail).joinToString(": "))

internal class FactoryGs1RuntimeCoreOpener(
    private val factory: Gs1CoreFactory,
) : Gs1RuntimeCoreOpener {
    override suspend fun open(profile: Gs1DiagnosticActivationProfile): Gs1RuntimeCoreOpenResult {
        return when (val resolution = factory.inspectProtocol(profile)) {
            is Gs1ProtocolResolution.Failure -> Gs1RuntimeCoreOpenResult.Failure(
                code = resolution.code,
                detail = resolution.detail,
            )
            Gs1ProtocolResolution.Unresolved -> Gs1RuntimeCoreOpenResult.Success(
                ResolvingGs1RuntimeCoreLease(profile, factory, Gs1WireProfile.UNRESOLVED),
            )
            is Gs1ProtocolResolution.Resolved -> if (resolution.binding == null) {
                Gs1RuntimeCoreOpenResult.Success(
                    ResolvingGs1RuntimeCoreLease(profile, factory, resolution.wireProfile),
                )
            } else {
                openResolved(profile, resolution)
            }
        }
    }

    private suspend fun openResolved(
        profile: Gs1DiagnosticActivationProfile,
        resolution: Gs1ProtocolResolution.Resolved,
    ): Gs1RuntimeCoreOpenResult = when (val opened = factory.openBound(profile, resolution)) {
            is Gs1CoreOpenResult.Failure -> Gs1RuntimeCoreOpenResult.Failure(
                code = opened.error.name,
                detail = opened.detail,
            )

            is Gs1CoreOpenResult.Success -> Gs1RuntimeCoreOpenResult.Success(
                FactoryGs1RuntimeCoreLease(
                    coordinator = opened.coordinator,
                    initialNextIndex = opened.nextSensorIndex,
                    wireProfile = resolution.wireProfile,
                ),
            )
        }
}

/** Product opener reuses the exact diagnostic transport, verifier and algorithm lease. */
internal class FactoryGs1ApprovedRuntimeCoreOpener(
    private val factory: Gs1CoreFactory,
    private val permitIssuer: Gs1ProductPermitIssuer,
) : Gs1RuntimeCoreOpener {
    override suspend fun open(profile: Gs1DiagnosticActivationProfile): Gs1RuntimeCoreOpenResult {
        val permit = when (val issued = permitIssuer.issue(profile)) {
            is Gs1ProductPermitIssueResult.Granted -> issued.permit
            is Gs1ProductPermitIssueResult.Denied -> return Gs1RuntimeCoreOpenResult.Failure(
                code = issued.error.name,
                detail = issued.detail,
            )
        }
        return when (val opened = factory.openApproved(profile, permit)) {
            is Gs1CoreOpenResult.Failure -> Gs1RuntimeCoreOpenResult.Failure(
                code = opened.error.name,
                detail = opened.detail,
            )
            is Gs1CoreOpenResult.Success -> {
                val wireProfile = permit.active.approval.wireProfile
                    .let { value -> Gs1WireProfile.entries.firstOrNull { it.name == value } }
                    ?: return Gs1RuntimeCoreOpenResult.Failure(
                        code = Gs1CoreOpenError.PRODUCT_APPROVAL_CONFIGURATION_MISMATCH.name,
                    )
                Gs1RuntimeCoreOpenResult.Success(
                    FactoryGs1RuntimeCoreLease(
                        coordinator = opened.coordinator,
                        initialNextIndex = opened.nextSensorIndex,
                        wireProfile = wireProfile,
                    ),
                )
            }
        }
    }
}

private class ResolvingGs1RuntimeCoreLease(
    private val profile: Gs1DiagnosticActivationProfile,
    private val factory: Gs1CoreFactory,
    initialWireProfile: Gs1WireProfile,
    private val v120Verifier: Gs1PacketVerifier = Gs1VerifiedPacketDecoder(),
    private val v115Verifier: Gs1PacketVerifier = Gs1V115VerifiedPacketDecoder(),
) : Gs1RuntimeCoreLease {
    override val initialNextIndex: Int = 1
    override var wireProfile: Gs1WireProfile = initialWireProfile
        private set
    private var delegate: FactoryGs1RuntimeCoreLease? = null
    private var pending: PendingResolution? = null
    private var closed = false

    override suspend fun ingest(packet: DurablyJournaledGs1Packet): Gs1PacketProcessingResult {
        if (closed) return Gs1PacketProcessingResult.Closed("GS1 protocol resolver is closed")
        delegate?.let { return it.ingest(packet) }
        if (pending != null) {
            return Gs1PacketProcessingResult.PersistenceUnavailable(
                "Protocol evidence is waiting for a durable binding",
            )
        }
        val classified = classify(packet)
        if (classified is ResolutionClassification.Failure) return classified.result
        classified as ResolutionClassification.Evidence
        pending = PendingResolution(packet, classified)
        return resolvePending()
    }

    override suspend fun retryPending(): Gs1PacketProcessingResult {
        delegate?.let { return it.retryPending() }
        if (pending == null) return Gs1PacketProcessingResult.NoPendingCommit
        return resolvePending()
    }

    override fun close() {
        if (closed) return
        closed = true
        delegate?.close()
        delegate = null
    }

    private fun classify(packet: DurablyJournaledGs1Packet): ResolutionClassification {
        val bytes = packet.encryptedPacketCopy()
        if (wireProfile == Gs1WireProfile.UNRESOLVED) {
            if (Gs1V115WireCodec.isV120Challenge(bytes)) {
                return ResolutionClassification.Evidence(
                    Gs1WireProfile.V120,
                    "EXACT_V120_CHALLENGE",
                    processPacketAfterOpen = false,
                )
            }
            return when (val decoded = v115Verifier.decode(bytes, packet.receivedAtEpochMs)) {
                is Gs1VerifiedPacketResult.Success -> ResolutionClassification.Evidence(
                    Gs1WireProfile.V115,
                    "VALIDATED_V115_ENVELOPE",
                    processPacketAfterOpen = decoded.samples.isNotEmpty(),
                    validatedTransportEnvelope = decoded.samples.isEmpty(),
                )
                is Gs1VerifiedPacketResult.Failure -> ResolutionClassification.Failure(
                    Gs1PacketProcessingResult.InvalidPacket(decoded.error, decoded.detail),
                )
            }
        }
        if (wireProfile != Gs1WireProfile.V120) {
            return ResolutionClassification.Failure(
                Gs1PacketProcessingResult.Closed("Unexpected unresolved protocol state"),
            )
        }
        return when (val decoded = v120Verifier.decode(bytes, packet.receivedAtEpochMs)) {
            is Gs1VerifiedPacketResult.Success -> if (decoded.samples.isEmpty()) {
                ResolutionClassification.Failure(
                    Gs1PacketProcessingResult.InvalidPacket(
                        Gs1VerifiedPacketError.NOT_GS1_DATA,
                        "V120 binding requires validated sensor data",
                    ),
                )
            } else {
                ResolutionClassification.Evidence(
                    Gs1WireProfile.V120,
                    "VALIDATED_V120_DATA",
                    processPacketAfterOpen = true,
                )
            }
            is Gs1VerifiedPacketResult.Failure -> ResolutionClassification.Failure(
                Gs1PacketProcessingResult.InvalidPacket(decoded.error, decoded.detail),
            )
        }
    }

    private suspend fun resolvePending(): Gs1PacketProcessingResult {
        val value = pending ?: return Gs1PacketProcessingResult.NoPendingCommit
        val bytes = value.packet.encryptedPacketCopy()
        val bound = factory.bindProtocolEvidence(
            profile = profile,
            wireProfile = value.evidence.wireProfile,
            evidenceKind = value.evidence.evidenceKind,
            evidence = bytes,
        )
        if (bound is Gs1ProtocolResolution.Failure) {
            if (bound.retryable) {
                return Gs1PacketProcessingResult.PersistenceUnavailable(
                    bound.detail ?: bound.code,
                )
            }
            pending = null
            closed = true
            return Gs1PacketProcessingResult.Closed(bound.detail ?: bound.code)
        }
        bound as Gs1ProtocolResolution.Resolved
        val opened = factory.openBound(profile, bound)
        if (opened is Gs1CoreOpenResult.Failure) {
            if (opened.error == Gs1CoreOpenError.STORAGE_UNAVAILABLE) {
                return Gs1PacketProcessingResult.PersistenceUnavailable(
                    opened.detail ?: opened.error.name,
                )
            }
            pending = null
            closed = true
            return Gs1PacketProcessingResult.Closed(opened.detail ?: opened.error.name)
        }
        opened as Gs1CoreOpenResult.Success
        wireProfile = bound.wireProfile
        val lease = FactoryGs1RuntimeCoreLease(
            coordinator = opened.coordinator,
            initialNextIndex = opened.nextSensorIndex,
            wireProfile = bound.wireProfile,
        )
        delegate = lease
        pending = null
        if (!value.evidence.processPacketAfterOpen) {
            return Gs1PacketProcessingResult.Completed(
                committedSamples = emptyList(),
                resolvedWireProfile = bound.wireProfile,
                validatedTransportEnvelope = value.evidence.validatedTransportEnvelope,
            )
        }
        return lease.ingest(value.packet).withResolvedWireProfile(bound.wireProfile)
    }

    private fun Gs1PacketProcessingResult.withResolvedWireProfile(
        profile: Gs1WireProfile,
    ): Gs1PacketProcessingResult = if (this is Gs1PacketProcessingResult.Completed) {
        copy(resolvedWireProfile = profile)
    } else {
        this
    }

    private data class PendingResolution(
        val packet: DurablyJournaledGs1Packet,
        val evidence: ResolutionClassification.Evidence,
    )

    private sealed interface ResolutionClassification {
        data class Evidence(
            val wireProfile: Gs1WireProfile,
            val evidenceKind: String,
            val processPacketAfterOpen: Boolean,
            val validatedTransportEnvelope: Boolean = false,
        ) : ResolutionClassification

        data class Failure(val result: Gs1PacketProcessingResult) : ResolutionClassification
    }
}

private class FactoryGs1RuntimeCoreLease(
    private val coordinator: Gs1ProcessingCoordinator,
    override val initialNextIndex: Int,
    override val wireProfile: Gs1WireProfile,
) : Gs1RuntimeCoreLease {
    private val processor = Gs1PacketProcessor(
        core = coordinator,
        decoder = when (wireProfile) {
            Gs1WireProfile.V115 -> Gs1V115VerifiedPacketDecoder()
            Gs1WireProfile.V120 -> Gs1VerifiedPacketDecoder()
            Gs1WireProfile.UNRESOLVED -> error("Resolved core lease requires a wire profile")
        },
        initialExpectedIndex = initialNextIndex,
        wireProfile = wireProfile,
    )

    override suspend fun ingest(packet: DurablyJournaledGs1Packet): Gs1PacketProcessingResult =
        processor.ingest(
            sourceIngressId = packet.ingressId,
            encryptedPacket = packet.encryptedPacketCopy(),
            receivedAtEpochMs = packet.receivedAtEpochMs,
            verifiedCommittedPrefixSampleCount = packet.verifiedCommittedPrefixSampleCount,
        )

    override suspend fun retryPending(): Gs1PacketProcessingResult =
        processor.retryPending()

    override fun close() {
        coordinator.close()
    }
}
