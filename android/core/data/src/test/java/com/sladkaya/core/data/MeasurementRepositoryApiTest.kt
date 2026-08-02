package com.sladkaya.core.data

import org.junit.Assert.assertFalse
import org.junit.Test

class MeasurementRepositoryApiTest {
    @Test
    fun publicRepositoryHasNoIndependentMeasurementOrUploadWriter() {
        val publicMethods = MeasurementRepository::class.java.methods.map { it.name }.toSet()

        listOf(
            "enqueue",
            "pending",
            "markUploaded",
            "markAttemptFailed",
            "discardSimulation",
        ).forEach { forbidden ->
            assertFalse("unexpected product persistence bypass: $forbidden", forbidden in publicMethods)
        }
    }
}
