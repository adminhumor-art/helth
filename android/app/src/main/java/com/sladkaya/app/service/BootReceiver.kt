package com.sladkaya.app.service

import android.app.AlarmManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.sladkaya.app.RequiredPermissionPolicy

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val supportedAction = intent.action == Intent.ACTION_BOOT_COMPLETED ||
            intent.action == Intent.ACTION_MY_PACKAGE_REPLACED ||
            intent.action == AlarmManager.ACTION_SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED
        if (intent.action == Intent.ACTION_BOOT_COMPLETED) {
            com.sladkaya.app.widget.GlucoseWidgetProvider.showNoFreshData(
                context,
                demoActive = false,
            )
        } else if (intent.action == Intent.ACTION_MY_PACKAGE_REPLACED) {
            com.sladkaya.app.widget.GlucoseWidgetProvider.refreshAll(context)
        }
        if (!supportedAction) return
        val recoveryTrigger = if (
            intent.action == AlarmManager.ACTION_SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED
        ) {
            AlarmEpisodeRecoveryTrigger.EXACT_ALARM_ACCESS_CHANGED
        } else {
            AlarmEpisodeRecoveryTrigger.BOOT_OR_PACKAGE_REPLACED
        }
        val watchdogRestored = SignalLossWatchdogRecoveryCoordinator(context)
            .restore(recoveryTrigger)
        val alarmRestored = AlarmEpisodeRecoveryCoordinator(context).restore(recoveryTrigger)
        if (!watchdogRestored || !alarmRestored) {
            com.sladkaya.app.widget.GlucoseWidgetProvider.showSetupRequired(context)
        }
        if (intent.action == AlarmManager.ACTION_SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED) {
            return
        }
        val hasPermissions = RequiredPermissionPolicy.hasMandatoryBlePermissions(context)
        val hasConfirmed = ConfirmedSensorConfigurationStore(context).hasConfirmedConfiguration()
        if (SensorBackgroundStartPolicy.shouldStart(hasConfirmed, hasPermissions)) {
            SensorForegroundService.start(context)
            return
        }
        val pendingDiagnostic = PendingDiagnosticGs1OnboardingStateStore(context)
            .loadPendingDiagnosticProfile()
        val diagnosticResumeMatches = pendingDiagnostic?.let { profile ->
            DiagnosticSessionPreferenceStore(context).matches(
                DiagnosticResumeIdentity.fingerprint(profile),
            )
        } == true
        if (
            SensorBackgroundStartPolicy.shouldResumeDiagnostic(
                diagnosticWasRunning = diagnosticResumeMatches,
                hasPendingDiagnosticConfiguration = pendingDiagnostic != null,
                hasMandatoryBlePermissions = hasPermissions,
            )
        ) {
            SensorForegroundService.startDiagnostic(context)
        }
    }
}
