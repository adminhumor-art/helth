package com.sladkaya.app.sync

import androidx.work.ExistingWorkPolicy
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.OneTimeWorkRequest
import androidx.work.PeriodicWorkRequest
import java.time.Duration
import org.junit.Assert.assertEquals
import org.junit.Test

class RemoteUploadWorkPolicyTest {
    @Test
    fun workIsUniqueConnectedAndContainsNoMedicalPayload() {
        assertEquals("sladkaya.remote-upload.drain", RemoteUploadWorkPlan.ONE_TIME_UNIQUE_NAME)
        assertEquals("sladkaya.remote-upload.reconcile", RemoteUploadWorkPlan.PERIODIC_UNIQUE_NAME)
        assertEquals(Duration.ofMinutes(15), RemoteUploadWorkPlan.RECONCILIATION_INTERVAL)
        assertEquals(Duration.ofSeconds(30), RemoteUploadWorkPlan.INITIAL_BACKOFF)
        assertEquals(emptyMap<String, Any>(), RemoteUploadWorkPlan.inputData)
    }

    @Test
    fun aDrainRequestedWhileTheUniqueChainIsRunningIsAppendedInsteadOfDiscarded() {
        val gateway = RecordingGateway()
        val scheduler = WorkManagerRemoteUploadScheduler(gateway)

        scheduler.requestDrain()
        scheduler.requestDrain()

        assertEquals(
            listOf(
                ExistingWorkPolicy.APPEND_OR_REPLACE,
                ExistingWorkPolicy.APPEND_OR_REPLACE,
            ),
            gateway.policies,
        )
    }

    private class RecordingGateway : RemoteUploadWorkGateway {
        val policies = mutableListOf<ExistingWorkPolicy>()

        override fun enqueueOneTime(
            name: String,
            policy: ExistingWorkPolicy,
            request: OneTimeWorkRequest,
        ) {
            assertEquals(RemoteUploadWorkPlan.ONE_TIME_UNIQUE_NAME, name)
            policies += policy
        }

        override fun enqueuePeriodic(
            name: String,
            policy: ExistingPeriodicWorkPolicy,
            request: PeriodicWorkRequest,
        ) = error("Not used")
    }
}
