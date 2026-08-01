package com.sladkaya.app.service

import com.sladkaya.core.model.AlarmKind

internal class AlarmReminderPolicy(
    private val repeatIntervalMs: Long = DEFAULT_REPEAT_INTERVAL_MS,
) {
    private val lastSentAt = mutableMapOf<AlarmKind, Long>()
    private val acknowledged = mutableSetOf<AlarmKind>()

    init {
        require(repeatIntervalMs > 0L)
    }

    fun onOpened(kinds: Set<AlarmKind>, nowElapsedMs: Long) {
        kinds.forEach { kind ->
            acknowledged -= kind
            lastSentAt[kind] = nowElapsedMs
        }
    }

    fun onClosed(kinds: Set<AlarmKind>) {
        kinds.forEach { kind ->
            acknowledged -= kind
            lastSentAt -= kind
        }
    }

    fun acknowledge(kinds: Set<AlarmKind>) {
        acknowledged += kinds
    }

    fun due(active: Set<AlarmKind>, nowElapsedMs: Long): Set<AlarmKind> {
        val inactive = lastSentAt.keys - active
        onClosed(inactive)
        return active.filterTo(linkedSetOf()) { kind ->
            val lastSent = lastSentAt[kind]
            val elapsed = lastSent?.let { nowElapsedMs - it }
            kind !in acknowledged && (elapsed == null || elapsed < 0L || elapsed >= repeatIntervalMs)
        }
    }

    fun markSent(kinds: Set<AlarmKind>, nowElapsedMs: Long) {
        kinds.forEach { lastSentAt[it] = nowElapsedMs }
    }

    private companion object {
        const val DEFAULT_REPEAT_INTERVAL_MS = 2 * 60_000L
    }
}
