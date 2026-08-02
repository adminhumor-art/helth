package com.sladkaya.app.service

import com.sladkaya.core.model.GlucoseReading
import com.sladkaya.core.model.ReadingQuality
import com.sladkaya.core.model.SensorFamily
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class SignalLossWatchdogPolicyTest {
    @Test
    fun newerValidReadingAdvancesDurableGenerationButExactDuplicateDoesNot() {
        val first = SignalLossWatchdogPolicy.record(
            previous = null,
            readingIdentity = IDENTITY_ONE,
            sensorTimeEpochMs = NOW,
            phoneTimeEpochMs = NOW,
            staleAfterMs = STALE,
            demo = false,
        )
        val duplicate = SignalLossWatchdogPolicy.record(
            previous = first,
            readingIdentity = IDENTITY_ONE,
            sensorTimeEpochMs = NOW,
            phoneTimeEpochMs = NOW,
            staleAfterMs = STALE,
            demo = false,
        )
        val newer = SignalLossWatchdogPolicy.record(
            previous = first,
            readingIdentity = IDENTITY_TWO,
            sensorTimeEpochMs = NOW + 60_000L,
            phoneTimeEpochMs = NOW + 60_000L,
            staleAfterMs = STALE,
            demo = false,
        )

        assertEquals(first, duplicate)
        assertNotEquals(first.generation, newer.generation)
        assertEquals(NOW + STALE, first.deadlineEpochMs)
    }

    @Test
    fun delayedOrOutOfOrderReadingCannotExtendTheDeadline() {
        val current = state(generation = 8L, identity = IDENTITY_TWO)

        val delayed = SignalLossWatchdogPolicy.record(
            previous = current,
            readingIdentity = IDENTITY_ONE,
            sensorTimeEpochMs = NOW - 60_000L,
            phoneTimeEpochMs = NOW + 60_000L,
            staleAfterMs = STALE,
            demo = false,
        )

        assertEquals(current, delayed)
    }

    @Test
    fun changedStaleThresholdCreatesNewGenerationForTheSameReading() {
        val current = state(generation = 8L)

        val changed = SignalLossWatchdogPolicy.record(
            previous = current,
            readingIdentity = current.readingIdentity,
            sensorTimeEpochMs = current.sensorTimeEpochMs,
            phoneTimeEpochMs = current.phoneTimeEpochMs,
            staleAfterMs = STALE / 2,
            demo = current.demo,
        )

        assertEquals(9L, changed.generation)
        assertEquals(NOW + STALE / 2, changed.deadlineEpochMs)
    }

    @Test
    fun conflictingIdentityAtTheSameTimestampsCannotHideAsASettingsChange() {
        val current = state(generation = 8L, identity = IDENTITY_ONE)

        val conflict = SignalLossWatchdogPolicy.record(
            previous = current,
            readingIdentity = IDENTITY_TWO,
            sensorTimeEpochMs = current.sensorTimeEpochMs,
            phoneTimeEpochMs = current.phoneTimeEpochMs,
            staleAfterMs = STALE / 2,
            demo = current.demo,
        )

        assertEquals(current, conflict)
    }

    @Test
    fun staleQueuedIntentCannotOpenSignalLossAfterNewReading() {
        val current = state(generation = 2L, identity = IDENTITY_TWO)

        assertEquals(
            SignalLossWatchdogDecision.REARM_CURRENT,
            SignalLossWatchdogPolicy.decide(
                state = current,
                deliveredGeneration = 1L,
                deliveredIdentity = IDENTITY_ONE,
                nowEpochMs = NOW + STALE,
                demoSessionLive = true,
            ),
        )
    }

    @Test
    fun alternatingSlotsCoverBothWatchdogArmCrashBoundaries() {
        val old = state(generation = 7L, identity = IDENTITY_ONE)
        val newer = SignalLossWatchdogPolicy.record(
            previous = old,
            readingIdentity = IDENTITY_TWO,
            sensorTimeEpochMs = NOW + 60_000L,
            phoneTimeEpochMs = NOW + 60_000L,
            staleAfterMs = STALE,
            demo = false,
        )

        assertNotEquals(
            SignalLossWatchdogSlotPolicy.slotFor(old.generation),
            SignalLossWatchdogSlotPolicy.slotFor(newer.generation),
        )
        assertEquals(
            SignalLossWatchdogDecision.OPEN_SIGNAL_LOSS,
            SignalLossWatchdogPolicy.decide(
                state = old,
                deliveredGeneration = old.generation,
                deliveredIdentity = old.readingIdentity,
                nowEpochMs = old.deadlineEpochMs,
                demoSessionLive = true,
            ),
        )
        assertEquals(
            SignalLossWatchdogDecision.REARM_CURRENT,
            SignalLossWatchdogPolicy.decide(
                state = old,
                deliveredGeneration = newer.generation,
                deliveredIdentity = newer.readingIdentity,
                nowEpochMs = old.deadlineEpochMs,
                demoSessionLive = true,
            ),
        )
        assertEquals(
            SignalLossWatchdogDecision.REARM_CURRENT,
            SignalLossWatchdogPolicy.decide(
                state = newer,
                deliveredGeneration = old.generation,
                deliveredIdentity = old.readingIdentity,
                nowEpochMs = old.deadlineEpochMs,
                demoSessionLive = true,
            ),
        )
    }

    @Test
    fun generationRolloverStillMovesToTheOtherSlot() {
        val previous = state(generation = Long.MAX_VALUE)
        val next = SignalLossWatchdogPolicy.record(
            previous = previous,
            readingIdentity = IDENTITY_TWO,
            sensorTimeEpochMs = NOW + 60_000L,
            phoneTimeEpochMs = NOW + 60_000L,
            staleAfterMs = STALE,
            demo = false,
        )

        assertEquals(2L, next.generation)
        assertNotEquals(
            SignalLossWatchdogSlotPolicy.slotFor(previous.generation),
            SignalLossWatchdogSlotPolicy.slotFor(next.generation),
        )
    }

    @Test
    fun deadlineAndClockRollbackOpenSignalLossWhileEarlyDeliveryReschedules() {
        val state = state()

        assertEquals(
            SignalLossWatchdogDecision.RESCHEDULE,
            SignalLossWatchdogPolicy.decide(
                state,
                state.generation,
                state.readingIdentity,
                nowEpochMs = state.deadlineEpochMs - 1L,
                demoSessionLive = true,
            ),
        )
        assertEquals(
            SignalLossWatchdogDecision.OPEN_SIGNAL_LOSS,
            SignalLossWatchdogPolicy.decide(
                state,
                state.generation,
                state.readingIdentity,
                nowEpochMs = state.deadlineEpochMs,
                demoSessionLive = true,
            ),
        )
        assertEquals(
            SignalLossWatchdogDecision.OPEN_SIGNAL_LOSS,
            SignalLossWatchdogPolicy.decide(
                state,
                state.generation,
                state.readingIdentity,
                nowEpochMs = NOW - 1L,
                demoSessionLive = true,
            ),
        )
    }

    @Test
    fun deadDemoIsDiscardedButProductWatchdogSurvivesProcessDeath() {
        assertEquals(
            SignalLossWatchdogDecision.DISCARD_DEMO,
            SignalLossWatchdogPolicy.decide(
                state = state(demo = true),
                deliveredGeneration = 1L,
                deliveredIdentity = IDENTITY_ONE,
                nowEpochMs = NOW + STALE,
                demoSessionLive = false,
            ),
        )
        assertEquals(
            SignalLossWatchdogDecision.OPEN_SIGNAL_LOSS,
            SignalLossWatchdogPolicy.decide(
                state = state(demo = false),
                deliveredGeneration = 1L,
                deliveredIdentity = IDENTITY_ONE,
                nowEpochMs = NOW + STALE,
                demoSessionLive = false,
            ),
        )
        assertEquals(
            SignalLossWatchdogDecision.DISCARD_DEMO,
            SignalLossWatchdogPolicy.decide(
                state = state(generation = 2L, identity = IDENTITY_TWO, demo = true),
                deliveredGeneration = 1L,
                deliveredIdentity = IDENTITY_ONE,
                nowEpochMs = NOW + STALE,
                demoSessionLive = false,
            ),
        )
    }

    @Test
    fun exactDeadlineHasInexactRevocationWatchdog() {
        assertEquals(
            SignalLossSchedulePlan(
                primaryKind = AlarmRepeatScheduleKind.EXACT_WAKEUP,
                triggerAtEpochMs = NOW + STALE,
                revocationWatchdogAtEpochMs = NOW + STALE + 60_000L,
            ),
            SignalLossSchedulePolicy.plan(
                state = state(),
                nowEpochMs = NOW,
                exactAlarmAccess = true,
            ),
        )
    }

    @Test
    fun missingExactAccessStillSchedulesAnInexactWakeup() {
        assertEquals(
            SignalLossSchedulePlan(
                primaryKind = AlarmRepeatScheduleKind.INEXACT_WAKEUP,
                triggerAtEpochMs = NOW + STALE,
                revocationWatchdogAtEpochMs = null,
            ),
            SignalLossSchedulePolicy.plan(
                state = state(),
                nowEpochMs = NOW,
                exactAlarmAccess = false,
            ),
        )
    }

    @Test
    fun invalidPersistentIdentityIsRejected() {
        assertThrows(IllegalArgumentException::class.java) {
            state(identity = "sensor-visible-name")
        }
    }

    @Test
    fun onlyFreshValidReadingCanMoveTheWatchdog() {
        val fresh = reading(sensorTimeEpochMs = NOW, phoneTimeEpochMs = NOW)

        assertTrueEligible(fresh, NOW)
        assertEquals(
            false,
            SignalLossWatchdogEligibility.canRecord(
                fresh.copy(quality = ReadingQuality.DEGRADED),
                nowEpochMs = NOW,
                staleAfterMs = STALE,
            ),
        )
        assertEquals(
            false,
            SignalLossWatchdogEligibility.canRecord(
                fresh.copy(sensorTimeEpochMs = NOW - STALE),
                nowEpochMs = NOW,
                staleAfterMs = STALE,
            ),
        )
    }

    private fun assertTrueEligible(reading: GlucoseReading, nowEpochMs: Long) {
        assertEquals(
            true,
            SignalLossWatchdogEligibility.canRecord(reading, nowEpochMs, STALE),
        )
    }

    private fun reading(
        sensorTimeEpochMs: Long,
        phoneTimeEpochMs: Long,
    ) = GlucoseReading(
        eventId = "event-1",
        sensorId = "sensor-1",
        sensorFamily = SensorFamily.SIMULATOR,
        sensorTimeEpochMs = sensorTimeEpochMs,
        phoneTimeEpochMs = phoneTimeEpochMs,
        glucoseMgDl = 100,
        trendMgDlPerMinute = 0.0,
        quality = ReadingQuality.VALID,
        sequence = 1L,
    )

    private fun state(
        generation: Long = 1L,
        identity: String = IDENTITY_ONE,
        demo: Boolean = false,
    ) = SignalLossWatchdogState(
        generation = generation,
        readingIdentity = identity,
        sensorTimeEpochMs = NOW,
        phoneTimeEpochMs = NOW,
        staleAfterMs = STALE,
        demo = demo,
    )

    private companion object {
        const val NOW = 1_800_000_000_000L
        const val STALE = 600_000L
        val IDENTITY_ONE = "a".repeat(64)
        val IDENTITY_TWO = "b".repeat(64)
    }
}
