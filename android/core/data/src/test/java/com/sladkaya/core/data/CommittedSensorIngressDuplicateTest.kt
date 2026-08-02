package com.sladkaya.core.data

import com.sladkaya.core.model.SensorFamily
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CommittedSensorIngressDuplicateTest {
    @Test
    fun handledCoverageReadsOnlyAnExactContiguousDurablyAcknowledgedRange() = runBlocking {
        val fixture = fixture()

        val result = fixture.dao.readHandledCoverage(
            CommittedSensorCoverageRequest(
                sensorId = "sensor-a",
                sensorFamily = SensorFamily.SIBIONICS_GS1,
                bluetoothAddress = "AA:BB:CC:DD:EE:FF",
                firstSequence = 8,
                lastSequence = 9,
            ),
        ) as CommittedSensorCoverageReadResult.Exact

        assertEquals(listOf(8, 9), result.samples.map { it.raw.sequence })
    }

    @Test
    fun pendingOrIncompleteRangeCannotProveHandledCoverage() = runBlocking {
        val pending = fixture(outcomes = emptyMap())
        val incompleteBase = fixture()
        val incompleteDao = RecordingCommittedIngressDao(
            ingress = incompleteBase.dao.ingress,
            raw = incompleteBase.dao.raw.mapValues { (_, rows) -> rows.take(1) },
            results = incompleteBase.dao.results,
            outcomes = incompleteBase.dao.outcomes,
        )
        val request = CommittedSensorCoverageRequest(
            sensorId = "sensor-a",
            sensorFamily = SensorFamily.SIBIONICS_GS1,
            bluetoothAddress = "AA:BB:CC:DD:EE:FF",
            firstSequence = 8,
            lastSequence = 9,
        )

        assertTrue(pending.dao.readHandledCoverage(request) is CommittedSensorCoverageReadResult.Mismatch)
        assertTrue(incompleteDao.readHandledCoverage(request) is CommittedSensorCoverageReadResult.Mismatch)
    }

    @Test
    fun exactEarlierHandledIngressProducesDuplicateProof() = runBlocking {
        val fixture = fixture()

        val result = fixture.dao.readExact(fixture.current.toRecord())
            as CommittedSensorIngressReadResult.HandledDuplicate

        assertEquals(fixture.earlier.ingressId, result.sourceIngress.ingressId)
        assertEquals(listOf(8, 9), result.samples.map { it.raw.sequence })
        assertEquals(SensorPacketIngressOutcomeStatus.CORE_COMMITTED, result.outcomeStatus)
    }

    @Test
    fun earlierPendingIngressCannotProveDuplicateDelivery() = runBlocking {
        val fixture = fixture(outcomes = emptyMap())

        assertTrue(
            fixture.dao.readExact(fixture.current.toRecord()) is
                CommittedSensorIngressReadResult.Mismatch,
        )
    }

    @Test
    fun tamperedHandledOutcomeCannotProveDuplicateDelivery() = runBlocking {
        val base = fixture()
        val valid = checkNotNull(base.dao.outcomes[base.earlier.ingressId])
        val tampered = listOf(
            valid.copy(handledAtEpochMs = valid.handledAtEpochMs + 1),
            valid.copy(detail = "unexpected-detail"),
        )

        tampered.forEach { outcome ->
            val dao = RecordingCommittedIngressDao(
                ingress = listOf(base.earlier, base.current),
                raw = base.dao.raw,
                results = base.dao.results,
                outcomes = mapOf(base.earlier.ingressId to outcome),
            )

            assertTrue(
                dao.readExact(base.current.toRecord()) is
                    CommittedSensorIngressReadResult.Mismatch,
            )
        }
    }

    @Test
    fun twoEarlierCandidatesAreAmbiguousEvenWhenBothWereHandled() = runBlocking {
        val base = fixture()
        val second = base.earlier.copy(
            ingressId = "attempt-b:0",
            attemptId = "attempt-b",
            receivedAtEpochMs = base.earlier.receivedAtEpochMs + 1,
        )
        val dao = RecordingCommittedIngressDao(
            ingress = listOf(base.earlier, second, base.current),
            raw = base.dao.raw + (second.ingressId to base.samples.map { raw ->
                raw.copy(
                    eventId = "second-${raw.sequence}",
                    sourceIngressId = second.ingressId,
                    phoneTimeEpochMs = second.receivedAtEpochMs,
                )
            }),
            results = base.dao.results + base.samples.associate { raw ->
                "second-${raw.sequence}" to result("second-${raw.sequence}", raw.sequence)
            },
            outcomes = base.dao.outcomes + (
                second.ingressId to handled(second, SensorPacketIngressOutcomeStatus.ALREADY_COVERED)
                ),
        )

        assertTrue(
            dao.readExact(base.current.toRecord()) is CommittedSensorIngressReadResult.Mismatch,
        )
    }

    @Test
    fun missingOrCorruptedEarlierResultLineageCannotProveDuplicate() = runBlocking {
        val base = fixture()
        val invalidResults = listOf(
            emptyMap(),
            base.dao.results.toMutableMap().apply {
                this[base.samples.first().eventId] = result(
                    eventId = base.samples.first().eventId,
                    sequence = 99,
                )
            },
        )

        invalidResults.forEach { results ->
            val dao = RecordingCommittedIngressDao(
                ingress = listOf(base.earlier, base.current),
                raw = base.dao.raw,
                results = results,
                outcomes = base.dao.outcomes,
            )

            assertTrue(
                dao.readExact(base.current.toRecord()) is
                    CommittedSensorIngressReadResult.Mismatch,
            )
        }
    }

    @Test
    fun sameHashWithDifferentBytesCannotProveDuplicate() = runBlocking {
        val base = fixture()
        val differentBytes = base.earlier.copy(
            encryptedPacket = byteArrayOf(9, 9, 9, 9),
            packetSha256 = base.current.packetSha256,
        )
        val dao = RecordingCommittedIngressDao(
            ingress = listOf(differentBytes, base.current),
            raw = base.dao.raw,
            results = base.dao.results,
            outcomes = base.dao.outcomes,
        )

        assertTrue(
            dao.readExact(base.current.toRecord()) is CommittedSensorIngressReadResult.Mismatch,
        )
    }

    @Test
    fun anotherSensorOrMacCannotProveDuplicate() = runBlocking {
        val base = fixture()
        val wrongCandidates = listOf(
            base.earlier.copy(sensorId = "sensor-b"),
            base.earlier.copy(bluetoothAddress = "11:22:33:44:55:66"),
        )

        wrongCandidates.forEach { wrong ->
            val dao = RecordingCommittedIngressDao(
                ingress = listOf(wrong, base.current),
                raw = base.dao.raw,
                results = base.dao.results,
                outcomes = base.dao.outcomes,
            )
            assertTrue(
                dao.readExact(base.current.toRecord()) is
                    CommittedSensorIngressReadResult.Mismatch,
            )
        }
    }

    @Test
    fun sameBytesAndReceiveTimeRemainExactByIngressFkThenProveTheLaterDuplicate() = runBlocking {
        val base = fixture()
        val currentAtSameMillis = base.current.copy(
            receivedAtEpochMs = base.earlier.receivedAtEpochMs,
        )
        val dao = RecordingCommittedIngressDao(
            ingress = listOf(base.earlier, currentAtSameMillis),
            raw = base.dao.raw,
            results = base.dao.results,
            outcomes = base.dao.outcomes,
        )

        val origin = dao.readExact(base.earlier.toRecord())
        val duplicate = dao.readExact(currentAtSameMillis.toRecord())

        assertTrue(origin is CommittedSensorIngressReadResult.Exact)
        assertTrue(duplicate is CommittedSensorIngressReadResult.HandledDuplicate)
        assertEquals(
            base.earlier.ingressId,
            (duplicate as CommittedSensorIngressReadResult.HandledDuplicate)
                .sourceIngress.ingressId,
        )
    }

    @Test
    fun duplicateChainUsesTheSingleRawBackedOriginAndIgnoresHandledLink() = runBlocking {
        val base = fixture()
        val handledLink = base.earlier.copy(
            ingressId = "attempt-b:0",
            attemptId = "attempt-b",
            receivedAtEpochMs = base.earlier.receivedAtEpochMs + 1,
        )
        val dao = RecordingCommittedIngressDao(
            ingress = listOf(base.earlier, handledLink, base.current),
            raw = base.dao.raw,
            results = base.dao.results,
            outcomes = base.dao.outcomes + (
                handledLink.ingressId to handled(
                    handledLink,
                    SensorPacketIngressOutcomeStatus.ALREADY_COVERED,
                )
                ),
        )

        val result = dao.readExact(base.current.toRecord())
            as CommittedSensorIngressReadResult.HandledDuplicate

        assertEquals(base.earlier.ingressId, result.sourceIngress.ingressId)
        assertEquals(SensorPacketIngressOutcomeStatus.CORE_COMMITTED, result.outcomeStatus)
    }

    @Test
    fun durableOutcomeProvesOriginEvenWhenAttemptIdSortsAfterCurrentAtSameMillis() = runBlocking {
        val base = fixture()
        val origin = base.earlier.copy(
            ingressId = "attempt-z:0",
            attemptId = "attempt-z",
        )
        val current = base.current.copy(
            ingressId = "attempt-a:0",
            attemptId = "attempt-a",
            receivedAtEpochMs = origin.receivedAtEpochMs,
        )
        val originRows = base.samples.map { raw ->
            raw.copy(sourceIngressId = origin.ingressId)
        }
        val dao = RecordingCommittedIngressDao(
            ingress = listOf(origin, current),
            raw = mapOf(origin.ingressId to originRows),
            results = base.dao.results,
            outcomes = mapOf(
                origin.ingressId to handled(
                    origin,
                    SensorPacketIngressOutcomeStatus.CORE_COMMITTED,
                ),
            ),
        )

        val result = dao.readExact(current.toRecord())
            as CommittedSensorIngressReadResult.HandledDuplicate

        assertEquals(origin.ingressId, result.sourceIngress.ingressId)
    }

    @Test
    fun currentIngressRowsKeepTheExactCurrentPath() = runBlocking {
        val base = fixture()
        val currentRows = base.samples.map { raw ->
            raw.copy(
                eventId = "current-${raw.sequence}",
                sourceIngressId = base.current.ingressId,
                phoneTimeEpochMs = base.current.receivedAtEpochMs,
            )
        }
        val dao = RecordingCommittedIngressDao(
            ingress = listOf(base.earlier, base.current),
            raw = base.dao.raw + (base.current.ingressId to currentRows),
            results = base.dao.results + currentRows.associate { raw ->
                raw.eventId to result(raw.eventId, raw.sequence)
            },
            outcomes = base.dao.outcomes,
        )

        val result = dao.readExact(base.current.toRecord())

        assertTrue(result is CommittedSensorIngressReadResult.Exact)
        assertEquals(listOf(8, 9), (result as CommittedSensorIngressReadResult.Exact)
            .samples.map { it.raw.sequence })
    }

    private fun fixture(
        outcomes: Map<String, SensorPacketIngressOutcomeEntity>? = null,
    ): DuplicateFixture {
        val packet = byteArrayOf(1, 2, 3, 4)
        val earlier = ingress(
            ingressId = "attempt-a:0",
            attemptId = "attempt-a",
            receivedAtEpochMs = 1_700_000_000_000L,
            packet = packet,
        )
        val current = ingress(
            ingressId = "attempt-c:0",
            attemptId = "attempt-c",
            receivedAtEpochMs = 1_700_000_001_000L,
            packet = packet,
        )
        val samples = listOf(8, 9).map { sequence -> raw(earlier, sequence) }
        val results = samples.associate { value ->
            value.eventId to result(value.eventId, value.sequence)
        }
        val resolvedOutcomes = outcomes ?: mapOf(
            earlier.ingressId to handled(earlier, SensorPacketIngressOutcomeStatus.CORE_COMMITTED),
        )
        return DuplicateFixture(
            earlier = earlier,
            current = current,
            samples = samples,
            dao = RecordingCommittedIngressDao(
                ingress = listOf(earlier, current),
                raw = mapOf(earlier.ingressId to samples),
                results = results,
                outcomes = resolvedOutcomes,
            ),
        )
    }

    private fun ingress(
        ingressId: String,
        attemptId: String,
        receivedAtEpochMs: Long,
        packet: ByteArray,
    ) = SensorPacketIngressEntity(
        ingressId = ingressId,
        sensorId = "sensor-a",
        sensorFamily = SensorFamily.SIBIONICS_GS1.wireName,
        bluetoothAddress = "AA:BB:CC:DD:EE:FF",
        attemptId = attemptId,
        ordinal = 0,
        receivedAtEpochMs = receivedAtEpochMs,
        encryptedPacket = packet,
        packetSha256 = packet.sha256(),
    )

    private fun raw(ingress: SensorPacketIngressEntity, sequence: Int) = RawSensorSampleEntity(
        eventId = "event-$sequence",
        sourceIngressId = ingress.ingressId,
        sensorId = ingress.sensorId,
        sensorFamily = ingress.sensorFamily,
        sequence = sequence,
        sensorTimeEpochMs = 1_700_000_000_000L + sequence * 60_000L,
        phoneTimeEpochMs = ingress.receivedAtEpochMs,
        packet = ingress.encryptedPacket,
        packetSha256 = ingress.packetSha256,
        currentRaw = 50 + sequence,
        temperatureRaw = 320 + sequence,
        historyDistance = 0,
        transportVariant = 0,
        sensorTimeWasClamped = false,
        addTimeSeconds = null,
    )

    private fun result(eventId: String, sequence: Int) = SensorAlgorithmResultEntity(
        eventId = eventId,
        sensorId = "sensor-a",
        sequence = sequence,
        sensorTimeEpochMs = 1_700_000_000_000L + sequence * 60_000L,
        nativeGlucoseMmolL = 5.7,
        displayedGlucoseMmolL = 5.7,
        nativeTrend = 0,
        glucoseWarning = 0,
        currentWarning = 0,
        temperatureWarning = 0,
        algorithmProfile = "V116A",
        algorithmVersion = "1.1.6A",
        binarySetId = "binary-set",
        sensitivityToken = "ABCDEFGH",
        sensitivityTokenSource = "PACKAGE_CODE",
        sensitivityCoefficient = 1.42,
        sensitivityEncoding = "NORMAL",
        initializationMode = "STANDARD",
        publishable = false,
        alarmEligible = false,
        algorithmErrorCode = "DIAGNOSTIC_ONLY",
        publicationApprovalId = null,
    )

    private fun handled(
        ingress: SensorPacketIngressEntity,
        status: SensorPacketIngressOutcomeStatus,
    ) = SensorPacketIngressOutcomeEntity(
        ingressId = ingress.ingressId,
        status = status.name,
        handledAtEpochMs = ingress.receivedAtEpochMs,
        detail = null,
    )
}

private data class DuplicateFixture(
    val earlier: SensorPacketIngressEntity,
    val current: SensorPacketIngressEntity,
    val samples: List<RawSensorSampleEntity>,
    val dao: RecordingCommittedIngressDao,
)

private class RecordingCommittedIngressDao(
    val ingress: List<SensorPacketIngressEntity>,
    val raw: Map<String, List<RawSensorSampleEntity>>,
    val results: Map<String, SensorAlgorithmResultEntity>,
    val outcomes: Map<String, SensorPacketIngressOutcomeEntity>,
) : CommittedSensorIngressDao() {
    override suspend fun ingressById(ingressId: String) =
        ingress.singleOrNull { it.ingressId == ingressId }

    override suspend fun duplicateIngressCandidates(
        currentIngressId: String,
        sensorId: String,
        sensorFamily: String,
        bluetoothAddress: String,
        packetSha256: String,
    ) = ingress.filter {
        it.ingressId != currentIngressId && it.sensorId == sensorId &&
            it.sensorFamily == sensorFamily && it.bluetoothAddress == bluetoothAddress &&
            it.packetSha256 == packetSha256
    }

    override suspend fun ingressOutcome(ingressId: String) = outcomes[ingressId]

    override suspend fun rawBySourceIngressId(sourceIngressId: String) =
        raw[sourceIngressId].orEmpty()

    override suspend fun rawBySensorRange(
        sensorId: String,
        sensorFamily: String,
        firstSequence: Int,
        lastSequence: Int,
    ) = raw.values.flatten().filter {
        it.sensorId == sensorId && it.sensorFamily == sensorFamily &&
            it.sequence in firstSequence..lastSequence
    }.sortedBy(RawSensorSampleEntity::sequence)

    override suspend fun resultByEvent(eventId: String) = results[eventId]
    override suspend fun measurementByEvent(eventId: String): MeasurementEntity? = null
    override suspend fun outboxByEvent(eventId: String): UploadOutboxEntity? = null
    override suspend fun physicalApproval(approvalId: String): PhysicalSensorApprovalEntity? = null
    override suspend fun publicationBinding(
        publicationBindingId: String,
    ): ProductPublicationBindingEntity? = null
}
