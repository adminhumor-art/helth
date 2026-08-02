package com.sladkaya.app.familyaccess

import com.sladkaya.app.sync.DeviceProvisioningIdentity
import java.nio.charset.StandardCharsets
import java.util.Base64

/** Shareable as one opaque block; the nonce is never exposed as a separate UI field. */
@JvmInline
internal value class FamilyInstallationRequest private constructor(val value: String) {
    override fun toString(): String = "FamilyInstallationRequest([REDACTED])"

    companion object {
        internal fun fromCanonical(value: String): FamilyInstallationRequest =
            FamilyInstallationRequest(value)
    }
}

internal sealed interface FamilyInstallationRequestParseResult {
    data class Valid(
        val identity: DeviceProvisioningIdentity,
    ) : FamilyInstallationRequestParseResult {
        override fun toString(): String = "Valid([REDACTED])"
    }

    data object Invalid : FamilyInstallationRequestParseResult
}

/**
 * Exact v1 wire format shared with the provisioning CLI:
 * SLKI1.base64url-no-padding(UTF8({"deviceId":"…","deviceNonce":"…"})).
 */
internal object FamilyInstallationRequestCodec {
    fun encode(identity: DeviceProvisioningIdentity): FamilyInstallationRequest {
        val json = canonicalJson(identity)
        val payload = Base64.getUrlEncoder().withoutPadding()
            .encodeToString(json.toByteArray(StandardCharsets.UTF_8))
        check(payload.length == ENCODED_PAYLOAD_CHARS)
        return FamilyInstallationRequest.fromCanonical(PREFIX + payload)
    }

    fun parse(candidate: String): FamilyInstallationRequestParseResult {
        if (candidate.length != REQUEST_CHARS || !REQUEST_SHAPE.matches(candidate)) {
            return FamilyInstallationRequestParseResult.Invalid
        }
        val payload = candidate.substring(PREFIX.length)
        val decoded = try {
            Base64.getUrlDecoder().decode(payload)
        } catch (_: IllegalArgumentException) {
            return FamilyInstallationRequestParseResult.Invalid
        }
        if (decoded.size != JSON_BYTES || decoded.any { it.toInt() !in ASCII_PRINTABLE }) {
            decoded.fill(0)
            return FamilyInstallationRequestParseResult.Invalid
        }
        val json = decoded.toString(StandardCharsets.UTF_8)
        decoded.fill(0)
        val match = CANONICAL_JSON.matchEntire(json)
            ?: return FamilyInstallationRequestParseResult.Invalid
        val identity = try {
            DeviceProvisioningIdentity(
                deviceId = match.groupValues[1],
                deviceNonce = match.groupValues[2],
            )
        } catch (_: IllegalArgumentException) {
            return FamilyInstallationRequestParseResult.Invalid
        }
        return if (encode(identity).value == candidate) {
            FamilyInstallationRequestParseResult.Valid(identity)
        } else {
            FamilyInstallationRequestParseResult.Invalid
        }
    }

    private fun canonicalJson(identity: DeviceProvisioningIdentity): String =
        "{\"deviceId\":\"${identity.deviceId}\",\"deviceNonce\":\"${identity.deviceNonce}\"}"

    private const val PREFIX = "SLKI1."
    private const val JSON_BYTES = 111
    private const val ENCODED_PAYLOAD_CHARS = 148
    private const val REQUEST_CHARS = PREFIX.length + ENCODED_PAYLOAD_CHARS
    private val ASCII_PRINTABLE = 0x20..0x7E
    private val REQUEST_SHAPE = Regex("^SLKI1\\.[A-Za-z0-9_-]{$ENCODED_PAYLOAD_CHARS}$")
    private val CANONICAL_JSON = Regex(
        "^\\{\"deviceId\":\"" +
            "([0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12})" +
            "\",\"deviceNonce\":\"([A-Za-z0-9_-]{43})\"\\}$",
    )
}
