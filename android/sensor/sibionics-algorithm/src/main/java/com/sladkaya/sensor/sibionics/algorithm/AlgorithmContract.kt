package com.sladkaya.sensor.sibionics.algorithm

import java.security.MessageDigest

enum class AlgorithmProfile(
    val targetLowMmolL: Double,
    val targetHighMmolL: Double,
    val stateSize: Int,
    internal val fiveMinuteAnchors: Boolean,
) {
    V116A(
        targetLowMmolL = 4.4,
        targetHighMmolL = 11.1,
        stateSize = 2_480,
        fiveMinuteAnchors = true,
    ),
    V115G(
        targetLowMmolL = 3.9,
        targetHighMmolL = 7.8,
        stateSize = 2_336,
        fiveMinuteAnchors = false,
    ),
}

enum class SensitivityTokenSource {
    PACKAGE_CODE,
}

enum class AlgorithmInitializationMode {
    STANDARD,
    FACTION,
}

data class SensitivityToken(
    val value: String,
    val source: SensitivityTokenSource,
) {
    fun isValid(): Boolean = value.length == 8 && value.all(Char::isAsciiLetterOrDigit)

    companion object {
        fun packageCode(value: String) = SensitivityToken(value, SensitivityTokenSource.PACKAGE_CODE)
    }
}

private fun Char.isAsciiLetterOrDigit(): Boolean =
    this in '0'..'9' || this in 'A'..'Z' || this in 'a'..'z'

data class AlgorithmInput(
    val index: Int,
    val sensorTimeEpochSeconds: Long,
    val signal: Double,
    val temperatureCelsius: Double,
    val historyDistance: Int,
) {
    fun isValid(): Boolean =
        index in 0..0xffff &&
            sensorTimeEpochSeconds > 0 &&
            signal.isFinite() && signal > 0.1 && signal < 3_000.0 &&
            temperatureCelsius.isFinite() &&
            historyDistance >= 0
}

data class AlgorithmCheckpoint(
    val profile: AlgorithmProfile,
    val binarySetId: String,
    val sensitivityToken: SensitivityToken,
    val initializationMode: AlgorithmInitializationMode,
    val lastProcessedIndex: Int,
    val lastSensorTimeEpochSeconds: Long,
    val nativeState: ByteArray,
    val nativeStateSha256: String,
    val displayOffsetMmolL: Double,
    val schemaVersion: Int,
    val algorithmVersion: String = "unknown",
)

data class AlgorithmWarnings(
    val glucose: Int,
    val current: Int,
    val temperature: Int,
)

data class AlgorithmOutput(
    val index: Int,
    val sensorTimeEpochSeconds: Long,
    val glucoseMmolL: Double,
    val nativeGlucoseMmolL: Double,
    val trend: Int,
    val warnings: AlgorithmWarnings,
    val algorithmProfile: AlgorithmProfile,
    val algorithmVersion: String,
    val tokenSource: SensitivityTokenSource,
    val initializationMode: AlgorithmInitializationMode,
)

enum class AlgorithmErrorCode {
    INVALID_SENSITIVITY_TOKEN,
    UNSUPPORTED_INITIALIZATION_MODE,
    PROFILE_MISMATCH,
    BINARY_SET_MISMATCH,
    SENSITIVITY_TOKEN_MISMATCH,
    INITIALIZATION_MODE_MISMATCH,
    STATE_SIZE_MISMATCH,
    STATE_HASH_MISMATCH,
    NATIVE_CREATE_FAILED,
    NATIVE_METADATA_FAILED,
    NATIVE_INIT_FAILED,
    NATIVE_RESTORE_FAILED,
    NATIVE_PROCESS_FAILED,
    NON_FINITE_NATIVE_OUTPUT,
    NATIVE_STATE_FAILED,
    INITIAL_HISTORY_REQUIRED,
    NON_SEQUENTIAL_INDEX,
    NON_SEQUENTIAL_SENSOR_TIME,
    INVALID_INPUT,
    INVALID_GLUCOSE,
    CHECKPOINT_COMMIT_REQUIRED,
    CHECKPOINT_COMMIT_MISMATCH,
    CLOSED,
}

data class AlgorithmError(
    val code: AlgorithmErrorCode,
    val message: String,
)

sealed interface AlgorithmOpenResult {
    data class Success(val session: SibionicsAlgorithmSession) : AlgorithmOpenResult
    data class Failure(val error: AlgorithmError) : AlgorithmOpenResult
}

sealed interface AlgorithmStepResult {
    data class Success(
        val output: AlgorithmOutput,
        val checkpoint: AlgorithmCheckpoint,
    ) : AlgorithmStepResult

    data class Failure(
        val error: AlgorithmError,
        val checkpoint: AlgorithmCheckpoint? = null,
        val diagnosticOutput: AlgorithmOutput? = null,
    ) : AlgorithmStepResult
}

sealed interface AlgorithmCommitResult {
    data object Success : AlgorithmCommitResult
    data class Failure(val error: AlgorithmError) : AlgorithmCommitResult
}

internal fun sha256(bytes: ByteArray): String = MessageDigest
    .getInstance("SHA-256")
    .digest(bytes)
    .joinToString(separator = "") { byte -> "%02x".format(byte.toInt() and 0xff) }
