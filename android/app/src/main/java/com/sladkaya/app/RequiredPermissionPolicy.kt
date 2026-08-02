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

    fun forStart(sdkInt: Int, alarmMonitoring: Boolean): List<String> =
        if (alarmMonitoring) forSdk(sdkInt) else mandatoryBleForSdk(sdkInt)

    fun hasPermissionsForStart(
        sdkInt: Int,
        grantedPermissions: Set<String>,
        alarmMonitoring: Boolean,
    ): Boolean = forStart(sdkInt, alarmMonitoring).all(grantedPermissions::contains)

    fun denialMessage(
        sdkInt: Int,
        grantedPermissions: Set<String>,
        alarmMonitoring: Boolean,
    ): String = when {
        !hasMandatoryBlePermissions(sdkInt, grantedPermissions) ->
            BLUETOOTH_DENIED_MESSAGE
        alarmMonitoring &&
            sdkInt >= 33 &&
            Manifest.permission.POST_NOTIFICATIONS !in grantedPermissions ->
            NOTIFICATION_DENIED_MESSAGE
        else -> BLUETOOTH_DENIED_MESSAGE
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

    fun hasPermissionsForStart(context: Context, alarmMonitoring: Boolean): Boolean {
        val granted = forStart(Build.VERSION.SDK_INT, alarmMonitoring)
            .filterTo(linkedSetOf()) { permission ->
                ContextCompat.checkSelfPermission(context, permission) ==
                    PackageManager.PERMISSION_GRANTED
            }
        return hasPermissionsForStart(Build.VERSION.SDK_INT, granted, alarmMonitoring)
    }

    fun denialMessage(context: Context, alarmMonitoring: Boolean): String {
        val granted = forStart(Build.VERSION.SDK_INT, alarmMonitoring)
            .filterTo(linkedSetOf()) { permission ->
                ContextCompat.checkSelfPermission(context, permission) ==
                    PackageManager.PERMISSION_GRANTED
            }
        return denialMessage(Build.VERSION.SDK_INT, granted, alarmMonitoring)
    }

    fun missingPermissions(context: Context, alarmMonitoring: Boolean = true): List<String> =
        forStart(Build.VERSION.SDK_INT, alarmMonitoring).filter { permission ->
            ContextCompat.checkSelfPermission(context, permission) !=
                PackageManager.PERMISSION_GRANTED
        }

    private const val BLUETOOTH_DENIED_MESSAGE =
        "Нужен доступ к Bluetooth для получения данных"
    private const val NOTIFICATION_DENIED_MESSAGE =
        "Разрешите уведомления для звуковых тревог"
}
