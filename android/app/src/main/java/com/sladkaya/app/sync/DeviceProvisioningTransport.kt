package com.sladkaya.app.sync

import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.runInterruptible

internal object DeviceProvisioningRequestEncoder {
    fun encode(
        activationCode: DeviceActivationCode,
        identity: DeviceProvisioningIdentity,
    ): ByteArray {
        val result = ByteArray(
            PREFIX.size + activationCode.value.length +
                DEVICE_ID_FIELD.size + identity.deviceId.length +
                NONCE_FIELD.size + identity.deviceNonce.length + SUFFIX.size,
        )
        var offset = 0
        offset = result.put(PREFIX, offset)
        offset = result.putAscii(activationCode.value, offset)
        offset = result.put(DEVICE_ID_FIELD, offset)
        offset = result.putAscii(identity.deviceId, offset)
        offset = result.put(NONCE_FIELD, offset)
        offset = result.putAscii(identity.deviceNonce, offset)
        offset = result.put(SUFFIX, offset)
        check(offset == result.size)
        return result
    }

    override fun toString(): String = "DeviceProvisioningRequestEncoder([REDACTED])"

    private fun ByteArray.put(source: ByteArray, start: Int): Int {
        source.copyInto(this, destinationOffset = start)
        return start + source.size
    }

    private fun ByteArray.putAscii(value: String, start: Int): Int {
        var offset = start
        value.forEach { character ->
            check(character.code in 0x21..0x7E)
            this[offset++] = character.code.toByte()
        }
        return offset
    }

    private val PREFIX = "{\"activationCode\":\"".toByteArray(Charsets.US_ASCII)
    private val DEVICE_ID_FIELD = "\",\"deviceId\":\"".toByteArray(Charsets.US_ASCII)
    private val NONCE_FIELD = "\",\"deviceNonce\":\"".toByteArray(Charsets.US_ASCII)
    private val SUFFIX = "\"}".toByteArray(Charsets.US_ASCII)
}

internal fun interface DeviceProvisioningClient {
    suspend fun provision(
        endpoint: RemoteUploadEndpoint,
        activationCode: DeviceActivationCode,
        identity: DeviceProvisioningIdentity,
    ): DeviceProvisioningExchangeResult
}

internal class HttpsDeviceProvisioningClient(
    private val transport: DeviceProvisioningTransport =
        HttpsUrlConnectionDeviceProvisioningTransport(),
) : DeviceProvisioningClient {
    override suspend fun provision(
        endpoint: RemoteUploadEndpoint,
        activationCode: DeviceActivationCode,
        identity: DeviceProvisioningIdentity,
    ): DeviceProvisioningExchangeResult {
        val requestBody = DeviceProvisioningRequestEncoder.encode(activationCode, identity)
        return try {
            val response = runInterruptible(Dispatchers.IO) {
                transport.execute(endpoint, requestBody)
            }
            DeviceProvisioningResponsePolicy.classify(
                statusCode = response.statusCode,
                contentType = response.contentType,
                responseBody = response.body,
                expectedOrigin = endpoint.origin,
                expectedIdentity = identity,
            )
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: ResponseTooLargeException) {
            DeviceProvisioningExchangeResult.ContractBlocked
        } catch (_: InvalidResponseEncodingException) {
            DeviceProvisioningExchangeResult.ContractBlocked
        } catch (error: IOException) {
            currentCoroutineContext().ensureActive()
            DeviceProvisioningResponsePolicy.classify(error)
        } finally {
            // The transport owns the first wipe; this second wipe also protects injected fakes.
            requestBody.fill(0)
        }
    }
}

internal fun interface DeviceProvisioningTransport {
    /** The implementation must erase [requestBody] before returning or throwing. */
    fun execute(
        endpoint: RemoteUploadEndpoint,
        requestBody: ByteArray,
    ): RemoteHttpResponse
}

internal fun interface DeviceProvisioningConnectionFactory {
    fun open(url: URL): HttpsURLConnection
}

internal class HttpsUrlConnectionDeviceProvisioningTransport(
    private val connectionFactory: DeviceProvisioningConnectionFactory =
        DeviceProvisioningConnectionFactory { url ->
            url.openConnection() as? HttpsURLConnection
                ?: throw SSLException("Secure provisioning transport unavailable")
        },
) : DeviceProvisioningTransport {
    override fun execute(
        endpoint: RemoteUploadEndpoint,
        requestBody: ByteArray,
    ): RemoteHttpResponse {
        var connection: HttpsURLConnection? = null
        return try {
            require(requestBody.size in 1..MAX_REQUEST_BYTES)
            val provisioningUrl = URL(endpoint.origin + PROVISIONING_PATH)
            connection = connectionFactory.open(provisioningUrl)
            connection.requestMethod = "POST"
            connection.instanceFollowRedirects = false
            connection.connectTimeout = CONNECT_TIMEOUT_MS
            connection.readTimeout = READ_TIMEOUT_MS
            connection.useCaches = false
            connection.doInput = true
            connection.doOutput = true
            connection.setFixedLengthStreamingMode(requestBody.size)
            connection.setRequestProperty("Content-Type", "application/json")
            connection.setRequestProperty("Accept", "application/json")
            connection.outputStream.use { output -> output.write(requestBody) }
            val status = connection.responseCode
            val body = if (status == HttpURLConnection.HTTP_CREATED) {
                connection.inputStream.use(RemoteResponseBodyReader::read)
            } else {
                ""
            }
            RemoteHttpResponse(
                statusCode = status,
                contentType = connection.contentType,
                body = body,
            )
        } finally {
            requestBody.fill(0)
            connection?.disconnect()
        }
    }

    private companion object {
        const val PROVISIONING_PATH = "/v1/device/provision"
        const val CONNECT_TIMEOUT_MS = 5_000
        const val READ_TIMEOUT_MS = 8_000
        const val MAX_REQUEST_BYTES = 1_024
    }
}
