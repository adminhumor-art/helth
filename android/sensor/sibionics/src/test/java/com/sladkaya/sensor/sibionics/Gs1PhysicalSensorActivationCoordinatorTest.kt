package com.sladkaya.sensor.sibionics

import com.sladkaya.core.data.PhysicalSensorActivationCommand
import com.sladkaya.core.data.PhysicalSensorActivationCommitResult
import com.sladkaya.core.data.PhysicalSensorActivationStore
import com.sladkaya.core.data.PhysicalSensorDiagnosticAnchor
import com.sladkaya.core.data.RawSensorSampleRecord
import com.sladkaya.core.data.SensorAlgorithmCheckpointRecord
import com.sladkaya.core.data.SensorAlgorithmResultRecord
import com.sladkaya.core.data.SensorProtocolBindingRecord
import com.sladkaya.core.model.SensorFamily
import java.security.MessageDigest
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class Gs1PhysicalSensorActivationCoordinatorTest {
    @Test
    fun ownerConfirmationBuildsOneStableLocalActivationFromExactDiagnosticEvidence() = runBlocking {
        val store = RecordingActivationStore(anchor())
        val coordinator = Gs1PhysicalSensorActivationCoordinator(
            store = store,
            nativeIdentityProvider = Gs1NativeArtifactIdentityProvider { _, _ ->
                Gs1NativeArtifactIdentity(
                    algorithmBinarySetSha256 = "12".repeat(32),
                    datahandleBinarySetSha256 = "34".repeat(32),
                )
            },
        )

        val first = coordinator.activate(profile(), EVENT_ID)
        val second = coordinator.activate(profile(), EVENT_ID)

        assertTrue(first is Gs1PhysicalSensorActivationResult.Activated)
        assertTrue(second is Gs1PhysicalSensorActivationResult.AlreadyActive)
        assertEquals(store.commands[0], store.commands[1])
        val command = store.commands.first().also { expected ->
            assertEquals(EVENT_ID, expected.diagnosticEventId)
            assertEquals("sensor-a", expected.approval.sensorId)
            assertEquals(60, expected.approval.approvedSequence)
            assertEquals(
                checkpoint().stateSha256,
                expected.approval.approvedCheckpointStateSha256,
            )
            assertEquals("12".repeat(32), expected.approval.nativeBinarySetSha256)
            assertEquals("34".repeat(32), expected.approval.nativeDatahandleBinarySetSha256)
        }
        assertEquals(64, command.publicationBindingId.length)
        assertEquals(64, command.approval.physicalValidationEvidenceSha256.length)
    }

    @Test
    fun mismatchBetweenVisibleProfileAndStoredAnchorFailsClosedBeforeCommit() = runBlocking {
        val store = RecordingActivationStore(anchor())
        val coordinator = coordinator(store)
        val otherProfile = validProfile(sensorId = "sensor-b")

        val result = coordinator.activate(otherProfile, EVENT_ID)

        assertEquals(Gs1PhysicalSensorActivationResult.EvidenceMismatch, result)
        assertTrue(store.commands.isEmpty())
    }

    @Test
    fun onlyARealtimeSuccessfulPostWarmupDiagnosticPointCanBeActivated() = runBlocking {
        val staleRaw = raw().copyForTest(phoneTimeEpochMs = SENSOR_TIME + 330_000L)
        val warmingCheckpoint = checkpoint(sequence = 45, sensorTime = SENSOR_START + 45 * 60_000L)

        val stale = coordinator(RecordingActivationStore(anchor(raw = staleRaw)))
            .activate(profile(), EVENT_ID)
        val warming = coordinator(
            RecordingActivationStore(
                anchor(
                    raw = raw(sequence = 45, sensorTime = warmingCheckpoint.sensorTimeEpochMs),
                    result = result(sequence = 45, sensorTime = warmingCheckpoint.sensorTimeEpochMs),
                    checkpoint = warmingCheckpoint,
                ),
            ),
        ).activate(profile(), EVENT_ID)

        assertEquals(Gs1PhysicalSensorActivationResult.ReadingNotEligible, stale)
        assertEquals(Gs1PhysicalSensorActivationResult.ReadingNotEligible, warming)
    }

    @Test
    fun storageConflictIsReturnedWithoutGuessingOrFallback() = runBlocking {
        val store = RecordingActivationStore(
            anchor = anchor(),
            commitResult = PhysicalSensorActivationCommitResult.Conflict("checkpoint changed"),
        )

        val result = coordinator(store).activate(profile(), EVENT_ID)

        assertEquals(
            Gs1PhysicalSensorActivationResult.Conflict("checkpoint changed"),
            result,
        )
        assertEquals(1, store.commands.size)
    }

    private fun coordinator(store: PhysicalSensorActivationStore) =
        Gs1PhysicalSensorActivationCoordinator(
            store = store,
            nativeIdentityProvider = Gs1NativeArtifactIdentityProvider { _, _ ->
                Gs1NativeArtifactIdentity("12".repeat(32), "34".repeat(32))
            },
        )

    private class RecordingActivationStore(
        private val anchor: PhysicalSensorDiagnosticAnchor?,
        private val commitResult: PhysicalSensorActivationCommitResult =
            PhysicalSensorActivationCommitResult.Activated,
    ) : PhysicalSensorActivationStore {
        val commands = mutableListOf<PhysicalSensorActivationCommand>()

        override suspend fun diagnosticAnchor(
            eventId: String,
        ): PhysicalSensorDiagnosticAnchor? = anchor?.takeIf { it.raw.eventId == eventId }

        override suspend fun approveAndActivate(
            command: PhysicalSensorActivationCommand,
        ): PhysicalSensorActivationCommitResult {
            commands += command
            return if (commands.size > 1 &&
                commitResult == PhysicalSensorActivationCommitResult.Activated
            ) {
                PhysicalSensorActivationCommitResult.AlreadyActive
            } else {
                commitResult
            }
        }
    }

    private fun anchor(
        raw: RawSensorSampleRecord = raw(),
        result: SensorAlgorithmResultRecord = result(),
        checkpoint: SensorAlgorithmCheckpointRecord = checkpoint(),
    ) = PhysicalSensorDiagnosticAnchor(
        protocol = protocol(),
        raw = raw,
        result = result,
        checkpoint = checkpoint,
    )

    private fun protocol() = SensorProtocolBindingRecord(
        sensorId = "sensor-a",
        bluetoothAddress = "AA:BB:CC:DD:EE:FF",
        sensorFamily = SensorFamily.SIBIONICS_GS1,
        transportVariant = 0,
        sensitivityToken = "ABCDEFGH",
        wireProfile = "V120",
        transportProtocol = "GS1_V120",
        transportCodecId = "GS1_V120_WIRE_V1",
        algorithmProfile = "V116A",
        sensitivityEncoding = "NORMAL",
        evidenceKind = "VALIDATED_V120_ENVELOPE",
        evidenceSha256 = "ab".repeat(32),
        schemaVersion = 1,
    )

    private fun raw(
        sequence: Int = 60,
        sensorTime: Long = SENSOR_TIME,
    ) = RawSensorSampleRecord(
        eventId = EVENT_ID,
        sourceIngressId = "ingress-60",
        sensorId = "sensor-a",
        sensorFamily = SensorFamily.SIBIONICS_GS1,
        sequence = sequence,
        sensorTimeEpochMs = sensorTime,
        phoneTimeEpochMs = sensorTime + 1_000L,
        packet = PACKET,
        packetSha256 = PACKET.sha256(),
        currentRaw = 11,
        temperatureRaw = 22,
        historyDistance = 0,
        transportVariant = 0,
    )

    private fun RawSensorSampleRecord.copyForTest(
        phoneTimeEpochMs: Long,
    ) = RawSensorSampleRecord(
        eventId = eventId,
        sourceIngressId = sourceIngressId,
        sensorId = sensorId,
        sensorFamily = sensorFamily,
        sequence = sequence,
        sensorTimeEpochMs = sensorTimeEpochMs,
        phoneTimeEpochMs = phoneTimeEpochMs,
        packet = packetCopy(),
        packetSha256 = packetSha256,
        currentRaw = currentRaw,
        temperatureRaw = temperatureRaw,
        historyDistance = historyDistance,
        transportVariant = transportVariant,
        sensorTimeWasClamped = sensorTimeWasClamped,
        addTimeSeconds = addTimeSeconds,
    )

    private fun result(
        sequence: Int = 60,
        sensorTime: Long = SENSOR_TIME,
    ) = SensorAlgorithmResultRecord(
        eventId = EVENT_ID,
        sensorId = "sensor-a",
        sequence = sequence,
        sensorTimeEpochMs = sensorTime,
        nativeGlucoseMmolL = 6.1,
        displayedGlucoseMmolL = 6.1,
        nativeTrend = 0,
        glucoseWarning = 0,
        currentWarning = 0,
        temperatureWarning = 0,
        algorithmProfile = "V116A",
        algorithmVersion = "1.1.6A",
        binarySetId = "native-set",
        sensitivityToken = "ABCDEFGH",
        sensitivityTokenSource = "PACKAGE_CODE",
        sensitivityCoefficient = 1.42,
        sensitivityEncoding = "NORMAL",
        initializationMode = "STANDARD",
        publishable = false,
        alarmEligible = false,
    )

    private fun checkpoint(
        sequence: Int = 60,
        sensorTime: Long = SENSOR_TIME,
    ) = SensorAlgorithmCheckpointRecord(
        sensorId = "sensor-a",
        bluetoothAddress = "AA:BB:CC:DD:EE:FF",
        sensorFamily = SensorFamily.SIBIONICS_GS1,
        transportVariant = 0,
        transportProtocol = "GS1_V120",
        transportCodecId = "GS1_V120_WIRE_V1",
        sequence = sequence,
        sensorTimeEpochMs = sensorTime,
        sensorStartTimeEpochMs = SENSOR_START,
        algorithmProfile = "V116A",
        algorithmVersion = "1.1.6A",
        binarySetId = "native-set",
        sensitivityToken = "ABCDEFGH",
        sensitivityTokenSource = "PACKAGE_CODE",
        sensitivityCoefficient = 1.42,
        sensitivityEncoding = "NORMAL",
        initializationMode = "STANDARD",
        state = ByteArray(2_480) { 1 },
        stateSha256 = ByteArray(2_480) { 1 }.sha256(),
        displayOffsetMmolL = 0.0,
        schemaVersion = 1,
    )

    private fun profile() = validProfile()

    private fun validProfile(sensorId: String = "sensor-a") =
        (Gs1DiagnosticActivationProfile.validate(
            sensorId = sensorId,
            family = SensorFamily.SIBIONICS_GS1,
            bluetoothAddress = "AA:BB:CC:DD:EE:FF",
            transportVariant = 0,
            packageCode = "ABCDEFGH",
        ) as Gs1DiagnosticActivationProfileValidation.Valid).profile

    private fun ByteArray.sha256(): String = MessageDigest.getInstance("SHA-256")
        .digest(this)
        .joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }

    private companion object {
        const val EVENT_ID = "event-60"
        const val SENSOR_START = 1_700_000_000_000L
        const val SENSOR_TIME = SENSOR_START + 60 * 60_000L
        val PACKET = byteArrayOf(1, 2, 3, 4)
    }
}
