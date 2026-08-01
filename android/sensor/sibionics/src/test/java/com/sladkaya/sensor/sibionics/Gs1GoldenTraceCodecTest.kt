package com.sladkaya.sensor.sibionics

import com.sladkaya.core.model.SensorFamily
import com.sladkaya.sensor.sibionics.algorithm.AlgorithmErrorCode
import com.sladkaya.sensor.sibionics.algorithm.AlgorithmInitializationMode
import com.sladkaya.sensor.sibionics.algorithm.AlgorithmProfile
import com.sladkaya.sensor.sibionics.algorithm.SensitivityEncoding
import com.sladkaya.sensor.sibionics.algorithm.SensitivityTokenSource
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class Gs1GoldenTraceCodecTest {
    private val codec = Gs1GoldenTraceCodec()

    @Test
    fun canonicalRoundTripPreservesPseudonymousEvidenceAndExactBits() {
        val trace = syntheticGoldenTrace()

        val first = codec.encode(trace)
        val second = codec.encode(trace)
        val parsed = codec.decode(first) as Gs1GoldenTraceDecodeResult.Success

        assertArrayEquals(first, second)
        assertArrayEquals(first, codec.encode(parsed.trace))
        assertEquals(Gs1GoldenTraceProvenance.SYNTHETIC_TEST_ONLY, parsed.trace.provenance)
        assertEquals(
            Gs1GoldenPrivacyClassification.SYNTHETIC_PUBLIC_FIXTURE,
            parsed.trace.privacyClassification,
        )
        assertEquals(SensorFamily.SIBIONICS_GS1, parsed.trace.family)
        assertEquals(
            AlgorithmInitializationMode.STANDARD,
            parsed.trace.sensitivityEvidence.initializationMode,
        )
        assertEquals(
            SensitivityTokenSource.PACKAGE_CODE,
            parsed.trace.sensitivityEvidence.tokenSource,
        )
        assertEquals(
            SensitivityEncoding.NORMAL,
            parsed.trace.sensitivityEvidence.encoding,
        )
        assertEquals(
            SYNTHETIC_SENSITIVITY_COEFFICIENT.toBits(),
            parsed.trace.sensitivityEvidence.coefficientBits,
        )
        assertEquals(6.25.toBits(), parsed.trace.notifications.first().samples.first().diagnostic.nativeGlucoseMmolLBits)
        assertFalse(first.decodeToString().contains("AA:BB:CC:DD:EE:FF"))
    }

    @Test
    fun unknownVersionCorruptionTruncationAndTrailingBytesFailClosed() {
        val encoded = codec.encode(syntheticGoldenTrace())

        val unknownVersion = encoded.copyOf().also { bytes ->
            val marker = "version=0001".encodeToByteArray()
            val offset = bytes.indexOfSlice(marker)
            bytes[offset + marker.lastIndex] = '2'.code.toByte()
        }
        val corrupted = encoded.copyOf().also { bytes ->
            val payloadOffset = bytes.indexOfSlice("\n\n".encodeToByteArray()) + 2
            bytes[payloadOffset + 8] = (bytes[payloadOffset + 8].toInt() xor 1).toByte()
        }
        val truncated = encoded.copyOf(encoded.size - 1)
        val trailing = encoded + byteArrayOf(0)

        assertFailure(Gs1GoldenTraceError.UNKNOWN_VERSION, codec.decode(unknownVersion))
        assertFailure(Gs1GoldenTraceError.INTEGRITY_MISMATCH, codec.decode(corrupted))
        assertFailure(Gs1GoldenTraceError.TRUNCATED, codec.decode(truncated))
        assertFailure(Gs1GoldenTraceError.TRAILING_DATA, codec.decode(trailing))
    }

    @Test
    fun integrityValidTraceWithConflictingIngressSequenceIsRejectedByParser() {
        val encoded = codec.encode(syntheticGoldenTrace())
        val payload = encoded.payloadText()
        val conflicting = payload.replaceFirst(
            "notification\t1\t",
            "notification\t0\t",
        )
        require(conflicting != payload)

        val rewritten = encoded.withPayload(conflicting.encodeToByteArray())

        assertFailure(Gs1GoldenTraceError.CONFLICTING_SEQUENCE, codec.decode(rewritten))
    }

    @Test
    fun integrityValidTraceWithConflictingDecodedSequenceIsRejectedByParser() {
        val encoded = codec.encode(syntheticGoldenTrace())
        val payload = encoded.payloadText()
        val conflicting = payload.replaceFirst(
            "sample\t2\t1700000120",
            "sample\t1\t1700000120",
        )
        require(conflicting != payload)

        assertFailure(
            Gs1GoldenTraceError.CONFLICTING_SEQUENCE,
            codec.decode(encoded.withPayload(conflicting.encodeToByteArray())),
        )
    }

    @Test
    fun sameVersionedFormatAcceptsGs1SbDiagnosticTrace() {
        val encoded = codec.encode(
            syntheticGoldenTrace().copy(family = SensorFamily.SIBIONICS_GS1SB),
        )

        val parsed = codec.decode(encoded) as Gs1GoldenTraceDecodeResult.Success

        assertEquals(SensorFamily.SIBIONICS_GS1SB, parsed.trace.family)
    }

    @Test
    fun checkedInFixtureIsExplicitlySyntheticAndCanonical() {
        val bytes = checkNotNull(javaClass.getResourceAsStream("/golden/gs1-synthetic-v1.trace"))
            .use { it.readBytes() }

        val parsed = codec.decode(bytes) as Gs1GoldenTraceDecodeResult.Success

        assertEquals(Gs1GoldenTraceProvenance.SYNTHETIC_TEST_ONLY, parsed.trace.provenance)
        assertTrue(parsed.trace.traceId.startsWith("synthetic-"))
        assertArrayEquals(codec.encode(syntheticGoldenTrace()), bytes)
        assertArrayEquals(bytes, codec.encode(parsed.trace))
        assertTrue(
            Gs1GoldenFixturePublicationGate.evaluate(parsed.trace, bytes) is
                Gs1GoldenFixturePublicationResult.AllowedSyntheticFixture,
        )
    }

    @Test
    fun repositoryGoldenDirectoryContainsOnlyThePinnedDeterministicFixture() {
        val root = Path.of(checkNotNull(javaClass.getResource("/golden")).toURI())
        val files = Files.walk(root).use { paths ->
            paths.filter(Files::isRegularFile)
                .map { root.relativize(it).toString() }
                .sorted()
                .toList()
        }

        assertEquals(listOf("gs1-synthetic-v1.trace"), files)
    }

    @Test
    fun selfDeclaredSyntheticMetadataCannotPublishDifferentPacketEvidence() {
        val base = syntheticGoldenTrace()
        val first = base.notifications.first()
        val differentPacket = byteArrayOf(0x61, 0x62, 0x63)
        val mislabeled = base.copy(
            notifications = listOf(
                first.copy(
                    encryptedPacket = differentPacket,
                    packetSha256 = differentPacket.sha256(),
                ),
                base.notifications.last(),
            ),
        )
        val bytes = codec.encode(mislabeled)

        assertTrue(
            Gs1GoldenFixturePublicationGate.evaluate(mislabeled, bytes) is
                Gs1GoldenFixturePublicationResult.Blocked,
        )
    }

    @Test
    fun privateReferenceCaptureIsNeverEligibleForRepositoryFixturePublication() {
        val privateTrace = syntheticGoldenTrace().copy(
            provenance = Gs1GoldenTraceProvenance.PRIVATE_REFERENCE_CAPTURE,
            privacyClassification = Gs1GoldenPrivacyClassification.PRIVATE_SENSITIVE_EVIDENCE,
            macIdentity = syntheticGoldenTrace().macIdentity.copy(
                evidenceKind = Gs1GoldenIdentityEvidenceKind.MANUAL_CODE_AND_ADVERTISEMENT,
            ),
        )

        val result = Gs1GoldenFixturePublicationGate.evaluate(privateTrace, codec.encode(privateTrace))

        result as Gs1GoldenFixturePublicationResult.Blocked
        assertEquals(Gs1GoldenPrivacyClassification.PRIVATE_SENSITIVE_EVIDENCE, result.classification)
    }

    @Test
    fun plannerRejectsNonStandardInitializationAndNonNormalSensitivity() {
        val base = syntheticGoldenTrace()

        val wrongMode = Gs1GoldenReplayPlanner().plan(
            base.copy(
                sensitivityEvidence = base.sensitivityEvidence.copy(
                    initializationMode = AlgorithmInitializationMode.FACTION,
                ),
            ),
        )
        val wrongEncoding = Gs1GoldenReplayPlanner().plan(
            base.copy(
                sensitivityEvidence = base.sensitivityEvidence.copy(
                    encoding = SensitivityEncoding.FACTION,
                ),
            ),
        )

        assertTrue(wrongMode is Gs1GoldenReplayPlanResult.Invalid)
        assertTrue(wrongEncoding is Gs1GoldenReplayPlanResult.Invalid)
    }

    @Test
    fun oversizedPayloadLineFailsAtTheBoundedCursorBeforeFieldValidation() {
        val encoded = codec.encode(syntheticGoldenTrace())
        val payload = encoded.payloadText()
        val oversized = payload.replaceFirst(
            Regex("trace-id\t[^\n]+"),
            "trace-id\t${"61".repeat(2_000)}",
        )

        val result = codec.decode(encoded.withPayload(oversized.encodeToByteArray()))

        result as Gs1GoldenTraceDecodeResult.Failure
        assertEquals(Gs1GoldenTraceError.INVALID_FIELD, result.error)
        assertTrue(result.detail.contains("line exceeds"))
    }

    private fun assertFailure(
        expected: Gs1GoldenTraceError,
        actual: Gs1GoldenTraceDecodeResult,
    ) {
        actual as Gs1GoldenTraceDecodeResult.Failure
        assertEquals(expected, actual.error)
    }

    private fun ByteArray.payloadText(): String {
        val payloadOffset = indexOfSlice("\n\n".encodeToByteArray()) + 2
        return copyOfRange(payloadOffset, size).decodeToString()
    }

    private fun ByteArray.withPayload(payload: ByteArray): ByteArray {
        val magic = "GS1-GOLDEN-TRACE\n" +
            "version=0001\n" +
            "payload-length=${payload.size.toString().padStart(10, '0')}\n" +
            "payload-sha256=${payload.sha256()}\n\n"
        return magic.encodeToByteArray() + payload
    }

    private fun ByteArray.indexOfSlice(needle: ByteArray): Int {
        for (offset in 0..size - needle.size) {
            if (needle.indices.all { this[offset + it] == needle[it] }) return offset
        }
        error("marker not found")
    }
}

internal fun syntheticGoldenTrace(
    secondTrend: Int = -2,
) = Gs1GoldenTrace(
    formatVersion = 1,
    traceId = "synthetic-gs1-codec-v1",
    provenance = Gs1GoldenTraceProvenance.SYNTHETIC_TEST_ONLY,
    privacyClassification = Gs1GoldenPrivacyClassification.SYNTHETIC_PUBLIC_FIXTURE,
    family = SensorFamily.SIBIONICS_GS1,
    algorithmProfile = AlgorithmProfile.V116A,
    algorithmVersion = "synthetic-native-v1",
    algorithmBinarySetId = "synthetic-binary-set-v1",
    transportProtocol = "GS1_V120",
    sensitivityEvidence = Gs1GoldenSensitivityEvidence(
        initializationMode = AlgorithmInitializationMode.STANDARD,
        tokenSource = SensitivityTokenSource.PACKAGE_CODE,
        encoding = SensitivityEncoding.NORMAL,
        coefficientBits = SYNTHETIC_SENSITIVITY_COEFFICIENT.toBits(),
        inputHmacScheme = Gs1GoldenSensitivityHmacScheme.HMAC_SHA256_TRACE_LOCAL_V1,
        inputHmacSha256 = syntheticSensitivityInputHmac(),
    ),
    macIdentity = Gs1GoldenMacIdentity(
        pseudonymScheme = Gs1GoldenMacPseudonymScheme.HMAC_SHA256_TRACE_LOCAL_V1,
        macPseudonym = "11".repeat(32),
        evidenceKind = Gs1GoldenIdentityEvidenceKind.SYNTHETIC,
        evidenceHmacSha256 = "22".repeat(32),
    ),
    notifications = listOf(
        goldenNotification(
            attemptOrdinal = 0,
            attemptPseudonym = "synthetic-attempt-a",
            ordinal = 0,
            receivedAtEpochMs = 1_700_000_061_000L,
            packet = byteArrayOf(0x41, 0x42, 0x43),
            samples = listOf(
                goldenSample(
                    index = 1,
                    sensorTimeEpochSeconds = 1_700_000_060L,
                    nativeGlucoseMmolL = 6.25,
                    displayedGlucoseMmolL = 5.0,
                    trend = 1,
                    stateByte = 1,
                ),
            ),
        ),
        goldenNotification(
            attemptOrdinal = 1,
            attemptPseudonym = "synthetic-attempt-b",
            ordinal = 0,
            receivedAtEpochMs = 1_700_000_121_000L,
            packet = byteArrayOf(0x51, 0x52, 0x53),
            samples = listOf(
                goldenSample(
                    index = 2,
                    sensorTimeEpochSeconds = 1_700_000_120L,
                    nativeGlucoseMmolL = 6.5,
                    displayedGlucoseMmolL = 5.1,
                    trend = secondTrend,
                    stateByte = 2,
                ),
            ),
        ),
    ),
)

private fun goldenNotification(
    attemptOrdinal: Int,
    attemptPseudonym: String,
    ordinal: Long,
    receivedAtEpochMs: Long,
    packet: ByteArray,
    samples: List<Gs1GoldenExpectedSample>,
) = Gs1GoldenNotification(
    attemptOrdinal = attemptOrdinal,
    attemptPseudonym = attemptPseudonym,
    ordinal = ordinal,
    receivedAtEpochMs = receivedAtEpochMs,
    encryptedPacket = packet,
    packetSha256 = packet.sha256(),
    expectedDecode = Gs1GoldenDecodeExpectation.GS1_DATA,
    expectedDecodeError = null,
    expectedDecrypted = true,
    samples = samples,
)

private fun goldenSample(
    index: Int,
    sensorTimeEpochSeconds: Long,
    nativeGlucoseMmolL: Double,
    displayedGlucoseMmolL: Double,
    trend: Int,
    stateByte: Int,
    algorithmErrorCode: AlgorithmErrorCode? = null,
) = Gs1GoldenExpectedSample(
    decoded = DecodedGs1RawSample(
        index = index,
        sensorTimeEpochSeconds = sensorTimeEpochSeconds,
        current = 49 + index,
        temperature = 320 + index,
        reindex = 0,
    ),
    diagnostic = Gs1GoldenExpectedDiagnostic(
        nativeGlucoseMmolLBits = nativeGlucoseMmolL.toBits(),
        displayedGlucoseMmolLBits = displayedGlucoseMmolL.toBits(),
        trend = trend,
        glucoseWarning = index,
        currentWarning = index + 10,
        temperatureWarning = index + 20,
        stateSha256 = ByteArray(AlgorithmProfile.V116A.stateSize) { stateByte.toByte() }.sha256(),
        algorithmErrorCode = algorithmErrorCode,
    ),
)

internal fun ByteArray.sha256(): String = MessageDigest.getInstance("SHA-256")
    .digest(this)
    .joinToString(separator = "") { "%02x".format(it.toInt() and 0xff) }

internal const val SYNTHETIC_SENSITIVITY_INPUT = "SYNTH001"
internal const val SYNTHETIC_SENSITIVITY_COEFFICIENT = 1.42f
internal val SYNTHETIC_GOLDEN_HMAC_KEY = "synthetic-golden-test-key-v1".encodeToByteArray()

internal fun syntheticSensitivityInputHmac(): String {
    val mac = Mac.getInstance("HmacSHA256")
    mac.init(SecretKeySpec(SYNTHETIC_GOLDEN_HMAC_KEY, "HmacSHA256"))
    return mac.doFinal(
        gs1GoldenSensitivityBindingMessage(
            traceId = "synthetic-gs1-codec-v1",
            tokenSource = SensitivityTokenSource.PACKAGE_CODE,
            exactInput = SYNTHETIC_SENSITIVITY_INPUT,
        ),
    ).joinToString(separator = "") { "%02x".format(it.toInt() and 0xff) }
}
