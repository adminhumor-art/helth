package com.sladkaya.core.data

import com.sladkaya.core.model.GlucoseReading

enum class UploadOutboxState(val wireName: String) {
    PENDING("PENDING"),
    LEASED("LEASED"),
    SENT("SENT"),
    BLOCKED("BLOCKED"),
}

enum class UploadDeliveryStatus(val wireName: String) {
    ACCEPTED("ACCEPTED"),
    RETRYABLE_NETWORK("RETRYABLE_NETWORK"),
    RETRYABLE_SERVER("RETRYABLE_SERVER"),
    BLOCKED_CREDENTIAL("BLOCKED_CREDENTIAL"),
    BLOCKED_ENDPOINT("BLOCKED_ENDPOINT"),
    BLOCKED_CONTRACT("BLOCKED_CONTRACT"),
    BLOCKED_CONFLICT("BLOCKED_CONFLICT"),
    ;

    companion object {
        val blockingStatuses: Set<UploadDeliveryStatus> = setOf(
            BLOCKED_CREDENTIAL,
            BLOCKED_ENDPOINT,
            BLOCKED_CONTRACT,
            BLOCKED_CONFLICT,
        )
    }
}

/** Accepts only application-owned diagnostic codes, never remote response bodies or credentials. */
data class UploadDeliveryReport(
    val status: UploadDeliveryStatus,
    val detail: String?,
) {
    init {
        require(detail == null || SANITIZED_DETAIL.matches(detail)) {
            "detail must be a bounded application-owned diagnostic code"
        }
    }

    private companion object {
        val SANITIZED_DETAIL = Regex("^[A-Z0-9][A-Z0-9_.:-]{0,63}$")
    }
}

data class UploadOutboxRecord(
    val eventId: String,
    val approvalId: String,
    val publicationBindingId: String,
    val remotePublicationBindingId: String,
    val httpsOrigin: String,
    val backendBindingId: String,
    val credentialId: String,
    val credentialRevision: Long,
    val expectedPatientId: String,
    val expectedDeviceId: String,
    val state: UploadOutboxState,
    val attempts: Int,
    val nextAttemptEpochMs: Long,
    val leaseToken: String?,
    val leaseExpiresAtEpochMs: Long?,
    val lastTransitionToken: String?,
    val sanitizedStatus: UploadDeliveryStatus?,
    val sanitizedDetail: String?,
) {
    init {
        require(eventId.isNotBlank())
        require(SHA256.matches(approvalId))
        require(SHA256.matches(publicationBindingId))
        require(SHA256.matches(remotePublicationBindingId))
        requireCanonicalHttpsOrigin(httpsOrigin)
        require(OPAQUE_IDENTIFIER.matches(backendBindingId))
        require(OPAQUE_IDENTIFIER.matches(credentialId))
        require(credentialRevision in 1L..ProductPublicationBindingRecord.MAX_CREDENTIAL_REVISION)
        requireCanonicalUuid(expectedPatientId)
        requireCanonicalUuid(expectedDeviceId)
        require(attempts >= 0)
        require(nextAttemptEpochMs > 0)
        if (state == UploadOutboxState.LEASED) {
            requireLeaseToken(requireNotNull(leaseToken))
            require(requireNotNull(leaseExpiresAtEpochMs) > 0)
        } else {
            require(leaseToken == null)
            require(leaseExpiresAtEpochMs == null)
        }
        if (lastTransitionToken != null) requireLeaseToken(lastTransitionToken)
        if (sanitizedStatus == null) require(sanitizedDetail == null)
        if (sanitizedDetail != null) UploadDeliveryReport(checkNotNull(sanitizedStatus), sanitizedDetail)
    }
}

data class LeasedUpload(
    val outbox: UploadOutboxRecord,
    val reading: GlucoseReading,
) {
    init {
        require(outbox.state == UploadOutboxState.LEASED)
        require(outbox.eventId == reading.eventId)
        require(outbox.approvalId.isNotBlank())
    }
}

data class UploadBlockedRecoveryKey(
    val eventId: String,
    val approvalId: String,
    val publicationBindingId: String,
    val remotePublicationBindingId: String,
    val httpsOrigin: String,
    val backendBindingId: String,
    val credentialId: String,
    val credentialRevision: Long,
    val expectedPatientId: String,
    val expectedDeviceId: String,
    val expectedBlockingStatus: UploadDeliveryStatus,
    val expectedOperationToken: String,
) {
    init {
        require(eventId.isNotBlank())
        require(SHA256.matches(approvalId))
        require(SHA256.matches(publicationBindingId))
        require(SHA256.matches(remotePublicationBindingId))
        requireCanonicalHttpsOrigin(httpsOrigin)
        require(OPAQUE_IDENTIFIER.matches(backendBindingId))
        require(OPAQUE_IDENTIFIER.matches(credentialId))
        require(credentialRevision in 1L..ProductPublicationBindingRecord.MAX_CREDENTIAL_REVISION)
        requireCanonicalUuid(expectedPatientId)
        requireCanonicalUuid(expectedDeviceId)
        require(expectedBlockingStatus in UploadDeliveryStatus.blockingStatuses)
        requireLeaseToken(expectedOperationToken)
    }
}

sealed interface UploadOutboxLeaseResult {
    data class Leased(val uploads: List<LeasedUpload>) : UploadOutboxLeaseResult
    data class Conflict(val reason: String) : UploadOutboxLeaseResult
}

sealed interface UploadOutboxTransitionResult {
    data object Applied : UploadOutboxTransitionResult
    data object AlreadyApplied : UploadOutboxTransitionResult
    data class Conflict(val reason: String) : UploadOutboxTransitionResult
}

internal fun requireLeaseToken(value: String) {
    require(LEASE_TOKEN.matches(value)) { "leaseToken must be a bounded opaque identifier" }
}

private val LEASE_TOKEN = Regex("^[A-Za-z0-9][A-Za-z0-9._:-]{7,127}$")
private val SHA256 = Regex("^[0-9a-f]{64}$")
private val OPAQUE_IDENTIFIER = Regex("^[A-Za-z0-9][A-Za-z0-9._:-]{0,127}$")
