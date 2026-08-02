package com.sladkaya.core.data

import com.sladkaya.core.model.ReadingQuality
import com.sladkaya.core.model.SensorFamily
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Test

class CommittedSensorIngressIntegrityTest {
    @Test
    fun exactUntamperedPublicationIsAccepted() = runBlocking {
        val fixture = publicationFixture()

        assertTrue(
            fixture.dao().readExact(fixture.ingress.toRecord()) is
                CommittedSensorIngressReadResult.Exact,
        )
    }

    @Test
    fun tamperedMeasurementGlucoseIsRejected() = runBlocking {
        val fixture = publicationFixture()

        assertMismatch(fixture.dao(
            measurement = fixture.measurement.copy(
                glucoseMgDl = fixture.measurement.glucoseMgDl + 1,
            ),
        ).readExact(fixture.ingress.toRecord()))
    }

    @Test
    fun tamperedMeasurementTrendIsRejected() = runBlocking {
        val fixture = publicationFixture()

        assertMismatch(fixture.dao(
            measurement = fixture.measurement.copy(
                trendMgDlPerMinute = fixture.measurement.trendMgDlPerMinute + 0.1,
            ),
        ).readExact(fixture.ingress.toRecord()))
    }

    @Test
    fun tamperedAlgorithmVersionIsRejected() = runBlocking {
        val fixture = publicationFixture()

        assertMismatch(fixture.dao(
            result = fixture.result.copy(algorithmVersion = "1.1.6B"),
        ).readExact(fixture.ingress.toRecord()))
    }

    @Test
    fun tamperedBinarySetIdIsRejected() = runBlocking {
        val fixture = publicationFixture()

        assertMismatch(fixture.dao(
            result = fixture.result.copy(binarySetId = "another-binary-set"),
        ).readExact(fixture.ingress.toRecord()))
    }

    @Test
    fun rawTransportVariantDifferentFromCanonicalApprovalIsRejected() = runBlocking {
        val fixture = publicationFixture()

        assertMismatch(fixture.dao(
            raw = fixture.raw.copy(transportVariant = fixture.raw.transportVariant + 1),
        ).readExact(fixture.ingress.toRecord()))
    }

    @Test
    fun malformedOutboxStateIsRejected() = runBlocking {
        val fixture = publicationFixture()

        assertMismatch(fixture.dao(
            outbox = fixture.outbox.copy(state = "MALFORMED"),
        ).readExact(fixture.ingress.toRecord()))
    }

    @Test
    fun malformedOutboxLeaseIsRejected() = runBlocking {
        val fixture = publicationFixture()

        assertMismatch(fixture.dao(
            outbox = fixture.outbox.copy(
                state = UploadOutboxState.LEASED.wireName,
                leaseToken = null,
                leaseExpiresAtEpochMs = null,
            ),
        ).readExact(fixture.ingress.toRecord()))
    }

    @Test
    fun malformedOutboxDeliveryStatusIsRejected() = runBlocking {
        val fixture = publicationFixture()

        assertMismatch(fixture.dao(
            outbox = fixture.outbox.copy(sanitizedStatus = "MALFORMED"),
        ).readExact(fixture.ingress.toRecord()))
    }

    @Test
    fun tamperedAlgorithmAndSensitivityProvenanceIsRejected() = runBlocking {
        val fixture = publicationFixture()
        val tamperedResults = listOf(
            "profile" to fixture.result.copy(algorithmProfile = "V115G"),
            "token" to fixture.result.copy(sensitivityToken = "12345678"),
            "token source" to fixture.result.copy(sensitivityTokenSource = "TAMPERED"),
            "coefficient" to fixture.result.copy(sensitivityCoefficient = 1.43),
            "encoding" to fixture.result.copy(sensitivityEncoding = "FACTION"),
            "initialization" to fixture.result.copy(initializationMode = "FACTION"),
        )

        tamperedResults.forEach { (field, result) ->
            assertTrue(
                "$field provenance tamper must fail closed",
                fixture.dao(result = result).readExact(fixture.ingress.toRecord()) is
                    CommittedSensorIngressReadResult.Mismatch,
            )
        }
    }

    private fun assertMismatch(result: CommittedSensorIngressReadResult) {
        assertTrue(result is CommittedSensorIngressReadResult.Mismatch)
    }
}

private data class PublicationIntegrityFixture(
    val ingress: SensorPacketIngressEntity,
    val raw: RawSensorSampleEntity,
    val result: SensorAlgorithmResultEntity,
    val measurement: MeasurementEntity,
    val outbox: UploadOutboxEntity,
    val approval: PhysicalSensorApprovalEntity,
    val binding: ProductPublicationBindingEntity,
) {
    fun dao(
        raw: RawSensorSampleEntity = this.raw,
        result: SensorAlgorithmResultEntity = this.result,
        measurement: MeasurementEntity = this.measurement,
        outbox: UploadOutboxEntity = this.outbox,
    ) = IntegrityCommittedIngressDao(
        ingress = ingress,
        raw = raw,
        result = result,
        measurement = measurement,
        outbox = outbox,
        approval = approval,
        binding = binding,
    )
}

private fun publicationFixture(): PublicationIntegrityFixture {
    val approvalRecord = PhysicalSensorApprovalRecord(
        sensorId = "sensor-a",
        bluetoothAddress = "AA:BB:CC:DD:EE:FF",
        sensorFamily = SensorFamily.SIBIONICS_GS1,
        transportVariant = 0,
        sensitivityToken = "ABCDEFGH",
        wireProfile = "V120",
        transportProtocol = "GS1_V120",
        transportCodecId = "transport-codec-test",
        algorithmProfile = "V116A",
        algorithmVersion = "1.1.6A",
        binarySetId = "algorithm-set",
        sensitivityTokenSource = "PACKAGE_CODE",
        sensitivityCoefficient = 1.42,
        sensitivityEncoding = "NORMAL",
        initializationMode = "STANDARD",
        displayOffsetMmolL = 0.0,
        protocolEvidenceKind = "VALIDATED_V120_ENVELOPE",
        protocolEvidenceSha256 = "ab".repeat(32),
        physicalValidationEvidenceSha256 = "cd".repeat(32),
        checkpointSchemaVersion = 1,
        approvedSequence = 1,
        approvedSensorTimeEpochMs = 1_700_000_060_000L,
        sensorStartTimeEpochMs = 1_700_000_000_000L,
        approvedCheckpointStateSha256 = "ef".repeat(32),
        nativeBinarySetSha256 = "12".repeat(32),
        nativeDatahandleBinarySetSha256 = "34".repeat(32),
        approvedAtEpochMs = 1_700_000_000_000L,
    )
    val bindingRecord = ProductPublicationBindingRecord(
        approvalId = approvalRecord.approvalId,
        httpsOrigin = "https://api.sladkaya.test",
        backendBindingId = "backend-binding-a",
        credentialId = "credential-a",
        credentialRevision = 3L,
        expectedPatientId = "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa",
        expectedDeviceId = "bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb",
        createdAtEpochMs = 1_700_000_001_000L,
    )
    val packet = byteArrayOf(1, 2, 3, 4)
    val ingress = SensorPacketIngressEntity(
        ingressId = "attempt-a:0",
        sensorId = approvalRecord.sensorId,
        sensorFamily = approvalRecord.sensorFamily.wireName,
        bluetoothAddress = approvalRecord.bluetoothAddress,
        attemptId = "attempt-a",
        ordinal = 0,
        receivedAtEpochMs = 1_700_000_001_000L,
        encryptedPacket = packet,
        packetSha256 = packet.sha256(),
    )
    val raw = RawSensorSampleEntity(
        eventId = "event-1",
        sourceIngressId = ingress.ingressId,
        sensorId = ingress.sensorId,
        sensorFamily = ingress.sensorFamily,
        sequence = 1,
        sensorTimeEpochMs = 1_700_000_060_000L,
        phoneTimeEpochMs = ingress.receivedAtEpochMs,
        packet = packet,
        packetSha256 = ingress.packetSha256,
        currentRaw = 51,
        temperatureRaw = 321,
        historyDistance = 0,
        transportVariant = 0,
        sensorTimeWasClamped = false,
        addTimeSeconds = null,
    )
    val result = SensorAlgorithmResultEntity(
        eventId = raw.eventId,
        sensorId = raw.sensorId,
        sequence = raw.sequence,
        sensorTimeEpochMs = raw.sensorTimeEpochMs,
        nativeGlucoseMmolL = 5.7,
        displayedGlucoseMmolL = 5.7,
        nativeTrend = 2,
        glucoseWarning = 0,
        currentWarning = 0,
        temperatureWarning = 0,
        algorithmProfile = approvalRecord.algorithmProfile,
        algorithmVersion = approvalRecord.algorithmVersion,
        binarySetId = approvalRecord.binarySetId,
        sensitivityToken = approvalRecord.sensitivityToken,
        sensitivityTokenSource = approvalRecord.sensitivityTokenSource,
        sensitivityCoefficient = approvalRecord.sensitivityCoefficient,
        sensitivityEncoding = approvalRecord.sensitivityEncoding,
        initializationMode = approvalRecord.initializationMode,
        publishable = true,
        alarmEligible = true,
        algorithmErrorCode = null,
        publicationApprovalId = approvalRecord.approvalId,
    )
    val measurement = MeasurementEntity(
        eventId = raw.eventId,
        sensorId = raw.sensorId,
        sensorFamily = raw.sensorFamily,
        sensorTimeEpochMs = raw.sensorTimeEpochMs,
        phoneTimeEpochMs = raw.phoneTimeEpochMs,
        glucoseMgDl = 103,
        trendMgDlPerMinute = 2.6,
        quality = ReadingQuality.VALID.wireName,
        sequence = raw.sequence.toLong(),
        publicationApprovalId = approvalRecord.approvalId,
        publicationBindingId = bindingRecord.publicationBindingId,
        httpsOrigin = bindingRecord.httpsOrigin,
        backendBindingId = bindingRecord.backendBindingId,
        credentialId = bindingRecord.credentialId,
        credentialRevision = bindingRecord.credentialRevision,
        expectedPatientId = bindingRecord.expectedPatientId,
        expectedDeviceId = bindingRecord.expectedDeviceId,
    )
    val outbox = UploadOutboxEntity.pending(
        eventId = raw.eventId,
        approvalId = approvalRecord.approvalId,
        publicationBindingId = bindingRecord.publicationBindingId,
        httpsOrigin = bindingRecord.httpsOrigin,
        backendBindingId = bindingRecord.backendBindingId,
        credentialId = bindingRecord.credentialId,
        credentialRevision = bindingRecord.credentialRevision,
        expectedPatientId = bindingRecord.expectedPatientId,
        expectedDeviceId = bindingRecord.expectedDeviceId,
        enqueuedAtEpochMs = raw.phoneTimeEpochMs,
    )
    return PublicationIntegrityFixture(
        ingress = ingress,
        raw = raw,
        result = result,
        measurement = measurement,
        outbox = outbox,
        approval = approvalRecord.toEntity(),
        binding = bindingRecord.toEntity(),
    )
}

private class IntegrityCommittedIngressDao(
    private val ingress: SensorPacketIngressEntity,
    private val raw: RawSensorSampleEntity,
    private val result: SensorAlgorithmResultEntity,
    private val measurement: MeasurementEntity,
    private val outbox: UploadOutboxEntity,
    private val approval: PhysicalSensorApprovalEntity,
    private val binding: ProductPublicationBindingEntity,
) : CommittedSensorIngressDao() {
    override suspend fun ingressById(ingressId: String) =
        ingress.takeIf { it.ingressId == ingressId }

    override suspend fun duplicateIngressCandidates(
        currentIngressId: String,
        sensorId: String,
        sensorFamily: String,
        bluetoothAddress: String,
        packetSha256: String,
    ): List<SensorPacketIngressEntity> = emptyList()

    override suspend fun ingressOutcome(ingressId: String): SensorPacketIngressOutcomeEntity? = null

    override suspend fun rawBySourceIngressId(sourceIngressId: String) =
        listOf(raw).takeIf { raw.sourceIngressId == sourceIngressId }.orEmpty()

    override suspend fun rawBySensorRange(
        sensorId: String,
        sensorFamily: String,
        firstSequence: Int,
        lastSequence: Int,
    ) = listOf(raw).filter {
        it.sensorId == sensorId && it.sensorFamily == sensorFamily &&
            it.sequence in firstSequence..lastSequence
    }

    override suspend fun resultByEvent(eventId: String) = result.takeIf { it.eventId == eventId }
    override suspend fun measurementByEvent(eventId: String) =
        measurement.takeIf { it.eventId == eventId }

    override suspend fun outboxByEvent(eventId: String) = outbox.takeIf { it.eventId == eventId }
    override suspend fun physicalApproval(approvalId: String) =
        approval.takeIf { it.approvalId == approvalId }

    override suspend fun publicationBinding(publicationBindingId: String) =
        binding.takeIf { it.publicationBindingId == publicationBindingId }
}
