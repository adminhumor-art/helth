package com.sladkaya.sensor.sibionics

import com.sladkaya.core.model.SensorFamily
import com.sladkaya.sensor.sibionics.algorithm.AlgorithmErrorCode
import com.sladkaya.sensor.sibionics.algorithm.AlgorithmInitializationMode
import com.sladkaya.sensor.sibionics.algorithm.AlgorithmProfile
import com.sladkaya.sensor.sibionics.algorithm.SensitivityEncoding
import com.sladkaya.sensor.sibionics.algorithm.SensitivityTokenSource
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import java.security.MessageDigest

internal enum class Gs1GoldenTraceProvenance {
    /** Parser/runner plumbing only. Never release or medical evidence. */
    SYNTHETIC_TEST_ONLY,

    /** Private evidence compared with a pinned reference build; never commit it. */
    PRIVATE_REFERENCE_CAPTURE,
}

internal enum class Gs1GoldenPrivacyClassification {
    SYNTHETIC_PUBLIC_FIXTURE,
    PRIVATE_SENSITIVE_EVIDENCE,
}

internal sealed interface Gs1GoldenFixturePublicationResult {
    data object AllowedSyntheticFixture : Gs1GoldenFixturePublicationResult
    data class Blocked(
        val classification: Gs1GoldenPrivacyClassification,
    ) : Gs1GoldenFixturePublicationResult
}

/**
 * Exact allowlist gate for repository fixtures. Metadata alone is not trusted:
 * only the canonical bytes of the reviewed deterministic fixture are accepted.
 */
internal object Gs1GoldenFixturePublicationGate {
    fun evaluate(
        trace: Gs1GoldenTrace,
        candidateBytes: ByteArray,
    ): Gs1GoldenFixturePublicationResult {
        val canonicalBytes = try {
            Gs1GoldenTraceCodec().encode(trace)
        } catch (_: IllegalArgumentException) {
            null
        }
        return if (trace.provenance == Gs1GoldenTraceProvenance.SYNTHETIC_TEST_ONLY &&
            trace.privacyClassification == Gs1GoldenPrivacyClassification.SYNTHETIC_PUBLIC_FIXTURE &&
            trace.macIdentity.evidenceKind == Gs1GoldenIdentityEvidenceKind.SYNTHETIC &&
            trace.traceId.startsWith(SYNTHETIC_PREFIX) &&
            trace.algorithmVersion.startsWith(SYNTHETIC_PREFIX) &&
            trace.algorithmBinarySetId.startsWith(SYNTHETIC_PREFIX) &&
            canonicalBytes != null &&
            MessageDigest.isEqual(canonicalBytes, candidateBytes) &&
            candidateBytes.gs1GoldenSha256() == PINNED_SYNTHETIC_FIXTURE_SHA256
        ) {
            Gs1GoldenFixturePublicationResult.AllowedSyntheticFixture
        } else {
            Gs1GoldenFixturePublicationResult.Blocked(trace.privacyClassification)
        }
    }

    private const val SYNTHETIC_PREFIX = "synthetic-"
    private const val PINNED_SYNTHETIC_FIXTURE_SHA256 =
        "8fd34810e448ce7a6c3fb0f6918a0b0786fd026f91662b958292887b37a0838b"
}

internal enum class Gs1GoldenMacPseudonymScheme {
    HMAC_SHA256_TRACE_LOCAL_V1,
}

internal enum class Gs1GoldenIdentityEvidenceKind {
    SYNTHETIC,
    MANUAL_CODE_AND_ADVERTISEMENT,
    DATA_MATRIX_AND_ADVERTISEMENT,
}

internal enum class Gs1GoldenSensitivityHmacScheme {
    HMAC_SHA256_TRACE_LOCAL_V1,
}

internal data class Gs1GoldenSensitivityEvidence(
    val initializationMode: AlgorithmInitializationMode,
    val tokenSource: SensitivityTokenSource,
    val encoding: SensitivityEncoding,
    /** Exact IEEE-754 Float bits returned by the sensitivity decoder. */
    val coefficientBits: Int,
    val inputHmacScheme: Gs1GoldenSensitivityHmacScheme,
    /** HMAC of the exact case-sensitive 8-character input; key stays outside the trace. */
    val inputHmacSha256: String,
)

internal enum class Gs1GoldenDecodeExpectation {
    GS1_DATA,
    NON_DATA,
    REJECTED,
}

internal data class Gs1GoldenMacIdentity(
    val pseudonymScheme: Gs1GoldenMacPseudonymScheme,
    /** Lower-case HMAC-SHA-256 only; a canonical MAC is never stored here. */
    val macPseudonym: String,
    val evidenceKind: Gs1GoldenIdentityEvidenceKind,
    /** Trace-local HMAC of private onboarding evidence; neither key nor evidence is stored. */
    val evidenceHmacSha256: String,
)

internal data class Gs1GoldenExpectedDiagnostic(
    /** Exact IEEE-754 bits avoid locale/decimal normalization during comparison. */
    val nativeGlucoseMmolLBits: Long,
    val displayedGlucoseMmolLBits: Long,
    val trend: Int,
    val glucoseWarning: Int,
    val currentWarning: Int,
    val temperatureWarning: Int,
    val stateSha256: String,
    val algorithmErrorCode: AlgorithmErrorCode?,
)

internal data class Gs1GoldenExpectedSample(
    val decoded: DecodedGs1RawSample,
    val diagnostic: Gs1GoldenExpectedDiagnostic,
)

/** One exact append-only BLE ingress receipt and its expected diagnostic interpretation. */
internal class Gs1GoldenNotification(
    val attemptOrdinal: Int,
    val attemptPseudonym: String,
    val ordinal: Long,
    val receivedAtEpochMs: Long,
    encryptedPacket: ByteArray,
    val packetSha256: String,
    val expectedDecode: Gs1GoldenDecodeExpectation,
    val expectedDecodeError: Gs1VerifiedPacketError?,
    val expectedDecrypted: Boolean,
    samples: List<Gs1GoldenExpectedSample>,
) {
    private val packet = encryptedPacket.copyOf()
    val samples: List<Gs1GoldenExpectedSample> = samples.toList()

    fun encryptedPacketCopy(): ByteArray = packet.copyOf()

    fun copy(
        attemptOrdinal: Int = this.attemptOrdinal,
        attemptPseudonym: String = this.attemptPseudonym,
        ordinal: Long = this.ordinal,
        receivedAtEpochMs: Long = this.receivedAtEpochMs,
        encryptedPacket: ByteArray = this.packet,
        packetSha256: String = this.packetSha256,
        expectedDecode: Gs1GoldenDecodeExpectation = this.expectedDecode,
        expectedDecodeError: Gs1VerifiedPacketError? = this.expectedDecodeError,
        expectedDecrypted: Boolean = this.expectedDecrypted,
        samples: List<Gs1GoldenExpectedSample> = this.samples,
    ) = Gs1GoldenNotification(
        attemptOrdinal = attemptOrdinal,
        attemptPseudonym = attemptPseudonym,
        ordinal = ordinal,
        receivedAtEpochMs = receivedAtEpochMs,
        encryptedPacket = encryptedPacket,
        packetSha256 = packetSha256,
        expectedDecode = expectedDecode,
        expectedDecodeError = expectedDecodeError,
        expectedDecrypted = expectedDecrypted,
        samples = samples,
    )
}

/**
 * Versioned golden input. A real capture is private sensitive evidence even
 * though direct identifiers and the sensitivity input are not stored as fields.
 */
internal class Gs1GoldenTrace(
    val formatVersion: Int,
    val traceId: String,
    val provenance: Gs1GoldenTraceProvenance,
    val privacyClassification: Gs1GoldenPrivacyClassification,
    val family: SensorFamily,
    val algorithmProfile: AlgorithmProfile,
    val algorithmVersion: String,
    val algorithmBinarySetId: String,
    val transportProtocol: String,
    val sensitivityEvidence: Gs1GoldenSensitivityEvidence,
    val macIdentity: Gs1GoldenMacIdentity,
    notifications: List<Gs1GoldenNotification>,
) {
    val notifications: List<Gs1GoldenNotification> = notifications.toList()

    fun copy(
        formatVersion: Int = this.formatVersion,
        traceId: String = this.traceId,
        provenance: Gs1GoldenTraceProvenance = this.provenance,
        privacyClassification: Gs1GoldenPrivacyClassification = this.privacyClassification,
        family: SensorFamily = this.family,
        algorithmProfile: AlgorithmProfile = this.algorithmProfile,
        algorithmVersion: String = this.algorithmVersion,
        algorithmBinarySetId: String = this.algorithmBinarySetId,
        transportProtocol: String = this.transportProtocol,
        sensitivityEvidence: Gs1GoldenSensitivityEvidence = this.sensitivityEvidence,
        macIdentity: Gs1GoldenMacIdentity = this.macIdentity,
        notifications: List<Gs1GoldenNotification> = this.notifications,
    ) = Gs1GoldenTrace(
        formatVersion = formatVersion,
        traceId = traceId,
        provenance = provenance,
        privacyClassification = privacyClassification,
        family = family,
        algorithmProfile = algorithmProfile,
        algorithmVersion = algorithmVersion,
        algorithmBinarySetId = algorithmBinarySetId,
        transportProtocol = transportProtocol,
        sensitivityEvidence = sensitivityEvidence,
        macIdentity = macIdentity,
        notifications = notifications,
    )
}

internal enum class Gs1GoldenTraceError {
    TRUNCATED,
    TRAILING_DATA,
    INVALID_HEADER,
    UNKNOWN_VERSION,
    INVALID_LENGTH,
    INTEGRITY_MISMATCH,
    INVALID_ENCODING,
    INVALID_FIELD,
    CONFLICTING_SEQUENCE,
    TOO_LARGE,
}

internal sealed interface Gs1GoldenTraceDecodeResult {
    data class Success(val trace: Gs1GoldenTrace) : Gs1GoldenTraceDecodeResult
    data class Failure(
        val error: Gs1GoldenTraceError,
        val detail: String,
    ) : Gs1GoldenTraceDecodeResult
}

/** Canonical UTF-8 codec with a fixed header, payload length and SHA-256. */
internal class Gs1GoldenTraceCodec(
    private val planner: Gs1GoldenReplayPlanner = Gs1GoldenReplayPlanner(),
) {
    fun encode(trace: Gs1GoldenTrace): ByteArray {
        val checked = planner.plan(trace)
        require(checked is Gs1GoldenReplayPlanResult.Ready) {
            (checked as Gs1GoldenReplayPlanResult.Invalid).detail
        }
        val payload = encodePayload(trace).encodeToByteArray()
        require(payload.size <= MAX_PAYLOAD_BYTES)
        val header = buildString {
            append(MAGIC_LINE).append('\n')
            append(VERSION_PREFIX).append(trace.formatVersion.toString().padStart(VERSION_DIGITS, '0')).append('\n')
            append(LENGTH_PREFIX).append(payload.size.toString().padStart(LENGTH_DIGITS, '0')).append('\n')
            append(HASH_PREFIX).append(payload.gs1GoldenSha256()).append('\n')
            append('\n')
        }.encodeToByteArray()
        return header + payload
    }

    fun decode(bytes: ByteArray): Gs1GoldenTraceDecodeResult {
        if (bytes.size > MAX_TRACE_BYTES) return failure(Gs1GoldenTraceError.TOO_LARGE, "trace exceeds size limit")
        val headerEnd = bytes.indexOf(HEADER_TERMINATOR)
        if (headerEnd < 0) return failure(Gs1GoldenTraceError.TRUNCATED, "complete header is missing")
        if (headerEnd > MAX_HEADER_BYTES) {
            return failure(Gs1GoldenTraceError.INVALID_HEADER, "header exceeds size limit")
        }
        val header = bytes.copyOfRange(0, headerEnd).decodeUtf8()
            ?: return failure(Gs1GoldenTraceError.INVALID_ENCODING, "header is not canonical UTF-8")
        val lines = header.split('\n')
        if (lines.size != HEADER_LINES || lines[0] != MAGIC_LINE) {
            return failure(Gs1GoldenTraceError.INVALID_HEADER, "magic or header line count is invalid")
        }
        val versionText = lines[1].removeExactPrefix(VERSION_PREFIX)
            ?: return failure(Gs1GoldenTraceError.INVALID_HEADER, "version header is invalid")
        if (versionText.length != VERSION_DIGITS || !versionText.all { it in '0'..'9' }) {
            return failure(Gs1GoldenTraceError.INVALID_HEADER, "version is not fixed-width decimal")
        }
        val version = versionText.toIntOrNull()
            ?: return failure(Gs1GoldenTraceError.INVALID_HEADER, "version is not representable")
        if (version != CURRENT_VERSION) {
            return failure(Gs1GoldenTraceError.UNKNOWN_VERSION, "unsupported trace version $version")
        }
        val lengthText = lines[2].removeExactPrefix(LENGTH_PREFIX)
            ?: return failure(Gs1GoldenTraceError.INVALID_HEADER, "payload length header is invalid")
        if (lengthText.length != LENGTH_DIGITS || !lengthText.all { it in '0'..'9' }) {
            return failure(Gs1GoldenTraceError.INVALID_LENGTH, "payload length is not fixed-width decimal")
        }
        val payloadLength = lengthText.toIntOrNull()
            ?: return failure(Gs1GoldenTraceError.INVALID_LENGTH, "payload length is not representable")
        if (payloadLength !in 1..MAX_PAYLOAD_BYTES) {
            return failure(Gs1GoldenTraceError.INVALID_LENGTH, "payload length is outside bounds")
        }
        val expectedHash = lines[3].removeExactPrefix(HASH_PREFIX)
            ?: return failure(Gs1GoldenTraceError.INVALID_HEADER, "payload hash header is invalid")
        if (!expectedHash.isLowerHex(SHA256_HEX_CHARS)) {
            return failure(Gs1GoldenTraceError.INVALID_HEADER, "payload hash is not lower-case SHA-256")
        }
        val payloadOffset = headerEnd + HEADER_TERMINATOR.size
        val available = bytes.size - payloadOffset
        if (available < payloadLength) return failure(Gs1GoldenTraceError.TRUNCATED, "payload is truncated")
        if (available > payloadLength) return failure(Gs1GoldenTraceError.TRAILING_DATA, "bytes follow the declared payload")
        val payload = bytes.copyOfRange(payloadOffset, bytes.size)
        if (payload.gs1GoldenSha256() != expectedHash) {
            return failure(Gs1GoldenTraceError.INTEGRITY_MISMATCH, "payload SHA-256 does not match")
        }
        val payloadText = payload.decodeUtf8()
            ?: return failure(Gs1GoldenTraceError.INVALID_ENCODING, "payload is not canonical UTF-8")
        val parsed = parsePayload(version, payloadText)
        if (parsed is Gs1GoldenTraceDecodeResult.Failure) return parsed
        val trace = (parsed as Gs1GoldenTraceDecodeResult.Success).trace
        return when (val plan = planner.plan(trace)) {
            is Gs1GoldenReplayPlanResult.Ready -> parsed
            is Gs1GoldenReplayPlanResult.Invalid -> failure(plan.error, plan.detail)
        }
    }

    private fun encodePayload(trace: Gs1GoldenTrace): String = buildString {
        line("trace-id", trace.traceId.hexUtf8())
        line("provenance", trace.provenance.name)
        line("privacy-classification", trace.privacyClassification.name)
        line("family", trace.family.name)
        line("algorithm-profile", trace.algorithmProfile.name)
        line("algorithm-version", trace.algorithmVersion.hexUtf8())
        line("algorithm-binary-set", trace.algorithmBinarySetId.hexUtf8())
        line("transport-protocol", trace.transportProtocol.hexUtf8())
        line("initialization-mode", trace.sensitivityEvidence.initializationMode.name)
        line("sensitivity-token-source", trace.sensitivityEvidence.tokenSource.name)
        line("sensitivity-encoding", trace.sensitivityEvidence.encoding.name)
        line("sensitivity-coefficient-bits", trace.sensitivityEvidence.coefficientBits.toUnsignedHex8())
        line("sensitivity-input-hmac-scheme", trace.sensitivityEvidence.inputHmacScheme.name)
        line("sensitivity-input-hmac-sha256", trace.sensitivityEvidence.inputHmacSha256)
        line("mac-pseudonym-scheme", trace.macIdentity.pseudonymScheme.name)
        line("mac-pseudonym", trace.macIdentity.macPseudonym)
        line("identity-evidence-kind", trace.macIdentity.evidenceKind.name)
        line("identity-evidence-hmac-sha256", trace.macIdentity.evidenceHmacSha256)
        line("notification-count", trace.notifications.size.toString())
        trace.notifications.forEach { notification ->
            line(
                "notification",
                notification.attemptOrdinal.toString(),
                notification.attemptPseudonym.hexUtf8(),
                notification.ordinal.toString(),
                notification.receivedAtEpochMs.toString(),
                notification.encryptedPacketCopy().toLowerHex(),
                notification.packetSha256,
                notification.expectedDecode.name,
                notification.expectedDecodeError?.name ?: NONE,
                notification.expectedDecrypted.asDigit(),
                notification.samples.size.toString(),
            )
            notification.samples.forEach { sample ->
                line(
                    "sample",
                    sample.decoded.index.toString(),
                    sample.decoded.sensorTimeEpochSeconds.toString(),
                    sample.decoded.current.toString(),
                    sample.decoded.temperature.toString(),
                    sample.decoded.reindex.toString(),
                )
                line(
                    "diagnostic",
                    sample.diagnostic.nativeGlucoseMmolLBits.toUnsignedHex16(),
                    sample.diagnostic.displayedGlucoseMmolLBits.toUnsignedHex16(),
                    sample.diagnostic.trend.toString(),
                    sample.diagnostic.glucoseWarning.toString(),
                    sample.diagnostic.currentWarning.toString(),
                    sample.diagnostic.temperatureWarning.toString(),
                    sample.diagnostic.stateSha256,
                    sample.diagnostic.algorithmErrorCode?.name ?: NONE,
                )
            }
        }
    }

    private fun parsePayload(version: Int, payload: String): Gs1GoldenTraceDecodeResult {
        if (!payload.endsWith('\n')) return failure(Gs1GoldenTraceError.INVALID_FIELD, "payload lacks canonical final newline")
        val cursor = PayloadCursor(payload)
        return try {
            val traceId = cursor.field("trace-id").decodeHexUtf8()
            val provenance = cursor.field("provenance").enumValue<Gs1GoldenTraceProvenance>()
            val privacy = cursor.field("privacy-classification").enumValue<Gs1GoldenPrivacyClassification>()
            val family = cursor.field("family").enumValue<SensorFamily>()
            val profile = cursor.field("algorithm-profile").enumValue<AlgorithmProfile>()
            val algorithmVersion = cursor.field("algorithm-version").decodeHexUtf8()
            val binarySet = cursor.field("algorithm-binary-set").decodeHexUtf8()
            val transport = cursor.field("transport-protocol").decodeHexUtf8()
            val initializationMode = cursor.field("initialization-mode").enumValue<AlgorithmInitializationMode>()
            val tokenSource = cursor.field("sensitivity-token-source").enumValue<SensitivityTokenSource>()
            val sensitivityEncoding = cursor.field("sensitivity-encoding").enumValue<SensitivityEncoding>()
            val coefficientBits = cursor.field("sensitivity-coefficient-bits").strictUnsignedHexInt()
            val inputHmacScheme = cursor.field("sensitivity-input-hmac-scheme")
                .enumValue<Gs1GoldenSensitivityHmacScheme>()
            val inputHmac = cursor.field("sensitivity-input-hmac-sha256")
            val scheme = cursor.field("mac-pseudonym-scheme").enumValue<Gs1GoldenMacPseudonymScheme>()
            val macPseudonym = cursor.field("mac-pseudonym")
            val evidenceKind = cursor.field("identity-evidence-kind").enumValue<Gs1GoldenIdentityEvidenceKind>()
            val evidenceHmac = cursor.field("identity-evidence-hmac-sha256")
            val notificationCount = cursor.field("notification-count").strictInt()
            if (notificationCount !in 1..MAX_NOTIFICATIONS) cursor.invalid("notification count is outside bounds")
            val notifications = buildList(notificationCount) {
                repeat(notificationCount) {
                    val fields = cursor.tagged("notification", NOTIFICATION_FIELDS)
                    val attemptOrdinal = fields[1].strictInt()
                    val attemptPseudonym = fields[2].decodeHexUtf8()
                    val ordinal = fields[3].strictLong()
                    val receivedAt = fields[4].strictLong()
                    val packet = fields[5].decodeLowerHex()
                    val packetHash = fields[6]
                    val decodeExpectation = fields[7].enumValue<Gs1GoldenDecodeExpectation>()
                    val decodeError = fields[8].nullableEnum<Gs1VerifiedPacketError>()
                    val decrypted = fields[9].strictBoolean()
                    val sampleCount = fields[10].strictInt()
                    if (sampleCount !in 0..MAX_SAMPLES_PER_NOTIFICATION) cursor.invalid("sample count is outside bounds")
                    val samples = buildList(sampleCount) {
                        repeat(sampleCount) {
                            val sampleFields = cursor.tagged("sample", SAMPLE_FIELDS)
                            val diagnosticFields = cursor.tagged("diagnostic", DIAGNOSTIC_FIELDS)
                            add(
                                Gs1GoldenExpectedSample(
                                    decoded = DecodedGs1RawSample(
                                        index = sampleFields[1].strictInt(),
                                        sensorTimeEpochSeconds = sampleFields[2].strictLong(),
                                        current = sampleFields[3].strictInt(),
                                        temperature = sampleFields[4].strictInt(),
                                        reindex = sampleFields[5].strictInt(),
                                    ),
                                    diagnostic = Gs1GoldenExpectedDiagnostic(
                                        nativeGlucoseMmolLBits = diagnosticFields[1].strictUnsignedHexLong(),
                                        displayedGlucoseMmolLBits = diagnosticFields[2].strictUnsignedHexLong(),
                                        trend = diagnosticFields[3].strictInt(),
                                        glucoseWarning = diagnosticFields[4].strictInt(),
                                        currentWarning = diagnosticFields[5].strictInt(),
                                        temperatureWarning = diagnosticFields[6].strictInt(),
                                        stateSha256 = diagnosticFields[7],
                                        algorithmErrorCode = diagnosticFields[8].nullableEnum<AlgorithmErrorCode>(),
                                    ),
                                ),
                            )
                        }
                    }
                    add(
                        Gs1GoldenNotification(
                            attemptOrdinal = attemptOrdinal,
                            attemptPseudonym = attemptPseudonym,
                            ordinal = ordinal,
                            receivedAtEpochMs = receivedAt,
                            encryptedPacket = packet,
                            packetSha256 = packetHash,
                            expectedDecode = decodeExpectation,
                            expectedDecodeError = decodeError,
                            expectedDecrypted = decrypted,
                            samples = samples,
                        ),
                    )
                }
            }
            if (!cursor.finished()) cursor.invalid("unknown or trailing payload fields")
            Gs1GoldenTraceDecodeResult.Success(
                Gs1GoldenTrace(
                    formatVersion = version,
                    traceId = traceId,
                    provenance = provenance,
                    privacyClassification = privacy,
                    family = family,
                    algorithmProfile = profile,
                    algorithmVersion = algorithmVersion,
                    algorithmBinarySetId = binarySet,
                    transportProtocol = transport,
                    sensitivityEvidence = Gs1GoldenSensitivityEvidence(
                        initializationMode = initializationMode,
                        tokenSource = tokenSource,
                        encoding = sensitivityEncoding,
                        coefficientBits = coefficientBits,
                        inputHmacScheme = inputHmacScheme,
                        inputHmacSha256 = inputHmac,
                    ),
                    macIdentity = Gs1GoldenMacIdentity(scheme, macPseudonym, evidenceKind, evidenceHmac),
                    notifications = notifications,
                ),
            )
        } catch (invalid: InvalidPayload) {
            failure(Gs1GoldenTraceError.INVALID_FIELD, invalid.message ?: "invalid payload")
        } catch (_: NoSuchElementException) {
            failure(Gs1GoldenTraceError.INVALID_FIELD, "unknown enum value")
        } catch (_: IllegalArgumentException) {
            failure(Gs1GoldenTraceError.INVALID_FIELD, "payload value is not representable")
        }
    }

    private class PayloadCursor(private val payload: String) {
        private var position = 0

        fun field(name: String): String {
            val values = tagged(name, 2)
            return values[1]
        }

        fun tagged(name: String, count: Int): List<String> {
            if (position >= payload.length) invalid("payload ended before $name")
            val lineEnd = payload.indexOf('\n', startIndex = position)
            if (lineEnd < 0) invalid("payload ended inside $name")
            val lineLength = lineEnd - position
            if (lineLength > MAX_PAYLOAD_LINE_CHARS) invalid("payload line exceeds size limit")
            val values = payload.substring(position, lineEnd).split('\t', limit = count + 1)
            position = lineEnd + 1
            if (values.size != count || values[0] != name) invalid("expected canonical $name field")
            return values
        }

        fun finished(): Boolean = position == payload.length

        fun invalid(message: String): Nothing = throw InvalidPayload(message)
    }

    private class InvalidPayload(message: String) : IllegalArgumentException(message)

    private companion object {
        const val CURRENT_VERSION = 1
        const val MAGIC_LINE = "GS1-GOLDEN-TRACE"
        const val VERSION_PREFIX = "version="
        const val LENGTH_PREFIX = "payload-length="
        const val HASH_PREFIX = "payload-sha256="
        const val VERSION_DIGITS = 4
        const val LENGTH_DIGITS = 10
        const val HEADER_LINES = 4
        const val SHA256_HEX_CHARS = 64
        const val MAX_PAYLOAD_BYTES = 16 * 1024 * 1024
        const val MAX_TRACE_BYTES = MAX_PAYLOAD_BYTES + 1_024
        const val MAX_HEADER_BYTES = 256
        const val MAX_PAYLOAD_LINE_CHARS = 2_048
        const val MAX_NOTIFICATIONS = 10_000
        const val MAX_SAMPLES_PER_NOTIFICATION = 29
        const val NOTIFICATION_FIELDS = 11
        const val SAMPLE_FIELDS = 6
        const val DIAGNOSTIC_FIELDS = 9
        const val NONE = "-"
        val HEADER_TERMINATOR = "\n\n".encodeToByteArray()
    }
}

private fun StringBuilder.line(vararg values: String) {
    append(values.joinToString("\t")).append('\n')
}

private fun failure(error: Gs1GoldenTraceError, detail: String) =
    Gs1GoldenTraceDecodeResult.Failure(error, detail)

private fun String.removeExactPrefix(prefix: String): String? =
    if (startsWith(prefix)) substring(prefix.length) else null

private fun ByteArray.indexOf(needle: ByteArray): Int {
    if (needle.isEmpty()) return 0
    for (offset in 0..size - needle.size) {
        if (needle.indices.all { this[offset + it] == needle[it] }) return offset
    }
    return -1
}

private fun ByteArray.decodeUtf8(): String? = try {
    StandardCharsets.UTF_8.newDecoder()
        .onMalformedInput(CodingErrorAction.REPORT)
        .onUnmappableCharacter(CodingErrorAction.REPORT)
        .decode(ByteBuffer.wrap(this))
        .toString()
} catch (_: Exception) {
    null
}

private fun String.hexUtf8(): String = encodeToByteArray().toLowerHex()

private fun String.decodeHexUtf8(): String = decodeLowerHex().decodeUtf8()
    ?: throw IllegalArgumentException("hex field is not canonical UTF-8")

private fun ByteArray.toLowerHex(): String = joinToString(separator = "") {
    "%02x".format(it.toInt() and 0xff)
}

private fun String.decodeLowerHex(): ByteArray {
    if (length % 2 != 0 || !isLowerHex(length)) throw IllegalArgumentException("not canonical lower-case hex")
    return ByteArray(length / 2) { offset ->
        substring(offset * 2, offset * 2 + 2).toInt(16).toByte()
    }
}

private fun String.isLowerHex(requiredLength: Int): Boolean =
    length == requiredLength && all { it in '0'..'9' || it in 'a'..'f' }

private fun String.strictInt(): Int {
    if (!matches(CANONICAL_INTEGER)) throw IllegalArgumentException("not canonical Int")
    return toInt()
}

private fun String.strictLong(): Long {
    if (!matches(CANONICAL_INTEGER)) throw IllegalArgumentException("not canonical Long")
    return toLong()
}

private fun String.strictBoolean(): Boolean = when (this) {
    "0" -> false
    "1" -> true
    else -> throw IllegalArgumentException("not canonical Boolean")
}

private fun Boolean.asDigit(): String = if (this) "1" else "0"

private inline fun <reified T : Enum<T>> String.enumValue(): T = enumValueOf(this)

private inline fun <reified T : Enum<T>> String.nullableEnum(): T? =
    if (this == "-") null else enumValue()

private fun Long.toUnsignedHex16(): String = java.lang.Long.toUnsignedString(this, 16).padStart(16, '0')

private fun Int.toUnsignedHex8(): String = Integer.toUnsignedString(this, 16).padStart(8, '0')

private fun String.strictUnsignedHexLong(): Long {
    if (!isLowerHex(16)) throw IllegalArgumentException("not fixed-width lower-case bits")
    return java.lang.Long.parseUnsignedLong(this, 16)
}

private fun String.strictUnsignedHexInt(): Int {
    if (!isLowerHex(8)) throw IllegalArgumentException("not fixed-width lower-case Float bits")
    return Integer.parseUnsignedInt(this, 16)
}

internal fun ByteArray.gs1GoldenSha256(): String = MessageDigest.getInstance("SHA-256")
    .digest(this)
    .toLowerHex()

/** Canonical private-input binding. The exact input and HMAC key never enter a trace. */
internal fun gs1GoldenSensitivityBindingMessage(
    traceId: String,
    tokenSource: SensitivityTokenSource,
    exactInput: String,
): ByteArray = listOf(
    "GS1-GOLDEN-SENSITIVITY-V1",
    traceId,
    tokenSource.name,
    exactInput,
).joinToString("\u0000").encodeToByteArray()

private val CANONICAL_INTEGER = Regex("^(?:0|-?[1-9][0-9]*)$")
