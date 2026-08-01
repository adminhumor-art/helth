package com.sladkaya.sensor.sibionics.algorithm

enum class SensitivityTokenInputError {
    MISSING,
    WRONG_LENGTH,
    NON_ASCII_ALPHANUMERIC,
}

sealed interface SensitivityTokenValidation {
    data class Valid(val token: SensitivityToken) : SensitivityTokenValidation
    data class Invalid(val error: SensitivityTokenInputError) : SensitivityTokenValidation
}

object SensitivityTokenPolicy {
    fun validatePackageCode(value: String?): SensitivityTokenValidation {
        if (value.isNullOrEmpty()) {
            return SensitivityTokenValidation.Invalid(SensitivityTokenInputError.MISSING)
        }
        if (value.length != REQUIRED_LENGTH) {
            return SensitivityTokenValidation.Invalid(SensitivityTokenInputError.WRONG_LENGTH)
        }
        val token = SensitivityToken.packageCode(value)
        if (!token.isValid()) {
            return SensitivityTokenValidation.Invalid(SensitivityTokenInputError.NON_ASCII_ALPHANUMERIC)
        }
        return SensitivityTokenValidation.Valid(token)
    }

    private const val REQUIRED_LENGTH = 8
}
