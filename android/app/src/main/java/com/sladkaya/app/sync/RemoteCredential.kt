package com.sladkaya.app.sync

import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.net.URI
import java.nio.charset.StandardCharsets

data class RemoteCredentialMetadata(
    val credentialId: String,
    val backendBindingId: String,
    val credentialRevision: Long,
    val expectedPatientId: String,
    val expectedDeviceId: String,
    val httpsOrigin: String,
) {
    init {
        require(OPAQUE_IDENTIFIER.matches(credentialId)) { "Credential identifier is invalid" }
        require(OPAQUE_IDENTIFIER.matches(backendBindingId)) { "Backend binding identifier is invalid" }
        require(credentialRevision in 1..MAX_JSON_SAFE_INTEGER) {
            "Credential revision must be a positive JSON-safe integer"
        }
        require(CANONICAL_UUID.matches(expectedPatientId)) { "Expected patient identifier is invalid" }
        require(CANONICAL_UUID.matches(expectedDeviceId)) { "Expected device identifier is invalid" }
        require(RemoteUploadEndpoint.require(httpsOrigin).origin == httpsOrigin) {
            "Credential origin must be canonical HTTPS origin"
        }
    }

    override fun toString(): String = "RemoteCredentialMetadata([REDACTED])"

    private companion object {
        const val MAX_JSON_SAFE_INTEGER = 9_007_199_254_740_991L
        val OPAQUE_IDENTIFIER = Regex("^[A-Za-z0-9][A-Za-z0-9._:-]{0,127}$")
        val CANONICAL_UUID = Regex(
            "^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$",
        )
    }
}

internal object RemoteCredentialAad {
    private const val FORMAT_VERSION = 1

    fun encode(metadata: RemoteCredentialMetadata): ByteArray = ByteArrayOutputStream().use { bytes ->
        DataOutputStream(bytes).use { output ->
            output.writeInt(FORMAT_VERSION)
            output.writeLengthPrefixed(metadata.credentialId)
            output.writeLengthPrefixed(metadata.backendBindingId)
            output.writeLong(metadata.credentialRevision)
            output.writeLengthPrefixed(metadata.expectedPatientId)
            output.writeLengthPrefixed(metadata.expectedDeviceId)
            output.writeLengthPrefixed(metadata.httpsOrigin)
        }
        bytes.toByteArray()
    }

    private fun DataOutputStream.writeLengthPrefixed(value: String) {
        val encoded = value.toByteArray(StandardCharsets.UTF_8)
        writeInt(encoded.size)
        write(encoded)
    }
}

class SecretBearerToken private constructor(private var secret: ByteArray?) : AutoCloseable {
    internal fun <T> useBytes(block: (ByteArray) -> T): T {
        val workingCopy = checkNotNull(secret) { "Bearer credential is closed" }.copyOf()
        return try {
            block(workingCopy)
        } finally {
            workingCopy.fill(0)
        }
    }

    internal fun <T> useHeaderValue(block: (String) -> T): T = useBytes { bytes ->
        block(bytes.toString(StandardCharsets.US_ASCII))
    }

    override fun close() {
        secret?.fill(0)
        secret = null
    }

    override fun toString(): String = "SecretBearerToken([REDACTED])"

    companion object {
        fun fromUtf8(value: ByteArray): SecretBearerToken {
            require(value.size in MIN_TOKEN_BYTES..MAX_TOKEN_BYTES && value.all { it.toInt() in 0x21..0x7E }) {
                "Bearer credential is invalid"
            }
            return SecretBearerToken(value.copyOf())
        }

        private const val MIN_TOKEN_BYTES = 32
        private const val MAX_TOKEN_BYTES = 4_096
    }
}

/** A short-lived credential. The caller that receives it must always close it. */
class RuntimeUploadCredential(
    val metadata: RemoteCredentialMetadata,
    val bearerToken: SecretBearerToken,
) : AutoCloseable {
    override fun close() = bearerToken.close()
    override fun toString(): String = "RuntimeUploadCredential([REDACTED])"
}

sealed interface CredentialLoadResult {
    data class Available(val credential: RuntimeUploadCredential) : CredentialLoadResult
    data object NotProvisioned : CredentialLoadResult
    data object MissingKey : CredentialLoadResult
    data object KeyUnavailable : CredentialLoadResult
    data object Corrupted : CredentialLoadResult
}

fun interface UploadCredentialProvider {
    suspend fun load(): CredentialLoadResult
}

internal object CredentialOutboxBindingPolicy {
    fun matches(
        credential: RemoteCredentialMetadata,
        expectedPatientId: String,
        expectedDeviceId: String,
        backendBindingId: String,
        credentialId: String,
        credentialRevision: Long,
        httpsOrigin: String,
    ): Boolean = credential.expectedPatientId == expectedPatientId &&
        credential.expectedDeviceId == expectedDeviceId &&
        credential.backendBindingId == backendBindingId &&
        credential.credentialId == credentialId &&
        credential.credentialRevision == credentialRevision &&
        credential.httpsOrigin == httpsOrigin
}

class RemoteUploadEndpoint private constructor(
    val origin: String,
) {
    val measurementsUri: URI = URI.create("$origin/v1/device/measurements")

    override fun equals(other: Any?): Boolean = other is RemoteUploadEndpoint && origin == other.origin
    override fun hashCode(): Int = origin.hashCode()
    override fun toString(): String = origin

    companion object {
        fun parse(candidate: String): RemoteUploadEndpointParseResult {
            val uri = try {
                URI(candidate)
            } catch (_: java.net.URISyntaxException) {
                return RemoteUploadEndpointParseResult.Invalid
            }
            if (!uri.scheme.equals("https", ignoreCase = true) ||
                uri.host.isNullOrBlank() ||
                uri.rawUserInfo != null ||
                uri.rawQuery != null ||
                uri.rawFragment != null ||
                uri.rawPath.isNotEmpty() ||
                (uri.port != -1 && uri.port != 443) ||
                !isAllowedHost(uri.host, uri.rawAuthority)
            ) {
                return RemoteUploadEndpointParseResult.Invalid
            }
            val canonical = try {
                URI("https", null, uri.host.lowercase(), -1, null, null, null).toASCIIString()
            } catch (_: java.net.URISyntaxException) {
                return RemoteUploadEndpointParseResult.Invalid
            }
            return RemoteUploadEndpointParseResult.Valid(RemoteUploadEndpoint(canonical))
        }

        fun require(candidate: String): RemoteUploadEndpoint =
            (parse(candidate) as? RemoteUploadEndpointParseResult.Valid)?.endpoint
                ?: throw IllegalArgumentException("Remote endpoint must be a canonical HTTPS origin")

        private fun isAllowedHost(host: String, rawAuthority: String?): Boolean {
            if (host.any(Char::isISOControl) || rawAuthority == null || '%' in rawAuthority) return false
            // ProductPublicationBindingRecord uses the same DNS/IPv4 origin contract.
            if (':' in host) return false
            if (host.length > 253 || host.endsWith('.') || '.' !in host) return false
            return host.lowercase().split('.').all { label ->
                label.length in 1..63 &&
                    label.first().isLetterOrDigit() &&
                    label.last().isLetterOrDigit() &&
                    label.all { it in 'a'..'z' || it in 'A'..'Z' || it in '0'..'9' || it == '-' }
            }
        }
    }
}

sealed interface RemoteUploadEndpointParseResult {
    data class Valid(val endpoint: RemoteUploadEndpoint) : RemoteUploadEndpointParseResult
    data object Invalid : RemoteUploadEndpointParseResult
}
