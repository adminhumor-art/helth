package com.sladkaya.app.sync

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.work.BackoffPolicy
import androidx.work.NetworkType
import androidx.work.ExistingWorkPolicy
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.OneTimeWorkRequest
import androidx.work.PeriodicWorkRequest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class RemoteUploadWorkRequestTest {
    @Test
    fun actualRequestsCarryOnlyExecutionPolicyAndNoMedicalData() {
        val oneTime = RemoteUploadWorkRequests.oneTime().workSpec
        val periodic = RemoteUploadWorkRequests.periodic().workSpec

        listOf(oneTime, periodic).forEach { spec ->
            assertEquals(NetworkType.CONNECTED, spec.constraints.requiredNetworkType)
            assertEquals(BackoffPolicy.EXPONENTIAL, spec.backoffPolicy)
            assertEquals(RemoteUploadWorkPlan.INITIAL_BACKOFF.toMillis(), spec.backoffDelayDuration)
            assertTrue(spec.input.keyValueMap.isEmpty())
        }
        assertEquals(RemoteUploadWorkPlan.RECONCILIATION_INTERVAL.toMillis(), periodic.intervalDuration)
    }

    @Test
    fun schedulerAppendsEveryDrainRequestAndKeepsOnlyOnePeriodicReconciler() {
        val gateway = RecordingGateway()
        val scheduler = WorkManagerRemoteUploadScheduler(gateway)

        scheduler.requestDrain()
        scheduler.requestDrain()
        scheduler.ensurePeriodicReconciliation()

        assertEquals(
            listOf(
                RemoteUploadWorkPlan.ONE_TIME_UNIQUE_NAME,
                RemoteUploadWorkPlan.ONE_TIME_UNIQUE_NAME,
            ),
            gateway.oneTimeNames,
        )
        assertEquals(
            listOf(
                ExistingWorkPolicy.APPEND_OR_REPLACE,
                ExistingWorkPolicy.APPEND_OR_REPLACE,
            ),
            gateway.oneTimePolicies,
        )
        assertEquals(RemoteUploadWorkPlan.PERIODIC_UNIQUE_NAME, gateway.periodicName)
        assertEquals(ExistingPeriodicWorkPolicy.KEEP, gateway.periodicPolicy)
    }

    private class RecordingGateway : RemoteUploadWorkGateway {
        val oneTimeNames = mutableListOf<String>()
        val oneTimePolicies = mutableListOf<ExistingWorkPolicy>()
        var periodicName: String? = null
        var periodicPolicy: ExistingPeriodicWorkPolicy? = null

        override fun enqueueOneTime(
            name: String,
            policy: ExistingWorkPolicy,
            request: OneTimeWorkRequest,
        ) {
            oneTimeNames += name
            oneTimePolicies += policy
        }

        override fun enqueuePeriodic(
            name: String,
            policy: ExistingPeriodicWorkPolicy,
            request: PeriodicWorkRequest,
        ) {
            periodicName = name
            periodicPolicy = policy
        }
    }
}
