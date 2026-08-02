package com.sladkaya.app.sync

import com.sladkaya.core.model.GlucoseReading
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.time.Instant
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.runInterruptible

sealed interface RemoteUploadResult {
    data object Accepted : RemoteUploadResult
    data object RetryableNetwork : RemoteUploadResult
    data object RetryableServer : RemoteUploadResult
    data object CredentialBlocked : RemoteUploadResult
    data object EndpointBlocked : RemoteUploadResult
    data object ContractBlocked : RemoteUploadResult
    data object ConflictBlocked : RemoteUploadResult
}

internal object RemoteUploadResponsePolicy {
    fun classify(
        statusCode: Int,
        responseBody: String,
        contentType: String? = "application/json",
    ): RemoteUploadResult = when {
        statusCode == 202 -> {
            val isJson = contentType?.substringBefore(';')?.trim()?.equals("application/json", ignoreCase = true) == true
            val accepted = isJson && EXACT_ACCEPTED_JSON.matches(responseBody)
            if (accepted) RemoteUploadResult.Accepted else RemoteUploadResult.ContractBlocked
        }
        statusCode == 408 || statusCode == 429 || statusCode in 500..599 -> RemoteUploadResult.RetryableServer
        statusCode == 401 || statusCode == 403 -> RemoteUploadResult.CredentialBlocked
        statusCode in 300..399 || statusCode == 404 -> RemoteUploadResult.EndpointBlocked
        statusCode == 409 -> RemoteUploadResult.ConflictBlocked
        else -> RemoteUploadResult.ContractBlocked
    }

    fun classify(error: IOException): RemoteUploadResult =
        if (error is SSLException) RemoteUploadResult.EndpointBlocked else RemoteUploadResult.RetryableNetwork

    private val EXACT_ACCEPTED_JSON = Regex(
        "^[ \\t\\r\\n]*\\{[ \\t\\r\\n]*\"accepted\"[ \\t\\r\\n]*:[ \\t\\r\\n]*true[ \\t\\r\\n]*}[ \\t\\r\\n]*$",
    )
}

fun interface RemoteMeasurementApiClient {
    suspend fun upload(
        endpoint: RemoteUploadEndpoint,
        credential: RuntimeUploadCredential,
        reading: GlucoseReading,
    ): RemoteUploadResult
}

internal data class RemoteHttpResponse(
    val statusCode: Int,
    val contentType: String?,
    val body: String,
) {
    override fun toString(): String = "RemoteHttpResponse(statusCode=$statusCode, body=[REDACTED])"
}

internal fun interface RemoteUploadTransport {
    fun execute(
        endpoint: RemoteUploadEndpoint,
        credential: RuntimeUploadCredential,
        requestBody: String,
    ): RemoteHttpResponse
}

class HttpsRemoteMeasurementApiClient internal constructor(
    private val transport: RemoteUploadTransport = HttpsUrlConnectionUploadTransport(),
) : RemoteMeasurementApiClient {
    override suspend fun upload(
        endpoint: RemoteUploadEndpoint,
        credential: RuntimeUploadCredential,
        reading: GlucoseReading,
    ): RemoteUploadResult {
        if (credential.metadata.httpsOrigin != endpoint.origin) {
            return RemoteUploadResult.CredentialBlocked
        }
        return try {
            val requestBody = reading.toUploadJson(credential.metadata)
            val response = runInterruptible(Dispatchers.IO) {
                transport.execute(endpoint, credential, requestBody)
            }
            RemoteUploadResponsePolicy.classify(response.statusCode, response.body, response.contentType)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: ResponseTooLargeException) {
            RemoteUploadResult.ContractBlocked
        } catch (error: InvalidResponseEncodingException) {
            RemoteUploadResult.ContractBlocked
        } catch (error: IOException) {
            currentCoroutineContext().ensureActive()
            RemoteUploadResponsePolicy.classify(error)
        }
    }
}

private class HttpsUrlConnectionUploadTransport : RemoteUploadTransport {
    override fun execute(
        endpoint: RemoteUploadEndpoint,
        credential: RuntimeUploadCredential,
        requestBody: String,
    ): RemoteHttpResponse {
        val connection = endpoint.measurementsUri.toURL().openConnection() as? HttpsURLConnection
            ?: throw SSLException("Secure upload transport unavailable")
        val requestBytes = requestBody.toByteArray(Charsets.UTF_8)
        return try {
            connection.requestMethod = "POST"
            connection.instanceFollowRedirects = false
            connection.connectTimeout = CONNECT_TIMEOUT_MS
            connection.readTimeout = READ_TIMEOUT_MS
            connection.doOutput = true
            connection.setFixedLengthStreamingMode(requestBytes.size)
            connection.setRequestProperty("Content-Type", "application/json")
            connection.setRequestProperty("Accept", "application/json")
            credential.bearerToken.useHeaderValue { token ->
                connection.setRequestProperty("Authorization", "Bearer $token")
            }
            connection.outputStream.use { output ->
                output.write(requestBytes)
            }
            val status = connection.responseCode
            val stream = if (status == HttpURLConnection.HTTP_ACCEPTED) connection.inputStream else null
            RemoteHttpResponse(
                statusCode = status,
                contentType = connection.contentType,
                body = stream?.use(RemoteResponseBodyReader::read).orEmpty(),
            )
        } finally {
            requestBytes.fill(0)
            connection.disconnect()
        }
    }

    private companion object {
        const val CONNECT_TIMEOUT_MS = 5_000
        const val READ_TIMEOUT_MS = 8_000
    }
}

internal object RemoteResponseBodyReader {
    private const val MAX_RESPONSE_BYTES = 16 * 1_024

    fun read(input: java.io.InputStream): String {
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(1_024)
        var total = 0
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            total += count
            if (total > MAX_RESPONSE_BYTES) throw ResponseTooLargeException()
            output.write(buffer, 0, count)
        }
        return try {
            Charsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(output.toByteArray()))
                .toString()
        } catch (_: java.nio.charset.CharacterCodingException) {
            throw InvalidResponseEncodingException()
        }
    }
}

internal class ResponseTooLargeException : IOException("Remote response exceeds configured limit")
internal class InvalidResponseEncodingException : IOException("Remote response encoding is invalid")

private fun GlucoseReading.toUploadJson(metadata: RemoteCredentialMetadata): String {
    require(trendMgDlPerMinute.isFinite()) { "Measurement trend must be finite" }
    return buildString {
        append('{')
        appendJsonStringField("deviceId", metadata.expectedDeviceId)
        appendJsonStringField("backendBindingId", metadata.backendBindingId)
        appendJsonStringField("credentialId", metadata.credentialId)
        appendJsonNumberField("credentialRevision", metadata.credentialRevision)
        appendJsonStringField("eventId", eventId)
        appendJsonStringField("sensorId", sensorId)
        appendJsonStringField("sensorFamily", sensorFamily.wireName)
        appendJsonStringField("sensorTime", Instant.ofEpochMilli(sensorTimeEpochMs).toString())
        appendJsonStringField("phoneTime", Instant.ofEpochMilli(phoneTimeEpochMs).toString())
        appendJsonNumberField("glucoseMgDl", glucoseMgDl.toLong())
        appendJsonRawNumberField("trendMgDlPerMinute", trendMgDlPerMinute.toString())
        appendJsonStringField("quality", quality.wireName)
        appendJsonNumberField("sequence", sequence, isLast = true)
        append('}')
    }
}

private fun StringBuilder.appendJsonStringField(name: String, value: String, isLast: Boolean = false) {
    appendJsonString(name)
    append(':')
    appendJsonString(value)
    if (!isLast) append(',')
}

private fun StringBuilder.appendJsonNumberField(name: String, value: Long, isLast: Boolean = false) {
    appendJsonRawNumberField(name, value.toString(), isLast)
}

private fun StringBuilder.appendJsonRawNumberField(name: String, value: String, isLast: Boolean = false) {
    appendJsonString(name)
    append(':')
    append(value)
    if (!isLast) append(',')
}

private fun StringBuilder.appendJsonString(value: String) {
    append('"')
    value.forEach { character ->
        when (character) {
            '"' -> append("\\\"")
            '\\' -> append("\\\\")
            '\b' -> append("\\b")
            '\u000C' -> append("\\f")
            '\n' -> append("\\n")
            '\r' -> append("\\r")
            '\t' -> append("\\t")
            else -> if (character.code < 0x20 || character.isSurrogate()) {
                append("\\u")
                append(character.code.toString(16).padStart(4, '0'))
            } else {
                append(character)
            }
        }
    }
    append('"')
}
