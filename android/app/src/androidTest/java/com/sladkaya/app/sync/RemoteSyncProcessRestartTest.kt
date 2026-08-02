package com.sladkaya.app.sync

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.work.WorkManager
import com.sladkaya.app.SladkayaApplication
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class RemoteSyncProcessRestartTest {
    @Test
    fun manifestStartsTheProductionApplicationAndRegistersPeriodicReconciliation() {
        val context = ApplicationProvider.getApplicationContext<Context>()

        assertTrue(context is SladkayaApplication)
        val work = WorkManager.getInstance(context)
            .getWorkInfosForUniqueWork(RemoteUploadWorkPlan.PERIODIC_UNIQUE_NAME)
            .get(10, TimeUnit.SECONDS)

        assertEquals(1, work.size)
    }
}
