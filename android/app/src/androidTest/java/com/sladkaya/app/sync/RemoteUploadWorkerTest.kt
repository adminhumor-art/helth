package com.sladkaya.app.sync

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.work.ListenableWorker
import androidx.work.testing.TestListenableWorkerBuilder
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class RemoteUploadWorkerTest {
    @Test
    fun productionConstructorCanBeRecreatedAfterColdProcessStartWithoutStaticInstaller() {
        val context = ApplicationProvider.getApplicationContext<Context>()

        val worker = TestListenableWorkerBuilder<RemoteUploadWorker>(context).build()

        assertTrue(worker.inputData.keyValueMap.isEmpty())
    }

    @Test
    fun workerMapsRetryWithoutAnyMedicalInputData() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val factory = RemoteUploadWorkerFactory {
            RemoteUploadWorkExecutor { RemoteUploadExecutionResult.Retry }
        }
        val worker = TestListenableWorkerBuilder<RemoteUploadWorker>(context)
            .setWorkerFactory(factory)
            .build()

        assertTrue(worker.inputData.keyValueMap.isEmpty())
        assertEquals(ListenableWorker.Result.retry(), worker.doWork())
    }

    @Test
    fun aFreshFactoryAfterProcessRestartCanRecreateWorkerAndBlockedWorkCompletes() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val firstFactory = RemoteUploadWorkerFactory {
            RemoteUploadWorkExecutor { RemoteUploadExecutionResult.Complete }
        }
        val secondFactory = RemoteUploadWorkerFactory {
            RemoteUploadWorkExecutor { RemoteUploadExecutionResult.Blocked }
        }

        val first = TestListenableWorkerBuilder<RemoteUploadWorker>(context)
            .setWorkerFactory(firstFactory)
            .build()
        val afterRestart = TestListenableWorkerBuilder<RemoteUploadWorker>(context)
            .setWorkerFactory(secondFactory)
            .build()

        assertEquals(ListenableWorker.Result.success(), first.doWork())
        assertEquals(ListenableWorker.Result.success(), afterRestart.doWork())
    }
}
