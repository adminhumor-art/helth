package com.sladkaya.app.service

import com.sladkaya.core.model.SensorFamily
import com.sladkaya.sensor.sibionics.Gs1MarketProfile
import com.sladkaya.sensor.sibionics.Gs1PendingDiagnosticProfile
import java.nio.charset.StandardCharsets
import java.security.MessageDigest

/** One-way identity binding for a user's explicit diagnostic-resume intent. */
internal object DiagnosticResumeIdentity {
    fun fingerprint(profile: Gs1PendingDiagnosticProfile): String = fingerprint(
        sensorId = profile.sensorId,
        family = profile.family,
        marketProfile = profile.marketProfile,
        bluetoothAddress = profile.canonicalBluetoothAddress,
        transportVariant = profile.transportVariant,
        packageCode = profile.packageCode,
    )

    fun fingerprint(
        sensorId: String,
        family: SensorFamily,
        marketProfile: Gs1MarketProfile,
        bluetoothAddress: String,
        transportVariant: Int,
        packageCode: String,
    ): String {
        val canonical = listOf(
            sensorId,
            family.name,
            marketProfile.name,
            bluetoothAddress.uppercase(),
            transportVariant.toString(),
            packageCode,
        ).joinToString(FIELD_SEPARATOR)
        return MessageDigest.getInstance("SHA-256")
            .digest(canonical.toByteArray(StandardCharsets.UTF_8))
            .joinToString(separator = "") { byte ->
                (byte.toInt() and 0xff).toString(16).padStart(2, '0')
            }
    }

    private const val FIELD_SEPARATOR = "\u001f"
}
