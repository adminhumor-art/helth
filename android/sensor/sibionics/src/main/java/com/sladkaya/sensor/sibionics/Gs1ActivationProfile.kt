package com.sladkaya.sensor.sibionics

import com.sladkaya.core.model.SensorFamily

enum class Gs1DiagnosticActivationProfileError {
    INVALID_SENSOR_ID,
    UNSUPPORTED_FAMILY,
    INVALID_BLUETOOTH_ADDRESS,
    UNVERIFIED_TRANSPORT_VARIANT,
    INVALID_PACKAGE_CODE,
}

sealed interface Gs1DiagnosticActivationProfileValidation {
    data class Valid(
        val profile: Gs1DiagnosticActivationProfile,
    ) : Gs1DiagnosticActivationProfileValidation

    data class Invalid(
        val error: Gs1DiagnosticActivationProfileError,
    ) : Gs1DiagnosticActivationProfileValidation
}

/**
 * Complete immutable identity required before a physical GS1/GS1Sb runtime may
 * open storage, native state or Bluetooth. There is intentionally no scan-first
 * fallback: the configured MAC must identify the exact sensor.
 */
class Gs1DiagnosticActivationProfile private constructor(
    val sensorId: String,
    val family: SensorFamily,
    val bluetoothAddress: String,
    val transportVariant: Int,
    val packageCode: String,
) {
    internal fun coreConfiguration() = Gs1CoreConfiguration(
        sensorId = sensorId,
        family = family,
        bluetoothAddress = bluetoothAddress,
        transportVariant = transportVariant,
        packageCode = packageCode,
    )

    companion object {
        fun validate(
            sensorId: String,
            family: SensorFamily,
            bluetoothAddress: String,
            transportVariant: Int,
            packageCode: String,
        ): Gs1DiagnosticActivationProfileValidation {
            if (sensorId.isBlank() || sensorId.length > MAX_SENSOR_ID_CHARS) {
                return Gs1DiagnosticActivationProfileValidation.Invalid(
                    Gs1DiagnosticActivationProfileError.INVALID_SENSOR_ID,
                )
            }
            if (family != SensorFamily.SIBIONICS_GS1 &&
                family != SensorFamily.SIBIONICS_GS1SB
            ) {
                return Gs1DiagnosticActivationProfileValidation.Invalid(
                    Gs1DiagnosticActivationProfileError.UNSUPPORTED_FAMILY,
                )
            }
            if (!BLUETOOTH_ADDRESS.matches(bluetoothAddress)) {
                return Gs1DiagnosticActivationProfileValidation.Invalid(
                    Gs1DiagnosticActivationProfileError.INVALID_BLUETOOTH_ADDRESS,
                )
            }
            if (transportVariant !in VERIFIED_TRANSPORT_VARIANTS) {
                return Gs1DiagnosticActivationProfileValidation.Invalid(
                    Gs1DiagnosticActivationProfileError.UNVERIFIED_TRANSPORT_VARIANT,
                )
            }
            if (packageCode.length != PACKAGE_CODE_CHARS ||
                !packageCode.all(Char::isAsciiLetterOrDigit)
            ) {
                return Gs1DiagnosticActivationProfileValidation.Invalid(
                    Gs1DiagnosticActivationProfileError.INVALID_PACKAGE_CODE,
                )
            }
            return Gs1DiagnosticActivationProfileValidation.Valid(
                Gs1DiagnosticActivationProfile(
                    sensorId = sensorId,
                    family = family,
                    bluetoothAddress = bluetoothAddress.uppercase(),
                    transportVariant = transportVariant,
                    packageCode = packageCode,
                ),
            )
        }

        private const val MAX_SENSOR_ID_CHARS = 128
        private const val PACKAGE_CODE_CHARS = 8
        private val VERIFIED_TRANSPORT_VARIANTS = setOf(0)
        private val BLUETOOTH_ADDRESS = Regex("^(?:[0-9A-Fa-f]{2}:){5}[0-9A-Fa-f]{2}$")
    }
}

private fun Char.isAsciiLetterOrDigit(): Boolean =
    this in '0'..'9' || this in 'A'..'Z' || this in 'a'..'z'
