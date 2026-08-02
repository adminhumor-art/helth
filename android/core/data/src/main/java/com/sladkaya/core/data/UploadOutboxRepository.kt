package com.sladkaya.core.data

import android.content.Context

interface UploadOutboxStore {
    suspend fun leaseDue(
        nowEpochMs: Long,
        leaseToken: String,
        leaseExpiresAtEpochMs: Long,
        limit: Int = 100,
    ): UploadOutboxLeaseResult

    suspend fun markSent(
        eventId: String,
        leaseToken: String,
        report: UploadDeliveryReport,
    ): UploadOutboxTransitionResult

    suspend fun reschedule(
        eventId: String,
        leaseToken: String,
        nextAttemptEpochMs: Long,
        report: UploadDeliveryReport,
    ): UploadOutboxTransitionResult

    suspend fun block(
        eventId: String,
        leaseToken: String,
        report: UploadDeliveryReport,
    ): UploadOutboxTransitionResult

    suspend fun requeueBlocked(
        key: UploadBlockedRecoveryKey,
        nextAttemptEpochMs: Long,
    ): UploadOutboxTransitionResult
}

class UploadOutboxRepository private constructor(
    private val dao: SensorCoreDao,
) : UploadOutboxStore {
    override suspend fun leaseDue(
        nowEpochMs: Long,
        leaseToken: String,
        leaseExpiresAtEpochMs: Long,
        limit: Int,
    ): UploadOutboxLeaseResult = try {
        UploadOutboxLeaseResult.Leased(
            dao.leaseDueUploads(
                nowEpochMs = nowEpochMs,
                leaseToken = leaseToken,
                leaseExpiresAtEpochMs = leaseExpiresAtEpochMs,
                limit = limit,
            ).map { value ->
                LeasedUpload(
                    outbox = value.outbox.toRecord(),
                    reading = value.measurement.toModel(),
                )
            },
        )
    } catch (conflict: SensorCoreConflictException) {
        UploadOutboxLeaseResult.Conflict(conflict.safeReason("Upload lease conflict"))
    } catch (_: IllegalArgumentException) {
        UploadOutboxLeaseResult.Conflict("Stored upload payload is malformed")
    } catch (_: NoSuchElementException) {
        UploadOutboxLeaseResult.Conflict("Stored upload payload contains unsupported typed data")
    }

    override suspend fun markSent(
        eventId: String,
        leaseToken: String,
        report: UploadDeliveryReport,
    ): UploadOutboxTransitionResult = transition {
        dao.markOutboxSent(eventId, leaseToken, report.toEntity())
    }

    override suspend fun reschedule(
        eventId: String,
        leaseToken: String,
        nextAttemptEpochMs: Long,
        report: UploadDeliveryReport,
    ): UploadOutboxTransitionResult = transition {
        dao.rescheduleOutbox(eventId, leaseToken, nextAttemptEpochMs, report.toEntity())
    }

    override suspend fun block(
        eventId: String,
        leaseToken: String,
        report: UploadDeliveryReport,
    ): UploadOutboxTransitionResult = transition {
        dao.blockOutbox(eventId, leaseToken, report.toEntity())
    }

    override suspend fun requeueBlocked(
        key: UploadBlockedRecoveryKey,
        nextAttemptEpochMs: Long,
    ): UploadOutboxTransitionResult = transition {
        dao.requeueBlockedOutbox(key, nextAttemptEpochMs)
    }

    private suspend fun transition(
        operation: suspend () -> SensorCoreCommitDisposition,
    ): UploadOutboxTransitionResult = try {
        when (operation()) {
            SensorCoreCommitDisposition.COMMITTED -> UploadOutboxTransitionResult.Applied
            SensorCoreCommitDisposition.ALREADY_COMMITTED ->
                UploadOutboxTransitionResult.AlreadyApplied
        }
    } catch (conflict: SensorCoreConflictException) {
        UploadOutboxTransitionResult.Conflict(conflict.safeReason("Upload transition conflict"))
    } catch (_: IllegalArgumentException) {
        UploadOutboxTransitionResult.Conflict("Upload transition request is malformed")
    }

    companion object {
        fun create(context: Context): UploadOutboxStore = UploadOutboxRepository(
            SladkayaDatabase.get(context.applicationContext).sensorCore(),
        )
    }
}

private fun SensorCoreConflictException.safeReason(fallback: String): String =
    message?.takeIf { it.isNotBlank() } ?: fallback
