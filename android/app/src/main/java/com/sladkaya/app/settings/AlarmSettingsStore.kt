package com.sladkaya.app.settings

import android.content.Context
import com.sladkaya.core.model.AlarmThresholds

internal data class LoadedAlarmSettings(
    val thresholds: AlarmThresholds,
    val recoveredFromCorruption: Boolean,
    val decodeError: AlarmSettingsDecodeError? = null,
)

internal class AlarmSettingsStore(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences(
        PREFERENCES,
        Context.MODE_PRIVATE,
    )
    private val codec = AlarmSettingsCodec()

    fun load(): LoadedAlarmSettings {
        val encoded = try {
            preferences.getString(KEY, null)
        } catch (_: ClassCastException) {
            return recovered(AlarmSettingsDecodeError.MALFORMED_ENCODING)
        } ?: return LoadedAlarmSettings(
            thresholds = AlarmThresholds(),
            recoveredFromCorruption = false,
        )
        return when (val decoded = codec.decode(encoded)) {
            is AlarmSettingsDecodeResult.Success -> LoadedAlarmSettings(
                thresholds = decoded.thresholds,
                recoveredFromCorruption = false,
            )
            is AlarmSettingsDecodeResult.Failure -> recovered(decoded.error)
        }
    }

    fun save(thresholds: AlarmThresholds): Boolean =
        preferences.edit().putString(KEY, codec.encode(thresholds)).commit()

    private fun recovered(error: AlarmSettingsDecodeError) = LoadedAlarmSettings(
        thresholds = AlarmThresholds(),
        recoveredFromCorruption = true,
        decodeError = error,
    )

    internal companion object {
        const val PREFERENCES = "alarm_settings"
        const val KEY = "thresholds_v1"
    }
}
