package com.sladkaya.sensor.sibionics

import com.sladkaya.core.model.ReadingQuality
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.withTimeoutOrNull

internal data class Gs1GattCommittedPresentation(
    val hasTransportProgress: Boolean,
    val hasFreshOutput: Boolean,
    val latestSequence: Long?,
    val latestQuality: ReadingQuality?,
    val issue: Gs1PacketProcessingResult.CommittedIssue?,
)

internal interface Gs1GattCommittedOutput {
    fun resetAttempt()

    fun abortPendingApplications(code: Gs1ProductLocalEffectsFailureCode)

    suspend fun onDurablyConfirmed(
        event: Gs1DiagnosticRuntimeEvent.Committed,
    ): Gs1GattCommittedPresentation
}

internal sealed interface Gs1GattDurableCommitResult {
    data class Accepted(
        val presentation: Gs1GattCommittedPresentation,
    ) : Gs1GattDurableCommitResult

    data class Rejected(
        val code: String,
        val detail: String? = null,
        val retryable: Boolean = false,
    ) : Gs1GattDurableCommitResult
}

/**
 * One immutable product batch plus the explicit durability boundary owned by
 * the application. The application must persist its local-effects cursor or
 * outbox before acknowledging; updating only an in-memory UI is not enough.
 */
enum class Gs1ProductLocalEffectsFailureCode(
    val retryable: Boolean,
) {
    STORAGE_UNAVAILABLE(true),
    STORAGE_CONFLICT(false),
    APPLICATION_STOPPED(false),
    LOCAL_EFFECTS_TIMEOUT(true),
}

class Gs1ProductPublicationBatch internal constructor(
    publications: List<Gs1ProductPublication>,
    private val durableApplication: CompletableDeferred<Unit>,
) {
    val publications: List<Gs1ProductPublication> = publications.toList()

    fun acknowledgeDurablyApplied(): Boolean = durableApplication.complete(Unit)

    fun rejectDurableApplication(code: Gs1ProductLocalEffectsFailureCode): Boolean =
        durableApplication.completeExceptionally(
            Gs1ProductLocalEffectsRejectedException(code),
        )

    internal fun isAwaitingDurableApplication(): Boolean = durableApplication.isActive
}

private class Gs1ProductLocalEffectsRejectedException(
    val code: Gs1ProductLocalEffectsFailureCode,
    detail: String? = null,
) : IllegalStateException(listOfNotNull(code.name, detail).joinToString(": "))

/**
 * The only bridge from a runtime commit to facade-visible values. The output is
 * called after the protocol cursor accepted the exact durably committed sample
 * batch, never before it.
 */
internal class Gs1GattDurableCommitGate(
    private val output: Gs1GattCommittedOutput,
) {
    suspend fun dispatch(
        event: Gs1DiagnosticRuntimeEvent.Committed,
        confirmDurableCursor: (List<DecodedGs1RawSample>) -> SessionAction,
    ): Gs1GattDurableCommitResult = when (
        val confirmation = confirmDurableCursor(event.samples)
    ) {
        is SessionAction.Failure -> Gs1GattDurableCommitResult.Rejected(
            code = "DURABLE_CURSOR_REJECTED",
            detail = confirmation.reason,
            retryable = false,
        )
        else -> deliver(event)
    }

    /** Called only after [Gs1PendingIngressRecovery] validates an exact replay range. */
    suspend fun dispatchValidatedRecovery(
        event: Gs1DiagnosticRuntimeEvent.Committed,
    ): Gs1GattDurableCommitResult = deliver(event)

    private suspend fun deliver(
        event: Gs1DiagnosticRuntimeEvent.Committed,
    ): Gs1GattDurableCommitResult = try {
        Gs1GattDurableCommitResult.Accepted(output.onDurablyConfirmed(event))
    } catch (rejected: Gs1ProductLocalEffectsRejectedException) {
        Gs1GattDurableCommitResult.Rejected(
            code = rejected.code.name,
            retryable = rejected.code.retryable,
        )
    }
}

internal class Gs1DiagnosticGattOutput : Gs1GattCommittedOutput {
    private val mutableLatestDiagnostic = MutableStateFlow<Gs1DiagnosticReading?>(null)
    val latestDiagnostic: StateFlow<Gs1DiagnosticReading?> =
        mutableLatestDiagnostic.asStateFlow()

    override fun resetAttempt() {
        mutableLatestDiagnostic.value = null
    }

    override fun abortPendingApplications(code: Gs1ProductLocalEffectsFailureCode) = Unit

    override suspend fun onDurablyConfirmed(
        event: Gs1DiagnosticRuntimeEvent.Committed,
    ): Gs1GattCommittedPresentation {
        val assessment = Gs1DiagnosticCommitPolicy.assess(
            diagnostics = event.diagnostics,
            committedSampleCount = event.samples.size,
            issueCount = event.issues.size,
            validatedTransportEnvelope = event.validatedTransportEnvelope,
        )
        assessment.latest?.let { mutableLatestDiagnostic.value = it }
        return Gs1GattCommittedPresentation(
            hasTransportProgress = assessment.hasTransportProgress,
            hasFreshOutput = assessment.hasFreshDiagnostic,
            latestSequence = assessment.latest?.sequence,
            latestQuality = assessment.latest?.quality,
            issue = event.issues.lastOrNull(),
        )
    }
}

internal class Gs1ProductGattOutput(
    bufferCapacity: Int = DEFAULT_BUFFER_CAPACITY,
    private val applicationTimeoutMillis: Long = DEFAULT_APPLICATION_TIMEOUT_MS,
) : Gs1GattCommittedOutput {
    init {
        require(bufferCapacity in 1..MAX_BUFFER_CAPACITY)
        require(applicationTimeoutMillis in 1..MAX_APPLICATION_TIMEOUT_MS)
    }

    // The product collector is attached before engine.start(). A bounded,
    // suspending channel preserves every immutable batch and applies backpressure
    // if that collector stalls; it never grows without bound or silently drops.
    private val batches = Channel<Gs1ProductPublicationBatch>(
        capacity = bufferCapacity,
        onBufferOverflow = BufferOverflow.SUSPEND,
    )
    val committedPublicationBatches: Flow<Gs1ProductPublicationBatch> = batches
        .receiveAsFlow()
        .filter(Gs1ProductPublicationBatch::isAwaitingDurableApplication)

    private val attemptLock = Any()
    private var attempt = ProductApplicationAttempt(epoch = 1L)
    private val pendingApplications =
        mutableMapOf<CompletableDeferred<Unit>, Long>()

    override fun resetAttempt() {
        val previous: ProductApplicationAttempt
        val previousApplications: List<CompletableDeferred<Unit>>
        synchronized(attemptLock) {
            previous = attempt
            previousApplications = pendingApplications
                .filterValues { epoch -> epoch == previous.epoch }
                .keys
                .toList()
            attempt = ProductApplicationAttempt(epoch = previous.epoch + 1L)
        }
        rejectAttempt(previous, previousApplications, Gs1ProductLocalEffectsFailureCode.APPLICATION_STOPPED)
    }

    override fun abortPendingApplications(code: Gs1ProductLocalEffectsFailureCode) {
        val current: ProductApplicationAttempt
        val applications: List<CompletableDeferred<Unit>>
        val effectiveCode: Gs1ProductLocalEffectsFailureCode
        synchronized(attemptLock) {
            current = attempt
            effectiveCode = current.abortedCode ?: code.also { current.abortedCode = it }
            applications = pendingApplications
                .filterValues { epoch -> epoch == current.epoch }
                .keys
                .toList()
        }
        rejectAttempt(current, applications, effectiveCode)
    }

    override suspend fun onDurablyConfirmed(
        event: Gs1DiagnosticRuntimeEvent.Committed,
    ): Gs1GattCommittedPresentation {
        val publications = event.publications.toList()
        if (publications.isNotEmpty()) {
            val durableApplication = CompletableDeferred<Unit>()
            val registration = register(durableApplication)
            val batch = Gs1ProductPublicationBatch(
                publications = publications,
                durableApplication = durableApplication,
            )
            try {
                val completed = withTimeoutOrNull(applicationTimeoutMillis) {
                    select<Unit> {
                        batches.onSend(batch) { }
                        registration.abortSignal.onAwait { code ->
                            throw Gs1ProductLocalEffectsRejectedException(code)
                        }
                    }
                    // Finalized/markHandled stays behind this await. A process
                    // death, stop, timeout or rejection leaves ingress recoverable.
                    select<Unit> {
                        durableApplication.onAwait { }
                        registration.abortSignal.onAwait { code ->
                            throw Gs1ProductLocalEffectsRejectedException(code)
                        }
                    }
                    true
                }
                if (completed != true) {
                    val timeout = Gs1ProductLocalEffectsRejectedException(
                        Gs1ProductLocalEffectsFailureCode.LOCAL_EFFECTS_TIMEOUT,
                    )
                    durableApplication.completeExceptionally(timeout)
                    throw timeout
                }
            } finally {
                synchronized(attemptLock) {
                    pendingApplications.remove(durableApplication)
                }
            }
        }
        return Gs1GattCommittedPresentation(
            hasTransportProgress = event.validatedTransportEnvelope ||
                event.samples.isNotEmpty() || event.issues.isNotEmpty() || publications.isNotEmpty(),
            hasFreshOutput = publications.isNotEmpty(),
            latestSequence = publications.lastOrNull()?.reading?.sequence
                ?: event.samples.lastOrNull()?.index?.toLong(),
            latestQuality = publications.lastOrNull()?.reading?.quality,
            issue = event.issues.lastOrNull(),
        )
    }

    private fun register(
        durableApplication: CompletableDeferred<Unit>,
    ): ProductApplicationRegistration = synchronized(attemptLock) {
        val current = attempt
        current.abortedCode?.let { code ->
            val rejected = Gs1ProductLocalEffectsRejectedException(code)
            durableApplication.completeExceptionally(rejected)
            throw rejected
        }
        pendingApplications[durableApplication] = current.epoch
        // abortPendingApplications and registration share this lock, so a stop
        // either wins before registration or observes this deferred afterwards.
        current.abortedCode?.let { code ->
            pendingApplications.remove(durableApplication)
            val rejected = Gs1ProductLocalEffectsRejectedException(code)
            durableApplication.completeExceptionally(rejected)
            throw rejected
        }
        ProductApplicationRegistration(
            epoch = current.epoch,
            abortSignal = current.abortSignal,
        )
    }

    private fun rejectAttempt(
        target: ProductApplicationAttempt,
        applications: List<CompletableDeferred<Unit>>,
        code: Gs1ProductLocalEffectsFailureCode,
    ) {
        target.abortSignal.complete(code)
        applications.forEach { application ->
            application.completeExceptionally(Gs1ProductLocalEffectsRejectedException(code))
        }
    }

    private class ProductApplicationAttempt(
        val epoch: Long,
        val abortSignal: CompletableDeferred<Gs1ProductLocalEffectsFailureCode> =
            CompletableDeferred(),
        var abortedCode: Gs1ProductLocalEffectsFailureCode? = null,
    )

    private data class ProductApplicationRegistration(
        val epoch: Long,
        val abortSignal: CompletableDeferred<Gs1ProductLocalEffectsFailureCode>,
    )

    internal companion object {
        const val DEFAULT_BUFFER_CAPACITY = 16
        const val MAX_BUFFER_CAPACITY = 64
        const val DEFAULT_APPLICATION_TIMEOUT_MS = 15_000L
        const val MAX_APPLICATION_TIMEOUT_MS = 300_000L
    }
}
