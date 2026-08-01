package com.sladkaya.app.settings

import com.sladkaya.core.model.AlarmThresholds
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.security.MessageDigest
import java.util.Base64

internal enum class AlarmSettingsDecodeError {
    VALUE_TOO_LARGE,
    MALFORMED_ENCODING,
    INTEGRITY_MISMATCH,
    INVALID_HEADER,
    UNKNOWN_VERSION,
    INVALID_THRESHOLDS,
}

internal sealed interface AlarmSettingsDecodeResult {
    data class Success(val thresholds: AlarmThresholds) : AlarmSettingsDecodeResult
    data class Failure(val error: AlarmSettingsDecodeError) : AlarmSettingsDecodeResult
}

/** Fixed-size, checksummed format. Corruption can never become a different alarm policy. */
internal class AlarmSettingsCodec {
    fun encode(thresholds: AlarmThresholds): String {
        val payload = ByteArrayOutputStream(PAYLOAD_BYTES).use { bytes ->
            DataOutputStream(bytes).use { output ->
                output.writeInt(MAGIC)
                output.writeInt(VERSION)
                output.writeInt(thresholds.lowMgDl)
                output.writeInt(thresholds.highMgDl)
                output.writeLong(thresholds.rapidFallMgDlPerMinute.toBits())
                output.writeLong(thresholds.rapidRiseMgDlPerMinute.toBits())
                output.writeInt(thresholds.recoveryHysteresisMgDl)
                output.writeLong(thresholds.staleAfterMs)
            }
            bytes.toByteArray()
        }
        check(payload.size == PAYLOAD_BYTES)
        return ENCODER.encodeToString(payload + payload.sha256())
    }

    fun decode(encoded: String): AlarmSettingsDecodeResult {
        if (encoded.length > MAX_ENCODED_CHARS) {
            return failure(AlarmSettingsDecodeError.VALUE_TOO_LARGE)
        }
        val packed = try {
            DECODER.decode(encoded)
        } catch (_: IllegalArgumentException) {
            return failure(AlarmSettingsDecodeError.MALFORMED_ENCODING)
        }
        if (packed.size != PACKED_BYTES || ENCODER.encodeToString(packed) != encoded) {
            return failure(AlarmSettingsDecodeError.MALFORMED_ENCODING)
        }
        val payload = packed.copyOfRange(0, PAYLOAD_BYTES)
        val expectedDigest = packed.copyOfRange(PAYLOAD_BYTES, PACKED_BYTES)
        if (!MessageDigest.isEqual(expectedDigest, payload.sha256())) {
            return failure(AlarmSettingsDecodeError.INTEGRITY_MISMATCH)
        }

        return DataInputStream(ByteArrayInputStream(payload)).use { input ->
            if (input.readInt() != MAGIC) return@use failure(AlarmSettingsDecodeError.INVALID_HEADER)
            if (input.readInt() != VERSION) return@use failure(AlarmSettingsDecodeError.UNKNOWN_VERSION)
            val thresholds = try {
                AlarmThresholds(
                    lowMgDl = input.readInt(),
                    highMgDl = input.readInt(),
                    rapidFallMgDlPerMinute = Double.fromBits(input.readLong()),
                    rapidRiseMgDlPerMinute = Double.fromBits(input.readLong()),
                    recoveryHysteresisMgDl = input.readInt(),
                    staleAfterMs = input.readLong(),
                )
            } catch (_: IllegalArgumentException) {
                return@use failure(AlarmSettingsDecodeError.INVALID_THRESHOLDS)
            }
            if (input.available() != 0) {
                failure(AlarmSettingsDecodeError.MALFORMED_ENCODING)
            } else {
                AlarmSettingsDecodeResult.Success(thresholds)
            }
        }
    }

    private fun failure(error: AlarmSettingsDecodeError) =
        AlarmSettingsDecodeResult.Failure(error)

    private fun ByteArray.sha256(): ByteArray = MessageDigest.getInstance("SHA-256").digest(this)

    private companion object {
        const val MAGIC = 0x534c414c
        const val VERSION = 1
        const val PAYLOAD_BYTES = 44
        const val SHA256_BYTES = 32
        const val PACKED_BYTES = PAYLOAD_BYTES + SHA256_BYTES
        const val MAX_ENCODED_CHARS = 256
        val ENCODER: Base64.Encoder = Base64.getUrlEncoder().withoutPadding()
        val DECODER: Base64.Decoder = Base64.getUrlDecoder()
    }
}
