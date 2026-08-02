package com.sladkaya.app.service

import com.sladkaya.core.model.GlucoseReading
import com.sladkaya.core.model.ReadingQuality
import java.security.MessageDigest

internal data class SignalLossWatchdogState(
    val generation: Long,
    val readingIdentity: String,
    val sensorTimeEpochMs: Long,
    val phoneTimeEpochMs: Long,
    val staleAfterMs: Long,
    val demo: Boolean,
) {
    init {
        require(generation > 0L)
        require(IDENTITY.matches(readingIdentity))
        require(sensorTimeEpochMs > 0L)
        require(phoneTimeEpochMs > 0L)
        require(staleAfterMs > 0L)
    }

    val deadlineEpochMs: Long
        get() = safeAdd(minOf(sensorTimeEpochMs, phoneTimeEpochMs), staleAfterMs)

    private companion object {
        val IDENTITY = Regex("^[0-9a-f]{64}$")
    }
}

internal enum class SignalLossWatchdogDecision {
    REARM_CURRENT,
    RESCHEDULE,
    OPEN_SIGNAL_LOSS,
    DISCARD_DEMO,
}

internal object SignalLossWatchdogPolicy {
    private const val MAX_SENSOR_FUTURE_SKEW_MS = 5 * 60_000L

    fun record(
        previous: SignalLossWatchdogState?,
        readingIdentity: String,
        sensorTimeEpochMs: Long,
        phoneTimeEpochMs: Long,
        staleAfterMs: Long,
        demo: Boolean,
    ): SignalLossWatchdogState {
        val candidate = SignalLossWatchdogState(
            generation = nextGeneration(previous?.generation),
            readingIdentity = readingIdentity,
            sensorTimeEpochMs = sensorTimeEpochMs,
            phoneTimeEpochMs = phoneTimeEpochMs,
            staleAfterMs = staleAfterMs,
            demo = demo,
        )
        if (previous == null || previous.demo != demo) return candidate
        if (
            previous.readingIdentity == readingIdentity &&
            previous.sensorTimeEpochMs == sensorTimeEpochMs &&
            previous.phoneTimeEpochMs == phoneTimeEpochMs &&
            previous.staleAfterMs == staleAfterMs
        ) {
            return previous
        }
        if (
            previous.readingIdentity == readingIdentity &&
            previous.sensorTimeEpochMs == sensorTimeEpochMs &&
            previous.phoneTimeEpochMs == phoneTimeEpochMs &&
            previous.staleAfterMs != staleAfterMs
        ) {
            return candidate
        }
        if (
            sensorTimeEpochMs <= previous.sensorTimeEpochMs ||
            phoneTimeEpochMs < previous.phoneTimeEpochMs
        ) {
            return previous
        }
        return candidate
    }

    fun decide(
        state: SignalLossWatchdogState,
        deliveredGeneration: Long,
        deliveredIdentity: String,
        nowEpochMs: Long,
        demoSessionLive: Boolean,
    ): SignalLossWatchdogDecision {
        require(nowEpochMs > 0L)
        if (state.demo && !demoSessionLive) {
            return SignalLossWatchdogDecision.DISCARD_DEMO
        }
        if (
            state.generation != deliveredGeneration ||
            state.readingIdentity != deliveredIdentity
        ) {
            return SignalLossWatchdogDecision.REARM_CURRENT
        }
        val clockMismatch = state.phoneTimeEpochMs > nowEpochMs ||
            state.sensorTimeEpochMs > safeAdd(nowEpochMs, MAX_SENSOR_FUTURE_SKEW_MS)
        return if (clockMismatch || nowEpochMs >= state.deadlineEpochMs) {
            SignalLossWatchdogDecision.OPEN_SIGNAL_LOSS
        } else {
            SignalLossWatchdogDecision.RESCHEDULE
        }
    }

    private fun nextGeneration(previous: Long?): Long = when (previous) {
        null -> 1L
        Long.MAX_VALUE -> 2L
        else -> previous + 1L
    }
}

internal object SignalLossWatchdogSlotPolicy {
    const val SLOT_COUNT = 2

    fun slotFor(generation: Long): Int {
        require(generation > 0L)
        return (generation and 1L).toInt()
    }
}

internal data class SignalLossSchedulePlan(
    val primaryKind: AlarmRepeatScheduleKind,
    val triggerAtEpochMs: Long,
    val revocationWatchdogAtEpochMs: Long?,
)

internal object SignalLossSchedulePolicy {
    fun plan(
        state: SignalLossWatchdogState,
        nowEpochMs: Long,
        exactAlarmAccess: Boolean,
    ): SignalLossSchedulePlan {
        require(nowEpochMs > 0L)
        val clockMismatch = state.phoneTimeEpochMs > nowEpochMs ||
            state.sensorTimeEpochMs > safeAdd(nowEpochMs, MAX_SENSOR_FUTURE_SKEW_MS)
        val trigger = if (clockMismatch || state.deadlineEpochMs <= nowEpochMs) {
            safeAdd(nowEpochMs, AlarmRepeatPlanPolicy.MIN_DELAY_MS)
        } else {
            state.deadlineEpochMs
        }
        return SignalLossSchedulePlan(
            primaryKind = if (exactAlarmAccess) {
                AlarmRepeatScheduleKind.EXACT_WAKEUP
            } else {
                AlarmRepeatScheduleKind.INEXACT_WAKEUP
            },
            triggerAtEpochMs = trigger,
            revocationWatchdogAtEpochMs = if (exactAlarmAccess) {
                safeAdd(trigger, AlarmRepeatPlanPolicy.REVOCATION_WATCHDOG_GRACE_MS)
            } else {
                null
            },
        )
    }

    private const val MAX_SENSOR_FUTURE_SKEW_MS = 5 * 60_000L
}

internal object SignalLossReadingIdentity {
    fun forReading(reading: GlucoseReading): String = listOf(
        reading.sensorId,
        reading.eventId,
        reading.sensorTimeEpochMs.toString(),
        reading.phoneTimeEpochMs.toString(),
        reading.sequence.toString(),
    ).joinToString("|").sha256()

    fun forMonitoringSession(sessionToken: String): String {
        require(sessionToken.isNotBlank())
        return "monitoring|$sessionToken".sha256()
    }
}

internal object SignalLossWatchdogEligibility {
    private const val MAX_SENSOR_FUTURE_SKEW_MS = 5 * 60_000L

    fun canRecord(
        reading: GlucoseReading,
        nowEpochMs: Long,
        staleAfterMs: Long,
    ): Boolean {
        require(nowEpochMs > 0L)
        require(staleAfterMs > 0L)
        if (reading.quality != ReadingQuality.VALID) return false
        if (
            reading.phoneTimeEpochMs > nowEpochMs ||
            reading.sensorTimeEpochMs > safeAdd(nowEpochMs, MAX_SENSOR_FUTURE_SKEW_MS)
        ) {
            return false
        }
        return nowEpochMs - reading.sensorTimeEpochMs < staleAfterMs &&
            nowEpochMs - reading.phoneTimeEpochMs < staleAfterMs
    }
}

internal sealed interface SignalLossWatchdogDecodeResult {
    data class Success(val state: SignalLossWatchdogState) : SignalLossWatchdogDecodeResult
    data object Failure : SignalLossWatchdogDecodeResult
}

internal class SignalLossWatchdogCodec {
    fun encode(state: SignalLossWatchdogState): String {
        val body = listOf(
            SCHEMA,
            state.generation.toString(),
            state.readingIdentity,
            state.sensorTimeEpochMs.toString(),
            state.phoneTimeEpochMs.toString(),
            state.staleAfterMs.toString(),
            if (state.demo) "1" else "0",
        ).joinToString(SEPARATOR)
        return "$body$SEPARATOR${body.sha256()}"
    }

    fun decode(encoded: String): SignalLossWatchdogDecodeResult = runCatching {
        val fields = encoded.split(SEPARATOR)
        require(fields.size == FIELD_COUNT)
        val body = fields.dropLast(1).joinToString(SEPARATOR)
        require(MessageDigest.isEqual(body.sha256().toByteArray(), fields.last().toByteArray()))
        require(fields[0] == SCHEMA)
        SignalLossWatchdogState(
            generation = fields[1].toLong(),
            readingIdentity = fields[2],
            sensorTimeEpochMs = fields[3].toLong(),
            phoneTimeEpochMs = fields[4].toLong(),
            staleAfterMs = fields[5].toLong(),
            demo = when (fields[6]) {
                "0" -> false
                "1" -> true
                else -> error("invalid boolean")
            },
        )
    }.fold(
        onSuccess = SignalLossWatchdogDecodeResult::Success,
        onFailure = { SignalLossWatchdogDecodeResult.Failure },
    )

    private companion object {
        const val SCHEMA = "signal-loss-watchdog-v1"
        const val SEPARATOR = "|"
        const val FIELD_COUNT = 8
    }
}

private fun safeAdd(value: Long, delta: Long): Long =
    if (value > Long.MAX_VALUE - delta) Long.MAX_VALUE else value + delta

private fun String.sha256(): String = MessageDigest.getInstance("SHA-256")
    .digest(toByteArray(Charsets.UTF_8))
    .joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }
