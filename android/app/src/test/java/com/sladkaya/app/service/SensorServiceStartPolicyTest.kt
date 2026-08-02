package com.sladkaya.app.service

import org.junit.Assert.assertEquals
import org.junit.Test

class SensorServiceStartPolicyTest {
    private val policy = SensorServiceStartPolicy()

    @Test
    fun ordinaryStartWithoutConfirmedConfigurationRequiresSetup() {
        assertEquals(
            SensorServiceStartMode.SetupRequired,
            policy.select(
                action = SensorServiceActions.START,
                hasConfirmedConfiguration = false,
                hasPendingDiagnosticConfiguration = false,
            ),
        )
    }

    @Test
    fun diagnosticStartRequiresItsDedicatedActionAndPendingProfile() {
        assertEquals(
            SensorServiceStartMode.DiagnosticSensor,
            policy.select(
                action = SensorServiceActions.START_DIAGNOSTIC,
                hasConfirmedConfiguration = false,
                hasPendingDiagnosticConfiguration = true,
                diagnosticResumeIdentityMatches = true,
            ),
        )
        assertEquals(
            SensorServiceStartMode.SetupRequired,
            policy.select(
                action = SensorServiceActions.START_DIAGNOSTIC,
                hasConfirmedConfiguration = false,
                hasPendingDiagnosticConfiguration = false,
                diagnosticResumeIdentityMatches = true,
            ),
        )
    }

    @Test
    fun redeliveredDiagnosticCannotRestartAfterStopOrProfileChange() {
        assertEquals(
            SensorServiceStartMode.SetupRequired,
            policy.select(
                action = SensorServiceActions.START_DIAGNOSTIC,
                hasConfirmedConfiguration = false,
                hasPendingDiagnosticConfiguration = true,
                diagnosticResumeIdentityMatches = false,
            ),
        )
    }

    @Test
    fun pendingDiagnosticProfileCannotEnableOrdinaryProductStart() {
        assertEquals(
            SensorServiceStartMode.SetupRequired,
            policy.select(
                action = SensorServiceActions.START,
                hasConfirmedConfiguration = false,
                hasPendingDiagnosticConfiguration = true,
            ),
        )
    }

    @Test
    fun nullIntentCanResumeOnlyAnExplicitlyRunningDiagnosticSession() {
        assertEquals(
            SensorServiceStartMode.DiagnosticSensor,
            policy.select(
                action = null,
                hasConfirmedConfiguration = false,
                hasPendingDiagnosticConfiguration = true,
                diagnosticResumeIdentityMatches = true,
            ),
        )
        assertEquals(
            SensorServiceStartMode.ConfiguredSensor,
            policy.select(
                action = null,
                hasConfirmedConfiguration = true,
                hasPendingDiagnosticConfiguration = true,
                diagnosticResumeIdentityMatches = false,
            ),
        )
    }

    @Test
    fun nullIntentResumesConfirmedProductWhenNoDiagnosticSessionOwnsRecovery() {
        assertEquals(
            SensorServiceStartMode.ConfiguredSensor,
            policy.select(
                action = null,
                hasConfirmedConfiguration = true,
                hasPendingDiagnosticConfiguration = false,
                diagnosticResumeIdentityMatches = false,
            ),
        )
    }

    @Test
    fun missingOrUnknownActionCannotEnableDemo() {
        listOf(false, true).forEach { hasConfirmedConfiguration ->
            listOf("", "unexpected.action").forEach { action ->
                assertEquals(
                    SensorServiceStartMode.SetupRequired,
                    policy.select(
                        action = action,
                        hasConfirmedConfiguration = hasConfirmedConfiguration,
                        hasPendingDiagnosticConfiguration = true,
                    ),
                )
            }
        }
    }

    @Test
    fun demoRequiresItsDedicatedExplicitAction() {
        assertEquals(
            SensorServiceStartMode.Demo,
            policy.select(
                action = SensorServiceActions.START_DEMO,
                hasConfirmedConfiguration = false,
                hasPendingDiagnosticConfiguration = false,
            ),
        )
    }

    @Test
    fun ordinaryStartWithConfirmedConfigurationSelectsConfiguredSensor() {
        assertEquals(
            SensorServiceStartMode.ConfiguredSensor,
            policy.select(
                action = SensorServiceActions.START,
                hasConfirmedConfiguration = true,
                hasPendingDiagnosticConfiguration = false,
            ),
        )
    }

    @Test
    fun demoActionDoesNotDependOnSavedConfiguration() {
        assertEquals(
            SensorServiceStartMode.Demo,
            policy.select(
                action = SensorServiceActions.START_DEMO,
                hasConfirmedConfiguration = true,
                hasPendingDiagnosticConfiguration = true,
            ),
        )
    }

    @Test
    fun backgroundStartRequiresBothConfigurationAndBlePermission() {
        assertEquals(true, SensorBackgroundStartPolicy.shouldStart(true, true))
        assertEquals(false, SensorBackgroundStartPolicy.shouldStart(false, true))
        assertEquals(false, SensorBackgroundStartPolicy.shouldStart(true, false))
    }

    @Test
    fun diagnosticBackgroundResumeRequiresIntentPendingProfileAndPermission() {
        assertEquals(
            true,
            SensorBackgroundStartPolicy.shouldResumeDiagnostic(
                diagnosticWasRunning = true,
                hasPendingDiagnosticConfiguration = true,
                hasMandatoryBlePermissions = true,
            ),
        )
        assertEquals(
            false,
            SensorBackgroundStartPolicy.shouldResumeDiagnostic(false, true, true),
        )
        assertEquals(
            false,
            SensorBackgroundStartPolicy.shouldResumeDiagnostic(true, false, true),
        )
        assertEquals(
            false,
            SensorBackgroundStartPolicy.shouldResumeDiagnostic(true, true, false),
        )
    }

    @Test
    fun productDataPathSurvivesUnavailableLocalAlarmWhileDemoStillRequiresIt() {
        assertEquals(
            true,
            AlarmMonitoringStartGate.canStart(
                SensorServiceStartMode.ConfiguredSensor,
                alarmReady = false,
            ),
        )
        assertEquals(
            false,
            AlarmMonitoringStartGate.canStart(
                SensorServiceStartMode.Demo,
                alarmReady = false,
            ),
        )
        assertEquals(
            true,
            AlarmMonitoringStartGate.canStart(
                SensorServiceStartMode.DiagnosticSensor,
                alarmReady = false,
            ),
        )
        assertEquals(
            true,
            AlarmMonitoringStartGate.canStart(
                SensorServiceStartMode.SetupRequired,
                alarmReady = false,
            ),
        )
    }

    @Test
    fun explicitStopRequiresDurableDiagnosticResumeMarkerClear() {
        assertEquals(true, SensorServiceStopPolicy.canStop(diagnosticResumeStateCleared = true))
        assertEquals(false, SensorServiceStopPolicy.canStop(diagnosticResumeStateCleared = false))
    }

    @Test
    fun runtimeReadinessRevocationStopsDemoButKeepsProductAndDiagnosticDataPathsAlive() {
        assertEquals(
            false,
            AlarmMonitoringRuntimeGate.canContinue(
                SensorServiceStartMode.Demo,
                alarmReady = false,
            ),
        )
        assertEquals(
            true,
            AlarmMonitoringRuntimeGate.canContinue(
                SensorServiceStartMode.ConfiguredSensor,
                alarmReady = false,
            ),
        )
        assertEquals(
            true,
            AlarmMonitoringRuntimeGate.canContinue(
                SensorServiceStartMode.DiagnosticSensor,
                alarmReady = false,
            ),
        )
    }

}
