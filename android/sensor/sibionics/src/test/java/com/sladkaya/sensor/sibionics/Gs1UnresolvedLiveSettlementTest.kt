package com.sladkaya.sensor.sibionics

import com.sladkaya.core.data.CommittedProductPublicationRecord
import com.sladkaya.core.data.CommittedSensorIngressReadResult
import com.sladkaya.core.data.CommittedSensorIngressReader
import com.sladkaya.core.data.CommittedSensorIngressSampleRecord
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
import com.sladkaya.core.sensor.SensorConfiguration
import java.security.MessageDigest
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class Gs1UnresolvedLiveSettlementTest {
    @Test
    fun v115DataIsRoomValidatedCursorConfirmedLocallyAckedThenMarkedHandled() = runBlocking {
        val ingress = ingress()
        val sample = sample()
        val publication = publication(ingress, sample)
        val order = mutableListOf<String>()
        val journal = SettlementJournal { outcome ->
            order += "outcome"
            outcomes += outcome
            SensorPacketIngressMarkHandledResult.MarkedHandled
        }
        val session = unresolvedSession().also { it.initial(ingress.bluetoothAddress) }
        val settler = Gs1UnresolvedLiveSettler(
            journal = journal,
            committedEventValidator = Gs1CommittedIngressEventValidator(
                CommittedSensorIngressReader {
                    order += "room"
                    CommittedSensorIngressReadResult.Exact(
                        listOf(committed(ingress, sample, publication)),
                    )
                },
            ),
        )
        val localAck = CompletableDeferred<Unit>()

        val settling = async {
            settler.settle(
                generation = 7L,
                session = session,
                ingress = ingress,
                result = completed(sample, publication),
            ) { roomEvent ->
                assertEquals(Gs1WireProfile.V115, session.wireProfile)
                order += "cursor"
                assertEquals(SessionAction.None, session.confirmDurablyCommitted(roomEvent.samples))
                localAck.await()
                order += "local"
                accepted()
            }
        }
        withTimeout(1_000L) {
            while ("cursor" !in order) kotlinx.coroutines.delay(1L)
        }
        assertTrue(journal.outcomes.isEmpty())

        localAck.complete(Unit)
        val result = settling.await() as Gs1UnresolvedLiveSettlementResult.Completed

        assertEquals(SessionAction.None, result.nextProtocolAction)
        assertEquals(listOf("room", "cursor", "local", "outcome"), order)
        assertEquals(SensorPacketIngressOutcomeStatus.CORE_COMMITTED, journal.outcomes.single().status)
        assertEquals(11, session.durableNextIndex)
    }

    @Test
    fun v115LocalEffectsFailureLeavesTheExactIngressPending() = runBlocking {
        val ingress = ingress()
        val sample = sample()
        val publication = publication(ingress, sample)
        val journal = SettlementJournal()
        val session = unresolvedSession().also { it.initial(ingress.bluetoothAddress) }
        val settler = settler(journal, ingress, sample, publication)

        val result = settler.settle(
            generation = 7L,
            session = session,
            ingress = ingress,
            result = completed(sample, publication),
        ) {
            Gs1GattDurableCommitResult.Rejected(
                code = "STORAGE_UNAVAILABLE",
                retryable = true,
            )
        }
            as Gs1UnresolvedLiveSettlementResult.Failed

        assertEquals("STORAGE_UNAVAILABLE", result.code)
        assertTrue(result.retryable)
        assertTrue(journal.outcomes.isEmpty())
    }

    @Test
    fun v115LocalEffectsFailurePreservesTypedRetryability() = runBlocking {
        val cases = listOf(
            Triple("STORAGE_UNAVAILABLE", true, "database unavailable"),
            Triple("LOCAL_EFFECTS_TIMEOUT", true, "local effects timed out"),
            Triple("STORAGE_CONFLICT", false, "cursor conflict"),
            Triple("APPLICATION_STOPPED", false, "application stopped"),
            Triple("DURABLE_CURSOR_REJECTED", false, "non-lineage commit"),
        )

        cases.forEach { (code, retryable, detail) ->
            val ingress = ingress()
            val sample = sample()
            val publication = publication(ingress, sample)
            val journal = SettlementJournal()
            val session = unresolvedSession().also { it.initial(ingress.bluetoothAddress) }

            val result = settler(journal, ingress, sample, publication).settle(
                generation = 7L,
                session = session,
                ingress = ingress,
                result = completed(sample, publication),
            ) {
                Gs1GattDurableCommitResult.Rejected(
                    code = code,
                    detail = detail,
                    retryable = retryable,
                )
            } as Gs1UnresolvedLiveSettlementResult.Failed

            assertEquals(code, result.code)
            assertEquals(detail, result.detail)
            assertEquals(retryable, result.retryable)
            assertTrue(journal.outcomes.isEmpty())
        }
    }

    @Test
    fun v115CancellationBeforeLocalAckLeavesTheExactIngressPending() = runBlocking {
        val ingress = ingress()
        val sample = sample()
        val publication = publication(ingress, sample)
        val journal = SettlementJournal()
        val session = unresolvedSession().also { it.initial(ingress.bluetoothAddress) }
        val settler = settler(journal, ingress, sample, publication)
        val neverAck = CompletableDeferred<Unit>()

        val settling = async {
            settler.settle(
                generation = 7L,
                session = session,
                ingress = ingress,
                result = completed(sample, publication),
            ) {
                neverAck.await()
                accepted()
            }
        }
        kotlinx.coroutines.delay(25L)
        settling.cancelAndJoin()

        assertTrue(journal.outcomes.isEmpty())
    }

    private fun settler(
        journal: SettlementJournal,
        ingress: SensorPacketIngressRecord,
        sample: DecodedGs1RawSample,
        publication: Gs1ProductPublication,
    ) = Gs1UnresolvedLiveSettler(
        journal = journal,
        committedEventValidator = Gs1CommittedIngressEventValidator(
            CommittedSensorIngressReader {
                CommittedSensorIngressReadResult.Exact(
                    listOf(committed(ingress, sample, publication)),
                )
            },
        ),
    )

    private fun completed(
        sample: DecodedGs1RawSample,
        publication: Gs1ProductPublication,
    ) = Gs1PacketProcessingResult.Completed(
        committedSamples = listOf(sample),
        publications = listOf(publication),
        resolvedWireProfile = Gs1WireProfile.V115,
    )

    private fun accepted() = Gs1GattDurableCommitResult.Accepted(
        Gs1GattCommittedPresentation(
            hasTransportProgress = true,
            hasFreshOutput = true,
            latestSequence = 10,
            latestQuality = ReadingQuality.VALID,
            issue = null,
        ),
    )

    private fun unresolvedSession() = SibionicsSession(
        family = SensorFamily.SIBIONICS_GS1,
        configuration = SensorConfiguration(
            sensorId = "sensor-a",
            bluetoothAddress = "AA:BB:CC:DD:EE:FF",
            protocolVariant = 0,
        ),
        initialNextIndex = 10,
        initialWireProfile = Gs1WireProfile.UNRESOLVED,
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
            publication.reading,
            publication.approvalId,
            publication.publicationBindingId,
        ),
    )

    private fun publication(
        ingress: SensorPacketIngressRecord,
        sample: DecodedGs1RawSample,
    ) = Gs1ProductPublication(
        reading = GlucoseReading(
            eventId = "event-10",
            sensorId = ingress.sensorId,
            sensorFamily = ingress.sensorFamily,
            sensorTimeEpochMs = sample.sensorTimeEpochSeconds * 1_000L,
            phoneTimeEpochMs = ingress.receivedAtEpochMs,
            glucoseMgDl = 104,
            trendMgDlPerMinute = 0.0,
            quality = ReadingQuality.VALID,
            sequence = sample.index.toLong(),
        ),
        approvalId = "ab".repeat(32),
        publicationBindingId = "cd".repeat(32),
    )

    private fun sample() = DecodedGs1RawSample(
        index = 10,
        sensorTimeEpochSeconds = 1_700_000_000L,
        current = 50,
        temperature = 320,
        reindex = 0,
        addTimeSeconds = 0,
    )

    private fun ingress(): SensorPacketIngressRecord {
        val packet = byteArrayOf(1, 2, 3)
        return SensorPacketIngressRecord(
            ingressId = "attempt-v115:0",
            sensorId = "sensor-a",
            sensorFamily = SensorFamily.SIBIONICS_GS1,
            bluetoothAddress = "AA:BB:CC:DD:EE:FF",
            attemptId = "attempt-v115",
            ordinal = 0,
            receivedAtEpochMs = 1_700_000_001_000L,
            encryptedPacket = packet,
            packetSha256 = MessageDigest.getInstance("SHA-256")
                .digest(packet)
                .joinToString("") { byte -> "%02x".format(byte) },
        )
    }
}

private class SettlementJournal(
    private val mark: suspend SettlementJournal.(SensorPacketIngressOutcomeRecord) ->
        SensorPacketIngressMarkHandledResult = { outcome ->
            outcomes += outcome
            SensorPacketIngressMarkHandledResult.MarkedHandled
        },
) : SensorPacketIngressJournal {
    val outcomes = mutableListOf<SensorPacketIngressOutcomeRecord>()

    override suspend fun append(record: SensorPacketIngressRecord) =
        SensorPacketIngressAppendResult.Appended

    override suspend fun pending(sensorId: String, canonicalMac: String) = emptyList<SensorPacketIngressRecord>()

    override suspend fun markHandled(record: SensorPacketIngressOutcomeRecord) = mark(record)
}
