package com.sladkaya.app.service

internal object SensorServiceActions {
    const val START = "com.sladkaya.app.action.START_SENSOR"
    const val START_DIAGNOSTIC = "com.sladkaya.app.action.START_DIAGNOSTIC_SENSOR"
    const val START_DEMO = "com.sladkaya.app.action.START_DEMO"
}

internal enum class SensorServiceStartMode {
    SetupRequired,
    ConfiguredSensor,
    DiagnosticSensor,
    Demo,
}

internal object AlarmMonitoringStartGate {
    fun canStart(mode: SensorServiceStartMode, alarmReady: Boolean): Boolean = when (mode) {
        SensorServiceStartMode.ConfiguredSensor,
        SensorServiceStartMode.Demo,
        -> alarmReady
        SensorServiceStartMode.DiagnosticSensor,
        SensorServiceStartMode.SetupRequired,
        -> true
    }
}

internal object AlarmMonitoringRuntimeGate {
    fun canContinue(mode: SensorServiceStartMode, alarmReady: Boolean): Boolean = when (mode) {
        SensorServiceStartMode.Demo -> alarmReady
        SensorServiceStartMode.ConfiguredSensor,
        SensorServiceStartMode.DiagnosticSensor,
        SensorServiceStartMode.SetupRequired,
        -> true
    }
}

internal object SensorBackgroundStartPolicy {
    fun shouldStart(
        hasConfirmedConfiguration: Boolean,
        hasMandatoryBlePermissions: Boolean,
    ): Boolean = hasConfirmedConfiguration && hasMandatoryBlePermissions

    fun shouldResumeDiagnostic(
        diagnosticWasRunning: Boolean,
        hasPendingDiagnosticConfiguration: Boolean,
        hasMandatoryBlePermissions: Boolean,
    ): Boolean = diagnosticWasRunning &&
        hasPendingDiagnosticConfiguration &&
        hasMandatoryBlePermissions
}

internal object SensorServiceStopPolicy {
    fun canStop(diagnosticResumeStateCleared: Boolean): Boolean =
        diagnosticResumeStateCleared
}

/** Pure fail-closed policy kept independent from Android for JVM tests. */
internal class SensorServiceStartPolicy {
    fun select(
        action: String?,
        hasConfirmedConfiguration: Boolean,
        hasPendingDiagnosticConfiguration: Boolean = false,
        diagnosticResumeIdentityMatches: Boolean = false,
    ): SensorServiceStartMode = when (action) {
        SensorServiceActions.START_DEMO -> SensorServiceStartMode.Demo
        SensorServiceActions.START_DIAGNOSTIC -> if (
            hasPendingDiagnosticConfiguration && diagnosticResumeIdentityMatches
        ) {
            SensorServiceStartMode.DiagnosticSensor
        } else {
            SensorServiceStartMode.SetupRequired
        }
        SensorServiceActions.START -> if (hasConfirmedConfiguration) {
            SensorServiceStartMode.ConfiguredSensor
        } else {
            SensorServiceStartMode.SetupRequired
        }
        null -> if (diagnosticResumeIdentityMatches && hasPendingDiagnosticConfiguration) {
            SensorServiceStartMode.DiagnosticSensor
        } else {
            SensorServiceStartMode.SetupRequired
        }
        else -> SensorServiceStartMode.SetupRequired
    }
}
