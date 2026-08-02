package com.sladkaya.core.data

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalReadingEffectDaoTest {
    @Test
    fun activeEarliestLeaseBlocksLaterReadingsUntilExactAcknowledgment() = runBlocking {
        val dao = RecordingLocalReadingEffectDao().apply {
            add(effectId = 1L, eventId = "event-2", sequence = 2L, enqueuedAt = NOW - 1_000L)
            add(effectId = 2L, eventId = "event-3", sequence = 3L, enqueuedAt = NOW)
        }

        val first = dao.leaseEarliest(NOW, LEASE_A, NOW + 10_000L)
            as LocalReadingEffectLeaseDecision.Leased
        assertEquals("event-2", first.value.reading.eventId)
        assertEquals(1, first.value.effect.attempts)
        assertEquals(first, dao.leaseEarliest(NOW, LEASE_A, NOW + 10_000L))
        assertTrue(
            dao.leaseEarliest(NOW, LEASE_B, NOW + 10_000L) is
                LocalReadingEffectLeaseDecision.BlockedByActiveLease,
        )

        assertEquals(
            SensorCoreCommitDisposition.COMMITTED,
            dao.acknowledgeEarliest(1L, "event-2", LEASE_A, NOW + 1_000L),
        )
        assertEquals(
            SensorCoreCommitDisposition.ALREADY_COMMITTED,
            dao.acknowledgeEarliest(1L, "event-2", LEASE_A, NOW + 1_000L),
        )
        val second = dao.leaseEarliest(NOW + 1_001L, LEASE_B, NOW + 20_000L)
            as LocalReadingEffectLeaseDecision.Leased
        assertEquals("event-3", second.value.reading.eventId)
    }

    @Test
    fun expiredLeaseReturnsTheSameEarliestReadingBeforeAnyLaterReading() = runBlocking {
        val dao = RecordingLocalReadingEffectDao().apply {
            add(effectId = 1L, eventId = "event-2", sequence = 2L, enqueuedAt = NOW - 1_000L)
            add(effectId = 2L, eventId = "event-3", sequence = 3L, enqueuedAt = NOW)
        }

        dao.leaseEarliest(NOW, LEASE_A, NOW + 10_000L)
        val recovered = dao.leaseEarliest(NOW + 10_001L, LEASE_B, NOW + 20_000L)
            as LocalReadingEffectLeaseDecision.Leased

        assertEquals("event-2", recovered.value.reading.eventId)
        assertEquals(2, recovered.value.effect.attempts)
    }

    @Test
    fun corruptedProductLineageCannotBeLeasedOrSkipped() {
        val dao = RecordingLocalReadingEffectDao().apply {
            add(effectId = 1L, eventId = "event-2", sequence = 2L, enqueuedAt = NOW - 1_000L)
            add(effectId = 2L, eventId = "event-3", sequence = 3L, enqueuedAt = NOW)
            measurements["event-2"] = checkNotNull(measurements["event-2"]).copy(
                publicationBindingId = "ef".repeat(32),
            )
        }

        assertThrows(SensorCoreConflictException::class.java) {
            runBlocking { dao.leaseEarliest(NOW, LEASE_A, NOW + 10_000L) }
        }
        assertEquals("PENDING", dao.effects.getValue(2L).state)
        assertEquals(0, dao.effects.getValue(2L).attempts)
    }

    @Test
    fun phoneClockRollbackDoesNotInvalidateAnOtherwiseExactLeaseAndAck() = runBlocking {
        val dao = RecordingLocalReadingEffectDao().apply {
            add(
                effectId = 1L,
                eventId = "event-future-phone-time",
                sequence = 2L,
                enqueuedAt = NOW + 60_000L,
            )
        }

        val lease = dao.leaseEarliest(NOW, LEASE_A, NOW + 10_000L)
            as LocalReadingEffectLeaseDecision.Leased
        assertEquals("event-future-phone-time", lease.value.reading.eventId)
        assertEquals(
            SensorCoreCommitDisposition.COMMITTED,
            dao.acknowledgeEarliest(
                effectId = lease.value.effect.effectId,
                eventId = lease.value.effect.eventId,
                leaseToken = LEASE_A,
                acknowledgedAtEpochMs = NOW + 1_000L,
            ),
        )
    }

    @Test
    fun exactLocalLineageCanBeLeasedWithoutAnyRemoteUploadRow() = runBlocking {
        val dao = RecordingLocalReadingEffectDao().apply {
            add(effectId = 1L, eventId = "offline-event", sequence = 2L, enqueuedAt = NOW)
        }

        val leased = dao.leaseEarliest(NOW + 1L, LEASE_A, NOW + 10_000L)
            as LocalReadingEffectLeaseDecision.Leased

        assertEquals("offline-event", leased.value.reading.eventId)
        assertEquals(1L, leased.value.effect.effectId)
    }

    private companion object {
        const val NOW = 1_700_000_200_000L
        const val LEASE_A = "local-effect-lease-a"
        const val LEASE_B = "local-effect-lease-b"
    }
}

private class RecordingLocalReadingEffectDao : LocalReadingEffectDao() {
    val effects = linkedMapOf<Long, LocalReadingEffectEntity>()
    val measurements = mutableMapOf<String, MeasurementEntity>()
    private val rawSamples = mutableMapOf<String, RawSensorSampleEntity>()
    private val results = mutableMapOf<String, SensorAlgorithmResultEntity>()
    private val approvals = mutableMapOf<String, PhysicalSensorApprovalEntity>()
    private var activeBinding: ActiveSensorPublicationBindingEntity? = null

    fun add(effectId: Long, eventId: String, sequence: Long, enqueuedAt: Long) {
        val approval = localApproval().toEntity()
        val approvalId = approval.approvalId
        val publicationBindingId = "cd".repeat(32)
        val sensorTime = enqueuedAt - 1_000L
        approvals[approvalId] = approval
        activeBinding = ActiveSensorPublicationBindingEntity(
            activeSlot = ACTIVE_PUBLICATION_BINDING_SLOT,
            publicationBindingId = publicationBindingId,
            approvalId = approvalId,
        )
        effects[effectId] = LocalReadingEffectEntity.pending(
            eventId = eventId,
            approvalId = approvalId,
            publicationBindingId = publicationBindingId,
            enqueuedAtEpochMs = enqueuedAt,
        ).copy(effectId = effectId)
        measurements[eventId] = MeasurementEntity(
            eventId = eventId,
            sensorId = "sensor-a",
            sensorFamily = "sibionics_gs1",
            sensorTimeEpochMs = sensorTime,
            phoneTimeEpochMs = enqueuedAt,
            glucoseMgDl = 103,
            trendMgDlPerMinute = 0.0,
            quality = "valid",
            sequence = sequence,
            publicationApprovalId = approvalId,
            publicationBindingId = publicationBindingId,
            httpsOrigin = null,
            backendBindingId = null,
            credentialId = null,
            credentialRevision = null,
            expectedPatientId = null,
            expectedDeviceId = null,
        )
        rawSamples[eventId] = RawSensorSampleEntity(
            eventId = eventId,
            sourceIngressId = "attempt-a:$sequence",
            sensorId = "sensor-a",
            sensorFamily = "sibionics_gs1",
            sequence = sequence.toInt(),
            sensorTimeEpochMs = sensorTime,
            phoneTimeEpochMs = enqueuedAt,
            packet = byteArrayOf(1, 2, 3),
            packetSha256 = "01".repeat(32),
            currentRaw = 53,
            temperatureRaw = 322,
            historyDistance = 0,
            transportVariant = 0,
            sensorTimeWasClamped = false,
            addTimeSeconds = null,
        )
        results[eventId] = SensorAlgorithmResultEntity(
            eventId = eventId,
            sensorId = "sensor-a",
            sequence = sequence.toInt(),
            sensorTimeEpochMs = sensorTime,
            nativeGlucoseMmolL = 5.7,
            displayedGlucoseMmolL = 5.7,
            nativeTrend = 0,
            glucoseWarning = 0,
            currentWarning = 0,
            temperatureWarning = 0,
            algorithmProfile = "V116A",
            algorithmVersion = "1.1.6A",
            binarySetId = "set",
            sensitivityToken = "ABCDEFGH",
            sensitivityTokenSource = "PACKAGE_CODE",
            sensitivityCoefficient = 1.42,
            sensitivityEncoding = "NORMAL",
            initializationMode = "STANDARD",
            publishable = true,
            alarmEligible = true,
            algorithmErrorCode = null,
            publicationApprovalId = approvalId,
        )
    }

    override suspend fun earliestUnacknowledged(): LocalReadingEffectEntity? =
        effects.values.firstOrNull { it.state != "ACKNOWLEDGED" }

    override suspend fun effectById(effectId: Long): LocalReadingEffectEntity? = effects[effectId]

    override suspend fun effectByEvent(eventId: String): LocalReadingEffectEntity? =
        effects.values.singleOrNull { it.eventId == eventId }

    override suspend fun effectsByOperationToken(token: String): List<LocalReadingEffectEntity> =
        effects.values.filter { it.leaseToken == token || it.lastTransitionToken == token }

    override suspend fun measurement(eventId: String): MeasurementEntity? = measurements[eventId]

    override suspend fun raw(eventId: String): RawSensorSampleEntity? = rawSamples[eventId]

    override suspend fun result(eventId: String): SensorAlgorithmResultEntity? = results[eventId]

    override suspend fun activeSensorBinding(): ActiveSensorPublicationBindingEntity? = activeBinding

    override suspend fun physicalApproval(approvalId: String): PhysicalSensorApprovalEntity? =
        approvals[approvalId]

    override suspend fun recoverExpiredLeases(nowEpochMs: Long): Int {
        val expired = effects.values.filter {
            it.state == "LEASED" && checkNotNull(it.leaseExpiresAtEpochMs) <= nowEpochMs
        }
        expired.forEach { value ->
            effects[value.effectId] = value.copy(
                state = "PENDING",
                leaseToken = null,
                leaseExpiresAtEpochMs = null,
                lastTransitionToken = value.leaseToken,
            )
        }
        return expired.size
    }

    override suspend fun acquireLease(
        effectId: Long,
        leaseToken: String,
        leaseExpiresAtEpochMs: Long,
    ): Int {
        val value = effects[effectId] ?: return 0
        if (value.state != "PENDING") return 0
        effects[effectId] = value.copy(
            state = "LEASED",
            attempts = value.attempts + 1,
            leaseToken = leaseToken,
            leaseExpiresAtEpochMs = leaseExpiresAtEpochMs,
        )
        return 1
    }

    override suspend fun setAcknowledged(
        effectId: Long,
        eventId: String,
        leaseToken: String,
        acknowledgedAtEpochMs: Long,
    ): Int {
        val value = effects[effectId] ?: return 0
        if (value.eventId != eventId || value.state != "LEASED" || value.leaseToken != leaseToken) {
            return 0
        }
        effects[effectId] = value.copy(
            state = "ACKNOWLEDGED",
            leaseToken = null,
            leaseExpiresAtEpochMs = null,
            lastTransitionToken = leaseToken,
            acknowledgedAtEpochMs = acknowledgedAtEpochMs,
        )
        return 1
    }

    private fun localApproval() = PhysicalSensorApprovalRecord(
        sensorId = "sensor-a",
        bluetoothAddress = "AA:BB:CC:DD:EE:FF",
        sensorFamily = com.sladkaya.core.model.SensorFamily.SIBIONICS_GS1,
        transportVariant = 0,
        sensitivityToken = "ABCDEFGH",
        wireProfile = "V120",
        transportProtocol = "GS1_V120",
        transportCodecId = "transport-codec-test",
        algorithmProfile = "V116A",
        algorithmVersion = "1.1.6A",
        binarySetId = "set",
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
        schemaVersion = 1,
    )
}
