package com.sladkaya.app.service

import android.app.AlarmManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.sladkaya.app.RequiredPermissionPolicy
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val supportedAction = intent.action == Intent.ACTION_BOOT_COMPLETED ||
            intent.action == Intent.ACTION_MY_PACKAGE_REPLACED ||
            intent.action == AlarmManager.ACTION_SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED
        if (intent.action == Intent.ACTION_BOOT_COMPLETED) {
            DirectBootRecoveryNotification.cancel(context)
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
        val pendingResult = goAsync()
        val appContext = context.applicationContext
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                ProductLocalDeliveryProductionRuntime.createDrain(appContext).runBounded()
                if (
                    intent.action ==
                    AlarmManager.ACTION_SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED
                ) {
                    return@launch
                }
                val hasPermissions = RequiredPermissionPolicy
                    .hasMandatoryBlePermissions(appContext)
                val productConfiguration = LocalProductSensorConfigurationSource(
                    appContext,
                ).active()
                val pendingDiagnostic = PendingDiagnosticGs1OnboardingStateStore(appContext)
                    .loadPendingDiagnosticProfile()
                val diagnosticResumeMatches = pendingDiagnostic?.let { profile ->
                    DiagnosticSessionPreferenceStore(appContext).matches(
                        DiagnosticResumeIdentity.fingerprint(profile),
                    )
                } == true
                when (
                    SensorBackgroundLaunchPolicy.decide(
                        hasConfirmedConfiguration =
                            productConfiguration is ProductSensorConfigurationResult.Available,
                        diagnosticWasRunning = diagnosticResumeMatches,
                        hasPendingDiagnosticConfiguration = pendingDiagnostic != null,
                        hasMandatoryBlePermissions = hasPermissions,
                    )
                ) {
                    SensorBackgroundLaunchDecision.Diagnostic ->
                        SensorForegroundService.startDiagnostic(appContext)
                    SensorBackgroundLaunchDecision.Product ->
                        SensorForegroundService.ensureStarted(appContext)
                    SensorBackgroundLaunchDecision.None -> Unit
                }
            } finally {
                pendingResult.finish()
            }
        }
    }
}
