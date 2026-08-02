package com.sladkaya.app.service

import com.sladkaya.core.model.AlarmKind
import java.security.MessageDigest

internal data class AlarmReadingSnapshot(
    val glucoseMgDl: Int,
    val sensorTimeEpochMs: Long,
    val phoneTimeEpochMs: Long,
) {
    init {
        require(glucoseMgDl in 20..600)
        require(sensorTimeEpochMs > 0L)
        require(phoneTimeEpochMs > 0L)
    }
}

internal data class AlarmEpisode(
    val id: String,
    val activeKinds: Set<AlarmKind>,
    val acknowledged: Boolean,
    val openedAtEpochMs: Long,
    val lastAlertAtEpochMs: Long,
    val demo: Boolean,
    val reading: AlarmReadingSnapshot?,
) {
    init {
        require(EPISODE_ID.matches(id))
        require(activeKinds.isNotEmpty())
        require(openedAtEpochMs > 0L)
        require(lastAlertAtEpochMs > 0L)
    }

    private companion object {
        val EPISODE_ID = Regex("^[A-Za-z0-9_-]{16,128}$")
    }
}

internal data class AlarmEpisodeTransition(
    val episode: AlarmEpisode?,
    val alertNow: Boolean,
    val cancelNotification: Boolean,
    val rescheduleRepeat: Boolean,
)

internal sealed interface AlarmEpisodeAcknowledgement {
    data class Accepted(val episode: AlarmEpisode) : AlarmEpisodeAcknowledgement
    data object Stale : AlarmEpisodeAcknowledgement
    data object Missing : AlarmEpisodeAcknowledgement
}

internal object AlarmEpisodePolicy {
    fun transition(
        previous: AlarmEpisode?,
        activeKinds: Set<AlarmKind>,
        newlyOpenedKinds: Set<AlarmKind>,
        nowEpochMs: Long,
        snapshot: AlarmReadingSnapshot?,
        demo: Boolean,
        nextEpisodeId: String,
    ): AlarmEpisodeTransition {
        require(nowEpochMs > 0L)
        require(newlyOpenedKinds.all(activeKinds::contains))
        if (activeKinds.isEmpty()) {
            return AlarmEpisodeTransition(
                episode = null,
                alertNow = false,
                cancelNotification = previous != null,
                rescheduleRepeat = false,
            )
        }
        val startsNewEpisode = previous == null || previous.demo != demo ||
            newlyOpenedKinds.isNotEmpty()
        val episode = if (startsNewEpisode) {
            AlarmEpisode(
                id = nextEpisodeId,
                activeKinds = activeKinds.toSet(),
                acknowledged = false,
                openedAtEpochMs = nowEpochMs,
                lastAlertAtEpochMs = nowEpochMs,
                demo = demo,
                reading = snapshot,
            )
        } else {
            previous.copy(
                activeKinds = activeKinds.toSet(),
                reading = snapshot ?: previous.reading,
            )
        }
        return AlarmEpisodeTransition(
            episode = episode,
            alertNow = startsNewEpisode,
            cancelNotification = false,
            rescheduleRepeat = startsNewEpisode,
        )
    }

    fun acknowledge(
        current: AlarmEpisode?,
        requestedEpisodeId: String,
    ): AlarmEpisodeAcknowledgement = when {
        current == null -> AlarmEpisodeAcknowledgement.Missing
        current.id != requestedEpisodeId -> AlarmEpisodeAcknowledgement.Stale
        else -> AlarmEpisodeAcknowledgement.Accepted(current.copy(acknowledged = true))
    }

    fun repeatDue(episode: AlarmEpisode, nowEpochMs: Long, repeatIntervalMs: Long): Boolean {
        require(nowEpochMs > 0L)
        require(repeatIntervalMs > 0L)
        if (episode.acknowledged) return false
        val elapsed = nowEpochMs - episode.lastAlertAtEpochMs
        return elapsed < 0L || elapsed >= repeatIntervalMs
    }

    fun markAlerted(episode: AlarmEpisode, nowEpochMs: Long): AlarmEpisode {
        require(nowEpochMs > 0L)
        return episode.copy(lastAlertAtEpochMs = nowEpochMs)
    }

    fun markDeliveryPending(
        episode: AlarmEpisode,
        nowEpochMs: Long,
        repeatIntervalMs: Long,
    ): AlarmEpisode {
        require(nowEpochMs > 0L)
        require(repeatIntervalMs > 0L)
        return episode.copy(
            lastAlertAtEpochMs = maxOf(1L, nowEpochMs - repeatIntervalMs),
        )
    }
}

internal sealed interface AlarmEpisodeDecodeResult {
    data class Success(val episode: AlarmEpisode) : AlarmEpisodeDecodeResult
    data object Failure : AlarmEpisodeDecodeResult
}

internal class AlarmEpisodeCodec {
    fun encode(episode: AlarmEpisode): String {
        val reading = episode.reading
        val body = listOf(
            SCHEMA,
            episode.id,
            episode.activeKinds.sortedBy(AlarmKind::ordinal).joinToString(",", transform = AlarmKind::name),
            if (episode.acknowledged) "1" else "0",
            episode.openedAtEpochMs.toString(),
            episode.lastAlertAtEpochMs.toString(),
            if (episode.demo) "1" else "0",
            reading?.glucoseMgDl?.toString() ?: NULL,
            reading?.sensorTimeEpochMs?.toString() ?: NULL,
            reading?.phoneTimeEpochMs?.toString() ?: NULL,
        ).joinToString(SEPARATOR)
        return "$body$SEPARATOR${body.sha256()}"
    }

    fun decode(encoded: String): AlarmEpisodeDecodeResult = runCatching {
        val fields = encoded.split(SEPARATOR)
        require(fields.size == FIELD_COUNT)
        val body = fields.dropLast(1).joinToString(SEPARATOR)
        require(MessageDigest.isEqual(body.sha256().toByteArray(), fields.last().toByteArray()))
        require(fields[0] == SCHEMA)
        val activeKinds = fields[2].split(',')
            .filter(String::isNotBlank)
            .map(AlarmKind::valueOf)
            .toSet()
        require(activeKinds.isNotEmpty())
        val readingFields = fields.subList(7, 10)
        val reading = when {
            readingFields.all { it == NULL } -> null
            readingFields.any { it == NULL } -> error("partial reading")
            else -> AlarmReadingSnapshot(
                glucoseMgDl = fields[7].toInt(),
                sensorTimeEpochMs = fields[8].toLong(),
                phoneTimeEpochMs = fields[9].toLong(),
            )
        }
        AlarmEpisode(
            id = fields[1],
            activeKinds = activeKinds,
            acknowledged = fields[3].strictBoolean(),
            openedAtEpochMs = fields[4].toLong(),
            lastAlertAtEpochMs = fields[5].toLong(),
            demo = fields[6].strictBoolean(),
            reading = reading,
        )
    }.fold(
        onSuccess = AlarmEpisodeDecodeResult::Success,
        onFailure = { AlarmEpisodeDecodeResult.Failure },
    )

    private fun String.strictBoolean(): Boolean = when (this) {
        "0" -> false
        "1" -> true
        else -> error("invalid boolean")
    }

    private fun String.sha256(): String = MessageDigest.getInstance("SHA-256")
        .digest(toByteArray(Charsets.UTF_8))
        .joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }

    private companion object {
        const val SCHEMA = "alarm-episode-v1"
        const val SEPARATOR = "|"
        const val NULL = "-"
        const val FIELD_COUNT = 11
    }
}
