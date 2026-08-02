package com.sladkaya.app.service

import android.content.Context

internal sealed interface AlarmEpisodeLoadResult {
    data object Empty : AlarmEpisodeLoadResult
    data class Active(val episode: AlarmEpisode) : AlarmEpisodeLoadResult
    data object Corrupt : AlarmEpisodeLoadResult
}

internal sealed interface AlarmEpisodeStoreAcknowledgement {
    data class Accepted(val episode: AlarmEpisode) : AlarmEpisodeStoreAcknowledgement
    data object Stale : AlarmEpisodeStoreAcknowledgement
    data object Missing : AlarmEpisodeStoreAcknowledgement
    data object Corrupt : AlarmEpisodeStoreAcknowledgement
    data object PersistenceFailed : AlarmEpisodeStoreAcknowledgement
}

internal class AlarmEpisodeMutationGate {
    private val monitor = Any()

    fun <T> runAtomically(block: () -> T): T = synchronized(monitor) { block() }
}

internal class AlarmEpisodePreferenceStore(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences(
        PREFERENCES,
        Context.MODE_PRIVATE,
    )
    private val codec = AlarmEpisodeCodec()

    fun <T> atomically(block: AlarmEpisodePreferenceStore.() -> T): T =
        MUTATION_GATE.runAtomically { block() }

    fun load(): AlarmEpisodeLoadResult = MUTATION_GATE.runAtomically {
        loadLocked()
    }

    fun save(episode: AlarmEpisode): Boolean = MUTATION_GATE.runAtomically {
        saveLocked(episode)
    }

    fun clear(): Boolean = MUTATION_GATE.runAtomically {
        runCatching { preferences.edit().remove(KEY).commit() }.getOrDefault(false)
    }

    fun acknowledge(episodeId: String): AlarmEpisodeStoreAcknowledgement =
        MUTATION_GATE.runAtomically {
        when (val loaded = loadLocked()) {
            AlarmEpisodeLoadResult.Empty -> AlarmEpisodeStoreAcknowledgement.Missing
            AlarmEpisodeLoadResult.Corrupt -> AlarmEpisodeStoreAcknowledgement.Corrupt
            is AlarmEpisodeLoadResult.Active -> when (
                val result = AlarmEpisodePolicy.acknowledge(loaded.episode, episodeId)
            ) {
                AlarmEpisodeAcknowledgement.Missing -> AlarmEpisodeStoreAcknowledgement.Missing
                AlarmEpisodeAcknowledgement.Stale -> AlarmEpisodeStoreAcknowledgement.Stale
                is AlarmEpisodeAcknowledgement.Accepted -> {
                    if (saveLocked(result.episode)) {
                        AlarmEpisodeStoreAcknowledgement.Accepted(result.episode)
                    } else {
                        AlarmEpisodeStoreAcknowledgement.PersistenceFailed
                    }
                }
            }
        }
    }

    private fun loadLocked(): AlarmEpisodeLoadResult {
        val encoded = try {
            preferences.getString(KEY, null)
        } catch (_: RuntimeException) {
            return AlarmEpisodeLoadResult.Corrupt
        } ?: return if (preferences.contains(KEY)) {
            AlarmEpisodeLoadResult.Corrupt
        } else {
            AlarmEpisodeLoadResult.Empty
        }
        return when (val decoded = codec.decode(encoded)) {
            AlarmEpisodeDecodeResult.Failure -> AlarmEpisodeLoadResult.Corrupt
            is AlarmEpisodeDecodeResult.Success -> AlarmEpisodeLoadResult.Active(decoded.episode)
        }
    }

    private fun saveLocked(episode: AlarmEpisode): Boolean = runCatching {
        preferences.edit().putString(KEY, codec.encode(episode)).commit()
    }.getOrDefault(false)

    internal companion object {
        const val PREFERENCES = "active_alarm_episode"
        const val KEY = "episode_v1"
        private val MUTATION_GATE = AlarmEpisodeMutationGate()
    }
}
