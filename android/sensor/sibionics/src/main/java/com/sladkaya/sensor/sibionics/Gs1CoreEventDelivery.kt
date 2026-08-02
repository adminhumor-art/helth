package com.sladkaya.sensor.sibionics

import java.util.concurrent.CancellationException
import kotlinx.coroutines.channels.ClosedSendChannelException

/**
 * Signals that a durable commit did not reach the ordered GATT actor. The
 * runtime must stop before emitting Finalized so the ingress remains pending.
 */
internal class Gs1CommittedDeliveryUnavailableException(
    val code: String,
    val detail: String? = null,
    val retryable: Boolean,
) : IllegalStateException(detail ?: code)

internal suspend fun deliverGs1CoreEventOrFailCommitted(
    event: Gs1DiagnosticRuntimeEvent,
    activeGeneration: Long?,
    accepting: Boolean,
    deliver: suspend () -> Unit,
) {
    val eventGeneration = when (event) {
        is Gs1DiagnosticRuntimeEvent.Finalized -> event.generation
        is Gs1DiagnosticRuntimeEvent.Committed -> event.generation
        is Gs1DiagnosticRuntimeEvent.Failed -> event.generation
        is Gs1DiagnosticRuntimeEvent.RetryingPersistence -> event.generation
    }
    val unavailableReason = when {
        activeGeneration == null -> "No active GATT attempt for generation $eventGeneration"
        activeGeneration != eventGeneration ->
            "GATT generation $activeGeneration cannot accept generation $eventGeneration"
        !accepting -> "GATT attempt $eventGeneration is no longer accepting events"
        else -> null
    }
    if (unavailableReason != null) {
        if (event is Gs1DiagnosticRuntimeEvent.Committed) {
            throw Gs1CommittedDeliveryUnavailableException(
                code = "GATT_COMMITTED_DELIVERY_UNAVAILABLE",
                detail = unavailableReason,
                retryable = true,
            )
        }
        return
    }

    try {
        deliver()
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (closed: ClosedSendChannelException) {
        if (event is Gs1DiagnosticRuntimeEvent.Committed) {
            throw Gs1CommittedDeliveryUnavailableException(
                code = "GATT_COMMITTED_DELIVERY_UNAVAILABLE",
                detail = "GATT mailbox closed before commit delivery for generation $eventGeneration",
                retryable = true,
            )
        }
        // A secondary Failed/Finalized notification is best-effort once the
        // attempt is closed; it must not replace the original runtime state.
    }
}
