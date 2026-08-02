package com.sladkaya.app

import android.app.Application
import android.os.UserManager
import com.sladkaya.app.service.DirectBootRecoveryPolicy
import com.sladkaya.app.service.ProductLocalDeliveryProductionRuntime
import com.sladkaya.app.sync.RemoteSyncProcessLifecycle
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class SladkayaApplication : Application() {
    private val processScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onCreate() {
        super.onCreate()
        val userUnlocked = getSystemService(UserManager::class.java).isUserUnlocked
        val isMainProcess = Application.getProcessName() == packageName
        if (!ApplicationRuntimeInitializationPolicy.shouldInitialize(userUnlocked, isMainProcess)) {
            return
        }
        DirectBootRecoveryPolicy.initializeCredentialProtectedRuntimeIfAllowed(userUnlocked) {
            ProductLocalDeliveryProductionRuntime.install()
            processScope.launch {
                ProductLocalDeliveryProductionRuntime.createDrain(this@SladkayaApplication)
                    .runBounded()
            }
            RemoteSyncProcessLifecycle.create(this).onProcessStarted()
        }
    }
}

internal object ApplicationRuntimeInitializationPolicy {
    fun shouldInitialize(
        userUnlocked: Boolean,
        isMainProcess: Boolean,
    ): Boolean = userUnlocked && isMainProcess
}
