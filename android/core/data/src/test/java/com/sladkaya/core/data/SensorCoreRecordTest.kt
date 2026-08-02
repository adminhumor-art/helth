package com.sladkaya.core.data

import com.sladkaya.core.model.GlucoseReading
import com.sladkaya.core.model.ReadingQuality
import com.sladkaya.core.model.SensorFamily
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import java.security.MessageDigest

class SensorCoreRecordTest {
    @Test
    fun rawPacketIsCopiedAtThePublicBoundary() {
        val packet = byteArrayOf(1, 2, 3, 4)
        val raw = raw(packet = packet)

        packet[0] = 99
        val firstRead = raw.packetCopy()
        firstRead[1] = 88

        assertArrayEquals(byteArrayOf(1, 2, 3, 4), raw.packetCopy())
    }

    @Test
    fun atomicRecordRejectsAnyCrossComponentIdentityMismatch() {
        val raw = raw()
        val result = result()
        val checkpoint = checkpoint()

        assertThrows(IllegalArgumentException::class.java) {
            AtomicSensorCoreRecord(
                raw,
                result.copy(sequence = result.sequence + 1),
                checkpoint,
                reading(),
                publicationContext(),
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            AtomicSensorCoreRecord(
                raw,
                result,
                checkpoint.copy(sensorId = "another"),
                reading(),
                publicationContext(),
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            AtomicSensorCoreRecord(
                raw,
                result,
                checkpoint,
                reading().copy(eventId = "another"),
                publicationContext(),
            )
        }
    }

    @Test
    fun entityBundlePreservesRawAndAlgorithmProvenance() {
        val record = AtomicSensorCoreRecord(
            raw(),
            result(),
            checkpoint(),
            reading(),
            publicationContext(),
        )

        val bundle = record.toEntityBundle()

        assertArrayEquals(byteArrayOf(1, 2, 3, 4), bundle.raw.packet)
        assertEquals("V116A", bundle.result.algorithmProfile)
        assertEquals("PACKAGE_CODE", bundle.result.sensitivityTokenSource)
        assertEquals(1.42, bundle.result.sensitivityCoefficient, 0.0001)
        assertEquals("NORMAL", bundle.result.sensitivityEncoding)
        assertEquals("STANDARD", bundle.result.initializationMode)
        assertEquals(true, bundle.result.alarmEligible)
        assertEquals(null, bundle.result.algorithmErrorCode)
        assertEquals(1, bundle.checkpoint.schemaVersion)
        assertEquals("AA:BB:CC:DD:EE:FF", bundle.checkpoint.bluetoothAddress)
        assertEquals(SensorFamily.SIBIONICS_GS1.wireName, bundle.checkpoint.sensorFamily)
        assertEquals("GS1_V120", bundle.checkpoint.transportProtocol)
        assertEquals(1_700_000_000_000L, bundle.checkpoint.sensorStartTimeEpochMs)
        assertEquals(101, bundle.measurement?.glucoseMgDl)
        assertEquals(publicationContext().approvalId, bundle.measurement?.publicationApprovalId)
        assertEquals("backend-binding-a", bundle.measurement?.backendBindingId)
        assertEquals(3L, bundle.measurement?.credentialRevision)
    }

    @Test
    fun checkpointRejectsAnythingTheAlgorithmCannotRestore() {
        val valid = checkpoint()

        assertThrows(IllegalArgumentException::class.java) {
            valid.copy(sensitivityToken = "!!!!!!!!")
        }
        assertThrows(IllegalArgumentException::class.java) {
            valid.copy(schemaVersion = 2)
        }
        assertThrows(IllegalArgumentException::class.java) {
            valid.copy(bluetoothAddress = "aa:bb:cc:dd:ee:ff")
        }
        assertThrows(IllegalArgumentException::class.java) {
            valid.copy(sensorStartTimeEpochMs = valid.sensorStartTimeEpochMs + 60_000L)
        }
        assertThrows(IllegalArgumentException::class.java) {
            valid.copy(state = ByteArray(2_479), stateSha256 = ByteArray(2_479).sha256())
        }
        assertThrows(IllegalArgumentException::class.java) {
            valid.copy(
                algorithmProfile = "V115G",
                state = ByteArray(2_480),
                stateSha256 = ByteArray(2_480).sha256(),
            )
        }
    }

    @Test
    fun factionInitializationProvenanceIsAcceptedOnlyWhenResultAndCheckpointAgree() {
        val factionResult = result().copy(
            sensitivityEncoding = "FACTION",
            initializationMode = "FACTION",
        )
        val factionCheckpoint = checkpoint().copy(
            sensitivityEncoding = "FACTION",
            initializationMode = "FACTION",
        )

        val record = AtomicSensorCoreRecord(
            raw = raw(),
            result = factionResult,
            checkpoint = factionCheckpoint,
            measurement = reading(),
            publicationContext = publicationContext(),
        )

        assertEquals("FACTION", record.result.initializationMode)
        assertEquals("FACTION", record.checkpoint.sensitivityEncoding)
    }

    @Test
    fun productMeasurementRequiresTypedPublicationContext() {
        assertThrows(IllegalArgumentException::class.java) {
            AtomicSensorCoreRecord(raw(), result(), checkpoint(), reading(), publicationContext = null)
        }
    }

    @Test
    fun onlyValidQualityCanBecomeAProductMeasurement() {
        val warming = reading().copy(quality = ReadingQuality.WARMING_UP)

        assertThrows(IllegalArgumentException::class.java) {
            AtomicSensorCoreRecord(
                raw(),
                result(),
                checkpoint(),
                warming,
                publicationContext(),
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            AtomicSensorCoreRecord(
                raw(),
                result().copy(alarmEligible = false),
                checkpoint(),
                warming,
                publicationContext(),
            )
        }
    }

    @Test
    fun approvedCheckpointCanAdvanceWithoutCreatingAProductMeasurement() {
        val publication = publicationContext()
        val approval = publication.approvedCheckpointContext()
        val stateOnly = AtomicSensorCoreRecord(
            raw = raw(),
            result = result().copy(
                publishable = false,
                alarmEligible = false,
                algorithmErrorCode = "HISTORICAL_SAMPLE",
                publicationApprovalId = publication.approvalId,
            ),
            checkpoint = checkpoint().copy(
                publicationApprovalId = publication.approvalId,
            ),
            measurement = null,
            publicationContext = null,
            approvedCheckpointContext = approval,
        )

        val bundle = stateOnly.toEntityBundle()
        assertEquals(null, bundle.measurement)
        assertEquals(null, bundle.publicationContext)
        assertEquals(approval, bundle.approvedCheckpointContext)
        assertEquals(publication.approvalId, bundle.result.publicationApprovalId)
        assertEquals(publication.approvalId, bundle.checkpoint.publicationApprovalId)
    }

    @Test
    fun productMeasurementRejectsAStaleApprovedCheckpointBinding() {
        val publication = publicationContext()

        assertThrows(IllegalArgumentException::class.java) {
            AtomicSensorCoreRecord(
                raw = raw(),
                result = result(),
                checkpoint = checkpoint(),
                measurement = reading(),
                publicationContext = publication,
                approvedCheckpointContext = publication.approvedCheckpointContext().copy(
                    publicationBindingId = "ef".repeat(32),
                ),
            )
        }
    }

    private fun raw(packet: ByteArray = byteArrayOf(1, 2, 3, 4)) = RawSensorSampleRecord(
        eventId = "event-10",
        sourceIngressId = "attempt-a:10",
        sensorId = "sensor-a",
        sensorFamily = SensorFamily.SIBIONICS_GS1,
        sequence = 10,
        sensorTimeEpochMs = 1_700_000_600_000L,
        phoneTimeEpochMs = 1_700_000_601_000L,
        packet = packet,
        packetSha256 = packet.sha256(),
        currentRaw = 52,
        temperatureRaw = 321,
        historyDistance = 0,
        transportVariant = 0,
    )

    private fun result() = SensorAlgorithmResultRecord(
        eventId = "event-10",
        sensorId = "sensor-a",
        sequence = 10,
        sensorTimeEpochMs = 1_700_000_600_000L,
        nativeGlucoseMmolL = 5.61,
        displayedGlucoseMmolL = 5.61,
        nativeTrend = 2,
        glucoseWarning = 0,
        currentWarning = 0,
        temperatureWarning = 0,
        algorithmProfile = "V116A",
        algorithmVersion = "1.1.6A",
        binarySetId = "v116a-arm64-test",
        sensitivityToken = "ABCDEFGH",
        sensitivityTokenSource = "PACKAGE_CODE",
        sensitivityCoefficient = 1.42,
        sensitivityEncoding = "NORMAL",
        initializationMode = "STANDARD",
        publishable = true,
        publicationApprovalId = "ab".repeat(32),
    )

    private fun checkpoint() = SensorAlgorithmCheckpointRecord(
        sensorId = "sensor-a",
        bluetoothAddress = "AA:BB:CC:DD:EE:FF",
        sensorFamily = SensorFamily.SIBIONICS_GS1,
        transportVariant = 0,
        transportProtocol = "GS1_V120",
        transportCodecId = "transport-codec-test",
        sequence = 10,
        sensorTimeEpochMs = 1_700_000_600_000L,
        sensorStartTimeEpochMs = 1_700_000_000_000L,
        algorithmProfile = "V116A",
        algorithmVersion = "1.1.6A",
        binarySetId = "v116a-arm64-test",
        sensitivityToken = "ABCDEFGH",
        sensitivityTokenSource = "PACKAGE_CODE",
        sensitivityCoefficient = 1.42,
        sensitivityEncoding = "NORMAL",
        initializationMode = "STANDARD",
        state = ByteArray(2_480) { it.toByte() },
        stateSha256 = ByteArray(2_480) { it.toByte() }.sha256(),
        displayOffsetMmolL = 0.4,
        schemaVersion = 1,
        publicationApprovalId = "ab".repeat(32),
    )

    private fun reading() = GlucoseReading(
        eventId = "event-10",
        sensorId = "sensor-a",
        sensorFamily = SensorFamily.SIBIONICS_GS1,
        sensorTimeEpochMs = 1_700_000_600_000L,
        phoneTimeEpochMs = 1_700_000_601_000L,
        glucoseMgDl = 101,
        trendMgDlPerMinute = -1.0,
        quality = ReadingQuality.VALID,
        sequence = 10,
    )

    private fun publicationContext() = ProductPublicationContext(
        approvalId = "ab".repeat(32),
        publicationBindingId = "cd".repeat(32),
        httpsOrigin = "https://api.sladkaya.test",
        backendBindingId = "backend-binding-a",
        credentialId = "credential-a",
        credentialRevision = 3L,
        expectedPatientId = "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa",
        expectedDeviceId = "bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb",
        nativeBinarySetSha256 = "12".repeat(32),
        nativeDatahandleBinarySetSha256 = "34".repeat(32),
    )

    private fun ByteArray.sha256(): String = MessageDigest.getInstance("SHA-256")
        .digest(this)
        .joinToString("") { "%02x".format(it) }
}
