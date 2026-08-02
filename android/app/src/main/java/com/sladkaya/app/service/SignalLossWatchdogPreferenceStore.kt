package com.sladkaya.app.service

import android.content.Context

internal sealed interface SignalLossWatchdogLoadResult {
    data object Empty : SignalLossWatchdogLoadResult
    data class Active(val state: SignalLossWatchdogState) : SignalLossWatchdogLoadResult
    data object Corrupt : SignalLossWatchdogLoadResult
}

internal class SignalLossWatchdogPreferenceStore(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences(
        PREFERENCES,
        Context.MODE_PRIVATE,
    )
    private val codec = SignalLossWatchdogCodec()

    fun load(): SignalLossWatchdogLoadResult = MUTATION_GATE.runAtomically {
        val encoded = try {
            preferences.getString(KEY, null)
        } catch (_: RuntimeException) {
            return@runAtomically SignalLossWatchdogLoadResult.Corrupt
        } ?: return@runAtomically if (preferences.contains(KEY)) {
            SignalLossWatchdogLoadResult.Corrupt
        } else {
            SignalLossWatchdogLoadResult.Empty
        }
        when (val decoded = codec.decode(encoded)) {
            SignalLossWatchdogDecodeResult.Failure -> SignalLossWatchdogLoadResult.Corrupt
            is SignalLossWatchdogDecodeResult.Success -> {
                SignalLossWatchdogLoadResult.Active(decoded.state)
            }
        }
    }

    fun save(state: SignalLossWatchdogState): Boolean = MUTATION_GATE.runAtomically {
        runCatching {
            preferences.edit().putString(KEY, codec.encode(state)).commit()
        }.getOrDefault(false)
    }

    fun clear(): Boolean = MUTATION_GATE.runAtomically {
        runCatching { preferences.edit().remove(KEY).commit() }.getOrDefault(false)
    }

    internal companion object {
        const val PREFERENCES = "signal_loss_watchdog"
        const val KEY = "state_v1"
        private val MUTATION_GATE = AlarmEpisodeMutationGate()
    }
}
