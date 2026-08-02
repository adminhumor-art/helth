package com.sladkaya.app

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings

object BatteryOptimizationAccess {
    fun needsUserAction(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return false
        val powerManager = context.getSystemService(PowerManager::class.java)
        return BatteryOptimizationAccessPolicy.needsUserAction(
            sdkInt = Build.VERSION.SDK_INT,
            ignoringBatteryOptimizations = powerManager.isIgnoringBatteryOptimizations(
                context.packageName,
            ),
        )
    }

    fun requestExemptionIntent(context: Context): Intent = Intent(
        Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
        Uri.parse("package:${context.packageName}"),
    )

    fun fallbackSettingsIntent(): Intent = Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
}

internal object BatteryOptimizationAccessPolicy {
    fun needsUserAction(sdkInt: Int, ignoringBatteryOptimizations: Boolean): Boolean =
        sdkInt >= Build.VERSION_CODES.M && !ignoringBatteryOptimizations
}
