package com.sladkaya.sensor.sibionics

import com.sladkaya.core.data.AtomicSensorCoreRecord
import com.sladkaya.core.data.ProductPublicationContext
import com.sladkaya.core.data.RawSensorSampleRecord
import com.sladkaya.core.data.SensorAlgorithmCheckpointRecord
import com.sladkaya.core.data.SensorAlgorithmResultRecord
import com.sladkaya.core.model.SensorFamily
import java.security.MessageDigest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Contract required before an approved product runtime can safely replay
 * history. Such a step must advance the exact approved algorithm lineage, but
 * it must not create a measurement, alarm candidate or upload outbox entry.
 */
class Gs1ApprovedCheckpointAtomicContractTest {
    @Test
    fun approvedDegradedCheckpointCanAdvanceAtomicallyWithoutProductMeasurement() {
        val approvalId = "ab".repeat(32)
        val context = productContext(approvalId)
        val raw = rawSample()
        val result = algorithmResult(raw, approvalId)
        val checkpoint = checkpoint(raw, approvalId)

        val commit = AtomicSensorCoreRecord(
            raw = raw,
            result = result,
            checkpoint = checkpoint,
            measurement = null,
            publicationContext = null,
            approvedCheckpointContext = context.approvedCheckpointContext(),
        )

        assertNull(commit.measurement)
        assertFalse(commit.result.publishable)
        assertFalse(commit.result.alarmEligible)
        assertEquals(approvalId, commit.checkpoint.publicationApprovalId)
        assertEquals(approvalId, commit.result.publicationApprovalId)
    }

    private fun rawSample() = RawSensorSampleRecord(
        eventId = "approved-history-event",
        sourceIngressId = "attempt-a:42",
        sensorId = SENSOR_ID,
        sensorFamily = SensorFamily.SIBIONICS_GS1,
        sequence = 42,
        sensorTimeEpochMs = 1_800_000_000_000L,
        phoneTimeEpochMs = 1_800_000_600_000L,
        packet = byteArrayOf(1, 2, 3, 4),
        packetSha256 = byteArrayOf(1, 2, 3, 4).sha256(),
        currentRaw = 120,
        temperatureRaw = 320,
        historyDistance = 3,
        transportVariant = 2,
    )

    private fun algorithmResult(
        raw: RawSensorSampleRecord,
        approvalId: String,
    ) = SensorAlgorithmResultRecord(
        eventId = raw.eventId,
        sensorId = raw.sensorId,
        sequence = raw.sequence,
        sensorTimeEpochMs = raw.sensorTimeEpochMs,
        nativeGlucoseMmolL = 5.8,
        displayedGlucoseMmolL = 5.8,
        nativeTrend = 0,
        glucoseWarning = 0,
        currentWarning = 0,
        temperatureWarning = 0,
        algorithmProfile = "V116A",
        algorithmVersion = "1.1.6A",
        binarySetId = "v116a-arm64-v8a-test",
        sensitivityToken = "ABCDEFGH",
        sensitivityTokenSource = "PACKAGE_CODE",
        sensitivityCoefficient = 1.42,
        sensitivityEncoding = "NORMAL",
        initializationMode = "STANDARD",
        publishable = false,
        alarmEligible = false,
        publicationApprovalId = approvalId,
    )

    private fun checkpoint(
        raw: RawSensorSampleRecord,
        approvalId: String,
    ): SensorAlgorithmCheckpointRecord {
        val state = ByteArray(2_480) { index -> (index % 251).toByte() }
        return SensorAlgorithmCheckpointRecord(
            sensorId = raw.sensorId,
            bluetoothAddress = "AA:BB:CC:DD:EE:FF",
            sensorFamily = raw.sensorFamily,
            transportVariant = raw.transportVariant,
            transportProtocol = "GS1_V120",
            transportCodecId = "sha256:${"34".repeat(32)}",
            sequence = raw.sequence,
            sensorTimeEpochMs = raw.sensorTimeEpochMs,
            sensorStartTimeEpochMs = raw.sensorTimeEpochMs - raw.sequence * 60_000L,
            algorithmProfile = "V116A",
            algorithmVersion = "1.1.6A",
            binarySetId = "v116a-arm64-v8a-test",
            sensitivityToken = "ABCDEFGH",
            sensitivityTokenSource = "PACKAGE_CODE",
            sensitivityCoefficient = 1.42,
            sensitivityEncoding = "NORMAL",
            initializationMode = "STANDARD",
            state = state,
            stateSha256 = state.sha256(),
            displayOffsetMmolL = 0.15,
            schemaVersion = 1,
            publicationApprovalId = approvalId,
        )
    }

    private fun productContext(approvalId: String) = ProductPublicationContext(
        approvalId = approvalId,
        publicationBindingId = "cd".repeat(32),
        remotePublicationBindingId = "de".repeat(32),
        httpsOrigin = "https://family.example",
        backendBindingId = "binding-1",
        credentialId = "credential-1",
        credentialRevision = 1,
        expectedPatientId = "11111111-1111-4111-8111-111111111111",
        expectedDeviceId = "22222222-2222-4222-8222-222222222222",
        nativeBinarySetSha256 = "12".repeat(32),
        nativeDatahandleBinarySetSha256 = "34".repeat(32),
    )

    private fun ByteArray.sha256(): String = MessageDigest.getInstance("SHA-256")
        .digest(this)
        .joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }

    private companion object {
        const val SENSOR_ID = "GS1SB-CN-APPROVED-001"
    }
}
