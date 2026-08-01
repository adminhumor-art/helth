package com.sladkaya.sensor.sibionics

import com.sladkaya.core.model.ReadingQuality
import com.sladkaya.core.model.SensorFamily
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class Gs1PacketProcessorTest {
    @Test
    fun verifiedBatchIsCommittedInOrderAndReturnsOnlyDiagnostics() = runBlocking {
        val first = sample(1)
        val second = sample(2)
        val core = ScriptedGs1SampleProcessor(
            processResults = ArrayDeque(listOf(
                diagnostic(1),
                diagnostic(2),
            )),
        )
        val packet = byteArrayOf(4, 8, 15, 16, 23, 42)
        val processor = processor(core, listOf(first, second))

        val result = processor.ingest(packet) as Gs1PacketProcessingResult.Completed

        assertEquals(listOf(1, 2), core.processedSamples.map { it.index })
        core.packets.forEach { assertArrayEquals(packet, it) }
        assertEquals(listOf(1, 2), result.committedSamples.map { it.index })
        assertEquals(listOf(1L, 2L), result.diagnostics.map { it.sequence })
    }

    @Test
    fun committedDiagnosticErrorAdvancesCursorButRemainsExplicitlyVisible() = runBlocking {
        val core = ScriptedGs1SampleProcessor(
            processResults = ArrayDeque(listOf(
                Gs1ProcessingResult.Rejected(
                    code = "INVALID_GLUCOSE",
                    message = "diagnostic persisted",
                    checkpointCommitted = true,
                ),
                diagnostic(2),
            )),
        )
        val processor = processor(core, listOf(sample(1), sample(2)))

        val result = processor.ingest(byteArrayOf(1)) as Gs1PacketProcessingResult.Completed

        assertEquals(listOf(1, 2), result.committedSamples.map { it.index })
        assertEquals(listOf(2L), result.diagnostics.map { it.sequence })
        assertEquals(listOf(1), result.committedIssues.map { it.sequence })
        assertEquals(listOf("INVALID_GLUCOSE"), result.committedIssues.map { it.code })
        assertEquals(listOf("diagnostic persisted"), result.committedIssues.map { it.message })
    }

    @Test
    fun diagnosticCandidateAdvancesBatchButCannotEnterProductReadings() = runBlocking {
        val core = ScriptedGs1SampleProcessor(
            processResults = ArrayDeque(
                listOf(
                    Gs1ProcessingResult.Diagnostic(
                        Gs1DiagnosticReading(
                            eventId = "diagnostic-1",
                            sensorId = "sensor-a",
                            sensorFamily = SensorFamily.SIBIONICS_GS1,
                            sensorTimeEpochMs = 1_700_000_060_000L,
                            phoneTimeEpochMs = 1_700_000_061_000L,
                            glucoseMgDl = 108,
                            trendMgDlPerMinute = -2.6,
                            quality = ReadingQuality.VALID,
                            sequence = 1,
                        ),
                    ),
                ),
            ),
        )
        val processor = processor(core, listOf(sample(1)))

        val result = processor.ingest(byteArrayOf(1)) as Gs1PacketProcessingResult.Completed

        assertEquals(listOf(1), result.committedSamples.map { it.index })
        assertEquals(listOf(108), result.diagnostics.map { it.glucoseMgDl })
    }

    @Test
    fun uncertainCommitBlocksNewPacketsAndExactRetryContinuesRemainingBatch() = runBlocking {
        val core = ScriptedGs1SampleProcessor(
            processResults = ArrayDeque(listOf(Gs1ProcessingResult.PersistenceUnavailable("lost response"))),
            retryResults = ArrayDeque(listOf(diagnostic(1))),
        )
        core.processResults += diagnostic(2)
        val processor = processor(core, listOf(sample(1), sample(2)))

        val uncertain = processor.ingest(byteArrayOf(7))
        val blocked = processor.ingest(byteArrayOf(8))
        val recovered = processor.retryPending() as Gs1PacketProcessingResult.Completed

        assertTrue(uncertain is Gs1PacketProcessingResult.PersistenceUnavailable)
        assertTrue(blocked is Gs1PacketProcessingResult.PersistenceUnavailable)
        assertEquals(listOf(1, 2), core.processedSamples.map { it.index })
        assertEquals(1, core.retryCalls)
        assertEquals(listOf(1L, 2L), recovered.diagnostics.map { it.sequence })
    }

    @Test
    fun nonCommittedRejectionNeverSkipsToLaterSample() = runBlocking {
        val core = ScriptedGs1SampleProcessor(
            processResults = ArrayDeque(listOf(
                Gs1ProcessingResult.Rejected("NON_SEQUENTIAL_INDEX", "gap"),
                diagnostic(2),
            )),
        )
        val processor = processor(core, listOf(sample(1), sample(2)))

        val result = processor.ingest(byteArrayOf(1)) as Gs1PacketProcessingResult.Rejected

        assertEquals("NON_SEQUENTIAL_INDEX", result.code)
        assertEquals(listOf(1), core.processedSamples.map { it.index })
    }

    @Test
    fun packetThatFailsDualParserNeverReachesCore() = runBlocking {
        val core = ScriptedGs1SampleProcessor()
        val processor = Gs1PacketProcessor(
            core = core,
            decoder = Gs1PacketVerifier {
                Gs1VerifiedPacketResult.Failure(Gs1VerifiedPacketError.PARSER_PARITY_MISMATCH)
            },
        )

        val result = processor.ingest(byteArrayOf(9))

        assertTrue(result is Gs1PacketProcessingResult.InvalidPacket)
        assertTrue(core.processedSamples.isEmpty())
    }

    @Test
    fun malformedLaterSampleRejectsWholeBatchBeforeNativeOrStorageCanAdvance() = runBlocking {
        val core = ScriptedGs1SampleProcessor(
            processResults = ArrayDeque(
                listOf(
                    diagnostic(1),
                    diagnostic(3),
                ),
            ),
        )
        val processor = processor(core, listOf(sample(1), sample(3)))

        val result = processor.ingest(byteArrayOf(1)) as Gs1PacketProcessingResult.Rejected

        assertEquals("BATCH_SEQUENCE_INVALID", result.code)
        assertTrue(core.processedSamples.isEmpty())
        assertTrue(result.committedSamples.isEmpty())
    }

    @Test
    fun restoredSessionAcceptsOnlyItsPersistedNextIndex() = runBlocking {
        val core = ScriptedGs1SampleProcessor(
            processResults = ArrayDeque(listOf(diagnostic(41))),
        )
        val processor = Gs1PacketProcessor(
            core = core,
            decoder = Gs1PacketVerifier {
                Gs1VerifiedPacketResult.Success(
                    samples = listOf(sample(41)),
                    nativeRecords = emptyList(),
                    decrypted = true,
                )
            },
            initialExpectedIndex = 41,
        )

        val result = processor.ingest(byteArrayOf(1)) as Gs1PacketProcessingResult.Completed

        assertEquals(listOf(41), result.committedSamples.map { it.index })
        assertEquals(listOf(41), core.processedSamples.map { it.index })
    }

    @Test
    fun terminalFailureReturnsTheAlreadyCommittedDiagnosticPrefix() = runBlocking {
        val core = ScriptedGs1SampleProcessor(
            processResults = ArrayDeque(
                listOf(
                    diagnostic(1),
                    Gs1ProcessingResult.StorageConflict("checkpoint conflict"),
                ),
            ),
        )
        val processor = processor(core, listOf(sample(1), sample(2)))

        val result = processor.ingest(byteArrayOf(1)) as Gs1PacketProcessingResult.StorageConflict

        assertEquals(listOf(1), result.committedSamples.map { it.index })
        assertEquals(listOf(1L), result.diagnostics.map { it.sequence })
    }

    @Test
    fun terminalFailureKeepsAnyCommittedDiagnosticIssueFromItsPrefix() = runBlocking {
        val core = ScriptedGs1SampleProcessor(
            processResults = ArrayDeque(
                listOf(
                    Gs1ProcessingResult.Rejected(
                        code = "INVALID_GLUCOSE",
                        message = "diagnostic persisted",
                        checkpointCommitted = true,
                    ),
                    Gs1ProcessingResult.StorageConflict("checkpoint conflict"),
                ),
            ),
        )
        val processor = processor(core, listOf(sample(1), sample(2)))

        val result = processor.ingest(byteArrayOf(1)) as Gs1PacketProcessingResult.StorageConflict

        assertEquals(listOf(1), result.committedSamples.map { it.index })
        assertEquals(listOf("INVALID_GLUCOSE"), result.committedIssues.map { it.code })
    }

    private fun processor(
        core: ScriptedGs1SampleProcessor,
        samples: List<DecodedGs1RawSample>,
    ) = Gs1PacketProcessor(
        core = core,
        decoder = Gs1PacketVerifier {
            Gs1VerifiedPacketResult.Success(samples, nativeRecords = emptyList(), decrypted = true)
        },
    )

    private fun sample(index: Int) = DecodedGs1RawSample(
        index = index,
        sensorTimeEpochSeconds = 1_700_000_000L + index * 60L,
        current = 50,
        temperature = 321,
        reindex = 0,
    )

    private fun diagnostic(index: Int) = Gs1ProcessingResult.Diagnostic(
        Gs1DiagnosticReading(
            eventId = "diagnostic-$index",
            sensorId = "sensor-a",
            sensorFamily = SensorFamily.SIBIONICS_GS1,
            sensorTimeEpochMs = (1_700_000_000L + index * 60L) * 1_000L,
            phoneTimeEpochMs = (1_700_000_001L + index * 60L) * 1_000L,
            glucoseMgDl = 100 + index,
            trendMgDlPerMinute = 0.0,
            quality = ReadingQuality.VALID,
            sequence = index.toLong(),
        ),
    )
}

private class ScriptedGs1SampleProcessor(
    val processResults: ArrayDeque<Gs1ProcessingResult> = ArrayDeque(),
    private val retryResults: ArrayDeque<Gs1ProcessingResult> = ArrayDeque(),
) : Gs1SampleProcessor {
    val packets = mutableListOf<ByteArray>()
    val processedSamples = mutableListOf<DecodedGs1RawSample>()
    var retryCalls = 0

    override suspend fun process(
        encryptedPacket: ByteArray,
        sample: DecodedGs1RawSample,
    ): Gs1ProcessingResult {
        packets += encryptedPacket.copyOf()
        processedSamples += sample
        return processResults.removeFirst()
    }

    override suspend fun retryPendingCommit(): Gs1ProcessingResult {
        retryCalls += 1
        return retryResults.removeFirst()
    }
}
