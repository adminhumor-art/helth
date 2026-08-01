package com.sladkaya.app.service

import android.content.Context

/**
 * Reads only the confirmation marker owned by the future onboarding flow.
 * This service never creates or guesses a sensor configuration itself.
 */
internal class ConfirmedSensorConfigurationStore(context: Context) {
    private val preferences = context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)

    fun hasConfirmedConfiguration(): Boolean =
        preferences.getInt(KEY_SCHEMA_VERSION, 0) == CURRENT_SCHEMA_VERSION &&
            preferences.getBoolean(KEY_CONFIRMED, false) &&
            !preferences.getString(KEY_SENSOR_ID, null).isNullOrBlank() &&
            BLUETOOTH_ADDRESS.matches(preferences.getString(KEY_BLUETOOTH_ADDRESS, "").orEmpty()) &&
            preferences.getString(KEY_PACKAGE_CODE, "").orEmpty().let { code ->
                code.length == PACKAGE_CODE_LENGTH && code.all(Char::isAsciiLetterOrDigit)
            } &&
            preferences.getInt(KEY_TRANSPORT_VARIANT, -1) == VERIFIED_TRANSPORT_VARIANT

    private companion object {
        const val PREFERENCES = "confirmed_sensor_configuration"
        const val KEY_SCHEMA_VERSION = "schema_version"
        const val KEY_CONFIRMED = "confirmed"
        const val KEY_SENSOR_ID = "sensor_id"
        const val KEY_BLUETOOTH_ADDRESS = "bluetooth_address"
        const val KEY_PACKAGE_CODE = "package_code"
        const val KEY_TRANSPORT_VARIANT = "transport_variant"
        const val CURRENT_SCHEMA_VERSION = 1
        const val PACKAGE_CODE_LENGTH = 8
        const val VERIFIED_TRANSPORT_VARIANT = 0
        val BLUETOOTH_ADDRESS = Regex("^(?:[0-9A-Fa-f]{2}:){5}[0-9A-Fa-f]{2}$")
    }
}

private fun Char.isAsciiLetterOrDigit(): Boolean =
    this in '0'..'9' || this in 'A'..'Z' || this in 'a'..'z'
