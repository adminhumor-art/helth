package com.sladkaya.sensor.sibionics

import com.sladkaya.core.data.CommittedSensorIngressReadResult
import com.sladkaya.core.data.CommittedSensorIngressReader
import com.sladkaya.core.data.CommittedSensorIngressSampleRecord
import com.sladkaya.core.data.SensorPacketIngressJournal
import com.sladkaya.core.data.SensorPacketIngressMarkHandledResult
import com.sladkaya.core.data.SensorPacketIngressOutcomeRecord
import com.sladkaya.core.data.SensorPacketIngressOutcomeStatus
import java.util.concurrent.CancellationException

internal sealed interface Gs1PendingIngressRecoveryResult {
    data class Completed(
        val finalCoreCursor: Int,
        val finalWireProfile: Gs1WireProfile,
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
    private val committedIngressReader: CommittedSensorIngressReader,
    private val codec: SibionicsPacketCodec,
    private val replay: suspend (Long, DurablyJournaledGs1Packet) -> Gs1RuntimeAwaitResult,
    private val onValidatedCommit: suspend (Gs1DiagnosticRuntimeEvent.Committed) -> Unit = {},
) {
    suspend fun recover(
        profile: Gs1DiagnosticActivationProfile,
        generation: Long,
        initialCoreCursor: Int,
        initialWireProfile: Gs1WireProfile,
    ): Gs1PendingIngressRecoveryResult {
        require(generation > 0L)
        if (initialCoreCursor == CURSOR_AFTER_LAST_SENSOR_INDEX) {
            return sequenceExhausted()
        }
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

        var cursor = initialCoreCursor
        var wireProfile = initialWireProfile
        var handled = 0
        records.forEach { record ->
            if (record.sensorId != profile.sensorId ||
                record.sensorFamily != profile.family ||
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
                val planner = Gs1PendingIngressRecoveryPlanner(
                    profile.family,
                    codec,
                    wireProfile,
                )
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
                        when (
                            val covered = readCoveredCommit(
                                profile = profile,
                                generation = generation,
                                entry = entry,
                                maximumCommittedIndex = checkNotNull(entry.lastIndex),
                            )
                        ) {
                            is CoveredCommitRead.Failure -> return covered.result
                            is CoveredCommitRead.Success -> covered.event?.let { recoveredCommit ->
                                deliverRecoveredCommit(recoveredCommit)?.let { return it }
                            }
                        }
                        mark(entry, SensorPacketIngressOutcomeStatus.ALREADY_COVERED, detail = null)
                            ?.let { return it }
                        remaining.remove(record)
                        handled += 1
                    }

                    Gs1PendingIngressRecoveryDisposition.REPLAY_EXACT -> {
                        if (replayEntry == null) replayEntry = entry
                    }

                    Gs1PendingIngressRecoveryDisposition.RESOLVE_EXACT -> {
                        if (replayEntry == null) replayEntry = entry
                    }

                    Gs1PendingIngressRecoveryDisposition.BLOCKED_BY_GAP -> {
                        if (blockedEntry == null) blockedEntry = entry
                    }

                    Gs1PendingIngressRecoveryDisposition.PARTIAL_OVERLAP -> {
                        if (replayEntry == null) {
                            when (
                                val covered = readCoveredCommit(
                                    profile = profile,
                                    generation = generation,
                                    entry = entry,
                                    maximumCommittedIndex = cursor - 1,
                                )
                            ) {
                                is CoveredCommitRead.Failure -> return covered.result
                                is CoveredCommitRead.Success -> covered.event?.let { recoveredCommit ->
                                    deliverRecoveredCommit(recoveredCommit)?.let { return it }
                                }
                            }
                            // The exact Room-linked prefix has been proved. Replay the
                            // still-uncommitted suffix from the original immutable ingress
                            // before a later notification can take ownership of its index.
                            replayEntry = entry
                            break@recoveryScan
                        }
                    }

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

            val entry = replayEntry ?: run {
                return Gs1PendingIngressRecoveryResult.Completed(
                    finalCoreCursor = cursor,
                    finalWireProfile = wireProfile,
                    handledRecords = handled,
                    blocked = blockedEntry,
                )
            }
            val record = entry.record
            val replayResult = try {
                replay(
                    generation,
                    DurablyJournaledGs1Packet(
                        ingress = record,
                        verifiedCommittedPrefixSampleCount = entry.verifiedCommittedPrefixSampleCount(),
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
            when (val validation = validateReplay(entry, replayResult, generation)) {
                is ReplayValidation.Success -> {
                    validation.committedEvent?.let { recoveredCommit ->
                        deliverRecoveredCommit(recoveredCommit)?.let { return it }
                    }
                    validation.terminalFailure?.let { return it }
                    mark(
                        entry,
                        if (entry.disposition == Gs1PendingIngressRecoveryDisposition.RESOLVE_EXACT) {
                            SensorPacketIngressOutcomeStatus.NON_DATA
                        } else {
                            SensorPacketIngressOutcomeStatus.CORE_COMMITTED
                        },
                        detail = null,
                    )
                        ?.let { return it }
                    cursor = if (
                        entry.disposition == Gs1PendingIngressRecoveryDisposition.PARTIAL_OVERLAP
                    ) {
                        checkNotNull(entry.lastIndex) + 1
                    } else {
                        entry.projectedCursorAfter
                    }
                    validation.resolvedWireProfile?.let { wireProfile = it }
                    remaining.remove(record)
                    handled += 1
                    if (cursor == CURSOR_AFTER_LAST_SENSOR_INDEX) {
                        return sequenceExhausted()
                    }
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
            finalWireProfile = wireProfile,
            handledRecords = handled,
        )
    }

    /**
     * Reconstructs only rows atomically linked to this exact ingress. Packet
     * decoding supplies the expected raw prefix; it never supplies a product
     * glucose value or publication lineage.
     */
    private suspend fun readCoveredCommit(
        profile: Gs1DiagnosticActivationProfile,
        generation: Long,
        entry: Gs1PendingIngressRecoveryEntry,
        maximumCommittedIndex: Int,
    ): CoveredCommitRead {
        val read = try {
            committedIngressReader.read(entry.record)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            return CoveredCommitRead.Failure(
                failed(
                    code = "RECOVERY_COMMITTED_EVIDENCE_UNAVAILABLE",
                    detail = failure.message,
                    retryable = true,
                ),
            )
        }
        val evidence = when (read) {
            is CommittedSensorIngressReadResult.Mismatch -> return CoveredCommitRead.Failure(
                failed(
                    code = "RECOVERY_COMMITTED_EVIDENCE_MISMATCH",
                    detail = read.reason,
                    retryable = false,
                ),
            )
            is CommittedSensorIngressReadResult.Exact -> CoveredEvidence(
                sourceIngress = entry.record,
                samples = read.samples,
                localEffectsAlreadyAcknowledged = false,
            )
            is CommittedSensorIngressReadResult.HandledDuplicate -> {
                if (!read.sourceIngress.isExactDuplicateSourceFor(entry.record)) {
                    return CoveredCommitRead.Failure(
                        failed(
                            code = "RECOVERY_COMMITTED_EVIDENCE_MISMATCH",
                            detail = "Handled duplicate source does not match current ingress",
                            retryable = false,
                        ),
                    )
                }
                CoveredEvidence(
                    sourceIngress = read.sourceIngress,
                    samples = read.samples,
                    localEffectsAlreadyAcknowledged = true,
                )
            }
        }
        val exact = evidence.samples
        val expectedPrefix = entry.expectedSamples
            .takeWhile { sample -> sample.index <= maximumCommittedIndex }
        val exactPrefix = exact.size == expectedPrefix.size && exact.zip(expectedPrefix).all {
            (committed, expected) -> committed.matchesExpected(
                sourceIngress = evidence.sourceIngress,
                profile = profile,
                expected = expected,
            )
        }
        if (!exactPrefix) {
            return CoveredCommitRead.Failure(
                failed(
                    code = "RECOVERY_COMMITTED_PREFIX_MISMATCH",
                    detail = "${entry.record.ingressId}: durable rows are not an exact ingress prefix",
                    retryable = false,
                ),
            )
        }
        if (exact.isEmpty()) return CoveredCommitRead.Success(event = null)
        if (evidence.localEffectsAlreadyAcknowledged) {
            return CoveredCommitRead.Success(event = null)
        }

        return CoveredCommitRead.Success(
            event = Gs1DiagnosticRuntimeEvent.Committed(
                generation = generation,
                ingress = evidence.sourceIngress,
                samples = exact.map { committed -> committed.raw.toDecodedSample() },
                diagnostics = emptyList(),
                publications = exact.mapNotNull { committed ->
                    committed.productPublication?.let { publication ->
                        Gs1ProductPublication(
                            reading = publication.reading,
                            approvalId = publication.approvalId,
                            publicationBindingId = publication.publicationBindingId,
                        )
                    }
                },
                issues = emptyList(),
                validatedTransportEnvelope = true,
            ),
        )
    }

    private suspend fun deliverRecoveredCommit(
        event: Gs1DiagnosticRuntimeEvent.Committed,
    ): Gs1PendingIngressRecoveryResult.Failed? = try {
        // Delivery (including the product local-effects acknowledgement) must
        // finish before markHandled. Otherwise the exact ingress stays pending.
        onValidatedCommit(event)
        null
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (failure: Gs1CommittedDeliveryUnavailableException) {
        failed(
            code = failure.code,
            detail = failure.detail,
            retryable = failure.retryable,
        )
    } catch (failure: Exception) {
        failed(
            code = "RECOVERY_COMMITTED_OUTPUT_UNAVAILABLE",
            detail = failure.message,
            retryable = true,
        )
    }

    private fun validateReplay(
        entry: Gs1PendingIngressRecoveryEntry,
        result: Gs1RuntimeAwaitResult,
        generation: Long,
    ): ReplayValidation = when (result) {
        Gs1RuntimeAwaitResult.StaleGeneration -> ReplayValidation.Failure(
            failed("RECOVERY_STALE_CORE_GENERATION", entry.record.ingressId, retryable = true),
        )

        Gs1RuntimeAwaitResult.Closed -> ReplayValidation.Failure(
            failed("RECOVERY_CORE_CLOSED", entry.record.ingressId, retryable = true),
        )

        is Gs1RuntimeAwaitResult.Processed -> when (val core = result.result) {
            is Gs1PacketProcessingResult.Completed -> {
                if (entry.disposition == Gs1PendingIngressRecoveryDisposition.RESOLVE_EXACT) {
                    val resolved = core.resolvedWireProfile
                    if (resolved == null || core.committedSamples.isNotEmpty()) {
                        ReplayValidation.Failure(
                            failed(
                                "RECOVERY_PROTOCOL_RESOLUTION_MISMATCH",
                                entry.record.ingressId,
                                retryable = false,
                            ),
                        )
                    } else {
                        ReplayValidation.Success(
                            resolvedWireProfile = resolved,
                            committedEvent = core.toCommittedEvent(generation, entry.record),
                        )
                    }
                } else {
                    validateCommittedPrefix(
                        entry = entry,
                        generation = generation,
                        samples = core.committedSamples,
                        diagnostics = core.diagnostics,
                        publications = core.publications,
                        issues = core.committedIssues,
                        requireWholeRange = true,
                        resolvedWireProfile = core.resolvedWireProfile,
                    )
                }
            }

            is Gs1PacketProcessingResult.InvalidPacket -> ReplayValidation.Quarantine(
                core.detail ?: core.error.name,
            )

            is Gs1PacketProcessingResult.Rejected -> validateTerminalPrefix(
                entry = entry,
                generation = generation,
                samples = core.committedSamples,
                diagnostics = core.diagnostics,
                publications = core.publications,
                issues = core.committedIssues,
                terminalFailure = failed(
                    "RECOVERY_CORE_REJECTED",
                    "${core.code}: ${core.message}",
                    retryable = false,
                ),
            )

            is Gs1PacketProcessingResult.StorageConflict -> validateTerminalPrefix(
                entry = entry,
                generation = generation,
                samples = core.committedSamples,
                diagnostics = core.diagnostics,
                publications = core.publications,
                issues = core.committedIssues,
                terminalFailure = failed("RECOVERY_STORAGE_CONFLICT", core.reason, retryable = false),
            )

            is Gs1PacketProcessingResult.Closed -> validateTerminalPrefix(
                entry = entry,
                generation = generation,
                samples = core.committedSamples,
                diagnostics = core.diagnostics,
                publications = core.publications,
                issues = core.committedIssues,
                terminalFailure = failed("RECOVERY_CORE_CLOSED", core.reason, retryable = true),
            )

            is Gs1PacketProcessingResult.PersistenceUnavailable -> ReplayValidation.Failure(
                failed("RECOVERY_PERSISTENCE_PENDING", core.message, retryable = true),
            )

            Gs1PacketProcessingResult.NoPendingCommit -> ReplayValidation.Failure(
                failed("RECOVERY_PENDING_COMMIT_LOST", null, retryable = false),
            )
        }
    }

    private fun validateTerminalPrefix(
        entry: Gs1PendingIngressRecoveryEntry,
        generation: Long,
        samples: List<DecodedGs1RawSample>,
        diagnostics: List<Gs1DiagnosticReading>,
        publications: List<Gs1ProductPublication>,
        issues: List<Gs1PacketProcessingResult.CommittedIssue>,
        terminalFailure: Gs1PendingIngressRecoveryResult.Failed,
    ): ReplayValidation {
        if (samples.isEmpty()) return ReplayValidation.Failure(terminalFailure)
        return validateCommittedPrefix(
            entry = entry,
            generation = generation,
            samples = samples,
            diagnostics = diagnostics,
            publications = publications,
            issues = issues,
            requireWholeRange = false,
            terminalFailure = terminalFailure,
        )
    }

    private fun validateCommittedPrefix(
        entry: Gs1PendingIngressRecoveryEntry,
        generation: Long,
        samples: List<DecodedGs1RawSample>,
        diagnostics: List<Gs1DiagnosticReading>,
        publications: List<Gs1ProductPublication>,
        issues: List<Gs1PacketProcessingResult.CommittedIssue>,
        requireWholeRange: Boolean,
        resolvedWireProfile: Gs1WireProfile? = null,
        terminalFailure: Gs1PendingIngressRecoveryResult.Failed? = null,
    ): ReplayValidation {
        val expectedReplay = entry.expectedSamples.drop(entry.verifiedCommittedPrefixSampleCount())
        val expectedFirst = expectedReplay.firstOrNull()?.index ?: checkNotNull(entry.firstIndex)
        val expectedLast = expectedReplay.lastOrNull()?.index ?: checkNotNull(entry.lastIndex)
        val indices = samples.map(DecodedGs1RawSample::index)
        val contiguousPrefix = indices.isNotEmpty() &&
            indices.first() == expectedFirst &&
            indices.last() <= expectedLast &&
            indices.zipWithNext().all { (left, right) -> right == left + 1 }
        val wholeRange = indices.size == expectedLast - expectedFirst + 1 &&
            indices.lastOrNull() == expectedLast
        if (!contiguousPrefix || requireWholeRange && !wholeRange) {
            return ReplayValidation.Failure(
                failed(
                    code = if (requireWholeRange) {
                        "RECOVERY_COMMIT_RANGE_MISMATCH"
                    } else {
                        "RECOVERY_COMMIT_PREFIX_MISMATCH"
                    },
                    detail = "${entry.record.ingressId}: expected prefix of " +
                        "$expectedFirst..$expectedLast, got $indices",
                    retryable = false,
                ),
            )
        }
        if (samples != expectedReplay.take(samples.size)) {
            return ReplayValidation.Failure(
                failed(
                    code = "RECOVERY_COMMIT_SAMPLE_MISMATCH",
                    detail = "${entry.record.ingressId}: committed raw values do not match ingress",
                    retryable = false,
                ),
            )
        }
        val committedIndices = indices.map(Int::toLong).toSet()
        val diagnosticIndices = diagnostics.map(Gs1DiagnosticReading::sequence)
        val publicationIndices = publications.map { it.reading.sequence }
        val issueIndices = issues.map { it.sequence.toLong() }
        val outputIndices = diagnosticIndices + publicationIndices + issueIndices
        val duplicateWithinOneOutputKind = diagnosticIndices.size != diagnosticIndices.distinct().size ||
            publicationIndices.size != publicationIndices.distinct().size ||
            issueIndices.size != issueIndices.distinct().size
        if (outputIndices.any { it !in committedIndices } || duplicateWithinOneOutputKind
        ) {
            return ReplayValidation.Failure(
                failed(
                    code = "RECOVERY_COMMIT_OUTPUT_MISMATCH",
                    detail = "${entry.record.ingressId}: output is outside the durable prefix",
                    retryable = false,
                ),
            )
        }
        return ReplayValidation.Success(
            resolvedWireProfile = resolvedWireProfile,
            committedEvent = Gs1DiagnosticRuntimeEvent.Committed(
                generation = generation,
                ingress = entry.record,
                samples = samples.toList(),
                diagnostics = diagnostics.toList(),
                publications = publications.toList(),
                issues = issues.toList(),
            ),
            terminalFailure = terminalFailure,
        )
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

    private fun sequenceExhausted() = failed(
        code = "SENSOR_SEQUENCE_EXHAUSTED",
        detail = "Sensor sample sequence reached its terminal index",
        retryable = false,
    )

    private sealed interface ReplayValidation {
        data class Success(
            val resolvedWireProfile: Gs1WireProfile? = null,
            val committedEvent: Gs1DiagnosticRuntimeEvent.Committed? = null,
            val terminalFailure: Gs1PendingIngressRecoveryResult.Failed? = null,
        ) : ReplayValidation
        data class Quarantine(val detail: String) : ReplayValidation
        data class Failure(val result: Gs1PendingIngressRecoveryResult.Failed) : ReplayValidation
    }

    private sealed interface CoveredCommitRead {
        data class Success(
            val event: Gs1DiagnosticRuntimeEvent.Committed?,
        ) : CoveredCommitRead

        data class Failure(
            val result: Gs1PendingIngressRecoveryResult.Failed,
        ) : CoveredCommitRead
    }

    private data class CoveredEvidence(
        val sourceIngress: com.sladkaya.core.data.SensorPacketIngressRecord,
        val samples: List<CommittedSensorIngressSampleRecord>,
        val localEffectsAlreadyAcknowledged: Boolean,
    )

    private companion object {
        const val CURSOR_AFTER_LAST_SENSOR_INDEX = 0x1_0000
        const val MAX_OUTCOME_DETAIL_CHARS = 512
    }
}

private fun Gs1PacketProcessingResult.Completed.toCommittedEvent(
    generation: Long,
    ingress: com.sladkaya.core.data.SensorPacketIngressRecord,
) = Gs1DiagnosticRuntimeEvent.Committed(
    generation = generation,
    ingress = ingress,
    samples = committedSamples.toList(),
    diagnostics = diagnostics.toList(),
    publications = publications.toList(),
    issues = committedIssues.toList(),
    validatedTransportEnvelope = validatedTransportEnvelope,
)

private fun CommittedSensorIngressSampleRecord.matchesExpected(
    sourceIngress: com.sladkaya.core.data.SensorPacketIngressRecord,
    profile: Gs1DiagnosticActivationProfile,
    expected: DecodedGs1RawSample,
): Boolean = raw.sourceIngressId == sourceIngress.ingressId &&
    raw.sensorId == profile.sensorId &&
    raw.sensorFamily == profile.family &&
    raw.sequence == expected.index &&
    raw.sensorTimeEpochMs == expected.sensorTimeEpochSeconds * 1_000L &&
    raw.phoneTimeEpochMs == sourceIngress.receivedAtEpochMs &&
    raw.packetSha256 == sourceIngress.packetSha256 &&
    raw.packetCopy().contentEquals(sourceIngress.encryptedPacketCopy()) &&
    raw.currentRaw == expected.current &&
    raw.temperatureRaw == expected.temperature &&
    raw.historyDistance == expected.reindex &&
    raw.transportVariant == profile.transportVariant &&
    raw.sensorTimeWasClamped == expected.sensorTimeWasClamped &&
    raw.addTimeSeconds == expected.addTimeSeconds

private fun com.sladkaya.core.data.SensorPacketIngressRecord.isExactDuplicateSourceFor(
    current: com.sladkaya.core.data.SensorPacketIngressRecord,
): Boolean = ingressId != current.ingressId &&
    sensorId == current.sensorId &&
    sensorFamily == current.sensorFamily &&
    bluetoothAddress == current.bluetoothAddress &&
    packetSha256 == current.packetSha256 &&
    encryptedPacketCopy().contentEquals(current.encryptedPacketCopy())

private fun com.sladkaya.core.data.RawSensorSampleRecord.toDecodedSample() = DecodedGs1RawSample(
    index = sequence,
    sensorTimeEpochSeconds = sensorTimeEpochMs / 1_000L,
    current = currentRaw,
    temperature = temperatureRaw,
    reindex = historyDistance,
    sensorTimeWasClamped = sensorTimeWasClamped,
    addTimeSeconds = addTimeSeconds,
)

private fun Gs1PendingIngressRecoveryEntry.verifiedCommittedPrefixSampleCount(): Int =
    if (disposition == Gs1PendingIngressRecoveryDisposition.PARTIAL_OVERLAP) {
        val first = checkNotNull(firstIndex)
        val count = projectedCursorBefore - first
        check(count in 1 until expectedSamples.size) {
            "Partial overlap must contain a non-empty proved prefix and replayable suffix"
        }
        count
    } else {
        0
    }
