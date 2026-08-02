package com.sladkaya.sensor.sibionics

import com.sladkaya.core.data.CommittedProductPublicationRecord
import com.sladkaya.core.data.CommittedSensorIngressReadResult
import com.sladkaya.core.data.CommittedSensorIngressReader
import com.sladkaya.core.data.CommittedSensorIngressSampleRecord
import com.sladkaya.core.data.RawSensorSampleRecord
import com.sladkaya.core.data.SensorPacketIngressRecord
import com.sladkaya.core.model.GlucoseReading
import com.sladkaya.core.model.ReadingQuality
import com.sladkaya.core.model.SensorFamily
import java.security.MessageDigest
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class Gs1CommittedIngressEventValidatorTest {
    @Test
    fun inMemoryGlucoseCannotReplaceADifferentRoomPublication() = runBlocking {
        val ingress = ingress()
        val sample = sample(10)
        val memory = publication(ingress, sample, glucose = 104)
        val room = publication(ingress, sample, glucose = 55)
        val validator = Gs1CommittedIngressEventValidator(
            CommittedSensorIngressReader {
                CommittedSensorIngressReadResult.Exact(
                    listOf(committed(ingress, sample, room)),
                )
            },
        )

        val result = validator.validate(event(ingress, sample, memory))
            as Gs1CommittedIngressEventValidation.Failed

        assertEquals("COMMITTED_ROOM_PUBLICATION_MISMATCH", result.code)
    }

    @Test
    fun missingRoomLineageBlocksTheBatchBeforeLocalEffects() = runBlocking {
        val ingress = ingress()
        val sample = sample(10)
        val validator = Gs1CommittedIngressEventValidator(
            CommittedSensorIngressReader {
                CommittedSensorIngressReadResult.Mismatch("outbox missing")
            },
        )

        val result = validator.validate(event(ingress, sample, publication(ingress, sample, 104)))
            as Gs1CommittedIngressEventValidation.Failed

        assertEquals("COMMITTED_ROOM_EVIDENCE_MISMATCH", result.code)
    }

    @Test
    fun exactRoomRowsBecomeTheOnlyAcceptedPublicationSource() = runBlocking {
        val ingress = ingress()
        val sample = sample(10)
        val room = publication(ingress, sample, glucose = 104)
        val validator = Gs1CommittedIngressEventValidator(
            CommittedSensorIngressReader {
                CommittedSensorIngressReadResult.Exact(
                    listOf(committed(ingress, sample, room)),
                )
            },
        )

        val result = validator.validate(event(ingress, sample, room))
            as Gs1CommittedIngressEventValidation.Accepted

        assertEquals(listOf(room.reading), result.event.publications.map { it.reading })
        assertEquals(listOf(room.approvalId), result.event.publications.map { it.approvalId })
        assertEquals(ingress, result.event.ingress)
    }

    @Test
    fun validatedEmptyTransportEnvelopeDoesNotRequireNonexistentRawRows() = runBlocking {
        val ingress = ingress()
        var reads = 0
        val validator = Gs1CommittedIngressEventValidator(
            CommittedSensorIngressReader {
                reads += 1
                error("empty transport envelope must not query medical rows")
            },
        )

        val result = validator.validate(
            Gs1DiagnosticRuntimeEvent.Committed(
                generation = 1L,
                ingress = ingress,
                samples = emptyList(),
                diagnostics = emptyList(),
                validatedTransportEnvelope = true,
            ),
        )

        assertTrue(result is Gs1CommittedIngressEventValidation.Accepted)
        assertEquals(0, reads)
    }

    @Test
    fun suffixSettlementSelectsOnlySuffixRoomPublicationAndNeverRepeatsCoveredPrefix() =
        runBlocking {
            val ingress = ingress()
            val samples = listOf(sample(9), sample(10), sample(11))
            val publications = samples.map { value -> publication(ingress, value, 100 + value.index) }
            val validator = Gs1CommittedIngressEventValidator(
                CommittedSensorIngressReader {
                    CommittedSensorIngressReadResult.Exact(
                        samples.zip(publications).map { (value, publication) ->
                            committed(ingress, value, publication)
                        },
                    )
                },
            )
            val suffix = samples.last()
            val suffixPublication = publications.last()

            val result = validator.validate(event(ingress, suffix, suffixPublication))
                as Gs1CommittedIngressEventValidation.Accepted

            assertEquals(listOf(11), result.event.samples.map { it.index })
            assertEquals(listOf(11L), result.event.publications.map { it.reading.sequence })
        }

    private fun event(
        ingress: SensorPacketIngressRecord,
        sample: DecodedGs1RawSample,
        publication: Gs1ProductPublication,
    ) = Gs1DiagnosticRuntimeEvent.Committed(
        generation = 1L,
        ingress = ingress,
        samples = listOf(sample),
        diagnostics = emptyList(),
        publications = listOf(publication),
    )

    private fun committed(
        ingress: SensorPacketIngressRecord,
        sample: DecodedGs1RawSample,
        publication: Gs1ProductPublication,
    ) = CommittedSensorIngressSampleRecord(
        raw = RawSensorSampleRecord(
            eventId = publication.reading.eventId,
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
        algorithmErrorCode = null,
        productPublication = CommittedProductPublicationRecord(
            reading = publication.reading,
            approvalId = publication.approvalId,
            publicationBindingId = publication.publicationBindingId,
        ),
    )

    private fun publication(
        ingress: SensorPacketIngressRecord,
        sample: DecodedGs1RawSample,
        glucose: Int,
    ) = Gs1ProductPublication(
        reading = GlucoseReading(
            eventId = "event-${sample.index}",
            sensorId = ingress.sensorId,
            sensorFamily = ingress.sensorFamily,
            sensorTimeEpochMs = sample.sensorTimeEpochSeconds * 1_000L,
            phoneTimeEpochMs = ingress.receivedAtEpochMs,
            glucoseMgDl = glucose,
            trendMgDlPerMinute = 0.0,
            quality = ReadingQuality.VALID,
            sequence = sample.index.toLong(),
        ),
        approvalId = "ab".repeat(32),
        publicationBindingId = "cd".repeat(32),
    )

    private fun sample(index: Int) = DecodedGs1RawSample(
        index = index,
        sensorTimeEpochSeconds = 1_700_000_000L + index * 60L,
        current = 50,
        temperature = 320,
        reindex = 0,
    )

    private fun ingress(): SensorPacketIngressRecord {
        val packet = byteArrayOf(1, 2, 3)
        return SensorPacketIngressRecord(
            ingressId = "attempt-a:0",
            sensorId = "sensor-a",
            sensorFamily = SensorFamily.SIBIONICS_GS1,
            bluetoothAddress = "AA:BB:CC:DD:EE:FF",
            attemptId = "attempt-a",
            ordinal = 0,
            receivedAtEpochMs = 1_700_000_000_000L,
            encryptedPacket = packet,
            packetSha256 = MessageDigest.getInstance("SHA-256")
                .digest(packet)
                .joinToString("") { byte -> "%02x".format(byte) },
        )
    }
}
