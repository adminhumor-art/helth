package com.sladkaya.sensor.sibionics

import com.sladkaya.core.model.ReadingQuality

internal data class Gs1DiagnosticCommitAssessment(
    val latest: Gs1DiagnosticReading?,
    val hasTransportProgress: Boolean,
    val hasFreshDiagnostic: Boolean,
)

internal data class Gs1DiagnosticCommitProgressPlan(
    val markStreaming: Boolean,
    val armSilenceWatchdog: Boolean,
)

internal object Gs1DiagnosticCommitProgressPolicy {
    fun plan(
        alreadyStreaming: Boolean,
        assessment: Gs1DiagnosticCommitAssessment,
    ): Gs1DiagnosticCommitProgressPlan = Gs1DiagnosticCommitProgressPlan(
        markStreaming = assessment.hasTransportProgress && !alreadyStreaming,
        armSilenceWatchdog = assessment.hasTransportProgress,
    )
}

/** Separates durable transport progress from medically fresh diagnostic data. */
internal object Gs1DiagnosticCommitPolicy {
    fun assess(
        diagnostics: List<Gs1DiagnosticReading>,
        committedSampleCount: Int = 0,
        issueCount: Int = 0,
        validatedTransportEnvelope: Boolean = false,
    ): Gs1DiagnosticCommitAssessment {
        require(committedSampleCount >= 0)
        require(issueCount >= 0)
        val latest = diagnostics.lastOrNull()
        return Gs1DiagnosticCommitAssessment(
            latest = latest,
            hasTransportProgress = validatedTransportEnvelope || diagnostics.isNotEmpty() ||
                committedSampleCount > 0 || issueCount > 0,
            hasFreshDiagnostic = latest?.quality == ReadingQuality.VALID,
        )
    }
}
