package com.sladkaya.app.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.sladkaya.app.RequiredPermissionPolicy

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val supportedAction = intent.action == Intent.ACTION_BOOT_COMPLETED ||
            intent.action == Intent.ACTION_MY_PACKAGE_REPLACED
        if (intent.action == Intent.ACTION_BOOT_COMPLETED) {
            com.sladkaya.app.widget.GlucoseWidgetProvider.showNoFreshData(
                context,
                demoActive = false,
            )
        } else if (intent.action == Intent.ACTION_MY_PACKAGE_REPLACED) {
            com.sladkaya.app.widget.GlucoseWidgetProvider.refreshAll(context)
        }
        val shouldStart = supportedAction && SensorBackgroundStartPolicy.shouldStart(
            hasConfirmedConfiguration = ConfirmedSensorConfigurationStore(context)
                .hasConfirmedConfiguration(),
            hasMandatoryBlePermissions = RequiredPermissionPolicy
                .hasMandatoryBlePermissions(context),
        )
        if (shouldStart) {
            SensorForegroundService.start(context)
        }
    }
}
