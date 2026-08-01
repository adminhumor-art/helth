package com.sladkaya.app

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat

internal object RequiredPermissionPolicy {
    fun mandatoryBleForSdk(sdkInt: Int): List<String> = buildList {
        if (sdkInt >= 31) {
            add(Manifest.permission.BLUETOOTH_SCAN)
            add(Manifest.permission.BLUETOOTH_CONNECT)
        } else {
            add(Manifest.permission.ACCESS_FINE_LOCATION)
        }
    }

    fun forSdk(sdkInt: Int): List<String> = buildList {
        addAll(mandatoryBleForSdk(sdkInt))
        if (sdkInt >= 33) add(Manifest.permission.POST_NOTIFICATIONS)
    }

    fun hasMandatoryBlePermissions(
        sdkInt: Int,
        grantedPermissions: Set<String>,
    ): Boolean = mandatoryBleForSdk(sdkInt).all(grantedPermissions::contains)

    fun hasMandatoryBlePermissions(context: Context): Boolean {
        val granted = mandatoryBleForSdk(Build.VERSION.SDK_INT)
            .filterTo(linkedSetOf()) { permission ->
                ContextCompat.checkSelfPermission(context, permission) ==
                    PackageManager.PERMISSION_GRANTED
            }
        return hasMandatoryBlePermissions(Build.VERSION.SDK_INT, granted)
    }

    fun missingPermissions(context: Context): List<String> =
        forSdk(Build.VERSION.SDK_INT).filter { permission ->
            ContextCompat.checkSelfPermission(context, permission) !=
                PackageManager.PERMISSION_GRANTED
        }
}
