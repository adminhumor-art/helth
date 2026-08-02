package com.sladkaya.app.sync

import com.sladkaya.core.data.LeasedUpload
import com.sladkaya.core.data.UploadDeliveryReport
import com.sladkaya.core.data.UploadDeliveryStatus
import com.sladkaya.core.data.UploadOutboxLeaseResult
import com.sladkaya.core.data.UploadOutboxRecord
import com.sladkaya.core.data.UploadOutboxState
import com.sladkaya.core.data.UploadOutboxStore
import com.sladkaya.core.data.UploadOutboxTransitionResult
import com.sladkaya.core.data.UploadBlockedRecoveryKey
import com.sladkaya.core.model.GlucoseReading
import com.sladkaya.core.model.ReadingQuality
import com.sladkaya.core.model.SensorFamily
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.CancellationException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Test

class MeasurementUploaderTest {
    @Test
    fun retryableFailureReschedulesDurableLeaseAndRequestsWorkRetry() = runBlocking {
        val outbox = FakeOutbox(leasedUpload())
        val uploader = uploader(outbox, RemoteUploadResult.RetryableNetwork)

        assertEquals(MeasurementFlushResult.RetryNeeded, uploader.flush())
        assertEquals(UploadDeliveryStatus.RETRYABLE_NETWORK, outbox.rescheduled.single().report.status)
        assertEquals(NOW + 30_000L, outbox.rescheduled.single().nextAttemptEpochMs)
        assertEquals(emptyList<String>(), outbox.sent)
    }

    @Test
    fun blockedCredentialIsPersistedWithoutCallingTransport() = runBlocking {
        val outbox = FakeOutbox(leasedUpload())
        var transportWasCalled = false
        val uploader = uploader(
            outbox = outbox,
            result = RemoteUploadResult.Accepted,
            metadata = metadata(credentialRevision = 2),
            onTransport = { transportWasCalled = true },
        )

        assertEquals(MeasurementFlushResult.Blocked, uploader.flush())
        assertEquals(UploadDeliveryStatus.BLOCKED_CREDENTIAL, outbox.blocked.single().report.status)
        assertFalse(transportWasCalled)
    }

    @Test
    fun acceptedLeaseIsMarkedSentAndCredentialIsAlwaysZeroized() = runBlocking {
        val outbox = FakeOutbox(leasedUpload())
        lateinit var issuedToken: SecretBearerToken
        val uploader = MeasurementUploader(
            outbox = outbox,
            credentials = UploadCredentialProvider {
                issuedToken = SecretBearerToken.fromUtf8(TOKEN.toByteArray())
                CredentialLoadResult.Available(RuntimeUploadCredential(metadata(), issuedToken))
            },
            client = RemoteMeasurementApiClient { _, _, _ -> RemoteUploadResult.Accepted },
            clock = { NOW },
            operationToken = { LEASE_TOKEN },
        )

        assertEquals(MeasurementFlushResult.Complete, uploader.flush())
        assertEquals(listOf(EVENT_ID), outbox.sent)
        assertThrows(IllegalStateException::class.java) { issuedToken.useBytes { } }
        Unit
    }

    @Test
    fun cancellationPropagatesAndStillZeroizesCredential() {
        val outbox = FakeOutbox(leasedUpload())
        lateinit var issuedToken: SecretBearerToken
        val uploader = MeasurementUploader(
            outbox = outbox,
            credentials = UploadCredentialProvider {
                issuedToken = SecretBearerToken.fromUtf8(TOKEN.toByteArray())
                CredentialLoadResult.Available(RuntimeUploadCredential(metadata(), issuedToken))
            },
            client = RemoteMeasurementApiClient { _, _, _ -> throw CancellationException("cancel") },
            clock = { NOW },
            operationToken = { LEASE_TOKEN },
        )

        assertThrows(CancellationException::class.java) { runBlocking { uploader.flush() } }
        assertThrows(IllegalStateException::class.java) { issuedToken.useBytes { } }
    }

    @Test
    fun unpublishablePayloadCanNeverReachNetwork() = runBlocking {
        val outbox = FakeOutbox(leasedUpload(reading = reading(SensorFamily.SIMULATOR)))
        var transportWasCalled = false
        val uploader = uploader(
            outbox = outbox,
            result = RemoteUploadResult.Accepted,
            onTransport = { transportWasCalled = true },
        )

        assertEquals(MeasurementFlushResult.Blocked, uploader.flush())
        assertEquals(UploadDeliveryStatus.BLOCKED_CONTRACT, outbox.blocked.single().report.status)
        assertFalse(transportWasCalled)
    }

    private fun uploader(
        outbox: FakeOutbox,
        result: RemoteUploadResult,
        metadata: RemoteCredentialMetadata = metadata(),
        onTransport: () -> Unit = {},
    ): MeasurementUploader = MeasurementUploader(
        outbox = outbox,
        credentials = UploadCredentialProvider {
            CredentialLoadResult.Available(
                RuntimeUploadCredential(
                    metadata = metadata,
                    bearerToken = SecretBearerToken.fromUtf8(TOKEN.toByteArray()),
                ),
            )
        },
        client = RemoteMeasurementApiClient { _, _, _ ->
            onTransport()
            result
        },
        clock = { NOW },
        operationToken = { LEASE_TOKEN },
    )

    private class FakeOutbox(private val upload: LeasedUpload) : UploadOutboxStore {
        private var leased = false
        val sent = mutableListOf<String>()
        val rescheduled = mutableListOf<Rescheduled>()
        val blocked = mutableListOf<Blocked>()

        override suspend fun leaseDue(
            nowEpochMs: Long,
            leaseToken: String,
            leaseExpiresAtEpochMs: Long,
            limit: Int,
        ): UploadOutboxLeaseResult {
            if (leased) return UploadOutboxLeaseResult.Leased(emptyList())
            leased = true
            assertEquals(NOW, nowEpochMs)
            assertEquals(LEASE_TOKEN, leaseToken)
            assertEquals(NOW + 120_000L, leaseExpiresAtEpochMs)
            assertEquals(RemoteUploadLeasePolicy.BATCH_SIZE, limit)
            return UploadOutboxLeaseResult.Leased(listOf(upload))
        }

        override suspend fun markSent(
            eventId: String,
            leaseToken: String,
            report: UploadDeliveryReport,
        ): UploadOutboxTransitionResult {
            assertEquals(UploadDeliveryStatus.ACCEPTED, report.status)
            sent += eventId
            return UploadOutboxTransitionResult.Applied
        }

        override suspend fun reschedule(
            eventId: String,
            leaseToken: String,
            nextAttemptEpochMs: Long,
            report: UploadDeliveryReport,
        ): UploadOutboxTransitionResult {
            rescheduled += Rescheduled(eventId, nextAttemptEpochMs, report)
            return UploadOutboxTransitionResult.Applied
        }

        override suspend fun block(
            eventId: String,
            leaseToken: String,
            report: UploadDeliveryReport,
        ): UploadOutboxTransitionResult {
            blocked += Blocked(eventId, report)
            return UploadOutboxTransitionResult.Applied
        }

        override suspend fun requeueBlocked(
            key: UploadBlockedRecoveryKey,
            nextAttemptEpochMs: Long,
        ): UploadOutboxTransitionResult = error("Not used")
    }

    private data class Rescheduled(
        val eventId: String,
        val nextAttemptEpochMs: Long,
        val report: UploadDeliveryReport,
    )

    private data class Blocked(val eventId: String, val report: UploadDeliveryReport)

    private fun leasedUpload(reading: GlucoseReading = reading()) = LeasedUpload(
        outbox = UploadOutboxRecord(
            eventId = EVENT_ID,
            approvalId = HASH_A,
            publicationBindingId = HASH_B,
            remotePublicationBindingId = HASH_C,
            httpsOrigin = ORIGIN,
            backendBindingId = "backend-1",
            credentialId = "credential-1",
            credentialRevision = 1,
            expectedPatientId = PATIENT_ID,
            expectedDeviceId = DEVICE_ID,
            state = UploadOutboxState.LEASED,
            attempts = 1,
            nextAttemptEpochMs = NOW,
            leaseToken = LEASE_TOKEN,
            leaseExpiresAtEpochMs = NOW + 120_000L,
            lastTransitionToken = null,
            sanitizedStatus = null,
            sanitizedDetail = null,
        ),
        reading = reading,
    )

    private fun metadata(credentialRevision: Long = 1) = RemoteCredentialMetadata(
        credentialId = "credential-1",
        backendBindingId = "backend-1",
        credentialRevision = credentialRevision,
        expectedPatientId = PATIENT_ID,
        expectedDeviceId = DEVICE_ID,
        httpsOrigin = ORIGIN,
    )

    private fun reading(family: SensorFamily = SensorFamily.SIBIONICS_GS1) = GlucoseReading(
        eventId = EVENT_ID,
        sensorId = "sensor-1",
        sensorFamily = family,
        sensorTimeEpochMs = NOW - 60_000L,
        phoneTimeEpochMs = NOW - 55_000L,
        glucoseMgDl = 100,
        trendMgDlPerMinute = 0.0,
        quality = ReadingQuality.VALID,
        sequence = 1L,
    )

    private companion object {
        const val NOW = 1_800_000_000_000L
        const val EVENT_ID = "00000000-0000-4000-8000-000000000301"
        const val PATIENT_ID = "00000000-0000-4000-8000-000000000001"
        const val DEVICE_ID = "00000000-0000-4000-8000-000000000201"
        const val ORIGIN = "https://family.example"
        const val TOKEN = "0123456789abcdef0123456789abcdef"
        const val LEASE_TOKEN = "upload-00000000-0000-4000-8000-000000000401"
        const val HASH_A = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
        const val HASH_B = "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb"
        const val HASH_C = "cccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccc"
    }
}
