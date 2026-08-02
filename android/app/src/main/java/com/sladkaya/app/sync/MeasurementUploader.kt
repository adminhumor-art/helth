package com.sladkaya.app.sync

import com.sladkaya.core.data.LeasedUpload
import com.sladkaya.core.data.UploadDeliveryReport
import com.sladkaya.core.data.UploadDeliveryStatus
import com.sladkaya.core.data.UploadOutboxLeaseResult
import com.sladkaya.core.data.UploadOutboxStore
import com.sladkaya.core.data.UploadOutboxTransitionResult
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

sealed interface MeasurementFlushResult {
    data object Complete : MeasurementFlushResult
    data object RetryNeeded : MeasurementFlushResult
    data object Blocked : MeasurementFlushResult
}

class MeasurementUploader(
    private val outbox: UploadOutboxStore,
    private val credentials: UploadCredentialProvider,
    private val client: RemoteMeasurementApiClient = HttpsRemoteMeasurementApiClient(),
    private val clock: () -> Long = System::currentTimeMillis,
    private val operationToken: () -> String = RemoteUploadLeasePolicy::newOperationToken,
) {
    private val flushMutex = Mutex()

    suspend fun flush(): MeasurementFlushResult = flushMutex.withLock {
        val nowEpochMs = clock()
        require(nowEpochMs > 0) { "Upload clock must be positive" }
        val leaseToken = operationToken()
        val lease = outbox.leaseDue(
            nowEpochMs = nowEpochMs,
            leaseToken = leaseToken,
            leaseExpiresAtEpochMs = RemoteUploadLeasePolicy.leaseExpiryEpochMs(nowEpochMs),
            limit = RemoteUploadLeasePolicy.BATCH_SIZE,
        )
        val uploads = when (lease) {
            is UploadOutboxLeaseResult.Leased -> lease.uploads
            is UploadOutboxLeaseResult.Conflict -> return@withLock MeasurementFlushResult.RetryNeeded
        }
        if (uploads.isEmpty()) return@withLock MeasurementFlushResult.Complete

        var retryNeeded = false
        var blocked = false
        uploads.forEach { upload ->
            when (process(upload, nowEpochMs)) {
                UploadProcessingResult.SENT -> Unit
                UploadProcessingResult.RETRY -> retryNeeded = true
                UploadProcessingResult.BLOCKED -> blocked = true
            }
        }
        when {
            retryNeeded -> MeasurementFlushResult.RetryNeeded
            uploads.size == RemoteUploadLeasePolicy.BATCH_SIZE -> MeasurementFlushResult.RetryNeeded
            blocked -> MeasurementFlushResult.Blocked
            else -> MeasurementFlushResult.Complete
        }
    }

    private suspend fun process(upload: LeasedUpload, nowEpochMs: Long): UploadProcessingResult {
        val outboxRecord = upload.outbox
        val leaseToken = checkNotNull(outboxRecord.leaseToken)
        if (!upload.reading.isEligibleForProductPublication || outboxRecord.attempts <= 0) {
            return block(
                upload = upload,
                leaseToken = leaseToken,
                report = UploadDeliveryReport(
                    UploadDeliveryStatus.BLOCKED_CONTRACT,
                    "LOCAL_OUTBOX_INVARIANT",
                ),
            )
        }

        val loaded = credentials.load()
        if (loaded !is CredentialLoadResult.Available) {
            return block(
                upload = upload,
                leaseToken = leaseToken,
                report = UploadDeliveryReport(
                    UploadDeliveryStatus.BLOCKED_CREDENTIAL,
                    loaded.safeDiagnosticCode(),
                ),
            )
        }

        val credential = loaded.credential
        return try {
            if (!CredentialOutboxBindingPolicy.matches(
                    credential = credential.metadata,
                    expectedPatientId = outboxRecord.expectedPatientId,
                    expectedDeviceId = outboxRecord.expectedDeviceId,
                    backendBindingId = outboxRecord.backendBindingId,
                    credentialId = outboxRecord.credentialId,
                    credentialRevision = outboxRecord.credentialRevision,
                    httpsOrigin = outboxRecord.httpsOrigin,
                )
            ) {
                return block(
                    upload = upload,
                    leaseToken = leaseToken,
                    report = UploadDeliveryReport(
                        UploadDeliveryStatus.BLOCKED_CREDENTIAL,
                        "CREDENTIAL_TUPLE_MISMATCH",
                    ),
                )
            }
            val endpoint = when (val parsed = RemoteUploadEndpoint.parse(outboxRecord.httpsOrigin)) {
                is RemoteUploadEndpointParseResult.Valid -> parsed.endpoint
                RemoteUploadEndpointParseResult.Invalid -> {
                    return block(
                        upload = upload,
                        leaseToken = leaseToken,
                        report = UploadDeliveryReport(
                            UploadDeliveryStatus.BLOCKED_ENDPOINT,
                            "HTTPS_ORIGIN_INVALID",
                        ),
                    )
                }
            }
            when (client.upload(endpoint, credential, upload.reading)) {
                RemoteUploadResult.Accepted -> transitionResult(
                    outbox.markSent(
                        eventId = outboxRecord.eventId,
                        leaseToken = leaseToken,
                        report = UploadDeliveryReport(
                            UploadDeliveryStatus.ACCEPTED,
                            "HTTP_202_ACCEPTED",
                        ),
                    ),
                    success = UploadProcessingResult.SENT,
                )
                RemoteUploadResult.RetryableNetwork -> reschedule(
                    upload,
                    leaseToken,
                    nowEpochMs,
                    UploadDeliveryReport(
                        UploadDeliveryStatus.RETRYABLE_NETWORK,
                        "NETWORK_RETRYABLE",
                    ),
                )
                RemoteUploadResult.RetryableServer -> reschedule(
                    upload,
                    leaseToken,
                    nowEpochMs,
                    UploadDeliveryReport(
                        UploadDeliveryStatus.RETRYABLE_SERVER,
                        "HTTP_RETRYABLE",
                    ),
                )
                RemoteUploadResult.CredentialBlocked -> block(
                    upload,
                    leaseToken,
                    UploadDeliveryReport(
                        UploadDeliveryStatus.BLOCKED_CREDENTIAL,
                        "HTTP_CREDENTIAL_REJECTED",
                    ),
                )
                RemoteUploadResult.EndpointBlocked -> block(
                    upload,
                    leaseToken,
                    UploadDeliveryReport(
                        UploadDeliveryStatus.BLOCKED_ENDPOINT,
                        "HTTPS_ENDPOINT_REJECTED",
                    ),
                )
                RemoteUploadResult.ContractBlocked -> block(
                    upload,
                    leaseToken,
                    UploadDeliveryReport(
                        UploadDeliveryStatus.BLOCKED_CONTRACT,
                        "INGEST_CONTRACT_REJECTED",
                    ),
                )
                RemoteUploadResult.ConflictBlocked -> block(
                    upload,
                    leaseToken,
                    UploadDeliveryReport(
                        UploadDeliveryStatus.BLOCKED_CONFLICT,
                        "INGEST_CONFLICT",
                    ),
                )
            }
        } finally {
            credential.close()
        }
    }

    private suspend fun reschedule(
        upload: LeasedUpload,
        leaseToken: String,
        nowEpochMs: Long,
        report: UploadDeliveryReport,
    ): UploadProcessingResult = transitionResult(
        outbox.reschedule(
            eventId = upload.outbox.eventId,
            leaseToken = leaseToken,
            nextAttemptEpochMs = RemoteUploadLeasePolicy.nextAttemptEpochMs(
                nowEpochMs,
                upload.outbox.attempts,
            ),
            report = report,
        ),
        success = UploadProcessingResult.RETRY,
    )

    private suspend fun block(
        upload: LeasedUpload,
        leaseToken: String,
        report: UploadDeliveryReport,
    ): UploadProcessingResult = transitionResult(
        outbox.block(upload.outbox.eventId, leaseToken, report),
        success = UploadProcessingResult.BLOCKED,
    )

    private fun transitionResult(
        result: UploadOutboxTransitionResult,
        success: UploadProcessingResult,
    ): UploadProcessingResult = when (result) {
        UploadOutboxTransitionResult.Applied,
        UploadOutboxTransitionResult.AlreadyApplied,
        -> success
        is UploadOutboxTransitionResult.Conflict -> UploadProcessingResult.RETRY
    }
}

private fun CredentialLoadResult.safeDiagnosticCode(): String = when (this) {
    is CredentialLoadResult.Available -> "CREDENTIAL_AVAILABLE"
    CredentialLoadResult.NotProvisioned -> "CREDENTIAL_NOT_PROVISIONED"
    CredentialLoadResult.MissingKey -> "CREDENTIAL_KEY_MISSING"
    CredentialLoadResult.KeyUnavailable -> "CREDENTIAL_KEY_UNAVAILABLE"
    CredentialLoadResult.Corrupted -> "CREDENTIAL_ENVELOPE_CORRUPT"
}

private enum class UploadProcessingResult {
    SENT,
    RETRY,
    BLOCKED,
}
