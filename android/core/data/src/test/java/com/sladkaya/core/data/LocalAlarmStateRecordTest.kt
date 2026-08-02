package com.sladkaya.core.data

import com.sladkaya.core.model.AlarmKind
import com.sladkaya.core.model.AlarmPolicyState
import com.sladkaya.core.model.AlarmThresholds
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class LocalAlarmStateRecordTest {
    @Test
    fun stateHashCoversPolicyThresholdEpisodeAndCursorFields() {
        val base = state().canonicalized()
        val variants = listOf(
            base.copy(policyState = base.policyState.copy(active = setOf(AlarmKind.HIGH))),
            base.copy(
                policyState = base.policyState.copy(
                    latestFreshSensorTimeEpochMs =
                        base.policyState.latestFreshSensorTimeEpochMs + 1L,
                ),
            ),
            base.copy(
                policyState = base.policyState.copy(
                    latestFreshPhoneTimeEpochMs =
                        base.policyState.latestFreshPhoneTimeEpochMs + 1L,
                ),
            ),
            base.copy(policyState = base.policyState.copy(phoneClockMovedBackwards = true)),
            base.copy(monitoringStartedAtEpochMs = base.monitoringStartedAtEpochMs + 1L),
            base.copy(lastEffectId = base.lastEffectId + 1L),
            base.copy(lastEventId = "event-other"),
            base.copy(lastSequence = base.lastSequence + 1L),
            base.copy(thresholds = AlarmThresholdSnapshot.from(AlarmThresholds(lowMgDl = 69))),
            base.copy(episodeGeneration = base.episodeGeneration + 1L),
            base.copy(
                episodeAcknowledged = true,
                episodeAcknowledgedAtEpochMs = base.updatedAtEpochMs + 1L,
            ),
        )

        variants.forEach { changed ->
            org.junit.Assert.assertNotEquals(base.stateSha256, changed.canonicalized().stateSha256)
        }
    }

    @Test
    fun activeEpisodeRequiresGenerationAndOpenTime() {
        assertThrows(IllegalArgumentException::class.java) {
            state().copy(episodeGeneration = 0L).canonicalized()
        }
        assertThrows(IllegalArgumentException::class.java) {
            state().copy(episodeOpenedAtEpochMs = null).canonicalized()
        }
        assertThrows(IllegalArgumentException::class.java) {
            state().copy(episodeAcknowledged = true).canonicalized()
        }
    }

    @Test
    fun canonicalStateRestoresCompleteAlarmPolicyState() {
        val state = state().canonicalized()

        assertEquals(setOf(AlarmKind.LOW), state.policyState.active)
        assertEquals(1_700_000_100_000L, state.policyState.latestFreshSensorTimeEpochMs)
        assertEquals(1_700_000_101_000L, state.policyState.latestFreshPhoneTimeEpochMs)
        assertEquals(false, state.policyState.phoneClockMovedBackwards)
    }

    private fun state() = LocalAlarmStateRecord(
        publicationBindingId = "cd".repeat(32),
        approvalId = "ab".repeat(32),
        monitoringStartedAtEpochMs = 1_700_000_000_000L,
        policyState = AlarmPolicyState(
            active = setOf(AlarmKind.LOW),
            latestFreshSensorTimeEpochMs = 1_700_000_100_000L,
            latestFreshPhoneTimeEpochMs = 1_700_000_101_000L,
            phoneClockMovedBackwards = false,
        ),
        lastEffectId = 7L,
        lastEventId = "event-7",
        lastSequence = 7L,
        thresholds = AlarmThresholdSnapshot.from(AlarmThresholds()),
        episodeGeneration = 1L,
        episodeAcknowledged = false,
        episodeAcknowledgedAtEpochMs = null,
        episodeOpenedAtEpochMs = 1_700_000_101_000L,
        updatedAtEpochMs = 1_700_000_101_000L,
        stateSha256 = "",
    )
}
