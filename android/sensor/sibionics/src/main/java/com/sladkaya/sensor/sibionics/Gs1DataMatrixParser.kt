package com.sladkaya.sensor.sibionics

const val MAX_RAW_GS1_DATA_MATRIX_CHARS = 512

enum class Gs1IdentityStatus {
    UNVERIFIED_IDENTITY,
}

enum class Gs1DataMatrixParseError {
    EMPTY_INPUT,
    INPUT_TOO_LONG,
    UNSUPPORTED_AIM_PREFIX,
    NON_ASCII_DATA,
    UNKNOWN_APPLICATION_IDENTIFIER,
    DUPLICATE_APPLICATION_IDENTIFIER,
    TRUNCATED_FIXED_LENGTH_FIELD,
    INVALID_FIXED_LENGTH_FIELD,
    INVALID_GTIN_CHECK_DIGIT,
    EMPTY_VARIABLE_LENGTH_FIELD,
    VARIABLE_LENGTH_FIELD_TOO_LONG,
    UNEXPECTED_GROUP_SEPARATOR,
    TRAILING_GROUP_SEPARATOR,
    MISSING_GTIN,
}

sealed interface Gs1DataMatrixParseResult {
    data class Success(val payload: ParsedGs1DataMatrix) : Gs1DataMatrixParseResult

    data class Failure(
        val error: Gs1DataMatrixParseError,
        val offset: Int? = null,
        val applicationIdentifier: String? = null,
    ) : Gs1DataMatrixParseResult
}

data class Gs1Gtin14(val value: String) {
    init {
        require(
            value.length == GTIN_LENGTH &&
                value.all(Char::isAsciiDigit) &&
                value.hasValidGtinCheckDigit(),
        )
    }
}

data class Gs1DateYYMMDD(val value: String) {
    init {
        require(value.length == GS1_DATE_LENGTH && value.all(Char::isAsciiDigit))
    }
}

data class Gs1Lot(val value: String) {
    init {
        require(value.isValidVariableValue(MAX_LOT_CHARS))
    }
}

data class Gs1Serial(val value: String) {
    init {
        require(value.isValidVariableValue(MAX_SERIAL_CHARS))
    }
}

data class Gs1AdditionalProductId(val value: String) {
    init {
        require(value.isValidVariableValue(MAX_ADDITIONAL_PRODUCT_ID_CHARS))
    }
}

data class Gs1SecondarySerial(val value: String) {
    init {
        require(value.isValidVariableValue(MAX_SECONDARY_SERIAL_CHARS))
    }
}

data class Gs1ManufacturerPrefix(val value: String) {
    init {
        require(value.length == MANUFACTURER_PREFIX_LENGTH && value.all(Char::isAsciiDigit))
    }
}

data class Gs1Sku(val value: String) {
    init {
        require(value.length == SKU_LENGTH && value.all(Char::isAsciiDigit))
    }
}

data class Gs1UnverifiedPackageCodeCandidate(val value: String) {
    init {
        require(value.length == PACKAGE_CODE_CANDIDATE_LENGTH && value.all(Char::isPrintableAscii))
    }
}

data class ParsedGs1DataMatrix(
    val gtin: Gs1Gtin14,
    val productionDate: Gs1DateYYMMDD?,
    val expirationDate: Gs1DateYYMMDD?,
    val lot: Gs1Lot?,
    val serial: Gs1Serial?,
    val additionalProductId: Gs1AdditionalProductId?,
    val secondarySerial: Gs1SecondarySerial?,
    val manufacturer: Gs1ManufacturerPrefix,
    val sku: Gs1Sku,
    val packageCodeCandidate: Gs1UnverifiedPackageCodeCandidate?,
    val identityStatus: Gs1IdentityStatus = Gs1IdentityStatus.UNVERIFIED_IDENTITY,
)

/**
 * Parses GS1 syntax only. Success never proves a physical sensor identity and
 * never selects a sensor family, transport variant, algorithm or activation path.
 */
object Gs1DataMatrixParser {
    fun parse(raw: String): Gs1DataMatrixParseResult {
        if (raw.length > MAX_RAW_GS1_DATA_MATRIX_CHARS) {
            return failure(Gs1DataMatrixParseError.INPUT_TOO_LONG)
        }

        val withoutAimPrefix = when {
            raw.startsWith(AIM_DATA_MATRIX_PREFIX) -> raw.substring(AIM_DATA_MATRIX_PREFIX.length)
            raw.startsWith(AIM_PREFIX_START) -> {
                return failure(Gs1DataMatrixParseError.UNSUPPORTED_AIM_PREFIX, offset = 0)
            }
            else -> raw
        }
        val normalized = normalizeScannerGroupSeparators(withoutAimPrefix)
        if (normalized.isEmpty()) return failure(Gs1DataMatrixParseError.EMPTY_INPUT)
        normalized.forEachIndexed { index, char ->
            if (char != GROUP_SEPARATOR && !char.isPrintableAscii()) {
                return failure(Gs1DataMatrixParseError.NON_ASCII_DATA, offset = index)
            }
        }

        var offset = if (normalized.first() == GROUP_SEPARATOR) 1 else 0
        if (offset == normalized.length) return failure(Gs1DataMatrixParseError.EMPTY_INPUT)

        val values = linkedMapOf<ApplicationIdentifier, String>()
        while (offset < normalized.length) {
            if (normalized[offset] == GROUP_SEPARATOR) {
                return failure(Gs1DataMatrixParseError.UNEXPECTED_GROUP_SEPARATOR, offset)
            }
            val identifier = ApplicationIdentifier.at(normalized, offset)
                ?: return failure(Gs1DataMatrixParseError.UNKNOWN_APPLICATION_IDENTIFIER, offset)
            if (identifier in values) {
                return failure(
                    error = Gs1DataMatrixParseError.DUPLICATE_APPLICATION_IDENTIFIER,
                    offset = offset,
                    applicationIdentifier = identifier.code,
                )
            }
            offset += identifier.code.length

            val field = if (identifier.fixedLength != null) {
                val valueEnd = offset + identifier.fixedLength
                if (valueEnd > normalized.length ||
                    normalized.indexOf(GROUP_SEPARATOR, startIndex = offset) in offset until valueEnd
                ) {
                    return failure(
                        error = Gs1DataMatrixParseError.TRUNCATED_FIXED_LENGTH_FIELD,
                        offset = offset,
                        applicationIdentifier = identifier.code,
                    )
                }
                normalized.substring(offset, valueEnd).also { value ->
                    if (!value.all(Char::isAsciiDigit)) {
                        return failure(
                            error = Gs1DataMatrixParseError.INVALID_FIXED_LENGTH_FIELD,
                            offset = offset,
                            applicationIdentifier = identifier.code,
                        )
                    }
                    offset = valueEnd
                }
            } else {
                val separator = normalized.indexOf(GROUP_SEPARATOR, startIndex = offset)
                val valueEnd = if (separator >= 0) separator else normalized.length
                val value = normalized.substring(offset, valueEnd)
                if (value.isEmpty()) {
                    return failure(
                        error = Gs1DataMatrixParseError.EMPTY_VARIABLE_LENGTH_FIELD,
                        offset = offset,
                        applicationIdentifier = identifier.code,
                    )
                }
                if (value.length > identifier.maxLength) {
                    return failure(
                        error = Gs1DataMatrixParseError.VARIABLE_LENGTH_FIELD_TOO_LONG,
                        offset = offset,
                        applicationIdentifier = identifier.code,
                    )
                }
                offset = valueEnd
                value
            }
            values[identifier] = field

            if (offset < normalized.length && normalized[offset] == GROUP_SEPARATOR) {
                offset += 1
                if (offset == normalized.length) {
                    return failure(Gs1DataMatrixParseError.TRAILING_GROUP_SEPARATOR, offset - 1)
                }
            }
        }

        val gtinValue = values[ApplicationIdentifier.GTIN]
            ?: return failure(Gs1DataMatrixParseError.MISSING_GTIN)
        if (!gtinValue.hasValidGtinCheckDigit()) {
            return failure(
                error = Gs1DataMatrixParseError.INVALID_GTIN_CHECK_DIGIT,
                applicationIdentifier = ApplicationIdentifier.GTIN.code,
            )
        }
        val gtin = Gs1Gtin14(gtinValue)
        val secondarySerial = values[ApplicationIdentifier.SECONDARY_SERIAL]?.let(::Gs1SecondarySerial)
        val candidate = secondarySerial?.value
            ?.takeIf { it.length == PACKAGE_CODE_CANDIDATE_LENGTH && it.all(Char::isPrintableAscii) }
            ?.let(::Gs1UnverifiedPackageCodeCandidate)

        return Gs1DataMatrixParseResult.Success(
            ParsedGs1DataMatrix(
                gtin = gtin,
                productionDate = values[ApplicationIdentifier.PRODUCTION_DATE]?.let(::Gs1DateYYMMDD),
                expirationDate = values[ApplicationIdentifier.EXPIRATION_DATE]?.let(::Gs1DateYYMMDD),
                lot = values[ApplicationIdentifier.LOT]?.let(::Gs1Lot),
                serial = values[ApplicationIdentifier.SERIAL]?.let(::Gs1Serial),
                additionalProductId = values[ApplicationIdentifier.ADDITIONAL_PRODUCT_ID]
                    ?.let(::Gs1AdditionalProductId),
                secondarySerial = secondarySerial,
                manufacturer = Gs1ManufacturerPrefix(
                    gtin.value.substring(MANUFACTURER_PREFIX_START, MANUFACTURER_PREFIX_END),
                ),
                sku = Gs1Sku(gtin.value.substring(SKU_START, SKU_END)),
                packageCodeCandidate = candidate,
            ),
        )
    }
}

private enum class ApplicationIdentifier(
    val code: String,
    val fixedLength: Int?,
    val maxLength: Int,
) {
    GTIN("01", GTIN_LENGTH, GTIN_LENGTH),
    PRODUCTION_DATE("11", GS1_DATE_LENGTH, GS1_DATE_LENGTH),
    EXPIRATION_DATE("17", GS1_DATE_LENGTH, GS1_DATE_LENGTH),
    LOT("10", null, MAX_LOT_CHARS),
    SERIAL("21", null, MAX_SERIAL_CHARS),
    ADDITIONAL_PRODUCT_ID("240", null, MAX_ADDITIONAL_PRODUCT_ID_CHARS),
    SECONDARY_SERIAL("250", null, MAX_SECONDARY_SERIAL_CHARS),
    ;

    companion object {
        private val longestCodeFirst = entries.sortedByDescending { it.code.length }

        fun at(value: String, offset: Int): ApplicationIdentifier? =
            longestCodeFirst.firstOrNull { value.startsWith(it.code, startIndex = offset) }
    }
}

private fun normalizeScannerGroupSeparators(value: String): String = buildString(value.length) {
    var offset = 0
    while (offset < value.length) {
        if (value.startsWith(SCANNER_GROUP_SEPARATOR, startIndex = offset)) {
            append(GROUP_SEPARATOR)
            offset += SCANNER_GROUP_SEPARATOR.length
        } else {
            append(value[offset])
            offset += 1
        }
    }
}

private fun failure(
    error: Gs1DataMatrixParseError,
    offset: Int? = null,
    applicationIdentifier: String? = null,
): Gs1DataMatrixParseResult.Failure = Gs1DataMatrixParseResult.Failure(
    error = error,
    offset = offset,
    applicationIdentifier = applicationIdentifier,
)

private fun String.isValidVariableValue(maxLength: Int): Boolean =
    isNotEmpty() && length <= maxLength && all(Char::isPrintableAscii)

private fun Char.isAsciiDigit(): Boolean = this in '0'..'9'

private fun String.hasValidGtinCheckDigit(): Boolean {
    if (length != GTIN_LENGTH || !all(Char::isAsciiDigit)) return false
    val weightedSum = substring(0, GTIN_LENGTH - 1).sumOfIndexed { index, char ->
        (char - '0') * if (index % 2 == 0) 3 else 1
    }
    val expected = (10 - weightedSum % 10) % 10
    return last() - '0' == expected
}

private inline fun CharSequence.sumOfIndexed(transform: (Int, Char) -> Int): Int {
    var total = 0
    for (index in indices) total += transform(index, this[index])
    return total
}

private fun Char.isPrintableAscii(): Boolean = code in PRINTABLE_ASCII_START..PRINTABLE_ASCII_END

private const val AIM_DATA_MATRIX_PREFIX = "]d2"
private const val AIM_PREFIX_START = "]"
private const val SCANNER_GROUP_SEPARATOR = "^]"
private const val GROUP_SEPARATOR = '\u001d'
private const val GTIN_LENGTH = 14
private const val GS1_DATE_LENGTH = 6
private const val MAX_LOT_CHARS = 20
private const val MAX_SERIAL_CHARS = 20
private const val MAX_ADDITIONAL_PRODUCT_ID_CHARS = 30
private const val MAX_SECONDARY_SERIAL_CHARS = 30
private const val MANUFACTURER_PREFIX_START = 1
private const val MANUFACTURER_PREFIX_END = 8
private const val MANUFACTURER_PREFIX_LENGTH = 7
private const val SKU_START = 8
private const val SKU_END = 13
private const val SKU_LENGTH = 5
private const val PACKAGE_CODE_CANDIDATE_LENGTH = 8
private const val PRINTABLE_ASCII_START = 0x20
private const val PRINTABLE_ASCII_END = 0x7e
