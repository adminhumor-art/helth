package com.sladkaya.app.sync

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.URL
import java.security.Principal
import java.security.cert.Certificate
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class DeviceProvisioningTransportTest {
    private val code = DeviceActivationCode.require(
        "SLK1-0123-4567-89AB-CDEF-GHJK-MNPQ-RSTV-WXYZ",
    )
    private val identity = DeviceProvisioningIdentity(
        deviceId = "00000000-0000-4000-8000-00000000020a",
        deviceNonce = "AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh8",
    )
    private val endpoint = RemoteUploadEndpoint.require("https://family.example")

    @Test
    fun requestEncoderMatchesTheExactBackendShapeAndDoesNotRenderSecrets() {
        val encoded = DeviceProvisioningRequestEncoder.encode(code, identity)
        try {
            assertEquals(
                "{" +
                    "\"activationCode\":\"${code.value}\"," +
                    "\"deviceId\":\"${identity.deviceId}\"," +
                    "\"deviceNonce\":\"${identity.deviceNonce}\"" +
                    "}",
                encoded.toString(Charsets.UTF_8),
            )
            assertFalse(DeviceProvisioningRequestEncoder.toString().contains(code.value))
        } finally {
            encoded.fill(0)
        }
    }

    @Test
    fun clientUsesBoundedBlockingTransportOffThreadAndClearsTheRequestBytes() = runBlocking {
        val callingThread = Thread.currentThread()
        var transportThread: Thread? = null
        var capturedRequest: ByteArray? = null
        val client = HttpsDeviceProvisioningClient(
            DeviceProvisioningTransport { actualEndpoint, requestBody ->
                assertEquals(endpoint, actualEndpoint)
                transportThread = Thread.currentThread()
                capturedRequest = requestBody
                RemoteHttpResponse(201, "application/json", successJson())
            },
        )

        val result = client.provision(endpoint, code, identity)

        assertTrue(result is DeviceProvisioningExchangeResult.Provisioned)
        assertNotSame(callingThread, transportThread)
        assertTrue(requireNotNull(capturedRequest).all { it == 0.toByte() })
        (result as DeviceProvisioningExchangeResult.Provisioned).payload.consumeToken { token ->
            token.close()
        }
    }

    @Test
    fun tlsAndOrdinaryNetworkFailuresHaveDifferentStableResults() = runBlocking {
        val tls = HttpsDeviceProvisioningClient(
            DeviceProvisioningTransport { _, _ -> throw SSLException("private tls detail") },
        )
        val network = HttpsDeviceProvisioningClient(
            DeviceProvisioningTransport { _, _ -> throw IOException("private network detail") },
        )

        assertEquals(
            DeviceProvisioningExchangeResult.EndpointBlocked,
            tls.provision(endpoint, code, identity),
        )
        assertEquals(
            DeviceProvisioningExchangeResult.RetryableNetwork,
            network.provision(endpoint, code, identity),
        )
    }

    @Test
    fun cancellationIsNotConvertedIntoAProvisioningFailure() {
        assertThrows(CancellationException::class.java) {
            runBlocking {
                HttpsDeviceProvisioningClient(
                    DeviceProvisioningTransport { _, _ ->
                        throw CancellationException("cancel")
                    },
                ).provision(endpoint, code, identity)
            }
        }
    }

    @Test
    fun redirectsAreBlockedAndDefaultTransportNeverFollowsThem() = runBlocking {
        val connection = RecordingProvisioningConnection(statusCode = 302, responseBody = "private")
        val transport = HttpsUrlConnectionDeviceProvisioningTransport { connection }
        val request = DeviceProvisioningRequestEncoder.encode(code, identity)

        val response = transport.execute(endpoint, request)

        assertEquals(302, response.statusCode)
        assertFalse(connection.instanceFollowRedirects)
        assertEquals("POST", connection.requestMethod)
        assertEquals("application/json", connection.getRequestProperty("Content-Type"))
        assertEquals("application/json", connection.getRequestProperty("Accept"))
        assertEquals(null, connection.getRequestProperty("Authorization"))
        assertEquals(0, connection.inputReads)
        assertTrue(request.all { it == 0.toByte() })
        assertTrue(connection.disconnected)
        assertEquals(
            DeviceProvisioningExchangeResult.ContractBlocked,
            DeviceProvisioningResponsePolicy.classify(
                response.statusCode,
                response.contentType,
                response.body,
                endpoint.origin,
                identity,
            ),
        )
    }

    @Test
    fun successfulResponseBodyIsBoundedAndStrictUtf8() {
        val oversized = RecordingProvisioningConnection(
            statusCode = 201,
            responseBody = "x".repeat(16 * 1_024 + 1),
        )
        val malformed = RecordingProvisioningConnection(
            statusCode = 201,
            responseBytes = byteArrayOf(0xC3.toByte(), 0x28),
        )

        assertThrows(ResponseTooLargeException::class.java) {
            HttpsUrlConnectionDeviceProvisioningTransport { oversized }
                .execute(endpoint, DeviceProvisioningRequestEncoder.encode(code, identity))
        }
        assertThrows(InvalidResponseEncodingException::class.java) {
            HttpsUrlConnectionDeviceProvisioningTransport { malformed }
                .execute(endpoint, DeviceProvisioningRequestEncoder.encode(code, identity))
        }
        assertTrue(oversized.disconnected)
        assertTrue(malformed.disconnected)
    }

    @Test
    fun transportErasesRejectedRequestBytesBeforeThrowing() {
        val oversized = ByteArray(1_025) { 7 }

        assertThrows(IllegalArgumentException::class.java) {
            HttpsUrlConnectionDeviceProvisioningTransport { error("must not open") }
                .execute(endpoint, oversized)
        }

        assertTrue(oversized.all { it == 0.toByte() })
    }

    private fun successJson(): String =
        "{" +
            "\"deviceToken\":\"0123456789abcdef0123456789abcdef01234567890\"," +
            "\"apiOrigin\":\"${endpoint.origin}\"," +
            "\"deviceId\":\"${identity.deviceId}\"," +
            "\"patientId\":\"00000000-0000-4000-8000-000000000001\"," +
            "\"backendBindingId\":\"backend-1\"," +
            "\"credentialId\":\"credential-1\"," +
            "\"credentialRevision\":1" +
            "}"
}

private class RecordingProvisioningConnection(
    statusCode: Int,
    responseBody: String = "",
    responseBytes: ByteArray = responseBody.toByteArray(Charsets.UTF_8),
) : HttpsURLConnection(URL("https://family.example/v1/device/provision")) {
    private val response = responseBytes.copyOf()
    private val output = ByteArrayOutputStream()
    private val configuredStatusCode = statusCode
    var inputReads = 0
    var disconnected = false
    var fixedLength = -1

    override fun getResponseCode(): Int = configuredStatusCode

    override fun getContentType(): String = "application/json"

    override fun getInputStream() = ByteArrayInputStream(response).also { inputReads += 1 }

    override fun getOutputStream() = output

    override fun setFixedLengthStreamingMode(contentLength: Int) {
        fixedLength = contentLength
        super.setFixedLengthStreamingMode(contentLength)
    }

    override fun disconnect() {
        disconnected = true
        response.fill(0)
        output.reset()
    }

    override fun usingProxy(): Boolean = false

    override fun connect() = Unit

    override fun getCipherSuite(): String = "TLS_FAKE"

    override fun getLocalCertificates(): Array<Certificate>? = null

    override fun getServerCertificates(): Array<Certificate> = emptyArray()

    override fun getPeerPrincipal(): Principal? = null

    override fun getLocalPrincipal(): Principal? = null
}
