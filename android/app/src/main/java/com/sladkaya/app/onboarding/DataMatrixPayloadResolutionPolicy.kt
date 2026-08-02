package com.sladkaya.app.onboarding

import com.sladkaya.sensor.sibionics.Gs1DataMatrixParseResult
import com.sladkaya.sensor.sibionics.Gs1DataMatrixParser

internal enum class DataMatrixManualReason {
    INVALID_GS1_PAYLOAD,
    UNSUPPORTED_MANUFACTURER,
    GS3_REQUIRES_SEPARATE_SETUP,
    TRANSMITTER_CODE_IS_NOT_SENSOR_CODE,
    CODE_NOT_DERIVABLE,
    CONFLICTING_CANDIDATES,
}

internal sealed interface DataMatrixPackageCodeResolution {
    data class Ready(
        val packageCode: String,
        val requiresUserConfirmation: Boolean,
    ) : DataMatrixPackageCodeResolution

    data class ManualRequired(
        val reason: DataMatrixManualReason,
    ) : DataMatrixPackageCodeResolution
}

/**
 * Resolves a camera payload without silently turning uncertain identity into activation.
 * Full GS1-derived values remain visible candidates until the user confirms the box code.
 */
internal object DataMatrixPayloadResolutionPolicy {
    fun resolve(rawValue: String): DataMatrixPackageCodeResolution {
        DataMatrixPackageCodePolicy.accept(rawValue)?.let { exact ->
            return DataMatrixPackageCodeResolution.Ready(
                packageCode = exact,
                requiresUserConfirmation = true,
            )
        }

        val parsed = when (val result = Gs1DataMatrixParser.parse(rawValue)) {
            is Gs1DataMatrixParseResult.Success -> result.payload
            is Gs1DataMatrixParseResult.Failure -> return manual(
                DataMatrixManualReason.INVALID_GS1_PAYLOAD,
            )
        }
        if (parsed.manufacturer.value != SIBIONICS_MANUFACTURER_PREFIX) {
            return manual(DataMatrixManualReason.UNSUPPORTED_MANUFACTURER)
        }
        when (parsed.sku.value) {
            in GS3_SKUS -> return manual(DataMatrixManualReason.GS3_REQUIRES_SEPARATE_SETUP)
            TRANSMITTER_SKU -> return manual(
                DataMatrixManualReason.TRANSMITTER_CODE_IS_NOT_SENSOR_CODE,
            )
        }

        val candidates = buildSet {
            parsed.packageCodeCandidate?.value
                ?.let(DataMatrixPackageCodePolicy::accept)
                ?.let(::add)
            parsed.serial?.value
                ?.derivePackageCodeCandidate(rawValue.canonicalGs1PayloadLength())
                ?.let(DataMatrixPackageCodePolicy::accept)
                ?.let(::add)
        }
        return when (candidates.size) {
            0 -> manual(DataMatrixManualReason.CODE_NOT_DERIVABLE)
            1 -> DataMatrixPackageCodeResolution.Ready(
                packageCode = candidates.single(),
                requiresUserConfirmation = true,
            )
            else -> manual(DataMatrixManualReason.CONFLICTING_CANDIDATES)
        }
    }

    /** Documented packaging layouts; the result remains unverified until user confirmation. */
    private fun String.derivePackageCodeCandidate(canonicalPayloadLength: Int): String? {
        val distanceFromEnd = if (canonicalPayloadLength < LONG_LAYOUT_MIN_CHARS) {
            SHORT_LAYOUT_CODE_END_FROM_TAIL
        } else {
            LONG_LAYOUT_CODE_END_FROM_TAIL
        }
        val end = length - distanceFromEnd
        val start = end - PACKAGE_CODE_CHARS
        return takeIf { start >= 0 && end <= length }?.substring(start, end)
    }

    private fun String.canonicalGs1PayloadLength(): Int {
        var offset = if (startsWith(AIM_DATA_MATRIX_PREFIX)) AIM_DATA_MATRIX_PREFIX.length else 0
        var canonicalLength = 0
        while (offset < length) {
            offset += if (startsWith(SCANNER_GROUP_SEPARATOR, startIndex = offset)) {
                SCANNER_GROUP_SEPARATOR.length
            } else {
                1
            }
            canonicalLength += 1
        }
        return canonicalLength
    }

    private fun manual(reason: DataMatrixManualReason) =
        DataMatrixPackageCodeResolution.ManualRequired(reason)

    private const val SIBIONICS_MANUFACTURER_PREFIX = "6972831"
    private val GS3_SKUS = setOf("64221", "64300")
    private const val TRANSMITTER_SKU = "64148"
    private const val LONG_LAYOUT_MIN_CHARS = 65
    private const val PACKAGE_CODE_CHARS = 8
    private const val SHORT_LAYOUT_CODE_END_FROM_TAIL = 3
    private const val LONG_LAYOUT_CODE_END_FROM_TAIL = 4
    private const val AIM_DATA_MATRIX_PREFIX = "]d2"
    private const val SCANNER_GROUP_SEPARATOR = "^]"
}
