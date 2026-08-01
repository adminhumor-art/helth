package com.sladkaya.sensor.sibionics

import com.sladkaya.core.data.SensorPacketIngressJournal
import com.sladkaya.core.data.SensorPacketIngressMarkHandledResult
import com.sladkaya.core.data.SensorPacketIngressOutcomeRecord
import com.sladkaya.core.data.SensorPacketIngressOutcomeStatus
import java.util.concurrent.CancellationException

internal sealed interface Gs1PendingIngressRecoveryResult {
    data class Completed(
        val finalCoreCursor: Int,
        val handledRecords: Int,
        val blocked: Gs1PendingIngressRecoveryEntry? = null,
    ) : Gs1PendingIngressRecoveryResult

    data class Failed(
        val code: String,
        val detail: String? = null,
        val retryable: Boolean,
    ) : Gs1PendingIngressRecoveryResult
}

/**
 * Reconciles append-only BLE evidence before a new live GATT connection starts.
 * Every replay uses the original encrypted bytes and waits for the stateful core.
 */
internal class Gs1PendingIngressRecovery(
    private val journal: SensorPacketIngressJournal,
    private val codec: SibionicsPacketCodec,
    private val replay: suspend (Long, DurablyJournaledGs1Packet) -> Gs1RuntimeAwaitResult,
) {
    suspend fun recover(
        profile: Gs1DiagnosticActivationProfile,
        generation: Long,
        initialCoreCursor: Int,
    ): Gs1PendingIngressRecoveryResult {
        require(generation > 0L)
        val records = try {
            journal.pending(profile.sensorId, profile.bluetoothAddress)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            return failed(
                code = "RECOVERY_INGRESS_STORAGE_UNAVAILABLE",
                detail = failure.message,
                retryable = true,
            )
        }

        val planner = Gs1PendingIngressRecoveryPlanner(profile.family, codec)
        var cursor = initialCoreCursor
        var handled = 0
        records.forEach { record ->
            if (record.sensorId != profile.sensorId ||
                record.bluetoothAddress != profile.bluetoothAddress
            ) {
                return failed(
                    code = "RECOVERY_INGRESS_IDENTITY_MISMATCH",
                    detail = record.ingressId,
                    retryable = false,
                )
            }
        }

        val remaining = records.toMutableList()
        while (remaining.isNotEmpty()) {
            var replayEntry: Gs1PendingIngressRecoveryEntry? = null
            var blockedEntry: Gs1PendingIngressRecoveryEntry? = null
            recoveryScan@ for (record in remaining.toList()) {
                val entry = planner.plan(cursor, listOf(record)).single()
                when (entry.disposition) {
                    Gs1PendingIngressRecoveryDisposition.NON_DATA -> {
                        mark(entry, SensorPacketIngressOutcomeStatus.NON_DATA, detail = null)
                            ?.let { return it }
                        remaining.remove(record)
                        handled += 1
                    }

                    Gs1PendingIngressRecoveryDisposition.QUARANTINE_INVALID -> {
                        mark(
                            entry,
                            SensorPacketIngressOutcomeStatus.QUARANTINED,
                            detail = entry.detail?.take(MAX_OUTCOME_DETAIL_CHARS),
                        )?.let { return it }
                        remaining.remove(record)
                        handled += 1
                    }

                    Gs1PendingIngressRecoveryDisposition.ALREADY_COVERED -> {
                        mark(entry, SensorPacketIngressOutcomeStatus.ALREADY_COVERED, detail = null)
                            ?.let { return it }
                        remaining.remove(record)
                        handled += 1
                    }

                    Gs1PendingIngressRecoveryDisposition.REPLAY_EXACT -> {
                        if (replayEntry == null) replayEntry = entry
                    }

                    Gs1PendingIngressRecoveryDisposition.BLOCKED_BY_GAP,
                    Gs1PendingIngressRecoveryDisposition.PARTIAL_OVERLAP,
                    -> if (blockedEntry == null) blockedEntry = entry

                    Gs1PendingIngressRecoveryDisposition.UNSUPPORTED_PROTOCOL -> {
                        // An unknown command may have changed device protocol state.
                        // Preserve live/recovery parity: evidence after it cannot be
                        // replayed until this command is understood or quarantined by
                        // an explicit compatibility decision.
                        blockedEntry = entry
                        break@recoveryScan
                    }
                }
            }

            val entry = replayEntry ?: return Gs1PendingIngressRecoveryResult.Completed(
                finalCoreCursor = cursor,
                handledRecords = handled,
                blocked = blockedEntry,
            )
            val record = entry.record
            val replayResult = try {
                replay(
                    generation,
                    DurablyJournaledGs1Packet(
                        ingressId = record.ingressId,
                        receivedAtEpochMs = record.receivedAtEpochMs,
                        encryptedPacket = entry.encryptedPacketCopy(),
                    ),
                )
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                return failed(
                    code = "RECOVERY_CORE_UNAVAILABLE",
                    detail = failure.message,
                    retryable = true,
                )
            }
            when (val validation = validateReplay(entry, replayResult)) {
                ReplayValidation.Success -> {
                    mark(entry, SensorPacketIngressOutcomeStatus.CORE_COMMITTED, detail = null)
                        ?.let { return it }
                    cursor = entry.projectedCursorAfter
                    remaining.remove(record)
                    handled += 1
                }

                is ReplayValidation.Quarantine -> {
                    mark(
                        entry,
                        SensorPacketIngressOutcomeStatus.QUARANTINED,
                        validation.detail.take(MAX_OUTCOME_DETAIL_CHARS),
                    )?.let { return it }
                    remaining.remove(record)
                    handled += 1
                }

                is ReplayValidation.Failure -> return validation.result
            }
        }
        return Gs1PendingIngressRecoveryResult.Completed(
            finalCoreCursor = cursor,
            handledRecords = handled,
        )
    }

    private fun validateReplay(
        entry: Gs1PendingIngressRecoveryEntry,
        result: Gs1RuntimeAwaitResult,
    ): ReplayValidation = when (result) {
        Gs1RuntimeAwaitResult.StaleGeneration -> ReplayValidation.Failure(
            failed("RECOVERY_STALE_CORE_GENERATION", entry.record.ingressId, retryable = true),
        )

        Gs1RuntimeAwaitResult.Closed -> ReplayValidation.Failure(
            failed("RECOVERY_CORE_CLOSED", entry.record.ingressId, retryable = true),
        )

        is Gs1RuntimeAwaitResult.Processed -> when (val core = result.result) {
            is Gs1PacketProcessingResult.Completed -> {
                val expectedFirst = checkNotNull(entry.firstIndex)
                val expectedLast = checkNotNull(entry.lastIndex)
                val indices = core.committedSamples.map { it.index }
                val exactRange = indices.size == expectedLast - expectedFirst + 1 &&
                    indices.firstOrNull() == expectedFirst &&
                    indices.lastOrNull() == expectedLast &&
                    indices.zipWithNext().all { (left, right) -> right == left + 1 }
                if (!exactRange) {
                    ReplayValidation.Failure(
                        failed(
                            "RECOVERY_COMMIT_RANGE_MISMATCH",
                            "${entry.record.ingressId}: expected $expectedFirst..$expectedLast, got $indices",
                            retryable = false,
                        ),
                    )
                } else {
                    ReplayValidation.Success
                }
            }

            is Gs1PacketProcessingResult.InvalidPacket -> ReplayValidation.Quarantine(
                core.detail ?: core.error.name,
            )

            is Gs1PacketProcessingResult.Rejected -> ReplayValidation.Failure(
                failed(
                    "RECOVERY_CORE_REJECTED",
                    "${core.code}: ${core.message}",
                    retryable = false,
                ),
            )

            is Gs1PacketProcessingResult.StorageConflict -> ReplayValidation.Failure(
                failed("RECOVERY_STORAGE_CONFLICT", core.reason, retryable = false),
            )

            is Gs1PacketProcessingResult.Closed -> ReplayValidation.Failure(
                failed("RECOVERY_CORE_CLOSED", core.reason, retryable = true),
            )

            is Gs1PacketProcessingResult.PersistenceUnavailable -> ReplayValidation.Failure(
                failed("RECOVERY_PERSISTENCE_PENDING", core.message, retryable = true),
            )

            Gs1PacketProcessingResult.NoPendingCommit -> ReplayValidation.Failure(
                failed("RECOVERY_PENDING_COMMIT_LOST", null, retryable = false),
            )
        }
    }

    private suspend fun mark(
        entry: Gs1PendingIngressRecoveryEntry,
        status: SensorPacketIngressOutcomeStatus,
        detail: String?,
    ): Gs1PendingIngressRecoveryResult.Failed? {
        // Using the immutable ingress timestamp makes a retry byte-identical even
        // if the process dies after SQLite commits but before returning success.
        val outcome = SensorPacketIngressOutcomeRecord(
            ingressId = entry.record.ingressId,
            status = status,
            handledAtEpochMs = entry.record.receivedAtEpochMs,
            detail = detail,
        )
        return try {
            when (val marked = journal.markHandled(outcome)) {
                SensorPacketIngressMarkHandledResult.MarkedHandled,
                SensorPacketIngressMarkHandledResult.AlreadyHandled,
                -> null

                is SensorPacketIngressMarkHandledResult.Conflict -> failed(
                    code = "RECOVERY_OUTCOME_CONFLICT",
                    detail = marked.reason,
                    retryable = false,
                )
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            failed(
                code = "RECOVERY_OUTCOME_STORAGE_UNAVAILABLE",
                detail = failure.message,
                retryable = true,
            )
        }
    }

    private fun failed(
        code: String,
        detail: String?,
        retryable: Boolean,
    ) = Gs1PendingIngressRecoveryResult.Failed(code, detail, retryable)

    private sealed interface ReplayValidation {
        data object Success : ReplayValidation
        data class Quarantine(val detail: String) : ReplayValidation
        data class Failure(val result: Gs1PendingIngressRecoveryResult.Failed) : ReplayValidation
    }

    private companion object {
        const val MAX_OUTCOME_DETAIL_CHARS = 512
    }
}
