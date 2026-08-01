package com.sladkaya.sensor.sibionics

import com.sladkaya.core.model.ReadingQuality

internal data class Gs1DiagnosticCommitAssessment(
    val latest: Gs1DiagnosticReading?,
    val hasTransportProgress: Boolean,
    val hasFreshDiagnostic: Boolean,
)

/** Separates durable transport progress from medically fresh diagnostic data. */
internal object Gs1DiagnosticCommitPolicy {
    fun assess(
        diagnostics: List<Gs1DiagnosticReading>,
        committedSampleCount: Int = 0,
        issueCount: Int = 0,
    ): Gs1DiagnosticCommitAssessment {
        require(committedSampleCount >= 0)
        require(issueCount >= 0)
        val latest = diagnostics.lastOrNull()
        return Gs1DiagnosticCommitAssessment(
            latest = latest,
            hasTransportProgress = diagnostics.isNotEmpty() ||
                committedSampleCount > 0 || issueCount > 0,
            hasFreshDiagnostic = latest?.quality == ReadingQuality.VALID,
        )
    }
}
