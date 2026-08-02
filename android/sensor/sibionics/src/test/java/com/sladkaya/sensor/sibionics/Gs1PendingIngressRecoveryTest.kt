package com.sladkaya.sensor.sibionics

import com.sladkaya.core.data.CommittedProductPublicationRecord
import com.sladkaya.core.data.CommittedSensorIngressReadResult
import com.sladkaya.core.data.CommittedSensorIngressReader
import com.sladkaya.core.data.CommittedSensorIngressSampleRecord
import com.sladkaya.core.data.CommittedSensorCoverageReadResult
import com.sladkaya.core.data.CommittedSensorCoverageRequest
import com.sladkaya.core.data.RawSensorSampleRecord
import com.sladkaya.core.data.SensorPacketIngressAppendResult
import com.sladkaya.core.data.SensorPacketIngressJournal
import com.sladkaya.core.data.SensorPacketIngressMarkHandledResult
import com.sladkaya.core.data.SensorPacketIngressOutcomeRecord
import com.sladkaya.core.data.SensorPacketIngressOutcomeStatus
import com.sladkaya.core.data.SensorPacketIngressRecord
import com.sladkaya.core.model.GlucoseReading
import com.sladkaya.core.model.ReadingQuality
import com.sladkaya.core.model.SensorFamily
import java.security.MessageDigest
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class Gs1PendingIngressRecoveryTest {
    private val codec = SibionicsPacketCodec()

    @Test
    fun unresolvedChallengeReturnsDurableV120ProfileForTheLiveSession() = runBlocking {
        val challenge = byteArrayOf(0x23, 0xf7.toByte(), 0x6f, 0xd9.toByte(), 0xf4.toByte())
        val journal = FakeRecoveryJournal(listOf(record(0, challenge)))
        val result = recovery(journal) {
            Gs1RuntimeAwaitResult.Processed(
                Gs1PacketProcessingResult.Completed(
                    committedSamples = emptyList(),
                    resolvedWireProfile = Gs1WireProfile.V120,
                ),
            )
        }.recover(
            profile = profile(transportVariant = 2),
            generation = 1L,
            initialCoreCursor = 1,
            initialWireProfile = Gs1WireProfile.UNRESOLVED,
        ) as Gs1PendingIngressRecoveryResult.Completed

        assertEquals(Gs1WireProfile.V120, result.finalWireProfile)
        assertEquals(SensorPacketIngressOutcomeStatus.NON_DATA, journal.outcomes.single().status)
    }

    @Test
    fun unresolvedV115DataReturnsDurableV115ProfileAndAdvancedCursor() = runBlocking {
        val packet = v115Response(index = 1)
        val journal = FakeRecoveryJournal(listOf(record(0, packet)))
        val result = recovery(journal) {
            val decoded = Gs1V115WireCodec.decode(packet, journal.records.single().receivedAtEpochMs)
                as Gs1V115DecodeResult.Success
            Gs1RuntimeAwaitResult.Processed(
                Gs1PacketProcessingResult.Completed(
                    committedSamples = decoded.records.map { it.sample },
                    resolvedWireProfile = Gs1WireProfile.V115,
                ),
            )
        }.recover(
            profile = profile(transportVariant = 2),
            generation = 1L,
            initialCoreCursor = 1,
            initialWireProfile = Gs1WireProfile.UNRESOLVED,
        ) as Gs1PendingIngressRecoveryResult.Completed

        assertEquals(2, result.finalCoreCursor)
        assertEquals(Gs1WireProfile.V115, result.finalWireProfile)
        assertEquals(SensorPacketIngressOutcomeStatus.CORE_COMMITTED, journal.outcomes.single().status)
    }

    @Test
    fun challengeAlreadyCoveredByV120BindingIsNonDataAndNeverReplayed() = runBlocking {
        val challenge = byteArrayOf(0x23, 0xf7.toByte(), 0x6f, 0xd9.toByte(), 0xf4.toByte())
        val journal = FakeRecoveryJournal(listOf(record(0, challenge)))
        var replayCalls = 0

        val result = recovery(journal) {
            replayCalls += 1
            error("bound challenge must not enter the stateful core again")
        }.recover(
            profile = profile(transportVariant = 2),
            generation = 1L,
            initialCoreCursor = 1,
            initialWireProfile = Gs1WireProfile.V120,
        ) as Gs1PendingIngressRecoveryResult.Completed

        assertEquals(Gs1WireProfile.V120, result.finalWireProfile)
        assertEquals(0, replayCalls)
        assertEquals(SensorPacketIngressOutcomeStatus.NON_DATA, journal.outcomes.single().status)
    }

    @Test
    fun orderedPendingEvidenceIsClassifiedReplayedExactlyAndMarked() = runBlocking {
        val acknowledgement = byteArrayOf(4, 0, 0, 0, 0xfc.toByte())
        val firstRaw = rawPacket(startIndex = 10, count = 1)
        val secondRaw = rawPacket(startIndex = 11, count = 1)
        val journal = FakeRecoveryJournal(
            listOf(
                record(0, acknowledgement),
                record(1, firstRaw),
                record(2, secondRaw),
            ),
        )
        val replayed = mutableListOf<ByteArray>()
        val recovery = recovery(journal) { packet ->
            val bytes = packet.encryptedPacketCopy()
            replayed += bytes
            val decoded = codec.decode(SensorFamily.SIBIONICS_GS1, bytes)
                as DecodedPacket.Gs1RawSamples
            Gs1RuntimeAwaitResult.Processed(
                Gs1PacketProcessingResult.Completed(
                    committedSamples = decoded.values,
                ),
            )
        }

        val result = recovery.recover(
            profile(),
            generation = 7L,
            initialCoreCursor = 10,
            initialWireProfile = Gs1WireProfile.V120,
        )
            as Gs1PendingIngressRecoveryResult.Completed

        assertEquals(12, result.finalCoreCursor)
        assertEquals(3, result.handledRecords)
        assertEquals(null, result.blocked)
        assertEquals(
            listOf(
                SensorPacketIngressOutcomeStatus.NON_DATA,
                SensorPacketIngressOutcomeStatus.CORE_COMMITTED,
                SensorPacketIngressOutcomeStatus.CORE_COMMITTED,
            ),
            journal.outcomes.map { it.status },
        )
        assertArrayEquals(firstRaw, replayed[0])
        assertArrayEquals(secondRaw, replayed[1])
    }

    @Test
    fun exactRecoveredProductBatchIsDeliveredBeforeIngressIsMarkedHandled() = runBlocking {
        val packet = rawPacket(startIndex = 10, count = 1)
        val order = mutableListOf<String>()
        val journal = FakeRecoveryJournal(listOf(record(0, packet))) { outcome ->
            order += "handled"
            outcomes += outcome
            SensorPacketIngressMarkHandledResult.MarkedHandled
        }
        val publication = recoveredPublication(sequence = 10)
        val delivered = mutableListOf<List<Gs1ProductPublication>>()
        val recovery = recovery(
            journal = journal,
            onValidatedCommit = { event ->
                order += "published"
                delivered += event.publications
            },
        ) { journaled ->
            val decoded = codec.decode(
                SensorFamily.SIBIONICS_GS1,
                journaled.encryptedPacketCopy(),
            ) as DecodedPacket.Gs1RawSamples
            Gs1RuntimeAwaitResult.Processed(
                Gs1PacketProcessingResult.Completed(
                    committedSamples = decoded.values,
                    publications = listOf(publication),
                    validatedTransportEnvelope = true,
                ),
            )
        }

        val result = recovery.recover(
            profile(),
            generation = 7L,
            initialCoreCursor = 10,
            initialWireProfile = Gs1WireProfile.V120,
        )

        assertTrue(result is Gs1PendingIngressRecoveryResult.Completed)
        assertEquals(listOf(listOf(publication)), delivered)
        assertEquals(listOf("published", "handled"), order)
    }

    @Test
    fun finalDurableSensorIndexEndsRecoveryWithAnExplicitTerminalResult() = runBlocking {
        val packet = rawPacket(startIndex = 0xffff, count = 1)
        val journal = FakeRecoveryJournal(listOf(record(0, packet)))
        var replayCalls = 0

        val result = recovery(journal) { journaled ->
            replayCalls += 1
            val decoded = codec.decode(
                SensorFamily.SIBIONICS_GS1,
                journaled.encryptedPacketCopy(),
            ) as DecodedPacket.Gs1RawSamples
            Gs1RuntimeAwaitResult.Processed(
                Gs1PacketProcessingResult.Completed(committedSamples = decoded.values),
            )
        }.recover(
            profile(),
            generation = 1L,
            initialCoreCursor = 0xffff,
            initialWireProfile = Gs1WireProfile.V120,
        ) as Gs1PendingIngressRecoveryResult.Failed

        assertEquals("SENSOR_SEQUENCE_EXHAUSTED", result.code)
        assertTrue(!result.retryable)
        assertEquals(1, replayCalls)
        assertEquals(SensorPacketIngressOutcomeStatus.CORE_COMMITTED, journal.outcomes.single().status)
    }

    @Test
    fun laterExactPacketClosesEarlierGapBeforeLiveConnection() = runBlocking {
        val journal = FakeRecoveryJournal(
            listOf(
                record(0, rawPacket(startIndex = 11, count = 1)),
                record(1, rawPacket(startIndex = 10, count = 1)),
            ),
        )
        val replayedIndices = mutableListOf<Int>()
        val result = recovery(journal) { packet ->
            val decoded = codec.decode(
                SensorFamily.SIBIONICS_GS1,
                packet.encryptedPacketCopy(),
            ) as DecodedPacket.Gs1RawSamples
            replayedIndices += decoded.values.single().index
            Gs1RuntimeAwaitResult.Processed(
                Gs1PacketProcessingResult.Completed(
                    committedSamples = decoded.values,
                ),
            )
        }.recover(
            profile(),
            generation = 1L,
            initialCoreCursor = 10,
            initialWireProfile = Gs1WireProfile.V120,
        )
            as Gs1PendingIngressRecoveryResult.Completed

        assertEquals(12, result.finalCoreCursor)
        assertEquals(null, result.blocked)
        assertEquals(listOf(10, 11), replayedIndices)
        assertEquals(2, journal.outcomes.size)
    }

    @Test
    fun unfillableGapRemainsPendingForLiveHistoryBackfill() = runBlocking {
        val journal = FakeRecoveryJournal(
            listOf(record(0, rawPacket(startIndex = 11, count = 1))),
        )
        val result = recovery(journal) { error("gap must not reach the core") }
            .recover(
                profile(),
                generation = 1L,
                initialCoreCursor = 10,
                initialWireProfile = Gs1WireProfile.V120,
            )
            as Gs1PendingIngressRecoveryResult.Completed

        assertEquals(10, result.finalCoreCursor)
        assertEquals(Gs1PendingIngressRecoveryDisposition.BLOCKED_BY_GAP, result.blocked?.disposition)
        assertTrue(journal.outcomes.isEmpty())
    }

    @Test
    fun unsupportedProtocolStopsRecoveryBeforeAnyLaterRawPacket() = runBlocking {
        val plainUnsupported = byteArrayOf(5, 0x55, 0, 0, 0, 0xa6.toByte())
        val journal = FakeRecoveryJournal(
            listOf(
                record(0, codec.encryptForTest(plainUnsupported)),
                record(1, rawPacket(startIndex = 10, count = 1)),
            ),
        )
        var replayCalls = 0

        val result = recovery(journal) {
            replayCalls += 1
            error("unsupported protocol must stop recovery before replay")
        }.recover(
            profile(),
            generation = 1L,
            initialCoreCursor = 10,
            initialWireProfile = Gs1WireProfile.V120,
        )
            as Gs1PendingIngressRecoveryResult.Completed

        assertEquals(10, result.finalCoreCursor)
        assertEquals(
            Gs1PendingIngressRecoveryDisposition.UNSUPPORTED_PROTOCOL,
            result.blocked?.disposition,
        )
        assertEquals(0, replayCalls)
        assertTrue(journal.outcomes.isEmpty())
    }

    @Test
    fun invalidAndAlreadyCoveredRecordsGetDurableTerminalOutcomesWithoutReplay() = runBlocking {
        val covered = rawPacket(startIndex = 8, count = 2)
        val invalid = rawPacket(startIndex = 10, count = 1).also {
            it[it.lastIndex] = (it.last() + 1).toByte()
        }
        val coveredIngress = record(0, covered)
        val journal = FakeRecoveryJournal(listOf(coveredIngress, record(1, invalid)))
        val coveredSamples = (codec.decode(
            SensorFamily.SIBIONICS_GS1,
            covered,
        ) as DecodedPacket.Gs1RawSamples).values.map { sample ->
            durableSample(coveredIngress, sample)
        }

        val result = recovery(
            journal = journal,
            committedIngressReader = FakeCommittedIngressReader(
                mapOf(
                    coveredIngress.ingressId to
                        CommittedSensorIngressReadResult.Exact(coveredSamples),
                ),
            ),
        ) { error("neither record is replayable") }
            .recover(
                profile(),
                generation = 1L,
                initialCoreCursor = 10,
                initialWireProfile = Gs1WireProfile.V120,
            )
            as Gs1PendingIngressRecoveryResult.Completed

        assertEquals(10, result.finalCoreCursor)
        assertEquals(
            listOf(
                SensorPacketIngressOutcomeStatus.ALREADY_COVERED,
                SensorPacketIngressOutcomeStatus.QUARANTINED,
            ),
            journal.outcomes.map { it.status },
        )
        assertEquals(journal.records[0].receivedAtEpochMs, journal.outcomes[0].handledAtEpochMs)
        assertEquals(journal.records[1].receivedAtEpochMs, journal.outcomes[1].handledAtEpochMs)
    }

    @Test
    fun alreadyCoveredRedeliversOnlyItsExactRoomLinkedPrefixBeforeMarkHandled() = runBlocking {
        val packet = rawPacket(startIndex = 8, count = 2)
        val ingress = record(0, packet)
        val decoded = (codec.decode(SensorFamily.SIBIONICS_GS1, packet) as DecodedPacket.Gs1RawSamples)
            .values
        val committed = decoded.map { sample ->
            durableSample(
                ingress = ingress,
                sample = sample,
                publication = durablePublication(sample, ingress),
            )
        }
        val order = mutableListOf<String>()
        val journal = FakeRecoveryJournal(listOf(ingress)) { outcome ->
            order += "handled"
            outcomes += outcome
            SensorPacketIngressMarkHandledResult.MarkedHandled
        }
        val delivered = mutableListOf<Gs1DiagnosticRuntimeEvent.Committed>()

        val result = recovery(
            journal = journal,
            committedIngressReader = FakeCommittedIngressReader(
                mapOf(ingress.ingressId to CommittedSensorIngressReadResult.Exact(committed)),
            ),
            onValidatedCommit = { event ->
                order += "published"
                delivered += event
            },
        ) { error("already-covered evidence must not rerun the native core") }
            .recover(
                profile(),
                generation = 4L,
                initialCoreCursor = 10,
                initialWireProfile = Gs1WireProfile.V120,
            ) as Gs1PendingIngressRecoveryResult.Completed

        assertEquals(1, result.handledRecords)
        assertEquals(listOf(8, 9), delivered.single().samples.map { it.index })
        assertEquals(listOf(8L, 9L), delivered.single().publications.map { it.reading.sequence })
        assertEquals(listOf("published", "handled"), order)
        assertEquals(SensorPacketIngressOutcomeStatus.ALREADY_COVERED, journal.outcomes.single().status)
    }

    @Test
    fun handledDuplicateIsMarkedOnlyAfterExactProofAndNeverRepublishesLocalEffects() = runBlocking {
        val packet = rawPacket(startIndex = 8, count = 2)
        val earlier = record(0, packet)
        val current = record(1, packet)
        val decoded = (codec.decode(SensorFamily.SIBIONICS_GS1, packet) as DecodedPacket.Gs1RawSamples)
            .values
        val sourceRows = decoded.map { sample ->
            durableSample(
                ingress = earlier,
                sample = sample,
                publication = durablePublication(sample, earlier),
            )
        }
        val order = mutableListOf<String>()
        val reader = CommittedSensorIngressReader { ingress ->
            assertEquals(current.ingressId, ingress.ingressId)
            order += "proved"
            CommittedSensorIngressReadResult.HandledDuplicate(
                sourceIngress = earlier,
                samples = sourceRows,
                outcomeStatus = SensorPacketIngressOutcomeStatus.CORE_COMMITTED,
            )
        }
        val journal = FakeRecoveryJournal(listOf(current)) { outcome ->
            order += "handled"
            outcomes += outcome
            SensorPacketIngressMarkHandledResult.MarkedHandled
        }

        val result = recovery(
            journal = journal,
            committedIngressReader = reader,
            onValidatedCommit = { error("handled duplicate must not replay local publications") },
        ) { error("handled duplicate must not rerun the native core") }
            .recover(
                profile(),
                generation = 4L,
                initialCoreCursor = 10,
                initialWireProfile = Gs1WireProfile.V120,
            ) as Gs1PendingIngressRecoveryResult.Completed

        assertEquals(1, result.handledRecords)
        assertEquals(listOf("proved", "handled"), order)
        assertEquals(SensorPacketIngressOutcomeStatus.ALREADY_COVERED, journal.outcomes.single().status)
    }

    @Test
    fun handledDuplicateWithIncompleteDecodedRangeRemainsPending() = runBlocking {
        val packet = rawPacket(startIndex = 8, count = 2)
        val earlier = record(0, packet)
        val current = record(1, packet)
        val decoded = (codec.decode(SensorFamily.SIBIONICS_GS1, packet) as DecodedPacket.Gs1RawSamples)
            .values
        val journal = FakeRecoveryJournal(listOf(current))

        val result = recovery(
            journal = journal,
            committedIngressReader = CommittedSensorIngressReader {
                CommittedSensorIngressReadResult.HandledDuplicate(
                    sourceIngress = earlier,
                    samples = listOf(durableSample(earlier, decoded.first())),
                    outcomeStatus = SensorPacketIngressOutcomeStatus.CORE_COMMITTED,
                )
            },
        ) { error("handled duplicate must not rerun the native core") }
            .recover(
                profile(),
                generation = 4L,
                initialCoreCursor = 10,
                initialWireProfile = Gs1WireProfile.V120,
            ) as Gs1PendingIngressRecoveryResult.Failed

        assertEquals("RECOVERY_COMMITTED_PREFIX_MISMATCH", result.code)
        assertTrue(journal.outcomes.isEmpty())
    }

    @Test
    fun alreadyCoveredReaderMismatchLeavesIngressPending() = runBlocking {
        val ingress = record(0, rawPacket(startIndex = 8, count = 2))
        val journal = FakeRecoveryJournal(listOf(ingress))

        val result = recovery(
            journal = journal,
            committedIngressReader = FakeCommittedIngressReader(
                mapOf(
                    ingress.ingressId to CommittedSensorIngressReadResult.Mismatch(
                        "source ingress mismatch",
                    ),
                ),
            ),
        ) { error("already-covered evidence must not rerun the native core") }
            .recover(
                profile(),
                generation = 4L,
                initialCoreCursor = 10,
                initialWireProfile = Gs1WireProfile.V120,
            ) as Gs1PendingIngressRecoveryResult.Failed

        assertEquals("RECOVERY_COMMITTED_EVIDENCE_MISMATCH", result.code)
        assertTrue(journal.outcomes.isEmpty())
    }

    @Test
    fun alreadyCoveredCannotMarkHandledWithAnyMissingDurablePrefixRow() = runBlocking {
        val packet = rawPacket(startIndex = 8, count = 3)
        val ingress = record(0, packet)
        val decoded = (codec.decode(SensorFamily.SIBIONICS_GS1, packet) as DecodedPacket.Gs1RawSamples)
            .values

        decoded.indices.forEach { missingPosition ->
            val journal = FakeRecoveryJournal(listOf(ingress))
            val incomplete = decoded.filterIndexed { index, _ -> index != missingPosition }
                .map { sample -> durableSample(ingress, sample) }

            val result = recovery(
                journal = journal,
                committedIngressReader = FakeCommittedIngressReader(
                    mapOf(
                        ingress.ingressId to CommittedSensorIngressReadResult.Exact(incomplete),
                    ),
                ),
            ) { error("already-covered evidence must not rerun the native core") }
                .recover(
                    profile(),
                    generation = 4L,
                    initialCoreCursor = 11,
                    initialWireProfile = Gs1WireProfile.V120,
                ) as Gs1PendingIngressRecoveryResult.Failed

            assertEquals("RECOVERY_COMMITTED_PREFIX_MISMATCH", result.code)
            assertTrue(journal.outcomes.isEmpty())
        }
    }

    @Test
    fun alreadyCoveredOutputFailureLeavesIngressPendingForAnotherRecovery() = runBlocking {
        val packet = rawPacket(startIndex = 8, count = 1)
        val ingress = record(0, packet)
        val sample = (codec.decode(
            SensorFamily.SIBIONICS_GS1,
            packet,
        ) as DecodedPacket.Gs1RawSamples).values.single()
        val journal = FakeRecoveryJournal(listOf(ingress))

        val result = recovery(
            journal = journal,
            committedIngressReader = FakeCommittedIngressReader(
                mapOf(
                    ingress.ingressId to CommittedSensorIngressReadResult.Exact(
                        listOf(durableSample(ingress, sample)),
                    ),
                ),
            ),
            onValidatedCommit = { throw IllegalStateException("local effects unavailable") },
        ) { error("already-covered evidence must not rerun the native core") }
            .recover(
                profile(),
                generation = 4L,
                initialCoreCursor = 10,
                initialWireProfile = Gs1WireProfile.V120,
            ) as Gs1PendingIngressRecoveryResult.Failed

        assertEquals("RECOVERY_COMMITTED_OUTPUT_UNAVAILABLE", result.code)
        assertTrue(journal.outcomes.isEmpty())
    }

    @Test
    fun typedCommittedOutputFailurePreservesCodeAndRetryabilityAndLeavesIngressPending() =
        runBlocking {
            val cases = listOf(
                Triple("STORAGE_UNAVAILABLE", true, "database unavailable"),
                Triple("LOCAL_EFFECTS_TIMEOUT", true, "local effects timed out"),
                Triple("STORAGE_CONFLICT", false, "cursor conflict"),
                Triple("APPLICATION_STOPPED", false, "application stopped"),
                Triple("DURABLE_CURSOR_REJECTED", false, "non-lineage commit"),
            )

            cases.forEach { (code, retryable, detail) ->
                val packet = rawPacket(startIndex = 8, count = 1)
                val ingress = record(0, packet)
                val sample = (codec.decode(
                    SensorFamily.SIBIONICS_GS1,
                    packet,
                ) as DecodedPacket.Gs1RawSamples).values.single()
                val journal = FakeRecoveryJournal(listOf(ingress))

                val result = recovery(
                    journal = journal,
                    committedIngressReader = FakeCommittedIngressReader(
                        mapOf(
                            ingress.ingressId to CommittedSensorIngressReadResult.Exact(
                                listOf(durableSample(ingress, sample)),
                            ),
                        ),
                    ),
                    onValidatedCommit = {
                        throw Gs1CommittedDeliveryUnavailableException(
                            code = code,
                            detail = detail,
                            retryable = retryable,
                        )
                    },
                ) { error("already-covered evidence must not rerun the native core") }
                    .recover(
                        profile(),
                        generation = 4L,
                        initialCoreCursor = 10,
                        initialWireProfile = Gs1WireProfile.V120,
                    ) as Gs1PendingIngressRecoveryResult.Failed

                assertEquals(code, result.code)
                assertEquals(detail, result.detail)
                assertEquals(retryable, result.retryable)
                assertTrue(journal.outcomes.isEmpty())
            }
        }

    @Test
    fun partialOverlapUsesTheOriginalIngressForItsHistorySuffix() = runBlocking {
        val packet = rawPacket(startIndex = 9, count = 2)
        val ingress = record(0, packet)
        val decoded = (codec.decode(
            SensorFamily.SIBIONICS_GS1,
            packet,
        ) as DecodedPacket.Gs1RawSamples).values
        val journal = FakeRecoveryJournal(listOf(ingress))

        val result = recovery(
            journal = journal,
            committedIngressReader = FakeCommittedIngressReader(
                mapOf(
                    ingress.ingressId to CommittedSensorIngressReadResult.Exact(
                        listOf(durableSample(ingress, decoded.first())),
                    ),
                ),
            ),
        ) { journaled ->
            assertEquals(1, journaled.verifiedCommittedPrefixSampleCount)
            Gs1RuntimeAwaitResult.Processed(
                Gs1PacketProcessingResult.Completed(listOf(decoded.last())),
            )
        }
            .recover(
                profile(),
                generation = 1L,
                initialCoreCursor = 10,
                initialWireProfile = Gs1WireProfile.V120,
            )
            as Gs1PendingIngressRecoveryResult.Completed

        assertEquals(11, result.finalCoreCursor)
        assertEquals(null, result.blocked)
        assertEquals(SensorPacketIngressOutcomeStatus.CORE_COMMITTED, journal.outcomes.single().status)
    }

    @Test
    fun partialOverlapRedeliversRoomPrefixThenCommitsAndDeliversItsSuffix() = runBlocking {
        val packet = rawPacket(startIndex = 9, count = 3)
        val ingress = record(0, packet)
        val decoded = (codec.decode(SensorFamily.SIBIONICS_GS1, packet) as DecodedPacket.Gs1RawSamples)
            .values
        val committedPrefix = decoded.take(2).map { sample ->
            durableSample(
                ingress = ingress,
                sample = sample,
                publication = durablePublication(sample, ingress),
            )
        }
        val delivered = mutableListOf<Gs1DiagnosticRuntimeEvent.Committed>()
        val journal = FakeRecoveryJournal(listOf(ingress))

        val result = recovery(
            journal = journal,
            committedIngressReader = FakeCommittedIngressReader(
                mapOf(
                    ingress.ingressId to CommittedSensorIngressReadResult.Exact(committedPrefix),
                ),
            ),
            onValidatedCommit = delivered::add,
        ) { journaled ->
            assertEquals(2, journaled.verifiedCommittedPrefixSampleCount)
            Gs1RuntimeAwaitResult.Processed(
                Gs1PacketProcessingResult.Completed(listOf(decoded.last())),
            )
        }
            .recover(
                profile(),
                generation = 5L,
                initialCoreCursor = 11,
                initialWireProfile = Gs1WireProfile.V120,
            ) as Gs1PendingIngressRecoveryResult.Completed

        assertEquals(12, result.finalCoreCursor)
        assertEquals(null, result.blocked)
        assertEquals(listOf(9, 10), delivered[0].samples.map { it.index })
        assertEquals(listOf(9L, 10L), delivered[0].publications.map { it.reading.sequence })
        assertEquals(listOf(11), delivered[1].samples.map { it.index })
        assertEquals(SensorPacketIngressOutcomeStatus.CORE_COMMITTED, journal.outcomes.single().status)
    }

    @Test
    fun partialOverlapReplaysOnlyItsOriginalIngressSuffixAndCompletesTheIngress() = runBlocking {
        val packet = rawPacket(startIndex = 9, count = 3)
        val ingress = record(0, packet)
        val decoded = (codec.decode(
            SensorFamily.SIBIONICS_GS1,
            packet,
        ) as DecodedPacket.Gs1RawSamples).values
        val durableRows = mutableListOf(
            durableSample(ingress, decoded[0]),
            durableSample(ingress, decoded[1]),
        )
        val reader = CommittedSensorIngressReader { requested ->
            assertEquals(ingress.ingressId, requested.ingressId)
            CommittedSensorIngressReadResult.Exact(durableRows.toList())
        }
        val journal = FakeRecoveryJournal(listOf(ingress))
        val replayed = mutableListOf<DurablyJournaledGs1Packet>()

        val result = recovery(
            journal = journal,
            committedIngressReader = reader,
        ) { journaled ->
            replayed += journaled
            assertEquals(ingress.ingressId, journaled.ingressId)
            assertArrayEquals(packet, journaled.encryptedPacketCopy())
            assertEquals(2, journaled.verifiedCommittedPrefixSampleCount)
            durableRows += durableSample(ingress, decoded[2])
            Gs1RuntimeAwaitResult.Processed(
                Gs1PacketProcessingResult.Completed(
                    committedSamples = listOf(decoded[2]),
                ),
            )
        }.recover(
            profile(),
            generation = 5L,
            initialCoreCursor = 11,
            initialWireProfile = Gs1WireProfile.V120,
        ) as Gs1PendingIngressRecoveryResult.Completed

        assertEquals(12, result.finalCoreCursor)
        assertEquals(null, result.blocked)
        assertEquals(1, replayed.size)
        assertEquals(SensorPacketIngressOutcomeStatus.CORE_COMMITTED, journal.outcomes.single().status)
        assertEquals(listOf(9, 10, 11), durableRows.map { it.raw.sequence })
    }

    @Test
    fun partialOverlapCannotPublishRoomRowFromTheUnverifiedSuffix() = runBlocking {
        val packet = rawPacket(startIndex = 9, count = 3)
        val ingress = record(0, packet)
        val decoded = (codec.decode(SensorFamily.SIBIONICS_GS1, packet) as DecodedPacket.Gs1RawSamples)
            .values
        val journal = FakeRecoveryJournal(listOf(ingress))
        val delivered = mutableListOf<Gs1DiagnosticRuntimeEvent.Committed>()

        val result = recovery(
            journal = journal,
            committedIngressReader = FakeCommittedIngressReader(
                mapOf(
                    ingress.ingressId to CommittedSensorIngressReadResult.Exact(
                        decoded.map { sample ->
                            durableSample(
                                ingress = ingress,
                                sample = sample,
                                publication = durablePublication(sample, ingress),
                            )
                        },
                    ),
                ),
            ),
            onValidatedCommit = delivered::add,
        ) { error("partial overlap must not rerun the native core") }
            .recover(
                profile(),
                generation = 5L,
                initialCoreCursor = 11,
                initialWireProfile = Gs1WireProfile.V120,
            ) as Gs1PendingIngressRecoveryResult.Failed

        assertEquals("RECOVERY_COMMITTED_PREFIX_MISMATCH", result.code)
        assertTrue(delivered.isEmpty())
        assertTrue(journal.outcomes.isEmpty())
    }

    @Test
    fun partialOverlapCannotProceedWithAMissingCoveredPrefixRow() = runBlocking {
        val packet = rawPacket(startIndex = 8, count = 4)
        val ingress = record(0, packet)
        val decoded = (codec.decode(SensorFamily.SIBIONICS_GS1, packet) as DecodedPacket.Gs1RawSamples)
            .values
        val journal = FakeRecoveryJournal(listOf(ingress))

        val result = recovery(
            journal = journal,
            committedIngressReader = FakeCommittedIngressReader(
                mapOf(
                    ingress.ingressId to CommittedSensorIngressReadResult.Exact(
                        listOf(
                            durableSample(ingress, decoded[0]),
                            durableSample(ingress, decoded[2]),
                        ),
                    ),
                ),
            ),
        ) { error("partial overlap must not rerun the native core") }
            .recover(
                profile(),
                generation = 5L,
                initialCoreCursor = 11,
                initialWireProfile = Gs1WireProfile.V120,
            ) as Gs1PendingIngressRecoveryResult.Failed

        assertEquals("RECOVERY_COMMITTED_PREFIX_MISMATCH", result.code)
        assertTrue(journal.outcomes.isEmpty())
    }

    @Test
    fun coreRejectionLeavesEvidencePendingAndFailsClosed() = runBlocking {
        val journal = FakeRecoveryJournal(listOf(record(0, rawPacket(startIndex = 10, count = 1))))
        val result = recovery(journal) {
            Gs1RuntimeAwaitResult.Processed(
                Gs1PacketProcessingResult.Rejected(
                    code = "NATIVE_REJECTED",
                    message = "state mismatch",
                ),
            )
        }.recover(
            profile(),
            generation = 1L,
            initialCoreCursor = 10,
            initialWireProfile = Gs1WireProfile.V120,
        )
            as Gs1PendingIngressRecoveryResult.Failed

        assertEquals("RECOVERY_CORE_REJECTED", result.code)
        assertTrue(!result.retryable)
        assertTrue(journal.outcomes.isEmpty())
    }

    @Test
    fun everyTerminalResultRedeliversItsExactDurablePrefixAndLeavesIngressPending() = runBlocking {
        val packet = rawPacket(startIndex = 10, count = 3)
        val decoded = (codec.decode(SensorFamily.SIBIONICS_GS1, packet) as DecodedPacket.Gs1RawSamples)
            .values
        val prefix = decoded.take(2)
        val publications = listOf(
            recoveredPublication(sequence = 10),
            recoveredPublication(sequence = 11),
        )
        val cases: List<Pair<String, (List<DecodedGs1RawSample>, List<Gs1ProductPublication>) ->
            Gs1PacketProcessingResult>> = listOf(
            "RECOVERY_CORE_REJECTED" to { samples, values ->
                Gs1PacketProcessingResult.Rejected(
                    code = "NATIVE_REJECTED_AFTER_COMMIT",
                    message = "terminal after durable prefix",
                    committedSamples = samples,
                    publications = values,
                )
            },
            "RECOVERY_STORAGE_CONFLICT" to { samples, values ->
                Gs1PacketProcessingResult.StorageConflict(
                    reason = "terminal after durable prefix",
                    committedSamples = samples,
                    publications = values,
                )
            },
            "RECOVERY_CORE_CLOSED" to { samples, values ->
                Gs1PacketProcessingResult.Closed(
                    reason = "terminal after durable prefix",
                    committedSamples = samples,
                    publications = values,
                )
            },
        )

        cases.forEach { (expectedCode, terminal) ->
            val journal = FakeRecoveryJournal(listOf(record(0, packet)))
            val delivered = mutableListOf<Gs1DiagnosticRuntimeEvent.Committed>()
            val result = recovery(
                journal = journal,
                onValidatedCommit = delivered::add,
            ) {
                Gs1RuntimeAwaitResult.Processed(terminal(prefix, publications))
            }.recover(
                profile(),
                generation = 1L,
                initialCoreCursor = 10,
                initialWireProfile = Gs1WireProfile.V120,
            ) as Gs1PendingIngressRecoveryResult.Failed

            assertEquals(expectedCode, result.code)
            assertEquals(listOf(10, 11), delivered.single().samples.map { it.index })
            assertEquals(listOf(10L, 11L), delivered.single().publications.map { it.reading.sequence })
            assertTrue(journal.outcomes.isEmpty())
        }
    }

    @Test
    fun terminalReplayCannotPublishAnOutputOutsideItsDurablePrefix() = runBlocking {
        val packet = rawPacket(startIndex = 10, count = 2)
        val decoded = (codec.decode(SensorFamily.SIBIONICS_GS1, packet) as DecodedPacket.Gs1RawSamples)
            .values
        val journal = FakeRecoveryJournal(listOf(record(0, packet)))
        val delivered = mutableListOf<Gs1DiagnosticRuntimeEvent.Committed>()

        val result = recovery(
            journal = journal,
            onValidatedCommit = delivered::add,
        ) {
            Gs1RuntimeAwaitResult.Processed(
                Gs1PacketProcessingResult.Rejected(
                    code = "NATIVE_REJECTED_AFTER_COMMIT",
                    message = "terminal after durable prefix",
                    committedSamples = decoded.take(1),
                    publications = listOf(recoveredPublication(sequence = 11)),
                ),
            )
        }.recover(
            profile(),
            generation = 1L,
            initialCoreCursor = 10,
            initialWireProfile = Gs1WireProfile.V120,
        ) as Gs1PendingIngressRecoveryResult.Failed

        assertEquals("RECOVERY_COMMIT_OUTPUT_MISMATCH", result.code)
        assertTrue(delivered.isEmpty())
        assertTrue(journal.outcomes.isEmpty())
    }

    @Test
    fun terminalReplayCannotSkipAnIndexInsideItsClaimedDurablePrefix() = runBlocking {
        val packet = rawPacket(startIndex = 10, count = 3)
        val decoded = (codec.decode(SensorFamily.SIBIONICS_GS1, packet) as DecodedPacket.Gs1RawSamples)
            .values
        val journal = FakeRecoveryJournal(listOf(record(0, packet)))
        val delivered = mutableListOf<Gs1DiagnosticRuntimeEvent.Committed>()

        val result = recovery(
            journal = journal,
            onValidatedCommit = delivered::add,
        ) {
            Gs1RuntimeAwaitResult.Processed(
                Gs1PacketProcessingResult.Closed(
                    reason = "non-contiguous durable prefix",
                    committedSamples = listOf(decoded[0], decoded[2]),
                ),
            )
        }.recover(
            profile(),
            generation = 1L,
            initialCoreCursor = 10,
            initialWireProfile = Gs1WireProfile.V120,
        ) as Gs1PendingIngressRecoveryResult.Failed

        assertEquals("RECOVERY_COMMIT_PREFIX_MISMATCH", result.code)
        assertTrue(delivered.isEmpty())
        assertTrue(journal.outcomes.isEmpty())
    }

    @Test
    fun replayCannotSubstituteDifferentRawValuesUnderTheExpectedIndex() = runBlocking {
        val packet = rawPacket(startIndex = 10, count = 1)
        val decoded = (codec.decode(SensorFamily.SIBIONICS_GS1, packet) as DecodedPacket.Gs1RawSamples)
            .values.single()
        val journal = FakeRecoveryJournal(listOf(record(0, packet)))
        val delivered = mutableListOf<Gs1DiagnosticRuntimeEvent.Committed>()

        val result = recovery(
            journal = journal,
            onValidatedCommit = delivered::add,
        ) {
            Gs1RuntimeAwaitResult.Processed(
                Gs1PacketProcessingResult.Completed(
                    committedSamples = listOf(decoded.copy(current = decoded.current + 1)),
                ),
            )
        }.recover(
            profile(),
            generation = 1L,
            initialCoreCursor = 10,
            initialWireProfile = Gs1WireProfile.V120,
        ) as Gs1PendingIngressRecoveryResult.Failed

        assertEquals("RECOVERY_COMMIT_SAMPLE_MISMATCH", result.code)
        assertTrue(delivered.isEmpty())
        assertTrue(journal.outcomes.isEmpty())
    }

    @Test
    fun restoredEvidenceFromAnotherSensorFamilyFailsClosedBeforeReplay() = runBlocking {
        val journal = FakeRecoveryJournal(
            listOf(
                record(
                    ordinal = 0,
                    packet = rawPacket(startIndex = 10, count = 1),
                    family = SensorFamily.SIBIONICS_GS1SB,
                ),
            ),
        )
        var replayCalls = 0

        val result = recovery(journal) {
            replayCalls += 1
            completedReplay(index = 10)
        }.recover(
            profile(),
            generation = 1L,
            initialCoreCursor = 10,
            initialWireProfile = Gs1WireProfile.V120,
        )
            as Gs1PendingIngressRecoveryResult.Failed

        assertEquals("RECOVERY_INGRESS_IDENTITY_MISMATCH", result.code)
        assertTrue(!result.retryable)
        assertEquals(0, replayCalls)
        assertTrue(journal.outcomes.isEmpty())
    }

    @Test
    fun coreInvalidPacketIsDurablyQuarantinedInsteadOfBlockingEveryRestart() = runBlocking {
        val journal = FakeRecoveryJournal(listOf(record(0, rawPacket(startIndex = 10, count = 1))))
        val result = recovery(journal) {
            Gs1RuntimeAwaitResult.Processed(
                Gs1PacketProcessingResult.InvalidPacket(
                    error = Gs1VerifiedPacketError.WIRE_PACKET_INVALID,
                    detail = "strict verifier rejected compatibility decode",
                ),
            )
        }.recover(
            profile(),
            generation = 1L,
            initialCoreCursor = 10,
            initialWireProfile = Gs1WireProfile.V120,
        )
            as Gs1PendingIngressRecoveryResult.Completed

        assertEquals(10, result.finalCoreCursor)
        assertEquals(1, result.handledRecords)
        assertEquals(SensorPacketIngressOutcomeStatus.QUARANTINED, journal.outcomes.single().status)
    }

    @Test
    fun uncertainOutcomeWriteCanBeRetriedWithByteIdenticalOutcomeRecord() = runBlocking {
        val journal = FakeRecoveryJournal(
            records = listOf(record(0, byteArrayOf(4, 0, 0, 0, 0xfc.toByte()))),
            mark = { outcome ->
                if (outcomes.isEmpty()) {
                    outcomes += outcome
                    throw IllegalStateException("commit response lost")
                }
                assertEquals(outcomes.single(), outcome)
                SensorPacketIngressMarkHandledResult.AlreadyHandled
            },
        )
        val first = recovery(journal) { error("ack is non-data") }
            .recover(
                profile(),
                generation = 1L,
                initialCoreCursor = 10,
                initialWireProfile = Gs1WireProfile.V120,
            )
        val second = recovery(journal) { error("ack is non-data") }
            .recover(
                profile(),
                generation = 2L,
                initialCoreCursor = 10,
                initialWireProfile = Gs1WireProfile.V120,
            )

        assertEquals("RECOVERY_OUTCOME_STORAGE_UNAVAILABLE", (first as Gs1PendingIngressRecoveryResult.Failed).code)
        assertTrue(second is Gs1PendingIngressRecoveryResult.Completed)
        assertEquals(1, journal.outcomes.size)
    }

    @Test
    fun liveHandledDuplicateIsProvedAndMarkedWithoutEnteringTheStatefulCore() = runBlocking {
        val packet = rawPacket(startIndex = 10, count = 2)
        val origin = record(0, packet)
        val current = record(1, packet)
        val originSamples = (codec.decode(
            SensorFamily.SIBIONICS_GS1,
            packet,
        ) as DecodedPacket.Gs1RawSamples).values.map { durableSample(origin, it) }
        val journal = FakeRecoveryJournal(listOf(current))
        val gate = Gs1LiveIngressDuplicateGate(
            journal = journal,
            committedIngressReader = CommittedSensorIngressReader {
                CommittedSensorIngressReadResult.HandledDuplicate(
                    sourceIngress = origin,
                    samples = originSamples,
                    outcomeStatus = SensorPacketIngressOutcomeStatus.CORE_COMMITTED,
                )
            },
            codec = codec,
        )

        val result = gate.resolve(
            profile = profile(),
            currentCoreCursor = 12,
            wireProfile = Gs1WireProfile.V120,
            currentIngress = current,
        )

        assertTrue(result is Gs1LiveIngressDuplicateResult.Handled)
        assertEquals(SensorPacketIngressOutcomeStatus.ALREADY_COVERED, journal.outcomes.single().status)
        assertEquals(current.ingressId, journal.outcomes.single().ingressId)
    }

    @Test
    fun liveHandledDuplicateWithIncompleteOriginLineageFailsClosedAndStaysPending() = runBlocking {
        val packet = rawPacket(startIndex = 10, count = 2)
        val origin = record(0, packet)
        val current = record(1, packet)
        val decoded = (codec.decode(
            SensorFamily.SIBIONICS_GS1,
            packet,
        ) as DecodedPacket.Gs1RawSamples).values
        val journal = FakeRecoveryJournal(listOf(current))
        val gate = Gs1LiveIngressDuplicateGate(
            journal = journal,
            committedIngressReader = CommittedSensorIngressReader {
                CommittedSensorIngressReadResult.HandledDuplicate(
                    sourceIngress = origin,
                    samples = listOf(durableSample(origin, decoded.first())),
                    outcomeStatus = SensorPacketIngressOutcomeStatus.CORE_COMMITTED,
                )
            },
            codec = codec,
        )

        val result = gate.resolve(
            profile = profile(),
            currentCoreCursor = 12,
            wireProfile = Gs1WireProfile.V120,
            currentIngress = current,
        ) as Gs1LiveIngressDuplicateResult.Failed

        assertEquals("LIVE_DUPLICATE_COMMITTED_PREFIX_MISMATCH", result.code)
        assertTrue(journal.outcomes.isEmpty())
    }

    @Test
    fun v115DuplicateProofIgnoresReceiveTimeDerivedTimestampsButKeepsRawIdentityExact() =
        runBlocking {
            val packet = v115Response(index = 10)
            val origin = record(0, packet)
            val current = SensorPacketIngressRecord(
                ingressId = "attempt-b:0",
                sensorId = origin.sensorId,
                sensorFamily = origin.sensorFamily,
                bluetoothAddress = origin.bluetoothAddress,
                attemptId = "attempt-b",
                ordinal = 0,
                receivedAtEpochMs = origin.receivedAtEpochMs + 60_000L,
                encryptedPacket = packet,
                packetSha256 = origin.packetSha256,
            )
            val originDecoded = (Gs1V115WireCodec.decode(
                packet,
                origin.receivedAtEpochMs,
            ) as Gs1V115DecodeResult.Success).records.single().sample
            val journal = FakeRecoveryJournal(listOf(current))
            val gate = Gs1LiveIngressDuplicateGate(
                journal = journal,
                committedIngressReader = CommittedSensorIngressReader {
                    CommittedSensorIngressReadResult.HandledDuplicate(
                        sourceIngress = origin,
                        samples = listOf(durableSample(origin, originDecoded)),
                        outcomeStatus = SensorPacketIngressOutcomeStatus.CORE_COMMITTED,
                    )
                },
                codec = codec,
            )

            val result = gate.resolve(
                profile = profile(),
                currentCoreCursor = 11,
                wireProfile = Gs1WireProfile.V115,
                currentIngress = current,
            )

            assertTrue(result is Gs1LiveIngressDuplicateResult.Handled)
            assertEquals(SensorPacketIngressOutcomeStatus.ALREADY_COVERED, journal.outcomes.single().status)
        }

    @Test
    fun v115ClampBoundaryIsPacketDerivedAndUnaffectedByDifferentReceiveSeconds() = runBlocking {
        // clamp = addTime > historyDistance * 60. At the exact boundary it is
        // false for every receive time, while sensor/phone timestamps still differ.
        val packet = v115Response(index = 10, historyDistance = 1, addTimeSeconds = 60)
        val origin = record(0, packet)
        val current = SensorPacketIngressRecord(
            ingressId = "attempt-boundary:0",
            sensorId = origin.sensorId,
            sensorFamily = origin.sensorFamily,
            bluetoothAddress = origin.bluetoothAddress,
            attemptId = "attempt-boundary",
            ordinal = 0,
            receivedAtEpochMs = origin.receivedAtEpochMs + 61_999L,
            encryptedPacket = packet,
            packetSha256 = origin.packetSha256,
        )
        val originDecoded = (Gs1V115WireCodec.decode(
            packet,
            origin.receivedAtEpochMs,
        ) as Gs1V115DecodeResult.Success).records.single().sample
        val currentDecoded = (Gs1V115WireCodec.decode(
            packet,
            current.receivedAtEpochMs,
        ) as Gs1V115DecodeResult.Success).records.single().sample
        assertTrue(!originDecoded.sensorTimeWasClamped)
        assertTrue(!currentDecoded.sensorTimeWasClamped)
        assertTrue(originDecoded.sensorTimeEpochSeconds != currentDecoded.sensorTimeEpochSeconds)
        val journal = FakeRecoveryJournal(listOf(current))
        val gate = Gs1LiveIngressDuplicateGate(
            journal = journal,
            committedIngressReader = CommittedSensorIngressReader {
                CommittedSensorIngressReadResult.HandledDuplicate(
                    sourceIngress = origin,
                    samples = listOf(durableSample(origin, originDecoded)),
                    outcomeStatus = SensorPacketIngressOutcomeStatus.CORE_COMMITTED,
                )
            },
            codec = codec,
        )

        val result = gate.resolve(
            profile = profile(),
            currentCoreCursor = 11,
            wireProfile = Gs1WireProfile.V115,
            currentIngress = current,
        )

        assertTrue(result is Gs1LiveIngressDuplicateResult.Handled)
    }

    @Test
    fun livePartialOverlapUsesOnlyExactHandledRoomPrefixThenSubmitsCurrentIngressSuffix() =
        runBlocking {
            val packet = rawPacket(startIndex = 9, count = 3)
            val current = record(2, packet)
            val origin = record(0, rawPacket(startIndex = 9, count = 2))
            val decoded = (codec.decode(
                SensorFamily.SIBIONICS_GS1,
                packet,
            ) as DecodedPacket.Gs1RawSamples).values
            val coverage = decoded.take(2).map { sample -> durableSample(origin, sample) }
            val journal = FakeRecoveryJournal(listOf(current))
            val gate = Gs1LiveIngressDuplicateGate(
                journal = journal,
                committedIngressReader = coverageReader(coverage),
                codec = codec,
            )

            val result = gate.resolve(
                profile = profile(),
                currentCoreCursor = 11,
                wireProfile = Gs1WireProfile.V120,
                currentIngress = current,
            ) as Gs1LiveIngressDuplicateResult.VerifiedSuffix

            val packetForCore = DurablyJournaledGs1Packet(current)
                .withVerifiedCommittedPrefix(result)
            val processed = mutableListOf<Int>()
            val processor = Gs1PacketProcessor(
                core = object : Gs1SampleProcessor {
                    override suspend fun process(
                        sourceIngressId: String,
                        encryptedPacket: ByteArray,
                        sample: DecodedGs1RawSample,
                        receivedAtEpochMs: Long,
                    ): Gs1ProcessingResult {
                        assertEquals(current.ingressId, sourceIngressId)
                        assertArrayEquals(packet, encryptedPacket)
                        assertEquals(current.receivedAtEpochMs, receivedAtEpochMs)
                        processed += sample.index
                        return Gs1ProcessingResult.ApprovedCheckpointOnly(
                            sequence = sample.index.toLong(),
                            quality = ReadingQuality.DEGRADED,
                        )
                    }

                    override suspend fun retryPendingCommit(): Gs1ProcessingResult =
                        error("partial overlap has no pending native commit")
                },
                decoder = Gs1PacketVerifier { _, _ ->
                    Gs1VerifiedPacketResult.Success(
                        samples = decoded,
                        nativeRecords = emptyList(),
                        decrypted = true,
                    )
                },
                initialExpectedIndex = 11,
                wireProfile = Gs1WireProfile.V120,
            )
            val completed = processor.ingest(
                sourceIngressId = packetForCore.ingressId,
                encryptedPacket = packetForCore.encryptedPacketCopy(),
                receivedAtEpochMs = packetForCore.receivedAtEpochMs,
                verifiedCommittedPrefixSampleCount =
                    packetForCore.verifiedCommittedPrefixSampleCount,
            ) as Gs1PacketProcessingResult.Completed
            val settlement = Gs1CommittedIngressEventValidator(
                CommittedSensorIngressReader {
                    CommittedSensorIngressReadResult.Exact(
                        listOf(durableSample(current, decoded.last())),
                    )
                },
            ).validate(
                Gs1DiagnosticRuntimeEvent.Committed(
                    generation = 1L,
                    ingress = current,
                    samples = completed.committedSamples,
                    diagnostics = completed.diagnostics,
                    publications = completed.publications,
                    issues = completed.committedIssues,
                    validatedTransportEnvelope = completed.validatedTransportEnvelope,
                ),
            ) as Gs1CommittedIngressEventValidation.Accepted

            assertEquals(2, result.committedPrefixSampleCount)
            assertEquals(2, packetForCore.verifiedCommittedPrefixSampleCount)
            assertEquals(listOf(11), processed)
            assertEquals(listOf(11), completed.committedSamples.map { it.index })
            assertEquals(listOf(11), settlement.event.samples.map { it.index })
            assertTrue(journal.outcomes.isEmpty())
        }

    @Test
    fun livePartialOverlapWithMissingRoomPrefixFailsClosedAndStaysPending() = runBlocking {
        val current = record(2, rawPacket(startIndex = 9, count = 3))
        val journal = FakeRecoveryJournal(listOf(current))
        val gate = Gs1LiveIngressDuplicateGate(
            journal = journal,
            committedIngressReader = coverageReader(emptyList()),
            codec = codec,
        )

        val result = gate.resolve(
            profile = profile(),
            currentCoreCursor = 11,
            wireProfile = Gs1WireProfile.V120,
            currentIngress = current,
        ) as Gs1LiveIngressDuplicateResult.Failed

        assertEquals("LIVE_PARTIAL_COMMITTED_PREFIX_MISMATCH", result.code)
        assertTrue(journal.outcomes.isEmpty())
    }

    @Test
    fun livePartialOverlapWithTamperedRoomRawValueFailsClosedAndStaysPending() = runBlocking {
        val packet = rawPacket(startIndex = 9, count = 3)
        val current = record(2, packet)
        val origin = record(0, rawPacket(startIndex = 9, count = 2))
        val decoded = (codec.decode(
            SensorFamily.SIBIONICS_GS1,
            packet,
        ) as DecodedPacket.Gs1RawSamples).values
        val coverage = decoded.take(2).map { sample -> durableSample(origin, sample) }.toMutableList()
        coverage[1] = coverage[1].copy(
            raw = coverage[1].raw.withCurrentRaw(coverage[1].raw.currentRaw + 1),
        )
        val journal = FakeRecoveryJournal(listOf(current))
        val gate = Gs1LiveIngressDuplicateGate(
            journal = journal,
            committedIngressReader = coverageReader(coverage),
            codec = codec,
        )

        val result = gate.resolve(
            profile = profile(),
            currentCoreCursor = 11,
            wireProfile = Gs1WireProfile.V120,
            currentIngress = current,
        ) as Gs1LiveIngressDuplicateResult.Failed

        assertEquals("LIVE_PARTIAL_COMMITTED_PREFIX_MISMATCH", result.code)
        assertTrue(journal.outcomes.isEmpty())
    }

    private fun coverageReader(
        samples: List<CommittedSensorIngressSampleRecord>,
    ) = object : CommittedSensorIngressReader {
        override suspend fun read(
            ingress: SensorPacketIngressRecord,
        ): CommittedSensorIngressReadResult = error("partial overlap must use handled range proof")

        override suspend fun readHandledCoverage(
            request: CommittedSensorCoverageRequest,
        ): CommittedSensorCoverageReadResult =
            CommittedSensorCoverageReadResult.Exact(samples)
    }

    private fun recovery(
        journal: SensorPacketIngressJournal,
        committedIngressReader: CommittedSensorIngressReader = FakeCommittedIngressReader(),
        onValidatedCommit: suspend (Gs1DiagnosticRuntimeEvent.Committed) -> Unit = {},
        replay: suspend (DurablyJournaledGs1Packet) -> Gs1RuntimeAwaitResult,
    ) = Gs1PendingIngressRecovery(
        journal = journal,
        committedIngressReader = committedIngressReader,
        codec = codec,
        replay = { _, packet -> replay(packet) },
        onValidatedCommit = onValidatedCommit,
    )

    private fun recoveredPublication(sequence: Long) = Gs1ProductPublication(
        reading = GlucoseReading(
            eventId = "recovered-$sequence",
            sensorId = "sensor-a",
            sensorFamily = SensorFamily.SIBIONICS_GS1,
            sensorTimeEpochMs = 1_700_000_000_000L + sequence * 60_000L,
            phoneTimeEpochMs = 1_700_000_001_000L + sequence * 60_000L,
            glucoseMgDl = 104,
            trendMgDlPerMinute = -1.3,
            quality = ReadingQuality.VALID,
            sequence = sequence,
        ),
        approvalId = "ab".repeat(32),
        publicationBindingId = "cd".repeat(32),
    )

    private fun durablePublication(
        sample: DecodedGs1RawSample,
        ingress: SensorPacketIngressRecord,
    ) = Gs1ProductPublication(
        reading = GlucoseReading(
            eventId = "durable-${sample.index}",
            sensorId = ingress.sensorId,
            sensorFamily = ingress.sensorFamily,
            sensorTimeEpochMs = sample.sensorTimeEpochSeconds * 1_000L,
            phoneTimeEpochMs = ingress.receivedAtEpochMs,
            glucoseMgDl = 104,
            trendMgDlPerMinute = -1.3,
            quality = ReadingQuality.VALID,
            sequence = sample.index.toLong(),
        ),
        approvalId = "ab".repeat(32),
        publicationBindingId = "cd".repeat(32),
    )

    private fun durableSample(
        ingress: SensorPacketIngressRecord,
        sample: DecodedGs1RawSample,
        publication: Gs1ProductPublication? = null,
    ) = CommittedSensorIngressSampleRecord(
        raw = RawSensorSampleRecord(
            eventId = publication?.reading?.eventId ?: "durable-${sample.index}",
            sourceIngressId = ingress.ingressId,
            sensorId = ingress.sensorId,
            sensorFamily = ingress.sensorFamily,
            sequence = sample.index,
            sensorTimeEpochMs = sample.sensorTimeEpochSeconds * 1_000L,
            phoneTimeEpochMs = ingress.receivedAtEpochMs,
            packet = ingress.encryptedPacketCopy(),
            packetSha256 = ingress.packetSha256,
            currentRaw = sample.current,
            temperatureRaw = sample.temperature,
            historyDistance = sample.reindex,
            transportVariant = 0,
            sensorTimeWasClamped = sample.sensorTimeWasClamped,
            addTimeSeconds = sample.addTimeSeconds,
        ),
        algorithmErrorCode = if (publication == null) "DIAGNOSTIC_ONLY" else null,
        productPublication = publication?.let { value ->
            CommittedProductPublicationRecord(
                reading = value.reading,
                approvalId = value.approvalId,
                publicationBindingId = value.publicationBindingId,
            )
        },
    )

    private fun RawSensorSampleRecord.withCurrentRaw(value: Int) = RawSensorSampleRecord(
        eventId = eventId,
        sourceIngressId = sourceIngressId,
        sensorId = sensorId,
        sensorFamily = sensorFamily,
        sequence = sequence,
        sensorTimeEpochMs = sensorTimeEpochMs,
        phoneTimeEpochMs = phoneTimeEpochMs,
        packet = packetCopy(),
        packetSha256 = packetSha256,
        currentRaw = value,
        temperatureRaw = temperatureRaw,
        historyDistance = historyDistance,
        transportVariant = transportVariant,
        sensorTimeWasClamped = sensorTimeWasClamped,
        addTimeSeconds = addTimeSeconds,
    )

    private fun profile(transportVariant: Int = 0): Gs1DiagnosticActivationProfile =
        (Gs1DiagnosticActivationProfile.validate(
            sensorId = "sensor-a",
            family = SensorFamily.SIBIONICS_GS1,
            bluetoothAddress = "AA:BB:CC:DD:EE:FF",
            transportVariant = transportVariant,
            packageCode = "ABCDEFGH",
        ) as Gs1DiagnosticActivationProfileValidation.Valid).profile

    private fun record(
        ordinal: Long,
        packet: ByteArray,
        family: SensorFamily = SensorFamily.SIBIONICS_GS1,
    ) = SensorPacketIngressRecord(
        ingressId = "attempt-a:$ordinal",
        sensorId = "sensor-a",
        sensorFamily = family,
        bluetoothAddress = "AA:BB:CC:DD:EE:FF",
        attemptId = "attempt-a",
        ordinal = ordinal,
        receivedAtEpochMs = 1_700_000_000_000L + ordinal,
        encryptedPacket = packet,
        packetSha256 = packet.sha256ForRecoveryTest(),
    )

    private fun completedReplay(index: Int) = Gs1RuntimeAwaitResult.Processed(
        Gs1PacketProcessingResult.Completed(
            committedSamples = listOf(
                DecodedGs1RawSample(
                    index = index,
                    sensorTimeEpochSeconds = 1_700_000_000L + index * 60L,
                    current = 50,
                    temperature = 321,
                    reindex = 0,
                ),
            ),
        ),
    )

    private fun rawPacket(startIndex: Int, count: Int): ByteArray {
        val length = 11 + count * 8
        val plain = ByteArray(length + 1)
        plain[0] = length.toByte()
        plain[1] = 0x08
        plain[2] = count.toByte()
        plain.putU16Le(3, startIndex)
        plain.putU32Le(5, 1_700_000_000L)
        repeat(count) { position ->
            val offset = 9 + position * 8
            plain.putU16Le(offset, 320 + position)
            plain.putU16Le(offset + 4, 50 + position)
        }
        plain.putU16Le(9 + count * 8, 0)
        plain[length] = SibionicsPacketCodec.checksum(plain, length)
        return codec.encryptForTest(plain)
    }

    private fun v115Response(
        index: Int,
        historyDistance: Int = 0,
        addTimeSeconds: Int = 0,
    ): ByteArray {
        val fields = listOf(index, 300, 20, 1_000, 0, historyDistance, addTimeSeconds)
        val record = fields.flatMap { value ->
            listOf((value ushr 8).toByte(), value.toByte())
        }.toByteArray()
        val body = byteArrayOf(0xaa.toByte(), 0x55, 0x09, 0x01) + record
        return body + (-body.sum()).toByte()
    }

    private fun ByteArray.putU16Le(offset: Int, value: Int) {
        this[offset] = value.toByte()
        this[offset + 1] = (value ushr 8).toByte()
    }

    private fun ByteArray.putU32Le(offset: Int, value: Long) {
        repeat(4) { byteIndex ->
            this[offset + byteIndex] = (value ushr (byteIndex * 8)).toByte()
        }
    }
}

private class FakeRecoveryJournal(
    val records: List<SensorPacketIngressRecord>,
    private val mark: suspend FakeRecoveryJournal.(SensorPacketIngressOutcomeRecord) ->
        SensorPacketIngressMarkHandledResult = { outcome ->
            outcomes += outcome
            SensorPacketIngressMarkHandledResult.MarkedHandled
        },
) : SensorPacketIngressJournal {
    val outcomes = mutableListOf<SensorPacketIngressOutcomeRecord>()

    override suspend fun append(record: SensorPacketIngressRecord) =
        SensorPacketIngressAppendResult.Appended

    override suspend fun pending(sensorId: String, canonicalMac: String) = records

    override suspend fun markHandled(record: SensorPacketIngressOutcomeRecord) = mark(record)
}

private class FakeCommittedIngressReader(
    private val results: Map<String, CommittedSensorIngressReadResult> = emptyMap(),
) : CommittedSensorIngressReader {
    override suspend fun read(
        ingress: SensorPacketIngressRecord,
    ): CommittedSensorIngressReadResult = results[ingress.ingressId]
        ?: CommittedSensorIngressReadResult.Exact(emptyList())
}

private fun ByteArray.sha256ForRecoveryTest(): String = MessageDigest.getInstance("SHA-256")
    .digest(this)
    .joinToString(separator = "") { byte -> "%02x".format(byte) }
