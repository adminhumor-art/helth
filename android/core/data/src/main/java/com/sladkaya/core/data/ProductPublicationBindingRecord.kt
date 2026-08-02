package com.sladkaya.core.data

import java.security.MessageDigest
import java.net.URI

/** Immutable routing/credential identity. Rotation creates a new record, never a new physical approval. */
data class ProductPublicationBindingRecord(
    val approvalId: String,
    val httpsOrigin: String,
    val backendBindingId: String,
    val credentialId: String,
    val credentialRevision: Long,
    val expectedPatientId: String,
    val expectedDeviceId: String,
    val createdAtEpochMs: Long,
    val schemaVersion: Int = SCHEMA_VERSION,
) {
    val publicationBindingId: String = canonicalId()

    init {
        require(SHA256.matches(approvalId))
        requireCanonicalHttpsOrigin(httpsOrigin)
        require(OPAQUE_IDENTIFIER.matches(backendBindingId))
        require(OPAQUE_IDENTIFIER.matches(credentialId))
        require(credentialRevision in 1L..MAX_CREDENTIAL_REVISION)
        requireCanonicalUuid(expectedPatientId)
        requireCanonicalUuid(expectedDeviceId)
        require(createdAtEpochMs > 0L)
        require(schemaVersion == SCHEMA_VERSION)
    }

    private fun canonicalId(): String {
        val fields = listOf(
            approvalId,
            httpsOrigin,
            backendBindingId,
            credentialId,
            credentialRevision.toString(),
            expectedPatientId,
            expectedDeviceId,
            createdAtEpochMs.toString(),
            schemaVersion.toString(),
        )
        val canonical = fields.joinToString("") { value ->
            "${value.encodeToByteArray().size}:$value"
        }
        return MessageDigest.getInstance("SHA-256")
            .digest(canonical.encodeToByteArray())
            .joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }
    }

    companion object {
        const val SCHEMA_VERSION = 1
        const val MAX_CREDENTIAL_REVISION = 9_007_199_254_740_991L
        private val SHA256 = Regex("^[0-9a-f]{64}$")
        private val OPAQUE_IDENTIFIER = Regex("^[A-Za-z0-9][A-Za-z0-9._:-]{0,127}$")
    }
}

internal fun requireCanonicalUuid(value: String) {
    require(CANONICAL_UUID.matches(value)) { "identifier must be a canonical lowercase UUID" }
}

internal fun requireCanonicalHttpsOrigin(value: String) {
    val uri = try {
        URI(value)
    } catch (_: Exception) {
        throw IllegalArgumentException("httpsOrigin must be a canonical HTTPS origin")
    }
    require(uri.scheme == "https")
    require(uri.rawUserInfo == null && uri.rawQuery == null && uri.rawFragment == null)
    require(uri.rawPath.isNullOrEmpty())
    require(!uri.host.isNullOrBlank())
    require(uri.port == -1 || uri.port == 443)
    require(uri.host.matches(Regex("^[A-Za-z0-9.-]+$")))
    val canonical = "https://${uri.host.lowercase()}"
    require(value == canonical) { "httpsOrigin must omit default port and use a lowercase host" }
}

private val CANONICAL_UUID = Regex(
    "^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$",
)

sealed interface ProductPublicationBindingCommitResult {
    data object Activated : ProductPublicationBindingCommitResult
    data object AlreadyActive : ProductPublicationBindingCommitResult
    data class Conflict(val reason: String) : ProductPublicationBindingCommitResult
}
