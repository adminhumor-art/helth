package com.sladkaya.sensor.sibionics

import com.sladkaya.core.model.ReadingQuality
import com.sladkaya.core.model.SensorFamily
import com.sladkaya.core.model.GlucoseReading
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class Gs1PacketProcessorTest {
    @Test
    fun approvedHistoryAdvancesBatchButOnlyValidProductValueLeavesTheProcessor() = runBlocking {
        val publication = Gs1ProductPublication(
            reading = GlucoseReading(
                eventId = "product-2",
                sensorId = "sensor-a",
                sensorFamily = SensorFamily.SIBIONICS_GS1,
                sensorTimeEpochMs = 1_700_000_120_000L,
                phoneTimeEpochMs = 1_700_000_121_000L,
                glucoseMgDl = 108,
                trendMgDlPerMinute = 0.0,
                quality = ReadingQuality.VALID,
                sequence = 2,
            ),
            approvalId = "ab".repeat(32),
            publicationBindingId = "cd".repeat(32),
        )
        val core = ScriptedGs1SampleProcessor(
            processResults = ArrayDeque(
                listOf(
                    Gs1ProcessingResult.ApprovedCheckpointOnly(
                        sequence = 1,
                        quality = ReadingQuality.DEGRADED,
                    ),
                    Gs1ProcessingResult.ProductPublicationReady(publication),
                ),
            ),
        )
        val processor = processor(core, listOf(sample(1), sample(2)))

        val result = processor.ingest(byteArrayOf(1), RECEIVED_AT)
            as Gs1PacketProcessingResult.Completed

        assertEquals(listOf(1, 2), result.committedSamples.map { it.index })
        assertTrue(result.diagnostics.isEmpty())
        assertEquals(listOf(publication), result.publications)
    }

    @Test
    fun verifiedEmptyEnvelopeIsTransportProgressWithoutCoreOrMedicalData() = runBlocking {
        val core = ScriptedGs1SampleProcessor()
        val processor = processor(core, emptyList())

        val result = processor.ingest(byteArrayOf(1), RECEIVED_AT)
            as Gs1PacketProcessingResult.Completed

        assertTrue(result.validatedTransportEnvelope)
        assertTrue(result.committedSamples.isEmpty())
        assertTrue(result.diagnostics.isEmpty())
        assertTrue(core.processedSamples.isEmpty())
    }

    @Test
    fun v115HistoryToCurrentBatchUsesExactAddTimeClampInsteadOfInventingMinuteSpacing() = runBlocking {
        val receivedAt = 1_700_000_000_999L
        val samples = listOf(
            DecodedGs1RawSample(
                index = 1,
                sensorTimeEpochSeconds = 1_699_999_970L,
                current = 50,
                temperature = 321,
                reindex = 1,
                sensorTimeWasClamped = false,
                addTimeSeconds = 30,
            ),
            DecodedGs1RawSample(
                index = 2,
                sensorTimeEpochSeconds = 1_700_000_000L,
                current = 51,
                temperature = 322,
                reindex = 0,
                sensorTimeWasClamped = true,
                addTimeSeconds = 30,
            ),
        )
        val core = ScriptedGs1SampleProcessor(
            processResults = ArrayDeque(listOf(diagnostic(1), diagnostic(2))),
        )
        val processor = processor(core, samples, wireProfile = Gs1WireProfile.V115)

        val result = processor.ingest(byteArrayOf(1), receivedAt)
            as Gs1PacketProcessingResult.Completed

        assertEquals(listOf(1, 2), result.committedSamples.map { it.index })
        assertEquals(
            listOf(1_699_999_970L, 1_700_000_000L),
            core.processedSamples.map { it.sensorTimeEpochSeconds },
        )
        assertEquals(listOf(false, true), core.processedSamples.map { it.sensorTimeWasClamped })
        assertEquals(listOf(30, 30), core.processedSamples.map { it.addTimeSeconds })
    }

    @Test
    fun v115DecreasingTimeIsRejectedWithItsActualNondecreasingContract() = runBlocking {
        val receivedAt = 1_700_000_000_999L
        val samples = listOf(
            DecodedGs1RawSample(
                index = 1,
                sensorTimeEpochSeconds = 1_700_000_000L,
                current = 50,
                temperature = 321,
                reindex = 1,
                addTimeSeconds = 60,
            ),
            DecodedGs1RawSample(
                index = 2,
                sensorTimeEpochSeconds = 1_699_999_940L,
                current = 51,
                temperature = 322,
                reindex = 1,
                addTimeSeconds = 0,
            ),
        )
        val core = ScriptedGs1SampleProcessor()
        val processor = processor(core, samples, wireProfile = Gs1WireProfile.V115)

        val result = processor.ingest(byteArrayOf(1), receivedAt)
            as Gs1PacketProcessingResult.Rejected

        assertEquals("BATCH_SEQUENCE_INVALID", result.code)
        assertTrue(result.message.contains("nondecreasing", ignoreCase = true))
        assertTrue(core.processedSamples.isEmpty())
    }

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

        val result = processor.ingest(packet, RECEIVED_AT) as Gs1PacketProcessingResult.Completed

        assertEquals(listOf(1, 2), core.processedSamples.map { it.index })
        assertEquals(listOf(TEST_INGRESS_ID, TEST_INGRESS_ID), core.sourceIngressIds)
        assertEquals(listOf(RECEIVED_AT, RECEIVED_AT), core.receivedAtValues)
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

        val result = processor.ingest(byteArrayOf(1), RECEIVED_AT) as Gs1PacketProcessingResult.Completed

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

        val result = processor.ingest(byteArrayOf(1), RECEIVED_AT) as Gs1PacketProcessingResult.Completed

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

        val uncertain = processor.ingest(byteArrayOf(7), RECEIVED_AT)
        val blocked = processor.ingest(byteArrayOf(8), RECEIVED_AT + 1)
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

        val result = processor.ingest(byteArrayOf(1), RECEIVED_AT) as Gs1PacketProcessingResult.Rejected

        assertEquals("NON_SEQUENTIAL_INDEX", result.code)
        assertEquals(listOf(1), core.processedSamples.map { it.index })
    }

    @Test
    fun packetThatFailsDualParserNeverReachesCore() = runBlocking {
        val core = ScriptedGs1SampleProcessor()
        val processor = Gs1PacketProcessor(
            core = core,
            decoder = Gs1PacketVerifier { _, _ ->
                Gs1VerifiedPacketResult.Failure(Gs1VerifiedPacketError.PARSER_PARITY_MISMATCH)
            },
            wireProfile = Gs1WireProfile.V120,
        )

        val result = processor.ingest(byteArrayOf(9), RECEIVED_AT)

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

        val result = processor.ingest(byteArrayOf(1), RECEIVED_AT) as Gs1PacketProcessingResult.Rejected

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
            decoder = Gs1PacketVerifier { _, _ ->
                Gs1VerifiedPacketResult.Success(
                    samples = listOf(sample(41)),
                    nativeRecords = emptyList(),
                    decrypted = true,
                )
            },
            initialExpectedIndex = 41,
            wireProfile = Gs1WireProfile.V120,
        )

        val result = processor.ingest(byteArrayOf(1), RECEIVED_AT) as Gs1PacketProcessingResult.Completed

        assertEquals(listOf(41), result.committedSamples.map { it.index })
        assertEquals(listOf(41), core.processedSamples.map { it.index })
    }

    @Test
    fun recoveryCanSkipOnlyAnExternallyVerifiedPrefixAndCommitTheOriginalIngressSuffix() =
        runBlocking {
            val core = ScriptedGs1SampleProcessor(
                processResults = ArrayDeque(listOf(diagnostic(11))),
            )
            val processor = Gs1PacketProcessor(
                core = core,
                decoder = Gs1PacketVerifier { _, _ ->
                    Gs1VerifiedPacketResult.Success(
                        samples = listOf(sample(9), sample(10), sample(11)),
                        nativeRecords = emptyList(),
                        decrypted = true,
                    )
                },
                initialExpectedIndex = 11,
                wireProfile = Gs1WireProfile.V120,
            )

            val result = processor.ingest(
                sourceIngressId = TEST_INGRESS_ID,
                encryptedPacket = byteArrayOf(1),
                receivedAtEpochMs = RECEIVED_AT,
                verifiedCommittedPrefixSampleCount = 2,
            ) as Gs1PacketProcessingResult.Completed

            assertEquals(listOf(11), core.processedSamples.map { it.index })
            assertEquals(listOf(11), result.committedSamples.map { it.index })
            assertEquals(listOf(TEST_INGRESS_ID), core.sourceIngressIds)
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

        val result = processor.ingest(byteArrayOf(1), RECEIVED_AT) as Gs1PacketProcessingResult.StorageConflict

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

        val result = processor.ingest(byteArrayOf(1), RECEIVED_AT) as Gs1PacketProcessingResult.StorageConflict

        assertEquals(listOf(1), result.committedSamples.map { it.index })
        assertEquals(listOf("INVALID_GLUCOSE"), result.committedIssues.map { it.code })
    }

    @Test
    fun terminalCommittedProductErrorReturnsImmediatelyWithItsCommittedPrefix() = runBlocking {
        val publication = Gs1ProductPublication(
            reading = GlucoseReading(
                eventId = "product-1",
                sensorId = "sensor-a",
                sensorFamily = SensorFamily.SIBIONICS_GS1,
                sensorTimeEpochMs = 1_700_000_060_000L,
                phoneTimeEpochMs = 1_700_000_061_000L,
                glucoseMgDl = 108,
                trendMgDlPerMinute = 0.0,
                quality = ReadingQuality.VALID,
                sequence = 1,
            ),
            approvalId = "ab".repeat(32),
            publicationBindingId = "cd".repeat(32),
        )
        val core = ScriptedGs1SampleProcessor(
            processResults = ArrayDeque(
                listOf(
                    Gs1ProcessingResult.ProductPublicationReady(publication),
                    Gs1ProcessingResult.Rejected(
                        code = "INVALID_GLUCOSE",
                        message = "approved checkpoint persisted",
                        checkpointCommitted = true,
                        terminalAfterCommit = true,
                    ),
                    diagnostic(3),
                ),
            ),
        )
        val processor = processor(core, listOf(sample(1), sample(2), sample(3)))

        val result = processor.ingest(byteArrayOf(1), RECEIVED_AT)
            as Gs1PacketProcessingResult.Rejected

        assertEquals("INVALID_GLUCOSE", result.code)
        assertEquals(listOf(1, 2), result.committedSamples.map { it.index })
        assertEquals(listOf(publication), result.publications)
        assertEquals(listOf(2), result.committedIssues.map { it.sequence })
        assertEquals(listOf(1, 2), core.processedSamples.map { it.index })
    }

    private fun processor(
        core: ScriptedGs1SampleProcessor,
        samples: List<DecodedGs1RawSample>,
        wireProfile: Gs1WireProfile = Gs1WireProfile.V120,
    ) = Gs1PacketProcessor(
        core = core,
        decoder = Gs1PacketVerifier { _, _ ->
            Gs1VerifiedPacketResult.Success(samples, nativeRecords = emptyList(), decrypted = true)
        },
        wireProfile = wireProfile,
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

    private companion object {
        const val RECEIVED_AT = 1_700_000_061_123L
        const val TEST_INGRESS_ID = "test-attempt:0"
    }
}

private class ScriptedGs1SampleProcessor(
    val processResults: ArrayDeque<Gs1ProcessingResult> = ArrayDeque(),
    private val retryResults: ArrayDeque<Gs1ProcessingResult> = ArrayDeque(),
) : Gs1SampleProcessor {
    val packets = mutableListOf<ByteArray>()
    val sourceIngressIds = mutableListOf<String>()
    val receivedAtValues = mutableListOf<Long>()
    val processedSamples = mutableListOf<DecodedGs1RawSample>()
    var retryCalls = 0

    override suspend fun process(
        sourceIngressId: String,
        encryptedPacket: ByteArray,
        sample: DecodedGs1RawSample,
        receivedAtEpochMs: Long,
    ): Gs1ProcessingResult {
        sourceIngressIds += sourceIngressId
        packets += encryptedPacket.copyOf()
        receivedAtValues += receivedAtEpochMs
        processedSamples += sample
        return processResults.removeFirst()
    }

    override suspend fun retryPendingCommit(): Gs1ProcessingResult {
        retryCalls += 1
        return retryResults.removeFirst()
    }
}

private suspend fun Gs1PacketProcessor.ingest(
    encryptedPacket: ByteArray,
    receivedAtEpochMs: Long,
): Gs1PacketProcessingResult = ingest(
    sourceIngressId = "test-attempt:0",
    encryptedPacket = encryptedPacket,
    receivedAtEpochMs = receivedAtEpochMs,
)
