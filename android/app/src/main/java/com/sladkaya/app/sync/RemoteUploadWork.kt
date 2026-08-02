package com.sladkaya.app.sync

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.OneTimeWorkRequest
import androidx.work.PeriodicWorkRequest
import androidx.work.WorkerParameters
import com.sladkaya.core.data.UploadOutboxRepository
import java.time.Duration
import java.util.concurrent.TimeUnit

internal object RemoteUploadWorkPlan {
    const val ONE_TIME_UNIQUE_NAME = "sladkaya.remote-upload.drain"
    const val PERIODIC_UNIQUE_NAME = "sladkaya.remote-upload.reconcile"
    val ONE_TIME_EXISTING_WORK_POLICY: ExistingWorkPolicy = ExistingWorkPolicy.APPEND_OR_REPLACE
    val RECONCILIATION_INTERVAL: Duration = Duration.ofMinutes(15)
    val INITIAL_BACKOFF: Duration = Duration.ofSeconds(30)
    val inputData: Map<String, Any> = emptyMap()
}

sealed interface RemoteUploadExecutionResult {
    data object Complete : RemoteUploadExecutionResult
    data object Retry : RemoteUploadExecutionResult
    data object Blocked : RemoteUploadExecutionResult
}

fun interface RemoteUploadWorkExecutor {
    suspend fun execute(): RemoteUploadExecutionResult
}

class RemoteUploadWorker internal constructor(
    appContext: Context,
    workerParameters: WorkerParameters,
    private val executor: RemoteUploadWorkExecutor,
) : CoroutineWorker(appContext, workerParameters) {
    constructor(appContext: Context, workerParameters: WorkerParameters) : this(
        appContext = appContext,
        workerParameters = workerParameters,
        executor = ProductionRemoteUploadWorkExecutor.create(appContext),
    )

    override suspend fun doWork(): Result = when (executor.execute()) {
        RemoteUploadExecutionResult.Complete -> Result.success()
        RemoteUploadExecutionResult.Retry -> Result.retry()
        RemoteUploadExecutionResult.Blocked -> Result.success()
    }
}

private class ProductionRemoteUploadWorkExecutor(
    private val uploader: MeasurementUploader,
) : RemoteUploadWorkExecutor {
    override suspend fun execute(): RemoteUploadExecutionResult = when (uploader.flush()) {
        MeasurementFlushResult.Complete -> RemoteUploadExecutionResult.Complete
        MeasurementFlushResult.RetryNeeded -> RemoteUploadExecutionResult.Retry
        MeasurementFlushResult.Blocked -> RemoteUploadExecutionResult.Blocked
    }

    companion object {
        fun create(context: Context): RemoteUploadWorkExecutor = ProductionRemoteUploadWorkExecutor(
            MeasurementUploader(
                outbox = UploadOutboxRepository.create(context.applicationContext),
                credentials = AndroidKeystoreCredentialVault(context.applicationContext),
            ),
        )
    }
}

class RemoteUploadWorkerFactory(
    private val executorFactory: (Context) -> RemoteUploadWorkExecutor,
) : androidx.work.WorkerFactory() {
    override fun createWorker(
        appContext: Context,
        workerClassName: String,
        workerParameters: WorkerParameters,
    ): androidx.work.ListenableWorker? = if (workerClassName == RemoteUploadWorker::class.java.name) {
        RemoteUploadWorker(appContext, workerParameters, executorFactory(appContext))
    } else {
        null
    }
}

interface RemoteUploadWorkScheduler {
    fun requestDrain()
    fun ensurePeriodicReconciliation()
}

class WorkManagerRemoteUploadScheduler internal constructor(
    private val gateway: RemoteUploadWorkGateway,
) : RemoteUploadWorkScheduler {
    constructor(workManager: WorkManager) : this(AndroidRemoteUploadWorkGateway(workManager))

    override fun requestDrain() {
        gateway.enqueueOneTime(
            RemoteUploadWorkPlan.ONE_TIME_UNIQUE_NAME,
            RemoteUploadWorkPlan.ONE_TIME_EXISTING_WORK_POLICY,
            RemoteUploadWorkRequests.oneTime(),
        )
    }

    override fun ensurePeriodicReconciliation() {
        gateway.enqueuePeriodic(
            RemoteUploadWorkPlan.PERIODIC_UNIQUE_NAME,
            ExistingPeriodicWorkPolicy.KEEP,
            RemoteUploadWorkRequests.periodic(),
        )
    }
}

internal interface RemoteUploadWorkGateway {
    fun enqueueOneTime(name: String, policy: ExistingWorkPolicy, request: OneTimeWorkRequest)
    fun enqueuePeriodic(name: String, policy: ExistingPeriodicWorkPolicy, request: PeriodicWorkRequest)
}

private class AndroidRemoteUploadWorkGateway(
    private val workManager: WorkManager,
) : RemoteUploadWorkGateway {
    override fun enqueueOneTime(name: String, policy: ExistingWorkPolicy, request: OneTimeWorkRequest) {
        workManager.enqueueUniqueWork(name, policy, request)
    }

    override fun enqueuePeriodic(name: String, policy: ExistingPeriodicWorkPolicy, request: PeriodicWorkRequest) {
        workManager.enqueueUniquePeriodicWork(name, policy, request)
    }
}

internal object RemoteUploadWorkRequests {
    fun oneTime() = OneTimeWorkRequestBuilder<RemoteUploadWorker>()
        .setConstraints(connectedConstraint())
        .setBackoffCriteria(
            BackoffPolicy.EXPONENTIAL,
            RemoteUploadWorkPlan.INITIAL_BACKOFF.toMillis(),
            TimeUnit.MILLISECONDS,
        )
        .build()

    fun periodic() = PeriodicWorkRequestBuilder<RemoteUploadWorker>(
        RemoteUploadWorkPlan.RECONCILIATION_INTERVAL.toMinutes(),
        TimeUnit.MINUTES,
    )
        .setConstraints(connectedConstraint())
        .setBackoffCriteria(
            BackoffPolicy.EXPONENTIAL,
            RemoteUploadWorkPlan.INITIAL_BACKOFF.toMillis(),
            TimeUnit.MILLISECONDS,
        )
        .build()

    private fun connectedConstraint(): Constraints = Constraints.Builder()
        .setRequiredNetworkType(NetworkType.CONNECTED)
        .build()
}
