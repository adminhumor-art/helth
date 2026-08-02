package com.sladkaya.sensor.sibionics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class Gs1DataMatrixParserTest {
    @Test
    fun parsesAFullElementStringWithAsciiGroupSeparators() {
        val gs = '\u001d'
        val input = buildString {
            append(gs)
            append("0106972831641803")
            append("11241219")
            append("17251218")
            append("10LT4F241247J")
            append(gs)
            append("21241247YEZ1450HAJ02")
        }

        val payload = Gs1DataMatrixParser.parse(input).requireSuccess()

        assertEquals("06972831641803", payload.gtin.value)
        assertEquals("241219", payload.productionDate?.value)
        assertEquals("251218", payload.expirationDate?.value)
        assertEquals("LT4F241247J", payload.lot?.value)
        assertEquals("241247YEZ1450HAJ02", payload.serial?.value)
        assertNull(payload.additionalProductId)
        assertNull(payload.secondarySerial)
        assertEquals("6972831", payload.manufacturer.value)
        assertEquals("64180", payload.sku.value)
        assertEquals(Gs1IdentityStatus.UNVERIFIED_IDENTITY, payload.identityStatus)
        assertNull(payload.packageCodeCandidate)
    }

    @Test
    fun acceptsScannerGroupSeparatorsAndAimDataMatrixPrefix() {
        val input = buildString {
            append("]d2^]")
            append("0106972831641476")
            append("11241223")
            append("17251222")
            append("10LT46241219C")
            append("^]21WD9QAXGA52WS4V")
            append("^]240PIN123")
            append("^]250Ab1Zcd34")
        }

        val payload = Gs1DataMatrixParser.parse(input).requireSuccess()

        assertEquals("06972831641476", payload.gtin.value)
        assertEquals("241223", payload.productionDate?.value)
        assertEquals("251222", payload.expirationDate?.value)
        assertEquals("LT46241219C", payload.lot?.value)
        assertEquals("WD9QAXGA52WS4V", payload.serial?.value)
        assertEquals("PIN123", payload.additionalProductId?.value)
        assertEquals("Ab1Zcd34", payload.secondarySerial?.value)
        assertEquals("6972831", payload.manufacturer.value)
        assertEquals("64147", payload.sku.value)
        assertEquals(Gs1IdentityStatus.UNVERIFIED_IDENTITY, payload.identityStatus)
        assertEquals("Ab1Zcd34", payload.packageCodeCandidate?.value)
    }

    @Test
    fun acceptsAimPrefixWithoutALeadingGroupSeparator() {
        val payload = Gs1DataMatrixParser.parse("]d2010697283164180321SERIAL").requireSuccess()

        assertEquals("06972831641803", payload.gtin.value)
        assertEquals("SERIAL", payload.serial?.value)
    }

    @Test
    fun secondarySerialIsOnlyAnUnverifiedPackageCodeCandidateAtExactlyEightAsciiCharacters() {
        listOf(
            "SHORT7" to null,
            "NINECHARS" to null,
            "AB CD123" to "AB CD123",
        ).forEach { (secondarySerial, expectedCandidate) ->
            val payload = Gs1DataMatrixParser.parse(
                "0106972831641803250$secondarySerial",
            ).requireSuccess()

            assertEquals(secondarySerial, payload.secondarySerial?.value)
            assertEquals(expectedCandidate, payload.packageCodeCandidate?.value)
            assertEquals(Gs1IdentityStatus.UNVERIFIED_IDENTITY, payload.identityStatus)
        }
    }

    @Test
    fun doesNotPromoteEightCharacterValuesFromOtherApplicationIdentifiers() {
        val gs = '\u001d'
        val payload = Gs1DataMatrixParser.parse(
            "010697283164180310Ab1Zcd34${gs}21Qr5Tuv67${gs}240PinCode8",
        ).requireSuccess()

        assertEquals("Ab1Zcd34", payload.lot?.value)
        assertEquals("Qr5Tuv67", payload.serial?.value)
        assertEquals("PinCode8", payload.additionalProductId?.value)
        assertNull(payload.packageCodeCandidate)
        assertEquals(Gs1IdentityStatus.UNVERIFIED_IDENTITY, payload.identityStatus)
    }

    @Test
    fun doesNotGuessAnApplicationIdentifierInsideAnUnterminatedVariableField() {
        val payload = Gs1DataMatrixParser.parse(
            "010697283164180310AB21SER",
        ).requireSuccess()

        assertEquals("AB21SER", payload.lot?.value)
        assertNull(payload.serial)
    }

    @Test
    fun rejectsEmptyAndOversizedInputsBeforeParsing() {
        assertFailure("", Gs1DataMatrixParseError.EMPTY_INPUT)
        assertFailure("]d2", Gs1DataMatrixParseError.EMPTY_INPUT)
        assertFailure(
            "0".repeat(MAX_RAW_GS1_DATA_MATRIX_CHARS + 1),
            Gs1DataMatrixParseError.INPUT_TOO_LONG,
        )
    }

    @Test
    fun rejectsUnsupportedAimPrefixAndNonAsciiData() {
        assertFailure("]Q30106972831641803", Gs1DataMatrixParseError.UNSUPPORTED_AIM_PREFIX)
        assertFailure("010697283164180321СЕРИЯ", Gs1DataMatrixParseError.NON_ASCII_DATA)
        assertFailure("010697283164180321ABC\u0001", Gs1DataMatrixParseError.NON_ASCII_DATA)
    }

    @Test
    fun rejectsUnknownAndDuplicateApplicationIdentifiers() {
        val gs = '\u001d'
        assertFailure(
            "010697283164180399UNKNOWN",
            Gs1DataMatrixParseError.UNKNOWN_APPLICATION_IDENTIFIER,
        )
        assertFailure(
            "0106972831641803${gs}0106972831641476",
            Gs1DataMatrixParseError.DUPLICATE_APPLICATION_IDENTIFIER,
        )
        assertFailure(
            "010697283164180321ONE${gs}21TWO",
            Gs1DataMatrixParseError.DUPLICATE_APPLICATION_IDENTIFIER,
        )
    }

    @Test
    fun rejectsTruncatedOrNonNumericFixedLengthFields() {
        assertFailure("010697283164", Gs1DataMatrixParseError.TRUNCATED_FIXED_LENGTH_FIELD)
        assertFailure(
            "01069728316418A3",
            Gs1DataMatrixParseError.INVALID_FIXED_LENGTH_FIELD,
        )
        assertFailure(
            "01069728316418031124A219",
            Gs1DataMatrixParseError.INVALID_FIXED_LENGTH_FIELD,
        )
    }

    @Test
    fun rejectsAGtinWithAnInvalidCheckDigitBeforeSkuRouting() {
        assertFailure(
            "0106972831641804",
            Gs1DataMatrixParseError.INVALID_GTIN_CHECK_DIGIT,
        )
    }

    @Test
    fun rejectsEmptyAndOversizedVariableLengthFields() {
        val gs = '\u001d'
        assertFailure(
            "010697283164180310${gs}21SERIAL",
            Gs1DataMatrixParseError.EMPTY_VARIABLE_LENGTH_FIELD,
        )
        assertFailure(
            "010697283164180310${"A".repeat(21)}",
            Gs1DataMatrixParseError.VARIABLE_LENGTH_FIELD_TOO_LONG,
        )
        assertFailure(
            "0106972831641803240${"A".repeat(31)}",
            Gs1DataMatrixParseError.VARIABLE_LENGTH_FIELD_TOO_LONG,
        )
    }

    @Test
    fun rejectsStrayConsecutiveAndTrailingGroupSeparators() {
        val gs = '\u001d'
        assertFailure(
            "$gs${gs}0106972831641803",
            Gs1DataMatrixParseError.UNEXPECTED_GROUP_SEPARATOR,
        )
        assertFailure(
            "0106972831641803${gs}${gs}21SERIAL",
            Gs1DataMatrixParseError.UNEXPECTED_GROUP_SEPARATOR,
        )
        assertFailure(
            "010697283164180321SERIAL$gs",
            Gs1DataMatrixParseError.TRAILING_GROUP_SEPARATOR,
        )
    }

    @Test
    fun requiresGtinForTypedManufacturerAndSkuIdentityFields() {
        assertFailure("21SERIAL", Gs1DataMatrixParseError.MISSING_GTIN)
    }

    private fun assertFailure(input: String, expected: Gs1DataMatrixParseError) {
        val result = Gs1DataMatrixParser.parse(input)

        assertTrue("Expected failure for '$input', got $result", result is Gs1DataMatrixParseResult.Failure)
        assertEquals(expected, (result as Gs1DataMatrixParseResult.Failure).error)
    }

    private fun Gs1DataMatrixParseResult.requireSuccess(): ParsedGs1DataMatrix {
        assertTrue("Expected success, got $this", this is Gs1DataMatrixParseResult.Success)
        return (this as Gs1DataMatrixParseResult.Success).payload
    }
}
