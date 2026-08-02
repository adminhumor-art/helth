package com.sladkaya.sensor.sibionics

import com.sladkaya.core.model.ReadingQuality
import com.sladkaya.core.model.SensorFamily
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class Gs1DiagnosticCommitPolicyTest {
    @Test
    fun degradedHistoryIsTransportProgressButNotFreshMedicalData() {
        val assessment = Gs1DiagnosticCommitPolicy.assess(
            listOf(diagnostic(7, ReadingQuality.DEGRADED)),
        )

        assertTrue(assessment.hasTransportProgress)
        assertFalse(assessment.hasFreshDiagnostic)
        assertEquals(ReadingQuality.DEGRADED, assessment.latest?.quality)
    }

    @Test
    fun warmupIsNotReportedAsFreshMedicalData() {
        val assessment = Gs1DiagnosticCommitPolicy.assess(
            listOf(diagnostic(8, ReadingQuality.WARMING_UP)),
        )

        assertTrue(assessment.hasTransportProgress)
        assertFalse(assessment.hasFreshDiagnostic)
    }

    @Test
    fun onlyLatestValidDiagnosticMarksTheStreamFresh() {
        val fresh = Gs1DiagnosticCommitPolicy.assess(
            listOf(
                diagnostic(9, ReadingQuality.DEGRADED),
                diagnostic(10, ReadingQuality.VALID),
            ),
        )
        val latestHistory = Gs1DiagnosticCommitPolicy.assess(
            listOf(
                diagnostic(10, ReadingQuality.VALID),
                diagnostic(11, ReadingQuality.DEGRADED),
            ),
        )

        assertTrue(fresh.hasFreshDiagnostic)
        assertEquals(10L, fresh.latest?.sequence)
        assertFalse(latestHistory.hasFreshDiagnostic)
        assertEquals(11L, latestHistory.latest?.sequence)
    }

    @Test
    fun emptyCommitCannotLookFresh() {
        val assessment = Gs1DiagnosticCommitPolicy.assess(emptyList())

        assertFalse(assessment.hasTransportProgress)
        assertFalse(assessment.hasFreshDiagnostic)
        assertNull(assessment.latest)
    }

    @Test
    fun emptyCommitCannotEndHandshakeOrArmAFalseStreamWatchdog() {
        val plan = Gs1DiagnosticCommitProgressPolicy.plan(
            alreadyStreaming = false,
            assessment = Gs1DiagnosticCommitPolicy.assess(emptyList()),
        )

        assertFalse(plan.markStreaming)
        assertFalse(plan.armSilenceWatchdog)
    }

    @Test
    fun validatedEmptyTransportEnvelopeEndsHandshakeWithoutLookingMedicallyFresh() {
        val assessment = Gs1DiagnosticCommitPolicy.assess(
            diagnostics = emptyList(),
            validatedTransportEnvelope = true,
        )
        val plan = Gs1DiagnosticCommitProgressPolicy.plan(
            alreadyStreaming = false,
            assessment = assessment,
        )

        assertTrue(assessment.hasTransportProgress)
        assertFalse(assessment.hasFreshDiagnostic)
        assertTrue(plan.markStreaming)
        assertTrue(plan.armSilenceWatchdog)
    }

    @Test
    fun durableProgressEndsHandshakeAndEveryLaterProgressRefreshesTheWatchdog() {
        val assessment = Gs1DiagnosticCommitPolicy.assess(
            diagnostics = emptyList(),
            committedSampleCount = 1,
        )

        assertEquals(
            Gs1DiagnosticCommitProgressPlan(
                markStreaming = true,
                armSilenceWatchdog = true,
            ),
            Gs1DiagnosticCommitProgressPolicy.plan(
                alreadyStreaming = false,
                assessment = assessment,
            ),
        )
        assertEquals(
            Gs1DiagnosticCommitProgressPlan(
                markStreaming = false,
                armSilenceWatchdog = true,
            ),
            Gs1DiagnosticCommitProgressPolicy.plan(
                alreadyStreaming = true,
                assessment = assessment,
            ),
        )
    }

    private fun diagnostic(sequence: Long, quality: ReadingQuality) = Gs1DiagnosticReading(
        eventId = "event-$sequence",
        sensorId = "sensor-a",
        sensorFamily = SensorFamily.SIBIONICS_GS1,
        sensorTimeEpochMs = 1_700_000_000_000L + sequence,
        phoneTimeEpochMs = 1_700_000_000_000L + sequence,
        glucoseMgDl = 100,
        trendMgDlPerMinute = 0.0,
        quality = quality,
        sequence = sequence,
    )
}
