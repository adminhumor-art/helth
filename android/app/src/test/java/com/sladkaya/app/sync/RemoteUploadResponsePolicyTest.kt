package com.sladkaya.app.sync

import java.io.IOException
import java.io.ByteArrayInputStream
import javax.net.ssl.SSLException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import com.sladkaya.core.model.GlucoseReading
import com.sladkaya.core.model.ReadingQuality
import com.sladkaya.core.model.SensorFamily
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertTrue
import org.junit.Test

class RemoteUploadResponsePolicyTest {
    @Test
    fun onlyAcceptedTrueIn202IsSuccess() {
        assertEquals(
            RemoteUploadResult.Accepted,
            RemoteUploadResponsePolicy.classify(202, "{\"accepted\":true}"),
        )
        assertEquals(
            RemoteUploadResult.ContractBlocked,
            RemoteUploadResponsePolicy.classify(202, "{\"accepted\":true,\"receiptId\":\"r1\"}"),
        )
        assertEquals(
            RemoteUploadResult.ContractBlocked,
            RemoteUploadResponsePolicy.classify(200, "{\"accepted\":true}"),
        )
        assertEquals(
            RemoteUploadResult.ContractBlocked,
            RemoteUploadResponsePolicy.classify(202, "{\"accepted\":false}"),
        )
        assertEquals(
            RemoteUploadResult.ContractBlocked,
            RemoteUploadResponsePolicy.classify(202, "not-json"),
        )
        assertEquals(
            RemoteUploadResult.ContractBlocked,
            RemoteUploadResponsePolicy.classify(202, "{\"accepted\":true}", "text/plain"),
        )
        listOf(
            "{\"accepted\":\"true\"}",
            "{\"accepted\":1}",
            "{\"accepted\":null}",
            "{\"accepted\":false,\"accepted\":true}",
        ).forEach { body ->
            assertEquals(RemoteUploadResult.ContractBlocked, RemoteUploadResponsePolicy.classify(202, body))
        }
    }

    @Test
    fun statusCodesAreClassifiedWithoutReturningServerContent() {
        listOf(408, 429, 500, 503).forEach { status ->
            assertEquals(RemoteUploadResult.RetryableServer, RemoteUploadResponsePolicy.classify(status, "ignored"))
        }
        listOf(401, 403).forEach { status ->
            assertEquals(RemoteUploadResult.CredentialBlocked, RemoteUploadResponsePolicy.classify(status, "ignored"))
        }
        listOf(301, 302, 307, 308, 404).forEach { status ->
            assertEquals(RemoteUploadResult.EndpointBlocked, RemoteUploadResponsePolicy.classify(status, "ignored"))
        }
        assertEquals(RemoteUploadResult.ContractBlocked, RemoteUploadResponsePolicy.classify(400, "ignored"))
        assertEquals(RemoteUploadResult.ConflictBlocked, RemoteUploadResponsePolicy.classify(409, "ignored"))
        assertEquals(RemoteUploadResult.ContractBlocked, RemoteUploadResponsePolicy.classify(422, "ignored"))
    }

    @Test
    fun transportFailuresSeparateTlsFromRetryableNetworkFailure() {
        assertEquals(RemoteUploadResult.EndpointBlocked, RemoteUploadResponsePolicy.classify(SSLException("tls")))
        assertEquals(RemoteUploadResult.RetryableNetwork, RemoteUploadResponsePolicy.classify(IOException("offline")))
    }

    @Test
    fun cancellationIsNeverClassifiedAsAnEndpointFailure() {
        org.junit.Assert.assertThrows(CancellationException::class.java) {
            runBlocking {
                val client = HttpsRemoteMeasurementApiClient(
                    RemoteUploadTransport { _, _, _ -> throw CancellationException("cancel") },
                )
                val credential = RuntimeUploadCredential(
                    RemoteCredentialMetadata(
                        credentialId = "credential-1",
                        backendBindingId = "backend-1",
                        credentialRevision = 1,
                        expectedPatientId = "00000000-0000-4000-8000-000000000001",
                        expectedDeviceId = "00000000-0000-4000-8000-000000000201",
                        httpsOrigin = "https://family.example",
                    ),
                    SecretBearerToken.fromUtf8("0123456789abcdef0123456789abcdef".toByteArray()),
                )
                try {
                    client.upload(RemoteUploadEndpoint.require("https://family.example"), credential, reading())
                } finally {
                    credential.close()
                }
            }
        }
    }

    @Test
    fun requestCarriesExactDeviceCredentialTupleAndNeverPatientId() = runBlocking {
        var capturedBody: String? = null
        val client = HttpsRemoteMeasurementApiClient(
            RemoteUploadTransport { _, _, body ->
                capturedBody = body
                RemoteHttpResponse(202, "application/json", "{\"accepted\":true}")
            },
        )
        val credential = credential()
        try {
            assertEquals(
                RemoteUploadResult.Accepted,
                client.upload(RemoteUploadEndpoint.require("https://family.example"), credential, reading()),
            )
        } finally {
            credential.close()
        }

        val body = requireNotNull(capturedBody)
        assertTrue(body.contains("\"deviceId\":\"00000000-0000-4000-8000-000000000201\""))
        assertTrue(body.contains("\"backendBindingId\":\"backend-1\""))
        assertTrue(body.contains("\"credentialId\":\"credential-1\""))
        assertTrue(body.contains("\"credentialRevision\":1"))
        assertFalse(body.contains("patientId"))
    }

    @Test
    fun credentialCannotBeSentToAnotherOrigin() = runBlocking {
        var transportWasCalled = false
        val client = HttpsRemoteMeasurementApiClient(
            RemoteUploadTransport { _, _, _ ->
                transportWasCalled = true
                RemoteHttpResponse(202, "application/json", "{\"accepted\":true}")
            },
        )
        val credential = credential()
        try {
            assertEquals(
                RemoteUploadResult.CredentialBlocked,
                client.upload(RemoteUploadEndpoint.require("https://other.example"), credential, reading()),
            )
        } finally {
            credential.close()
        }
        assertFalse(transportWasCalled)
    }

    @Test
    fun blockingTransportNeverRunsOnTheCallingThread() = runBlocking {
        val callingThread = Thread.currentThread()
        var transportThread: Thread? = null
        val client = HttpsRemoteMeasurementApiClient(
            RemoteUploadTransport { _, _, _ ->
                transportThread = Thread.currentThread()
                RemoteHttpResponse(202, "application/json", "{\"accepted\":true}")
            },
        )
        val credential = credential()
        try {
            assertEquals(
                RemoteUploadResult.Accepted,
                client.upload(RemoteUploadEndpoint.require("https://family.example"), credential, reading()),
            )
        } finally {
            credential.close()
        }

        assertNotSame(callingThread, transportThread)
    }

    @Test
    fun responseReaderRejectsOversizeAndMalformedUtf8() {
        org.junit.Assert.assertThrows(ResponseTooLargeException::class.java) {
            RemoteResponseBodyReader.read(ByteArrayInputStream(ByteArray(16 * 1_024 + 1)))
        }
        org.junit.Assert.assertThrows(InvalidResponseEncodingException::class.java) {
            RemoteResponseBodyReader.read(ByteArrayInputStream(byteArrayOf(0xC3.toByte(), 0x28)))
        }
        assertTrue(RemoteResponseBodyReader.read(ByteArrayInputStream("{}".toByteArray())) == "{}")
    }

    private fun credential() = RuntimeUploadCredential(
        RemoteCredentialMetadata(
            credentialId = "credential-1",
            backendBindingId = "backend-1",
            credentialRevision = 1,
            expectedPatientId = "00000000-0000-4000-8000-000000000001",
            expectedDeviceId = "00000000-0000-4000-8000-000000000201",
            httpsOrigin = "https://family.example",
        ),
        SecretBearerToken.fromUtf8("0123456789abcdef0123456789abcdef".toByteArray()),
    )

    private fun reading() = GlucoseReading(
        eventId = "event-1",
        sensorId = "sensor-1",
        sensorFamily = SensorFamily.SIBIONICS_GS1,
        sensorTimeEpochMs = 1_000,
        phoneTimeEpochMs = 1_000,
        glucoseMgDl = 100,
        trendMgDlPerMinute = 0.0,
        quality = ReadingQuality.VALID,
        sequence = 1,
    )
}
