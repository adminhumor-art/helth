package com.sladkaya.app.sync

import java.io.IOException
import java.util.Base64
import javax.net.ssl.SSLException

@JvmInline
internal value class DeviceActivationCode private constructor(val value: String) {
    override fun toString(): String = "DeviceActivationCode([REDACTED])"

    companion object {
        private val EXACT_CODE = Regex(
            "^SLK1-(?:[0123456789ABCDEFGHJKMNPQRSTVWXYZ]{4}-){7}" +
                "[0123456789ABCDEFGHJKMNPQRSTVWXYZ]{4}$",
        )

        fun require(value: String): DeviceActivationCode {
            require(EXACT_CODE.matches(value)) { "Activation code format is invalid" }
            return DeviceActivationCode(value)
        }
    }
}

internal data class DeviceProvisioningIdentity(
    val deviceId: String,
    val deviceNonce: String,
) {
    init {
        require(CANONICAL_UUID.matches(deviceId)) { "Installation device identifier is invalid" }
        require(isCanonicalNonce(deviceNonce)) { "Installation device nonce is invalid" }
    }

    override fun toString(): String = "DeviceProvisioningIdentity([REDACTED])"

    private companion object {
        private val CANONICAL_UUID = Regex(
            "^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$",
        )

        private fun isCanonicalNonce(candidate: String): Boolean {
            if (!CANONICAL_BASE64_URL.matches(candidate)) return false
            val decoded = try {
                Base64.getUrlDecoder().decode(candidate)
            } catch (_: IllegalArgumentException) {
                return false
            }
            return decoded.size == NONCE_BYTES &&
                Base64.getUrlEncoder().withoutPadding().encodeToString(decoded) == candidate
        }

        private const val NONCE_BYTES = 32
        private val CANONICAL_BASE64_URL = Regex("^[A-Za-z0-9_-]{43}$")
    }
}

internal sealed interface DeviceProvisioningExchangeResult {
    data class Provisioned(
        val payload: RemoteProvisioningPayload,
    ) : DeviceProvisioningExchangeResult {
        override fun toString(): String = "Provisioned([REDACTED])"
    }

    data object MalformedRequest : DeviceProvisioningExchangeResult
    data object ActivationRejected : DeviceProvisioningExchangeResult
    data object RetryableNetwork : DeviceProvisioningExchangeResult
    data object RetryableServer : DeviceProvisioningExchangeResult
    data object EndpointBlocked : DeviceProvisioningExchangeResult
    data object ContractBlocked : DeviceProvisioningExchangeResult
}

internal object DeviceProvisioningResponsePolicy {
    private val EXPECTED_KEYS = setOf(
        "deviceToken",
        "apiOrigin",
        "deviceId",
        "patientId",
        "backendBindingId",
        "credentialId",
        "credentialRevision",
    )
    private val CANONICAL_TOKEN = Regex("^[A-Za-z0-9_-]{43}$")

    fun classify(
        statusCode: Int,
        contentType: String?,
        responseBody: String,
        expectedOrigin: String,
        expectedIdentity: DeviceProvisioningIdentity,
    ): DeviceProvisioningExchangeResult = when {
        statusCode == 201 -> parseSuccess(
            contentType,
            responseBody,
            expectedOrigin,
            expectedIdentity,
        )
        statusCode == 400 -> DeviceProvisioningExchangeResult.MalformedRequest
        statusCode == 401 -> DeviceProvisioningExchangeResult.ActivationRejected
        statusCode == 408 || statusCode == 429 || statusCode in 500..599 ->
            DeviceProvisioningExchangeResult.RetryableServer
        else -> DeviceProvisioningExchangeResult.ContractBlocked
    }

    fun classify(error: IOException): DeviceProvisioningExchangeResult =
        if (error is SSLException) {
            DeviceProvisioningExchangeResult.EndpointBlocked
        } else {
            DeviceProvisioningExchangeResult.RetryableNetwork
        }

    private fun parseSuccess(
        contentType: String?,
        responseBody: String,
        expectedOrigin: String,
        expectedIdentity: DeviceProvisioningIdentity,
    ): DeviceProvisioningExchangeResult {
        val isJson = contentType?.substringBefore(';')?.trim()
            ?.equals("application/json", ignoreCase = true) == true
        if (!isJson || responseBody.length !in 2..MAX_RESPONSE_CHARS) {
            return DeviceProvisioningExchangeResult.ContractBlocked
        }
        val json = StrictFlatJsonObject.parse(responseBody)
            ?: return DeviceProvisioningExchangeResult.ContractBlocked
        if (json.keys != EXPECTED_KEYS) {
            return DeviceProvisioningExchangeResult.ContractBlocked
        }
        return try {
            if (EXPECTED_STRING_KEYS.any { json[it] !is StrictJsonValue.Text } ||
                json["credentialRevision"] !is StrictJsonValue.Integer
            ) {
                return DeviceProvisioningExchangeResult.ContractBlocked
            }
            val token = json.requireText("deviceToken")
            val origin = json.requireText("apiOrigin")
            val deviceId = json.requireText("deviceId")
            val revision = json.requireInteger("credentialRevision")
            if (!isCanonicalToken(token) || origin != expectedOrigin ||
                RemoteUploadEndpoint.require(origin).origin != origin ||
                deviceId != expectedIdentity.deviceId
            ) {
                return DeviceProvisioningExchangeResult.ContractBlocked
            }
            val metadata = RemoteCredentialMetadata(
                credentialId = json.requireText("credentialId"),
                backendBindingId = json.requireText("backendBindingId"),
                credentialRevision = revision,
                expectedPatientId = json.requireText("patientId"),
                expectedDeviceId = deviceId,
                httpsOrigin = origin,
            )
            val tokenBytes = token.toByteArray(Charsets.US_ASCII)
            DeviceProvisioningExchangeResult.Provisioned(
                RemoteProvisioningPayload.capture(metadata, tokenBytes),
            )
        } catch (_: IllegalArgumentException) {
            DeviceProvisioningExchangeResult.ContractBlocked
        }
    }

    private fun isCanonicalToken(candidate: String): Boolean {
        if (!CANONICAL_TOKEN.matches(candidate)) return false
        val decoded = try {
            Base64.getUrlDecoder().decode(candidate)
        } catch (_: IllegalArgumentException) {
            return false
        }
        return decoded.size == TOKEN_BYTES &&
            Base64.getUrlEncoder().withoutPadding().encodeToString(decoded) == candidate
    }

    private const val MAX_RESPONSE_CHARS = 16 * 1_024
    private const val TOKEN_BYTES = 32
    private val EXPECTED_STRING_KEYS = EXPECTED_KEYS - "credentialRevision"
}

private sealed interface StrictJsonValue {
    data class Text(val value: String) : StrictJsonValue
    data class Integer(val value: Long) : StrictJsonValue
}

private fun Map<String, StrictJsonValue>.requireText(key: String): String =
    (get(key) as StrictJsonValue.Text).value

private fun Map<String, StrictJsonValue>.requireInteger(key: String): Long =
    (get(key) as StrictJsonValue.Integer).value

/** Parses only the bounded flat JSON shape emitted by the provisioning endpoint. */
private object StrictFlatJsonObject {
    fun parse(source: String): Map<String, StrictJsonValue>? = Parser(source).parse()

    private class Parser(private val source: String) {
        private var offset = 0

        fun parse(): Map<String, StrictJsonValue>? {
            skipWhitespace()
            if (!consume('{')) return null
            skipWhitespace()
            val values = linkedMapOf<String, StrictJsonValue>()
            if (consume('}')) return finish(values)
            while (true) {
                val key = parseString() ?: return null
                skipWhitespace()
                if (!consume(':')) return null
                skipWhitespace()
                val value = when (peek()) {
                    '"' -> parseString()?.let(StrictJsonValue::Text)
                    in '0'..'9' -> parseInteger()?.let(StrictJsonValue::Integer)
                    else -> null
                } ?: return null
                if (values.put(key, value) != null) return null
                skipWhitespace()
                when {
                    consume(',') -> skipWhitespace()
                    consume('}') -> return finish(values)
                    else -> return null
                }
            }
        }

        private fun finish(values: Map<String, StrictJsonValue>): Map<String, StrictJsonValue>? {
            skipWhitespace()
            return values.takeIf { offset == source.length }
        }

        private fun parseString(): String? {
            if (!consume('"')) return null
            val start = offset
            while (offset < source.length) {
                val value = source[offset]
                when {
                    value == '"' -> return source.substring(start, offset).also { offset += 1 }
                    value == '\\' || value.code < 0x20 || value.isSurrogate() -> return null
                    else -> offset += 1
                }
            }
            return null
        }

        private fun parseInteger(): Long? {
            val start = offset
            while (peek() in '0'..'9') offset += 1
            val encoded = source.substring(start, offset)
            if (encoded.length > 1 && encoded.startsWith('0')) return null
            return encoded.toLongOrNull()
        }

        private fun skipWhitespace() {
            while (peek() == ' ' || peek() == '\t' || peek() == '\r' || peek() == '\n') {
                offset += 1
            }
        }

        private fun consume(expected: Char): Boolean {
            if (peek() != expected) return false
            offset += 1
            return true
        }

        private fun peek(): Char? = source.getOrNull(offset)
    }
}
