package com.sladkaya.app.service

internal object SensorServiceActions {
    const val START = "com.sladkaya.app.action.START_SENSOR"
    const val START_DEMO = "com.sladkaya.app.action.START_DEMO"
}

internal enum class SensorServiceStartMode {
    SetupRequired,
    ConfiguredSensor,
    Demo,
}

internal object SensorBackgroundStartPolicy {
    fun shouldStart(
        hasConfirmedConfiguration: Boolean,
        hasMandatoryBlePermissions: Boolean,
    ): Boolean = hasConfirmedConfiguration && hasMandatoryBlePermissions
}

/** Pure fail-closed policy kept independent from Android for JVM tests. */
internal class SensorServiceStartPolicy {
    fun select(
        action: String?,
        hasConfirmedConfiguration: Boolean,
    ): SensorServiceStartMode = when (action) {
        SensorServiceActions.START_DEMO -> SensorServiceStartMode.Demo
        SensorServiceActions.START -> if (hasConfirmedConfiguration) {
            SensorServiceStartMode.ConfiguredSensor
        } else {
            SensorServiceStartMode.SetupRequired
        }
        else -> SensorServiceStartMode.SetupRequired
    }
}
