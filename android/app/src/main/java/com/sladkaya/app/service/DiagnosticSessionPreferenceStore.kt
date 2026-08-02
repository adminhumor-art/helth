package com.sladkaya.app.service

import android.annotation.SuppressLint
import android.content.Context

/** Persists the user's request bound to one exact quarantined diagnostic profile. */
internal class DiagnosticSessionPreferenceStore(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences(
        PREFERENCES,
        Context.MODE_PRIVATE,
    )

    fun matches(profileFingerprint: String): Boolean = runCatching {
        profileFingerprint.isValidFingerprint() &&
            preferences.getString(KEY_PROFILE_FINGERPRINT, null) == profileFingerprint
    }.getOrDefault(false)

    @SuppressLint("UseKtx")
    fun markRunning(profileFingerprint: String): Boolean = runCatching {
        check(profileFingerprint.isValidFingerprint())
        preferences.edit()
            .clear()
            .putString(KEY_PROFILE_FINGERPRINT, profileFingerprint)
            .commit()
    }.getOrDefault(false)

    @SuppressLint("UseKtx")
    fun clear(): Boolean = runCatching {
        preferences.edit().clear().commit()
    }.getOrDefault(false)

    private companion object {
        const val PREFERENCES = "diagnostic_session_preference"
        const val KEY_PROFILE_FINGERPRINT = "profile_fingerprint"
    }
}

private fun String.isValidFingerprint(): Boolean =
    length == 64 && all { character -> character in '0'..'9' || character in 'a'..'f' }
