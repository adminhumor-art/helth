package com.sladkaya.app.onboarding

import com.sladkaya.sensor.sibionics.MAX_RAW_GS1_DATA_MATRIX_CHARS

internal object DataMatrixPackageCodePolicy {
    fun accept(rawValue: String?): String? = rawValue?.takeIf { value ->
        value.length == PACKAGE_CODE_LENGTH && value.all(Char::isAsciiLetterOrDigit)
    }

    private const val PACKAGE_CODE_LENGTH = 8
}

internal sealed interface DataMatrixScanDecision {
    data object AwaitingConfirmation : DataMatrixScanDecision

    data class Confirmed(
        val rawValue: String,
    ) : DataMatrixScanDecision

    data object AlreadyConfirmed : DataMatrixScanDecision
}

/**
 * Accepts one exact DataMatrix value from two consecutive analyzed frames.
 * Missing, malformed or multiple values break the sequence instead of being guessed.
 */
internal class DataMatrixScanConsensus {
    private var previousRawValue: String? = null
    private var consecutiveMatches = 0
    private var confirmed = false

    @Synchronized
    fun observe(rawValues: List<String?>): DataMatrixScanDecision {
        if (confirmed) return DataMatrixScanDecision.AlreadyConfirmed

        val current = rawValues.singleOrNull()?.takeIf(DataMatrixRawValuePolicy::accept)
        if (current == null) {
            resetCandidate()
            return DataMatrixScanDecision.AwaitingConfirmation
        }

        consecutiveMatches = if (current == previousRawValue) {
            consecutiveMatches + 1
        } else {
            previousRawValue = current
            1
        }
        if (consecutiveMatches < REQUIRED_CONSECUTIVE_MATCHES) {
            return DataMatrixScanDecision.AwaitingConfirmation
        }

        confirmed = true
        return DataMatrixScanDecision.Confirmed(current)
    }

    private fun resetCandidate() {
        previousRawValue = null
        consecutiveMatches = 0
    }

    private companion object {
        const val REQUIRED_CONSECUTIVE_MATCHES = 2
    }
}

private object DataMatrixRawValuePolicy {
    fun accept(value: String): Boolean =
        value.isNotEmpty() &&
            value.length <= MAX_RAW_GS1_DATA_MATRIX_CHARS &&
            value.all { char -> char == ASCII_GROUP_SEPARATOR || char.isPrintableAscii() }

    private fun Char.isPrintableAscii(): Boolean = code in 0x20..0x7e

    private const val ASCII_GROUP_SEPARATOR = '\u001d'
}

private fun Char.isAsciiLetterOrDigit(): Boolean =
    this in '0'..'9' || this in 'A'..'Z' || this in 'a'..'z'
