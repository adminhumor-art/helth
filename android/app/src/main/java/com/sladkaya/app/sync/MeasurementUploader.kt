package com.sladkaya.app.sync

import com.sladkaya.app.BuildConfig
import com.sladkaya.core.data.MeasurementRepository
import com.sladkaya.core.model.GlucoseReading
import java.net.HttpURLConnection
import java.net.URI
import java.time.Instant
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject

class MeasurementUploader(
    private val repository: MeasurementRepository,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val flushMutex = Mutex()

    suspend fun enqueue(reading: GlucoseReading) {
        reading.requireProductPublication()
        repository.enqueue(reading)
        flush()
    }

    suspend fun flush() = flushMutex.withLock {
        withContext(Dispatchers.IO) {
            while (true) {
                val batch = repository.pending()
                if (batch.isEmpty()) return@withContext
                for (next in batch) {
                    when (val action = MeasurementUploadBatchPolicy.action(next)) {
                        is MeasurementUploadAction.DiscardLegacySimulation -> {
                            repository.discardLegacySimulation(action.eventId)
                        }
                        MeasurementUploadAction.Upload -> {
                            if (BuildConfig.API_BASE_URL.isBlank() ||
                                BuildConfig.DEVICE_TOKEN.isBlank()
                            ) {
                                return@withContext
                            }
                            if (!upload(next)) {
                                repository.markAttemptFailed(next.eventId)
                                return@withContext
                            }
                            repository.markUploaded(next.eventId, clock())
                        }
                    }
                }
                if (batch.size < 100) return@withContext
            }
        }
    }

    private fun upload(reading: GlucoseReading): Boolean {
        val connection = URI.create(BuildConfig.API_BASE_URL.trimEnd('/') + "/v1/device/measurements").toURL().openConnection() as HttpURLConnection
        return try {
            connection.requestMethod = "POST"
            connection.connectTimeout = 5_000
            connection.readTimeout = 8_000
            connection.doOutput = true
            connection.setRequestProperty("Content-Type", "application/json")
            connection.setRequestProperty("Authorization", "Bearer ${BuildConfig.DEVICE_TOKEN}")
            connection.outputStream.bufferedWriter().use { it.write(reading.toJson()) }
            connection.responseCode in 200..299
        } catch (_: Exception) {
            false
        } finally {
            connection.disconnect()
        }
    }

    private fun GlucoseReading.toJson(): String = JSONObject()
        .put("eventId", eventId)
        .put("sensorId", sensorId)
        .put("sensorFamily", sensorFamily.wireName)
        .put("sensorTime", Instant.ofEpochMilli(sensorTimeEpochMs).toString())
        .put("phoneTime", Instant.ofEpochMilli(phoneTimeEpochMs).toString())
        .put("glucoseMgDl", glucoseMgDl)
        .put("trendMgDlPerMinute", trendMgDlPerMinute)
        .put("quality", quality.wireName)
        .put("sequence", sequence)
        .toString()
}

internal sealed interface MeasurementUploadAction {
    data class DiscardLegacySimulation(val eventId: String) : MeasurementUploadAction
    data object Upload : MeasurementUploadAction
}

internal object MeasurementUploadBatchPolicy {
    fun action(reading: GlucoseReading): MeasurementUploadAction =
        if (reading.isEligibleForProductPublication) {
            MeasurementUploadAction.Upload
        } else {
            MeasurementUploadAction.DiscardLegacySimulation(reading.eventId)
        }
}
