package com.sladkaya.sensor.sibionics.algorithm

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SensitivityTokenPolicyTest {
    @Test
    fun validPackageCodeIsPreservedExactlyWithoutSubstitution() {
        val result = SensitivityTokenPolicy.validatePackageCode("aB12cd34")

        assertEquals(
            SensitivityToken.packageCode("aB12cd34"),
            (result as SensitivityTokenValidation.Valid).token,
        )
    }

    @Test
    fun missingOrMalformedPackageCodeIsRejected() {
        assertEquals(
            SensitivityTokenInputError.MISSING,
            (SensitivityTokenPolicy.validatePackageCode(null) as SensitivityTokenValidation.Invalid).error,
        )
        assertEquals(
            SensitivityTokenInputError.WRONG_LENGTH,
            (SensitivityTokenPolicy.validatePackageCode("ABC") as SensitivityTokenValidation.Invalid).error,
        )
        assertEquals(
            SensitivityTokenInputError.NON_ASCII_ALPHANUMERIC,
            (SensitivityTokenPolicy.validatePackageCode("ABC-1234") as SensitivityTokenValidation.Invalid).error,
        )
        assertEquals(
            SensitivityTokenInputError.NON_ASCII_ALPHANUMERIC,
            (SensitivityTokenPolicy.validatePackageCode("АБВГ1234") as SensitivityTokenValidation.Invalid).error,
        )
    }

    @Test
    fun invalidCodeNeverProducesFallbackCandidate() {
        val result = SensitivityTokenPolicy.validatePackageCode("bad")

        assertTrue(result is SensitivityTokenValidation.Invalid)
    }
}
