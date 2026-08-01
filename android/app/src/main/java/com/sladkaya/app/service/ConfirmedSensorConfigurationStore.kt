package com.sladkaya.app.service

import android.content.Context

/**
 * Reads only a marker provisioned after future physical validation.
 * Code/advertisement onboarding never writes this store, and this service
 * never creates or guesses a sensor configuration itself.
 */
internal class ConfirmedSensorConfigurationStore(context: Context) {
    private val preferences = context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)

    fun hasConfirmedConfiguration(): Boolean = try {
        ConfirmedSensorConfigurationPolicy.isConfirmed(preferences.all)
    } catch (_: RuntimeException) {
        false
    }

    private companion object {
        const val PREFERENCES = "confirmed_sensor_configuration"
    }
}

internal object ConfirmedSensorConfigurationPolicy {
    fun isConfirmed(values: Map<String, *>): Boolean {
        val sensorId = values[KEY_SENSOR_ID] as? String ?: return false
        val bluetoothAddress = values[KEY_BLUETOOTH_ADDRESS] as? String ?: return false
        val packageCode = values[KEY_PACKAGE_CODE] as? String ?: return false
        return values[KEY_SCHEMA_VERSION] as? Int == CURRENT_SCHEMA_VERSION &&
            values[KEY_CONFIRMED] as? Boolean == true &&
            sensorId.isNotBlank() &&
            BLUETOOTH_ADDRESS.matches(bluetoothAddress) &&
            packageCode.length == PACKAGE_CODE_LENGTH &&
            packageCode.all(Char::isAsciiLetterOrDigit) &&
            values[KEY_TRANSPORT_VARIANT] as? Int == VERIFIED_TRANSPORT_VARIANT
    }

    private const val KEY_SCHEMA_VERSION = "schema_version"
    private const val KEY_CONFIRMED = "confirmed"
    private const val KEY_SENSOR_ID = "sensor_id"
    private const val KEY_BLUETOOTH_ADDRESS = "bluetooth_address"
    private const val KEY_PACKAGE_CODE = "package_code"
    private const val KEY_TRANSPORT_VARIANT = "transport_variant"
    private const val CURRENT_SCHEMA_VERSION = 1
    private const val PACKAGE_CODE_LENGTH = 8
    private const val VERIFIED_TRANSPORT_VARIANT = 0
    private val BLUETOOTH_ADDRESS = Regex("^(?:[0-9A-Fa-f]{2}:){5}[0-9A-Fa-f]{2}$")
}

private fun Char.isAsciiLetterOrDigit(): Boolean =
    this in '0'..'9' || this in 'A'..'Z' || this in 'a'..'z'
