package com.sladkaya.app

import android.app.Application
import com.sladkaya.app.sync.RemoteSyncProcessLifecycle

class SladkayaApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        RemoteSyncProcessLifecycle.create(this).onProcessStarted()
    }
}
