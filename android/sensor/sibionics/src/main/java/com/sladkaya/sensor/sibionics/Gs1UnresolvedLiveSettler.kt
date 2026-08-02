package com.sladkaya.sensor.sibionics

import com.sladkaya.core.data.SensorPacketIngressJournal
import com.sladkaya.core.data.SensorPacketIngressMarkHandledResult
import com.sladkaya.core.data.SensorPacketIngressOutcomeRecord
import com.sladkaya.core.data.SensorPacketIngressOutcomeStatus
import com.sladkaya.core.data.SensorPacketIngressRecord
import java.util.concurrent.CancellationException

internal sealed interface Gs1UnresolvedLiveSettlementResult {
    data class Completed(
        val nextProtocolAction: SessionAction,
        val presentation: Gs1GattCommittedPresentation? = null,
    ) : Gs1UnresolvedLiveSettlementResult

    data class Failed(
        val code: String,
        val detail: String? = null,
        val retryable: Boolean,
    ) : Gs1UnresolvedLiveSettlementResult
}

/** Settles the first live protocol-resolution result without re-emitting it on the live actor. */
internal class Gs1UnresolvedLiveSettler(
    private val journal: SensorPacketIngressJournal,
    private val committedEventValidator: Gs1CommittedIngressEventValidator,
) {
    suspend fun settle(
        generation: Long,
        session: SibionicsSession,
        ingress: SensorPacketIngressRecord,
        result: Gs1PacketProcessingResult,
        settleCommitted: suspend (Gs1DiagnosticRuntimeEvent.Committed) ->
            Gs1GattDurableCommitResult,
    ): Gs1UnresolvedLiveSettlementResult {
        val completed = result as? Gs1PacketProcessingResult.Completed
            ?: return terminalFailure(result)
        val resolved = completed.resolvedWireProfile ?: return failed(
            "PROTOCOL_RESOLUTION_MISSING",
            "Unresolved packet completed without a durable wire profile",
            retryable = false,
        )
        val memoryEvent = completed.toUnresolvedCommittedEventOrNull(generation, ingress)
        val roomEvent = if (memoryEvent == null) {
            null
        } else {
            when (val validated = committedEventValidator.validate(memoryEvent)) {
                is Gs1CommittedIngressEventValidation.Accepted -> validated.event
                is Gs1CommittedIngressEventValidation.Failed -> return failed(
                    validated.code,
                    validated.detail,
                    validated.retryable,
                )
            }
        }
        val protocolAction = session.confirmWireProfile(resolved)
        if (protocolAction is SessionAction.Failure) {
            return failed("PROTOCOL_PROFILE_CONFIRMATION_FAILED", protocolAction.reason, false)
        }
        val presentation = if (roomEvent == null) {
            null
        } else {
            when (val settled = settleCommitted(roomEvent)) {
                is Gs1GattDurableCommitResult.Accepted -> settled.presentation
                is Gs1GattDurableCommitResult.Rejected -> return failed(
                    settled.code,
                    settled.detail,
                    settled.retryable,
                )
            }
        }
        val outcome = SensorPacketIngressOutcomeRecord(
            ingressId = ingress.ingressId,
            status = if (completed.committedSamples.isEmpty()) {
                SensorPacketIngressOutcomeStatus.NON_DATA
            } else {
                SensorPacketIngressOutcomeStatus.CORE_COMMITTED
            },
            handledAtEpochMs = ingress.receivedAtEpochMs,
            detail = null,
        )
        try {
            when (val marked = journal.markHandled(outcome)) {
                SensorPacketIngressMarkHandledResult.MarkedHandled,
                SensorPacketIngressMarkHandledResult.AlreadyHandled,
                -> Unit

                is SensorPacketIngressMarkHandledResult.Conflict -> return failed(
                    "UNRESOLVED_INGRESS_OUTCOME_CONFLICT",
                    marked.reason,
                    retryable = false,
                )
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            return failed(
                "UNRESOLVED_INGRESS_OUTCOME_STORAGE_UNAVAILABLE",
                failure.message,
                retryable = true,
            )
        }
        return Gs1UnresolvedLiveSettlementResult.Completed(protocolAction, presentation)
    }

    private fun terminalFailure(
        result: Gs1PacketProcessingResult,
    ): Gs1UnresolvedLiveSettlementResult.Failed = when (result) {
        is Gs1PacketProcessingResult.InvalidPacket -> failed(
            result.error.name,
            result.detail,
            false,
        )
        is Gs1PacketProcessingResult.Rejected -> failed(result.code, result.message, false)
        is Gs1PacketProcessingResult.StorageConflict -> failed(
            "STORAGE_CONFLICT",
            result.reason,
            false,
        )
        is Gs1PacketProcessingResult.Closed -> failed("CORE_CLOSED", result.reason, false)
        is Gs1PacketProcessingResult.PersistenceUnavailable -> failed(
            "CORE_PERSISTENCE_PENDING",
            result.message,
            true,
        )
        Gs1PacketProcessingResult.NoPendingCommit -> failed("PENDING_COMMIT_LOST", null, false)
        is Gs1PacketProcessingResult.Completed -> error("handled by caller")
    }

    private fun failed(code: String, detail: String?, retryable: Boolean) =
        Gs1UnresolvedLiveSettlementResult.Failed(code, detail, retryable)
}

private fun Gs1PacketProcessingResult.Completed.toUnresolvedCommittedEventOrNull(
    generation: Long,
    ingress: SensorPacketIngressRecord,
): Gs1DiagnosticRuntimeEvent.Committed? {
    if (committedSamples.isEmpty() && diagnostics.isEmpty() && publications.isEmpty() &&
        committedIssues.isEmpty() && !validatedTransportEnvelope
    ) return null
    return Gs1DiagnosticRuntimeEvent.Committed(
        generation = generation,
        ingress = ingress,
        samples = committedSamples.toList(),
        diagnostics = diagnostics.toList(),
        publications = publications.toList(),
        issues = committedIssues.toList(),
        validatedTransportEnvelope = validatedTransportEnvelope,
    )
}
