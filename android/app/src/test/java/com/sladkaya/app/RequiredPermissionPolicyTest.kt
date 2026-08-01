package com.sladkaya.app

import android.Manifest
import org.junit.Assert.assertEquals
import org.junit.Test

class RequiredPermissionPolicyTest {
    @Test
    fun androidElevenRequestsLocationNeededForBleDiscovery() {
        assertEquals(
            listOf(Manifest.permission.ACCESS_FINE_LOCATION),
            RequiredPermissionPolicy.forSdk(30),
        )
    }

    @Test
    fun androidTwelveUsesDedicatedBluetoothPermissions() {
        assertEquals(
            listOf(
                Manifest.permission.BLUETOOTH_SCAN,
                Manifest.permission.BLUETOOTH_CONNECT,
            ),
            RequiredPermissionPolicy.forSdk(31),
        )
    }

    @Test
    fun androidThirteenAlsoRequestsNotificationPermissionForAlarms() {
        assertEquals(
            listOf(
                Manifest.permission.BLUETOOTH_SCAN,
                Manifest.permission.BLUETOOTH_CONNECT,
                Manifest.permission.POST_NOTIFICATIONS,
            ),
            RequiredPermissionPolicy.forSdk(33),
        )
    }

    @Test
    fun sensorStartRequiresEveryBlePermissionButNotNotificationPermission() {
        val bluetooth = setOf(
            Manifest.permission.BLUETOOTH_SCAN,
            Manifest.permission.BLUETOOTH_CONNECT,
        )

        assertEquals(true, RequiredPermissionPolicy.hasMandatoryBlePermissions(37, bluetooth))
        assertEquals(
            false,
            RequiredPermissionPolicy.hasMandatoryBlePermissions(
                37,
                bluetooth - Manifest.permission.BLUETOOTH_CONNECT,
            ),
        )
        assertEquals(
            true,
            RequiredPermissionPolicy.hasMandatoryBlePermissions(
                30,
                setOf(Manifest.permission.ACCESS_FINE_LOCATION),
            ),
        )
    }
}
