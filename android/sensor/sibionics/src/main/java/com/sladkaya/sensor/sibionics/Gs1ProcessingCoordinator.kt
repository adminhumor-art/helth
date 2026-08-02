package com.sladkaya.sensor.sibionics

import com.sladkaya.core.data.AtomicSensorCoreRecord
import com.sladkaya.core.data.RawSensorSampleRecord
import com.sladkaya.core.data.SensorAlgorithmCheckpointRecord
import com.sladkaya.core.data.SensorAlgorithmResultRecord
import com.sladkaya.core.data.SensorCoreCommitResult
import com.sladkaya.core.data.SensorCoreStore
import com.sladkaya.core.data.SensorFailureCommitResult
import com.sladkaya.core.data.SensorIngestionFailureRecord
import com.sladkaya.core.model.ReadingQuality
import com.sladkaya.core.model.SensorFamily
import com.sladkaya.sensor.sibionics.algorithm.AlgorithmCheckpoint
import com.sladkaya.sensor.sibionics.algorithm.AlgorithmCommitResult
import com.sladkaya.sensor.sibionics.algorithm.AlgorithmErrorCode
import com.sladkaya.sensor.sibionics.algorithm.AlgorithmOutput
import com.sladkaya.sensor.sibionics.algorithm.AlgorithmStepResult
import com.sladkaya.sensor.sibionics.algorithm.DecodedSensitivity
import com.sladkaya.sensor.sibionics.algorithm.SibionicsAlgorithmSession
import java.security.MessageDigest
import java.util.concurrent.CancellationException
import kotlin.math.roundToInt
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

internal sealed interface Gs1ProcessingResult {
    /**
     * A physically unvalidated candidate. Product measurement and alarm types
     * are intentionally absent from this diagnostic-only boundary.
     */
    data class Diagnostic(
        val candidate: Gs1DiagnosticReading,
    ) : Gs1ProcessingResult

    data class Rejected(
        val code: String,
        val message: String,
        val checkpointCommitted: Boolean = false,
    ) : Gs1ProcessingResult

    data class PersistenceUnavailable(val message: String) : Gs1ProcessingResult
    data class StorageConflict(val reason: String) : Gs1ProcessingResult
    data class Closed(val reason: String) : Gs1ProcessingResult
    data object NoPendingCommit : Gs1ProcessingResult
}

data class Gs1DiagnosticReading(
    val eventId: String,
    val sensorId: String,
    val sensorFamily: SensorFamily,
    val sensorTimeEpochMs: Long,
    val phoneTimeEpochMs: Long,
    val glucoseMgDl: Int,
    val trendMgDlPerMinute: Double,
    val quality: ReadingQuality,
    val sequence: Long,
)

/**
 * Owns the strict boundary between one decoded GS1/GS1Sb sample, the stateful
 * native algorithm and durable storage. A new native step is never accepted
 * until the previous state/result pair has been committed.
 */
internal class Gs1ProcessingCoordinator(
    private val sensorId: String,
    private val bluetoothAddress: String,
    private val family: SensorFamily,
    private val transportVariant: Int,
    private val algorithm: SibionicsAlgorithmSession,
    private val sensitivity: DecodedSensitivity,
    private val store: SensorCoreStore,
    private val transportProtocol: String,
    private val transportCodecId: String,
    private val phoneClock: () -> Long = System::currentTimeMillis,
) : AutoCloseable, Gs1SampleProcessor {
    private val mutex = Mutex()
    private var pending: PendingCommit? = null
    private var pendingFailure: PendingFailure? = null
    private var closed = false

    init {
        require(sensorId.isNotBlank() && sensorId.length <= 128)
        require(CANONICAL_BLUETOOTH_ADDRESS.matches(bluetoothAddress))
        require(family == SensorFamily.SIBIONICS_GS1 || family == SensorFamily.SIBIONICS_GS1SB)
        require(transportVariant >= 0)
    }

    override suspend fun process(
        encryptedPacket: ByteArray,
        sample: DecodedGs1RawSample,
        receivedAtEpochMs: Long,
    ): Gs1ProcessingResult = mutex.withLock {
        if (closed) return@withLock Gs1ProcessingResult.Closed("Sensor processing session is closed")
        if (pendingFailure != null) {
            return@withLock Gs1ProcessingResult.PersistenceUnavailable(
                "The previous failure evidence still requires a durable commit",
            )
        }
        if (pending != null) {
            return@withLock Gs1ProcessingResult.PersistenceUnavailable(
                "The previous native state still requires a durable commit",
            )
        }

        val phoneTime = receivedAtEpochMs
        if (encryptedPacket.isEmpty() || encryptedPacket.size > MAX_PACKET_BYTES) {
            return@withLock rejectAndRecord(
                encryptedPacket = encryptedPacket,
                sample = sample,
                phoneTime = phoneTime.coerceAtLeast(UNKNOWN_PHONE_TIME),
                code = "INVALID_PACKET",
                message = "Encrypted sensor packet must contain 1..$MAX_PACKET_BYTES bytes",
                nativeStateMayHaveChanged = false,
            )
        }
        val packet = encryptedPacket.copyOf()
        if (phoneTime <= 0 || sample.sensorTimeEpochSeconds !in 1..MAX_SENSOR_TIME_SECONDS) {
            return@withLock rejectAndRecord(
                encryptedPacket = packet,
                sample = sample,
                phoneTime = phoneTime.coerceAtLeast(UNKNOWN_PHONE_TIME),
                code = "INVALID_TIME",
                message = "Sensor and phone timestamps must be positive and representable",
                nativeStateMayHaveChanged = false,
            )
        }
        val algorithmInput = sample.toAlgorithmInput()
        if (!algorithmInput.isValid()) {
            return@withLock rejectAndRecord(
                encryptedPacket = packet,
                sample = sample,
                phoneTime = phoneTime,
                code = "INVALID_INPUT",
                message = "Decoded sensor sample is outside the algorithm input contract",
                nativeStateMayHaveChanged = false,
            )
        }

        val step = algorithm.process(algorithmInput)

        when (step) {
            is AlgorithmStepResult.Success -> {
                val sensorTimeMs = sample.sensorTimeEpochSeconds * MILLIS_PER_SECOND
                val ageMs = phoneTime - sensorTimeMs
                val realtime = sample.reindex == 0 && ageMs in 0 until MAX_REALTIME_AGE_MS
                val quality = when {
                    sample.index <= WARMUP_MINUTES -> ReadingQuality.WARMING_UP
                    realtime -> ReadingQuality.VALID
                    else -> ReadingQuality.DEGRADED
                }
                val completedResult = Gs1ProcessingResult.Diagnostic(
                    step.output.toDiagnosticReading(sample, phoneTime, quality),
                )
                val record = try {
                    buildRecord(
                        encryptedPacket = packet,
                        sample = sample,
                        phoneTime = phoneTime,
                        output = step.output,
                        checkpoint = step.checkpoint,
                        algorithmErrorCode = null,
                    )
                } catch (failure: Exception) {
                    closed = true
                    algorithm.close()
                    return@withLock rejectAndRecord(
                        encryptedPacket = packet,
                        sample = sample,
                        phoneTime = phoneTime,
                        code = "POST_NATIVE_RECORD_FAILED",
                        message = failure.message ?: "Post-algorithm record construction failed",
                        nativeStateMayHaveChanged = true,
                    )
                }
                pending = PendingCommit(
                    record = record,
                    checkpoint = step.checkpoint,
                    completedResult = completedResult,
                )
                commitPendingLocked()
            }

            is AlgorithmStepResult.Failure -> {
                val checkpoint = step.checkpoint
                val diagnostic = step.diagnosticOutput
                if (checkpoint == null || diagnostic == null) {
                    val terminal = step.error.code in TERMINAL_ALGORITHM_ERRORS
                    if (terminal) {
                        closed = true
                        algorithm.close()
                    }
                    return@withLock rejectAndRecord(
                        encryptedPacket = packet,
                        sample = sample,
                        phoneTime = phoneTime,
                        code = step.error.code.name,
                        message = step.error.message,
                        nativeStateMayHaveChanged = step.error.code in MAY_HAVE_MUTATED_NATIVE_ERRORS,
                    )
                }
                val record = try {
                    buildRecord(
                        encryptedPacket = packet,
                        sample = sample,
                        phoneTime = phoneTime,
                        output = diagnostic,
                        checkpoint = checkpoint,
                        algorithmErrorCode = step.error.code.name,
                    )
                } catch (failure: Exception) {
                    closed = true
                    algorithm.close()
                    return@withLock rejectAndRecord(
                        encryptedPacket = packet,
                        sample = sample,
                        phoneTime = phoneTime,
                        code = "POST_NATIVE_DIAGNOSTIC_FAILED",
                        message = failure.message ?: "Post-algorithm diagnostic construction failed",
                        nativeStateMayHaveChanged = true,
                    )
                }
                pending = PendingCommit(
                    record = record,
                    checkpoint = checkpoint,
                    completedResult = Gs1ProcessingResult.Rejected(
                        code = step.error.code.name,
                        message = step.error.message,
                        checkpointCommitted = true,
                    ),
                )
                commitPendingLocked()
            }
        }
    }

    /** Unit-test seam; production packet flow always supplies its durable ingress timestamp. */
    internal suspend fun process(
        encryptedPacket: ByteArray,
        sample: DecodedGs1RawSample,
    ): Gs1ProcessingResult = process(encryptedPacket, sample, phoneClock())

    override suspend fun retryPendingCommit(): Gs1ProcessingResult = mutex.withLock {
        if (pendingFailure != null) return@withLock commitPendingFailureLocked()
        if (closed) return@withLock Gs1ProcessingResult.Closed("Sensor processing session is closed")
        if (pending == null) return@withLock Gs1ProcessingResult.NoPendingCommit
        commitPendingLocked()
    }

    override fun close() {
        closed = true
        algorithm.close()
    }

    private suspend fun commitPendingLocked(): Gs1ProcessingResult {
        val value = pending ?: return Gs1ProcessingResult.NoPendingCommit
        val commitResult = try {
            store.commit(value.record)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            return Gs1ProcessingResult.PersistenceUnavailable(
                failure.message ?: "Sensor state could not be committed",
            )
        }

        if (commitResult is SensorCoreCommitResult.Conflict) {
            pending = null
            closed = true
            algorithm.close()
            return Gs1ProcessingResult.StorageConflict(commitResult.reason)
        }

        return when (val confirmation = algorithm.confirmPersisted(value.checkpoint)) {
            AlgorithmCommitResult.Success -> {
                pending = null
                value.completedResult
            }

            is AlgorithmCommitResult.Failure -> {
                pending = null
                closed = true
                Gs1ProcessingResult.Closed(confirmation.error.message)
            }
        }
    }

    private suspend fun rejectAndRecord(
        encryptedPacket: ByteArray,
        sample: DecodedGs1RawSample,
        phoneTime: Long,
        code: String,
        message: String,
        nativeStateMayHaveChanged: Boolean,
    ): Gs1ProcessingResult {
        val safeMessage = message.ifBlank { code }.take(MAX_FAILURE_MESSAGE_CHARS)
        val failureRecord = try {
            val packetHash = encryptedPacket.sha256()
            val failureIdentity = listOf(
                sensorId,
                family.wireName,
                sample.index.toString(),
                sample.sensorTimeEpochSeconds.toString(),
                packetHash,
                sample.current.toString(),
                sample.temperature.toString(),
                sample.reindex.toString(),
                transportVariant.toString(),
                code,
                nativeStateMayHaveChanged.toString(),
            ).joinToString("\u0000").encodeToByteArray().sha256()
            SensorIngestionFailureRecord(
                failureId = failureIdentity,
                sensorId = sensorId,
                sensorFamily = family,
                sequence = sample.index,
                reportedSensorTimeEpochSeconds = sample.sensorTimeEpochSeconds,
                phoneTimeEpochMs = phoneTime.coerceAtLeast(UNKNOWN_PHONE_TIME),
                packet = encryptedPacket,
                packetSha256 = packetHash,
                currentRaw = sample.current,
                temperatureRaw = sample.temperature,
                historyDistance = sample.reindex,
                transportVariant = transportVariant,
                failureCode = code,
                failureMessage = safeMessage,
                nativeStateMayHaveChanged = nativeStateMayHaveChanged,
            )
        } catch (failure: Exception) {
            return Gs1ProcessingResult.PersistenceUnavailable(
                "$code: failure evidence could not be constructed: ${failure.message ?: "unknown error"}",
            )
        }

        pendingFailure = PendingFailure(
            record = failureRecord,
            completedResult = Gs1ProcessingResult.Rejected(code, safeMessage),
        )
        return commitPendingFailureLocked()
    }

    private suspend fun commitPendingFailureLocked(): Gs1ProcessingResult {
        val value = pendingFailure ?: return Gs1ProcessingResult.NoPendingCommit
        val journalResult = try {
            store.recordFailure(value.record)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: LinkageError) {
            return Gs1ProcessingResult.PersistenceUnavailable(
                "${value.record.failureCode}: failure evidence could not be persisted: " +
                    (failure.message ?: "storage linkage failed"),
            )
        } catch (failure: Exception) {
            return Gs1ProcessingResult.PersistenceUnavailable(
                "${value.record.failureCode}: failure evidence could not be persisted: " +
                    (failure.message ?: "storage failed"),
            )
        }

        return when (journalResult) {
            SensorFailureCommitResult.Committed,
            SensorFailureCommitResult.AlreadyCommitted,
            -> {
                pendingFailure = null
                value.completedResult
            }

            is SensorFailureCommitResult.Conflict -> {
                pendingFailure = null
                closed = true
                algorithm.close()
                Gs1ProcessingResult.StorageConflict(
                    "Failure journal conflict: ${journalResult.reason}",
                )
            }
        }
    }

    private fun buildRecord(
        encryptedPacket: ByteArray,
        sample: DecodedGs1RawSample,
        phoneTime: Long,
        output: AlgorithmOutput,
        checkpoint: AlgorithmCheckpoint,
        algorithmErrorCode: String?,
    ): AtomicSensorCoreRecord {
        require(checkpoint.sensitivityToken == sensitivity.token) {
            "Decoded sensitivity does not belong to the active algorithm token"
        }
        val packetHash = encryptedPacket.sha256()
        val eventId = eventId(sample)
        val raw = RawSensorSampleRecord(
            eventId = eventId,
            sensorId = sensorId,
            sensorFamily = family,
            sequence = sample.index,
            sensorTimeEpochMs = sample.sensorTimeEpochSeconds * MILLIS_PER_SECOND,
            phoneTimeEpochMs = phoneTime,
            packet = encryptedPacket,
            packetSha256 = packetHash,
            currentRaw = sample.current,
            temperatureRaw = sample.temperature,
            historyDistance = sample.reindex,
            transportVariant = transportVariant,
            sensorTimeWasClamped = sample.sensorTimeWasClamped,
            addTimeSeconds = sample.addTimeSeconds,
        )
        val result = SensorAlgorithmResultRecord(
            eventId = eventId,
            sensorId = sensorId,
            sequence = sample.index,
            sensorTimeEpochMs = sample.sensorTimeEpochSeconds * MILLIS_PER_SECOND,
            nativeGlucoseMmolL = output.nativeGlucoseMmolL,
            displayedGlucoseMmolL = output.glucoseMmolL,
            nativeTrend = output.trend,
            glucoseWarning = output.warnings.glucose,
            currentWarning = output.warnings.current,
            temperatureWarning = output.warnings.temperature,
            algorithmProfile = checkpoint.profile.name,
            algorithmVersion = checkpoint.algorithmVersion,
            binarySetId = checkpoint.binarySetId,
            sensitivityToken = checkpoint.sensitivityToken.value,
            sensitivityTokenSource = checkpoint.sensitivityToken.source.name,
            sensitivityCoefficient = sensitivity.coefficient.toDouble(),
            sensitivityEncoding = sensitivity.encoding.name,
            initializationMode = checkpoint.initializationMode.name,
            publishable = false,
            alarmEligible = false,
            algorithmErrorCode = algorithmErrorCode,
        )
        val savedCheckpoint = SensorAlgorithmCheckpointRecord(
            sensorId = sensorId,
            bluetoothAddress = bluetoothAddress,
            sensorFamily = family,
            transportVariant = transportVariant,
            transportProtocol = transportProtocol,
            transportCodecId = transportCodecId,
            sequence = checkpoint.lastProcessedIndex,
            sensorTimeEpochMs = checkpoint.lastSensorTimeEpochSeconds * MILLIS_PER_SECOND,
            algorithmProfile = checkpoint.profile.name,
            algorithmVersion = checkpoint.algorithmVersion,
            binarySetId = checkpoint.binarySetId,
            sensitivityToken = checkpoint.sensitivityToken.value,
            sensitivityTokenSource = checkpoint.sensitivityToken.source.name,
            sensitivityCoefficient = sensitivity.coefficient.toDouble(),
            sensitivityEncoding = sensitivity.encoding.name,
            initializationMode = checkpoint.initializationMode.name,
            state = checkpoint.nativeState,
            stateSha256 = checkpoint.nativeStateSha256,
            displayOffsetMmolL = checkpoint.displayOffsetMmolL,
            schemaVersion = checkpoint.schemaVersion,
        )
        return AtomicSensorCoreRecord(raw, result, savedCheckpoint, measurement = null)
    }

    private fun AlgorithmOutput.toDiagnosticReading(
        sample: DecodedGs1RawSample,
        phoneTime: Long,
        quality: ReadingQuality,
    ) = Gs1DiagnosticReading(
        eventId = eventId(sample),
        sensorId = sensorId,
        sensorFamily = family,
        sensorTimeEpochMs = sample.sensorTimeEpochSeconds * MILLIS_PER_SECOND,
        phoneTimeEpochMs = phoneTime,
        glucoseMgDl = (glucoseMmolL * MG_DL_PER_MMOL_L).roundToInt(),
        trendMgDlPerMinute = (trend * NATIVE_TREND_SCALE).coerceIn(-20.0, 20.0),
        quality = quality,
        sequence = sample.index.toLong(),
    )

    private fun eventId(sample: DecodedGs1RawSample): String =
        "$sensorId\u0000${family.wireName}\u0000${sample.index}\u0000${sample.sensorTimeEpochSeconds}"
            .encodeToByteArray()
            .sha256()

    private data class PendingCommit(
        val record: AtomicSensorCoreRecord,
        val checkpoint: AlgorithmCheckpoint,
        val completedResult: Gs1ProcessingResult,
    )

    private data class PendingFailure(
        val record: SensorIngestionFailureRecord,
        val completedResult: Gs1ProcessingResult.Rejected,
    )

    private companion object {
        const val MAX_PACKET_BYTES = 250
        const val MAX_FAILURE_MESSAGE_CHARS = 1_024
        const val UNKNOWN_PHONE_TIME = 0L
        const val MAX_SENSOR_TIME_SECONDS = Long.MAX_VALUE / 1_000L
        const val MAX_REALTIME_AGE_MS = 330_000L
        const val MG_DL_PER_MMOL_L = 18.0
        const val NATIVE_TREND_SCALE = 1.3
        const val MILLIS_PER_SECOND = 1_000L
        const val WARMUP_MINUTES = 60
        const val TRANSPORT_PROTOCOL = "GS1_V120"
        val CANONICAL_BLUETOOTH_ADDRESS = Regex("^(?:[0-9A-F]{2}:){5}[0-9A-F]{2}$")
        val TERMINAL_ALGORITHM_ERRORS = setOf(
            AlgorithmErrorCode.CLOSED,
            AlgorithmErrorCode.NATIVE_PROCESS_FAILED,
            AlgorithmErrorCode.NON_FINITE_NATIVE_OUTPUT,
            AlgorithmErrorCode.NATIVE_STATE_FAILED,
        )
        val MAY_HAVE_MUTATED_NATIVE_ERRORS = setOf(
            AlgorithmErrorCode.NATIVE_PROCESS_FAILED,
            AlgorithmErrorCode.NON_FINITE_NATIVE_OUTPUT,
            AlgorithmErrorCode.NATIVE_STATE_FAILED,
        )
    }
}

private fun ByteArray.sha256(): String = MessageDigest.getInstance("SHA-256")
    .digest(this)
    .joinToString(separator = "") { byte -> "%02x".format(byte.toInt() and 0xff) }
