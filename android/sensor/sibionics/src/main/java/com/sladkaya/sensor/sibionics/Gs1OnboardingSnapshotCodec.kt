package com.sladkaya.sensor.sibionics

import com.sladkaya.core.model.SensorFamily
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.security.MessageDigest
import java.util.Base64

enum class Gs1OnboardingSnapshotDecodeError {
    ENCODED_VALUE_TOO_LARGE,
    MALFORMED_BASE64,
    CHECKSUM_MISMATCH,
    UNSUPPORTED_CODEC_VERSION,
    MALFORMED_PAYLOAD,
}

sealed interface Gs1OnboardingSnapshotDecodeResult {
    data class Success(
        val snapshot: Gs1OnboardingSnapshot,
    ) : Gs1OnboardingSnapshotDecodeResult

    data class Failure(
        val error: Gs1OnboardingSnapshotDecodeError,
    ) : Gs1OnboardingSnapshotDecodeResult
}

/** Deterministic, checksummed representation for the pending-only app store. */
object Gs1OnboardingSnapshotCodec {
    fun encode(snapshot: Gs1OnboardingSnapshot): String {
        require(snapshot.candidates.size <= MAX_ONBOARDING_CANDIDATES)
        val payload = ByteArrayOutputStream().use { bytes ->
            DataOutputStream(bytes).use { output ->
                output.writeInt(MAGIC)
                output.writeInt(CODEC_VERSION)
                output.writeLong(snapshot.revision)
                output.writeInt(snapshot.schemaVersion)
                output.writeUTF(snapshot.stage.name)
                output.writeNullableEnum(snapshot.family)
                output.writeNullableEnum(snapshot.marketProfile)
                output.writeNullableEnum(snapshot.codeSource)
                output.writeNullableString(snapshot.packageCode)
                output.writeNullableEnum(snapshot.rejectionReason)
                output.writeInt(snapshot.candidates.size)
                snapshot.candidates.forEach { candidate ->
                    output.writeUTF(candidate.deviceName)
                    output.writeUTF(candidate.canonicalBluetoothAddress)
                }
                output.writeNullableString(snapshot.selectedDeviceName)
                output.writeNullableString(snapshot.selectedBluetoothAddress)
            }
            bytes.toByteArray()
        }
        require(payload.size <= MAX_PAYLOAD_BYTES)
        val digest = sha256(payload)
        return Base64.getUrlEncoder().withoutPadding().encodeToString(payload + digest)
    }

    fun decode(encoded: String): Gs1OnboardingSnapshotDecodeResult {
        if (encoded.length > MAX_ENCODED_CHARS) {
            return Gs1OnboardingSnapshotDecodeResult.Failure(
                Gs1OnboardingSnapshotDecodeError.ENCODED_VALUE_TOO_LARGE,
            )
        }
        val packed = try {
            Base64.getUrlDecoder().decode(encoded)
        } catch (_: IllegalArgumentException) {
            return Gs1OnboardingSnapshotDecodeResult.Failure(
                Gs1OnboardingSnapshotDecodeError.MALFORMED_BASE64,
            )
        }
        if (packed.size <= SHA256_BYTES || packed.size > MAX_PACKED_BYTES) {
            return Gs1OnboardingSnapshotDecodeResult.Failure(
                Gs1OnboardingSnapshotDecodeError.MALFORMED_PAYLOAD,
            )
        }
        val payload = packed.copyOfRange(0, packed.size - SHA256_BYTES)
        val storedDigest = packed.copyOfRange(packed.size - SHA256_BYTES, packed.size)
        if (!MessageDigest.isEqual(storedDigest, sha256(payload))) {
            return Gs1OnboardingSnapshotDecodeResult.Failure(
                Gs1OnboardingSnapshotDecodeError.CHECKSUM_MISMATCH,
            )
        }
        return decodeVerifiedPayload(payload)
    }

    private fun decodeVerifiedPayload(payload: ByteArray): Gs1OnboardingSnapshotDecodeResult {
        val snapshot = try {
            DataInputStream(ByteArrayInputStream(payload)).use { input ->
                if (input.readInt() != MAGIC) return malformedPayload()
                if (input.readInt() != CODEC_VERSION) {
                    return Gs1OnboardingSnapshotDecodeResult.Failure(
                        Gs1OnboardingSnapshotDecodeError.UNSUPPORTED_CODEC_VERSION,
                    )
                }
                val revision = input.readLong()
                val schemaVersion = input.readInt()
                val stage = input.readEnum<Gs1OnboardingStage>()
                val family = input.readNullableEnum<SensorFamily>()
                val marketProfile = input.readNullableEnum<Gs1MarketProfile>()
                val codeSource = input.readNullableEnum<Gs1PackageCodeSource>()
                val packageCode = input.readNullableString()
                val rejectionReason = input.readNullableEnum<Gs1OnboardingRejectionReason>()
                val candidateCount = input.readInt()
                if (candidateCount !in 0..MAX_ONBOARDING_CANDIDATES) return malformedPayload()
                val candidates = List(candidateCount) {
                    Gs1ResolvedAdvertisement(
                        deviceName = input.readUTF(),
                        canonicalBluetoothAddress = input.readUTF(),
                    )
                }
                val selectedDeviceName = input.readNullableString()
                val selectedBluetoothAddress = input.readNullableString()
                if (input.available() != 0) return malformedPayload()
                Gs1OnboardingSnapshot(
                    revision = revision,
                    schemaVersion = schemaVersion,
                    stage = stage,
                    family = family,
                    marketProfile = marketProfile,
                    codeSource = codeSource,
                    packageCode = packageCode,
                    rejectionReason = rejectionReason,
                    candidates = candidates,
                    selectedDeviceName = selectedDeviceName,
                    selectedBluetoothAddress = selectedBluetoothAddress,
                )
            }
        } catch (_: Exception) {
            return malformedPayload()
        }
        return Gs1OnboardingSnapshotDecodeResult.Success(snapshot)
    }

    private fun malformedPayload() = Gs1OnboardingSnapshotDecodeResult.Failure(
        Gs1OnboardingSnapshotDecodeError.MALFORMED_PAYLOAD,
    )

    private fun sha256(bytes: ByteArray): ByteArray =
        MessageDigest.getInstance("SHA-256").digest(bytes)

    private const val MAGIC = 0x4753314f
    // The product has not shipped; this is the first and only persisted format.
    private const val CODEC_VERSION = 1
    private const val SHA256_BYTES = 32
    private const val MAX_PAYLOAD_BYTES = 64 * 1024
    private const val MAX_PACKED_BYTES = MAX_PAYLOAD_BYTES + SHA256_BYTES
    private const val MAX_ENCODED_CHARS = 100_000
}

private fun DataOutputStream.writeNullableString(value: String?) {
    writeBoolean(value != null)
    if (value != null) writeUTF(value)
}

private fun DataOutputStream.writeNullableEnum(value: Enum<*>?) {
    writeNullableString(value?.name)
}

private fun DataInputStream.readNullableString(): String? =
    if (readBoolean()) readUTF() else null

private inline fun <reified T : Enum<T>> DataInputStream.readEnum(): T =
    enumValueOf<T>(readUTF())

private inline fun <reified T : Enum<T>> DataInputStream.readNullableEnum(): T? =
    readNullableString()?.let { enumValueOf<T>(it) }
