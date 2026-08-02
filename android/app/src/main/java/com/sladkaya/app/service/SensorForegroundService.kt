package com.sladkaya.app.service

import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import androidx.core.content.ContextCompat
import com.sladkaya.app.AppState
import com.sladkaya.app.DiagnosticGattPresentationPolicy
import com.sladkaya.app.RequiredPermissionPolicy
import com.sladkaya.app.settings.AlarmSettingsStore
import com.sladkaya.core.model.AlarmChanges
import com.sladkaya.core.model.AlarmKind
import com.sladkaya.core.model.AlarmPolicy
import com.sladkaya.core.model.GlucoseReading
import com.sladkaya.core.model.ReadingQuality
import com.sladkaya.core.sensor.SensorConfiguration
import com.sladkaya.sensor.simulator.SimulationScenario
import com.sladkaya.sensor.simulator.SimulatorDriver
import com.sladkaya.sensor.sibionics.Gs1DiagnosticGattDriver
import com.sladkaya.sensor.sibionics.Gs1PendingDiagnosticProfile
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.lang.ref.WeakReference
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

class SensorForegroundService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val sessionTransitionScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val sessionTransitionMutex = Mutex()
    private val sessionRequestGate = ServiceSessionRequestGate()
    private val destroyed = AtomicBoolean(false)
    private val startPolicy = SensorServiceStartPolicy()
    private val demoSessionGate = DemoSessionGate()
    private val demoStartRequestGate = DemoStartRequestGate()
    private val alarmLock = Any()
    @Volatile
    private var demoDriver: SimulatorDriver? = null
    @Volatile
    private var diagnosticDriver: Gs1DiagnosticGattDriver? = null
    private var monitoringStartedAtEpochMs = System.currentTimeMillis()
    private var alarms = AlarmPolicy(
        monitoringStartedAtEpochMs = monitoringStartedAtEpochMs,
    )
    private lateinit var alarmEpisodeStore: AlarmEpisodePreferenceStore
    private lateinit var alarmRepeatScheduler: AlarmRepeatScheduler
    private lateinit var signalLossWatchdogStore: SignalLossWatchdogPreferenceStore
    private lateinit var signalLossWatchdogScheduler: SignalLossWatchdogScheduler
    @Volatile
    private var activeAlarmEpisodeId: String? = null
    @Volatile
    private var activeDemoSideEffectGeneration: Long? = null
    private var activeDemoStateGeneration: Long? = null
    private lateinit var notifier: AlarmNotifier
    private var collectionJob: Job? = null
    private var diagnosticCollectionJob: Job? = null
    private var activeDiagnosticStateGeneration: Long? = null
    private var foregroundStarted = false

    override fun onCreate() {
        super.onCreate()
        notifier = AlarmNotifier(this).also { it.createChannels() }
        alarmEpisodeStore = AlarmEpisodePreferenceStore(this)
        alarmRepeatScheduler = AlarmRepeatScheduler(this)
        signalLossWatchdogStore = SignalLossWatchdogPreferenceStore(this)
        signalLossWatchdogScheduler = SignalLossWatchdogScheduler(this)
        if (!RequiredPermissionPolicy.hasMandatoryBlePermissions(this)) {
            AppState.onSetupRequired(PERMISSION_REQUIRED_MESSAGE)
            com.sladkaya.app.widget.GlucoseWidgetProvider.showSetupRequired(this)
            stopSelf()
            return
        }
        startForeground(FOREGROUND_ID, notifier.foregroundStatus("Проверка настройки датчика"))
        foregroundStarted = true
        registerActiveInstance(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (!foregroundStarted) return START_NOT_STICKY
        val pendingDiagnosticProfile = PendingDiagnosticGs1OnboardingStateStore(this)
            .loadPendingDiagnosticProfile()
        val pendingDiagnosticFingerprint = pendingDiagnosticProfile
            ?.let(DiagnosticResumeIdentity::fingerprint)
        val diagnosticSessionStore = DiagnosticSessionPreferenceStore(this)
        val mode = startPolicy.select(
            action = intent?.action,
            hasConfirmedConfiguration = ConfirmedSensorConfigurationStore(this)
                .hasConfirmedConfiguration(),
            hasPendingDiagnosticConfiguration = pendingDiagnosticProfile != null,
            diagnosticResumeIdentityMatches = pendingDiagnosticFingerprint?.let(
                diagnosticSessionStore::matches,
            ) == true,
        )
        val alarmReadiness = readAlarmReadiness(this)
        if (!AlarmMonitoringStartGate.canStart(mode, alarmReadiness.ready)) {
            failClosed(alarmReadiness.userMessage(), startId)
            return START_NOT_STICKY
        }
        when (mode) {
            SensorServiceStartMode.Demo -> {
                if (!diagnosticSessionStore.clear()) {
                    failClosed(DIAGNOSTIC_RESUME_STATE_FAILED_MESSAGE, startId)
                    return START_NOT_STICKY
                }
                val restoredEpisode = when (val loaded = alarmEpisodeStore.load()) {
                    AlarmEpisodeLoadResult.Empty -> null
                    AlarmEpisodeLoadResult.Corrupt -> {
                        failClosed(ALARM_EPISODE_RECOVERY_FAILED_MESSAGE, startId)
                        return START_NOT_STICKY
                    }
                    is AlarmEpisodeLoadResult.Active -> {
                        if (!loaded.episode.demo) {
                            failClosed(ALARM_EPISODE_CONFLICT_MESSAGE, startId)
                            return START_NOT_STICKY
                        }
                        loaded.episode
                    }
                }
                requestDemoSession(restoredEpisode)
            }
            SensorServiceStartMode.DiagnosticSensor -> {
                if (
                    pendingDiagnosticFingerprint == null ||
                    !diagnosticSessionStore.matches(pendingDiagnosticFingerprint)
                ) {
                    failClosed(DIAGNOSTIC_RESUME_STATE_FAILED_MESSAGE, startId)
                    return START_NOT_STICKY
                }
                requestDiagnosticSession(checkNotNull(pendingDiagnosticProfile))
            }
            SensorServiceStartMode.SetupRequired -> failClosed(
                message = SETUP_REQUIRED_MESSAGE,
                startId = startId,
            )
            SensorServiceStartMode.ConfiguredSensor -> failClosed(
                message = CONFIGURED_SENSOR_NOT_CONNECTED_MESSAGE,
                startId = startId,
            )
        }
        return if (mode == SensorServiceStartMode.DiagnosticSensor) {
            START_REDELIVER_INTENT
        } else {
            START_NOT_STICKY
        }
    }

    private fun requestDiagnosticSession(profile: Gs1PendingDiagnosticProfile) {
        val request = sessionRequestGate.request()
        diagnosticDriver?.requestStop()
        getSystemService(android.app.NotificationManager::class.java).notify(
            FOREGROUND_ID,
            notifier.foregroundStatus("Подготовка диагностического подключения"),
        )
        sessionTransitionScope.launch {
            sessionTransitionMutex.withLock {
                stopAllSessionsLocked()
                if (destroyed.get() || !sessionRequestGate.isCurrent(request)) return@withLock
                startDiagnosticSessionLocked(profile)
            }
        }
    }

    private fun startDiagnosticSessionLocked(profile: Gs1PendingDiagnosticProfile) {
        val stateGeneration = AppState.onDiagnosticStarting()
        activeDiagnosticStateGeneration = stateGeneration
        com.sladkaya.app.widget.GlucoseWidgetProvider.showSetupRequired(this)
        val driver = Gs1DiagnosticGattDriver(this)
        diagnosticDriver = driver
        getSystemService(android.app.NotificationManager::class.java).notify(
            FOREGROUND_ID,
            notifier.foregroundStatus("Диагностическое подключение к датчику"),
        )
        diagnosticCollectionJob = scope.launch {
            launch {
                driver.state.collect { driverState ->
                    val presentation = DiagnosticGattPresentationPolicy.present(driverState)
                    if (
                        AppState.onDiagnosticStatus(
                            generation = stateGeneration,
                            phaseLabel = presentation.label,
                            technicalCode = presentation.technicalCode,
                            retryable = presentation.retryable,
                            allowsReading = presentation.allowsReading,
                        )
                    ) {
                        getSystemService(android.app.NotificationManager::class.java).notify(
                            FOREGROUND_ID,
                            notifier.foregroundStatus(presentation.label),
                        )
                    }
                }
            }
            launch {
                driver.latestDiagnostic.filterNotNull().collect { reading ->
                    AppState.onDiagnosticReading(stateGeneration, reading)
                }
            }
            try {
                driver.start(profile.diagnosticActivationProfile())
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: LinkageError) {
                AppState.onDiagnosticStatus(
                    generation = stateGeneration,
                    phaseLabel = "Нативное ядро недоступно на этом устройстве",
                    technicalCode = "NATIVE_RUNTIME_UNAVAILABLE",
                    retryable = false,
                )
            } catch (_: Exception) {
                AppState.onDiagnosticStatus(
                    generation = stateGeneration,
                    phaseLabel = "Диагностический запуск остановлен безопасно",
                    technicalCode = "DIAGNOSTIC_START_FAILED",
                    retryable = true,
                )
            }
        }
    }

    private fun requestDemoSession(restoredEpisode: AlarmEpisode?) {
        if (!demoStartRequestGate.claim()) return
        val request = sessionRequestGate.request()
        diagnosticDriver?.requestStop()
        sessionTransitionScope.launch {
            sessionTransitionMutex.withLock {
                stopAllSessionsLocked(releaseDemoStartClaim = false)
                if (destroyed.get() || !sessionRequestGate.isCurrent(request)) {
                    demoStartRequestGate.release()
                    return@withLock
                }
                startDemoSessionLocked(restoredEpisode)
            }
        }
    }

    private fun startDemoSessionLocked(restoredEpisode: AlarmEpisode?) {
        val sideEffectGeneration = demoSessionGate.activate()
        val stateGeneration = AppState.onDemoStarting()
        activeDemoSideEffectGeneration = sideEffectGeneration
        activeDemoStateGeneration = stateGeneration
        monitoringStartedAtEpochMs = System.currentTimeMillis()
        val monitoringIdentity = SignalLossReadingIdentity.forMonitoringSession(
            UUID.randomUUID().toString(),
        )
        val restoreSucceeded = alarmEpisodeStore.atomically {
            val currentRestoredEpisode = when (
                val resolution = DemoEpisodeStartPolicy.resolve(
                    restoredEpisode?.id,
                    alarmEpisodeStore.load(),
                )
            ) {
                DemoEpisodeStartResolution.NoEpisode -> null
                is DemoEpisodeStartResolution.Restored -> resolution.episode
                DemoEpisodeStartResolution.Conflict -> return@atomically false
            }
            val thresholds = AlarmSettingsStore(this@SensorForegroundService).load().thresholds
            synchronized(alarmLock) {
                alarms = AlarmPolicy(
                    thresholds = thresholds,
                    monitoringStartedAtEpochMs = monitoringStartedAtEpochMs,
                    initiallyOpen = currentRestoredEpisode?.activeKinds.orEmpty(),
                )
            }
            if (!armSignalLossWatchdogForMonitoringStart(
                    identity = monitoringIdentity,
                    startedAtEpochMs = monitoringStartedAtEpochMs,
                    staleAfterMs = thresholds.staleAfterMs,
                    demo = true,
                )
            ) {
                return@atomically false
            }
            activeAlarmEpisodeId = currentRestoredEpisode?.id
            currentRestoredEpisode?.let { episode ->
                AppState.onDemoAlarmState(stateGeneration, episode.activeKinds)
                val shown = notifier.showEpisode(episode, alert = false)
                val scheduled = if (episode.acknowledged) {
                    alarmRepeatScheduler.cancel()
                } else {
                    alarmRepeatScheduler.scheduleWithEmergencyFallback(episode)
                }
                if (!shown || !scheduled) return@atomically false
            }
            true
        }
        if (!restoreSucceeded) {
            demoSessionGate.invalidate()
            demoStartRequestGate.release()
            activeDemoSideEffectGeneration = null
            activeDemoStateGeneration = null
            AppState.onSetupRequired(ALARM_EPISODE_RECOVERY_FAILED_MESSAGE)
            com.sladkaya.app.widget.GlucoseWidgetProvider.showSetupRequired(this)
            getSystemService(android.app.NotificationManager::class.java).notify(
                FOREGROUND_ID,
                notifier.foregroundStatus(ALARM_EPISODE_RECOVERY_FAILED_MESSAGE),
            )
            stopSelf()
            return
        }
        com.sladkaya.app.widget.GlucoseWidgetProvider.showDemoWaiting(this)
        val driver = SimulatorDriver(SimulationScenario.NIGHT_LOW)
        demoDriver = driver
        getSystemService(android.app.NotificationManager::class.java)
            .notify(FOREGROUND_ID, notifier.foreground(null))
        collectionJob = scope.launch {
            launch {
                driver.state.collect { state ->
                    demoSessionGate.runIfCurrent(sideEffectGeneration) {
                        AppState.onDemoDriverState(stateGeneration, state)
                    }
                }
            }
            launch {
                driver.readings.filterNotNull().collect { reading ->
                    demoSessionGate.runIfCurrent(sideEffectGeneration) {
                        if (!ensureDemoAlarmMonitoringReady()) return@runIfCurrent
                        val nowEpochMs = System.currentTimeMillis()
                        val changes = synchronized(alarmLock) {
                            alarms.evaluate(reading, nowEpochMs)
                        }
                        if (!commitReadingAlarmState(
                                changes = changes,
                                reading = reading,
                                demo = true,
                                nowEpochMs = nowEpochMs,
                            )
                        ) {
                            failClosed(ALARM_EPISODE_PERSISTENCE_FAILED_MESSAGE)
                            return@runIfCurrent
                        }
                        if (AppState.onDemoReading(stateGeneration, reading, changes.active)) {
                            getSystemService(android.app.NotificationManager::class.java)
                                .notify(FOREGROUND_ID, notifier.foreground(reading))
                            com.sladkaya.app.widget.GlucoseWidgetProvider.updateDemoAll(
                                this@SensorForegroundService,
                                reading,
                            )
                        }
                    }
                }
            }
            launch {
                while (true) {
                    delay(30_000)
                    demoSessionGate.runIfCurrent(sideEffectGeneration) {
                        if (!ensureDemoAlarmMonitoringReady()) return@runIfCurrent
                        val nowEpochMs = System.currentTimeMillis()
                        val changes = synchronized(alarmLock) {
                            alarms.evaluateFreshness(nowEpochMs)
                        }
                        if (AppState.onDemoAlarmState(stateGeneration, changes.active)) {
                            if (!applyAlarmChanges(
                                    changes,
                                    AppState.state.value.latest,
                                    demo = true,
                                    nowEpochMs = nowEpochMs,
                                )
                            ) {
                                failClosed(ALARM_EPISODE_PERSISTENCE_FAILED_MESSAGE)
                                return@runIfCurrent
                            }
                            if (AlarmKind.SIGNAL_LOSS in changes.opened) {
                                com.sladkaya.app.widget.GlucoseWidgetProvider.showNoFreshData(
                                    this@SensorForegroundService,
                                    demoActive = true,
                                )
                            }
                        }
                    }
                }
            }
            driver.start(SensorConfiguration(sensorId = "simulator-development"))
        }
    }

    private fun ensureDemoAlarmMonitoringReady(): Boolean {
        val readiness = readAlarmReadiness(this)
        if (
            AlarmMonitoringRuntimeGate.canContinue(
                SensorServiceStartMode.Demo,
                readiness.ready,
            )
        ) {
            return true
        }
        failClosed(readiness.userMessage())
        return false
    }

    private fun failClosed(message: String, startId: Int? = null) {
        val failureRequest = sessionRequestGate.invalidate()
        demoSessionGate.invalidate()
        collectionJob?.cancel()
        diagnosticCollectionJob?.cancel()
        diagnosticDriver?.requestStop()
        AppState.onSetupRequired(message)
        com.sladkaya.app.widget.GlucoseWidgetProvider.showSetupRequired(this)
        getSystemService(android.app.NotificationManager::class.java)
            .notify(FOREGROUND_ID, notifier.foregroundStatus(message))
        sessionTransitionScope.launch {
            sessionTransitionMutex.withLock {
                if (!sessionRequestGate.isCurrent(failureRequest)) return@withLock
                stopAllSessionsLocked()
            }
            if (startId == null) stopSelf() else stopSelf(startId)
        }
    }

    private fun commitReadingAlarmState(
        changes: AlarmChanges,
        reading: GlucoseReading,
        demo: Boolean,
        nowEpochMs: Long,
    ): Boolean = alarmEpisodeStore.atomically {
        val staleAfterMs = AlarmSettingsStore(this@SensorForegroundService)
            .load().thresholds.staleAfterMs
        if (!armSignalLossWatchdogForReading(
                reading = reading,
                staleAfterMs = staleAfterMs,
                demo = demo,
                nowEpochMs = nowEpochMs,
            )
        ) {
            return@atomically false
        }
        applyAlarmChanges(changes, reading, demo, nowEpochMs)
    }

    private fun armSignalLossWatchdogForMonitoringStart(
        identity: String,
        startedAtEpochMs: Long,
        staleAfterMs: Long,
        demo: Boolean,
    ): Boolean = saveAndScheduleSignalLossWatchdog(
        identity = identity,
        sensorTimeEpochMs = startedAtEpochMs,
        phoneTimeEpochMs = startedAtEpochMs,
        staleAfterMs = staleAfterMs,
        demo = demo,
        nowEpochMs = startedAtEpochMs,
    )

    private fun armSignalLossWatchdogForReading(
        reading: GlucoseReading,
        staleAfterMs: Long,
        demo: Boolean,
        nowEpochMs: Long,
    ): Boolean {
        if (!SignalLossWatchdogEligibility.canRecord(reading, nowEpochMs, staleAfterMs)) {
            return true
        }
        return saveAndScheduleSignalLossWatchdog(
            identity = SignalLossReadingIdentity.forReading(reading),
            sensorTimeEpochMs = reading.sensorTimeEpochMs,
            phoneTimeEpochMs = reading.phoneTimeEpochMs,
            staleAfterMs = staleAfterMs,
            demo = demo,
            nowEpochMs = nowEpochMs,
        )
    }

    private fun saveAndScheduleSignalLossWatchdog(
        identity: String,
        sensorTimeEpochMs: Long,
        phoneTimeEpochMs: Long,
        staleAfterMs: Long,
        demo: Boolean,
        nowEpochMs: Long,
    ): Boolean {
        val previous = when (val loaded = signalLossWatchdogStore.load()) {
            SignalLossWatchdogLoadResult.Empty -> null
            SignalLossWatchdogLoadResult.Corrupt -> return false
            is SignalLossWatchdogLoadResult.Active -> loaded.state.also { state ->
                if (state.demo != demo) return false
            }
        }
        val state = SignalLossWatchdogPolicy.record(
            previous = previous,
            readingIdentity = identity,
            sensorTimeEpochMs = sensorTimeEpochMs,
            phoneTimeEpochMs = phoneTimeEpochMs,
            staleAfterMs = staleAfterMs,
            demo = demo,
        )
        return prearmAndPersistSignalLossWatchdog(previous, state, nowEpochMs)
    }

    private fun rearmSignalLossWatchdogForSettings(
        staleAfterMs: Long,
        demo: Boolean,
        nowEpochMs: Long,
    ): Boolean {
        val previous = when (val loaded = signalLossWatchdogStore.load()) {
            SignalLossWatchdogLoadResult.Empty -> return saveAndScheduleSignalLossWatchdog(
                identity = SignalLossReadingIdentity.forMonitoringSession(
                    UUID.randomUUID().toString(),
                ),
                sensorTimeEpochMs = monitoringStartedAtEpochMs,
                phoneTimeEpochMs = monitoringStartedAtEpochMs,
                staleAfterMs = staleAfterMs,
                demo = demo,
                nowEpochMs = nowEpochMs,
            )
            SignalLossWatchdogLoadResult.Corrupt -> return false
            is SignalLossWatchdogLoadResult.Active -> loaded.state
        }
        if (previous.demo != demo) return false
        val updated = SignalLossWatchdogPolicy.record(
            previous = previous,
            readingIdentity = previous.readingIdentity,
            sensorTimeEpochMs = previous.sensorTimeEpochMs,
            phoneTimeEpochMs = previous.phoneTimeEpochMs,
            staleAfterMs = staleAfterMs,
            demo = demo,
        )
        return prearmAndPersistSignalLossWatchdog(previous, updated, nowEpochMs)
    }

    private fun prearmAndPersistSignalLossWatchdog(
        previous: SignalLossWatchdogState?,
        candidate: SignalLossWatchdogState,
        nowEpochMs: Long,
    ): Boolean {
        if (!signalLossWatchdogScheduler.scheduleWithRetryFallback(candidate, nowEpochMs)) {
            return false
        }
        if (!signalLossWatchdogStore.save(candidate)) return false
        if (
            previous != null &&
            previous.generation != candidate.generation &&
            SignalLossWatchdogSlotPolicy.slotFor(previous.generation) !=
            SignalLossWatchdogSlotPolicy.slotFor(candidate.generation) &&
            !signalLossWatchdogScheduler.cancelGeneration(previous.generation)
        ) {
            reportAlarmDeliveryFailure(this, SIGNAL_LOSS_OLD_SLOT_CANCEL_FAILED_MESSAGE)
        }
        return true
    }

    private fun applyAlarmChanges(
        changes: AlarmChanges,
        reading: GlucoseReading?,
        demo: Boolean,
        nowEpochMs: Long,
    ): Boolean = alarmEpisodeStore.atomically {
        if (
            changes.opened.isEmpty() &&
            changes.closed.isEmpty() &&
            changes.active.isEmpty()
        ) return@atomically true
        val previous = when (val loaded = alarmEpisodeStore.load()) {
            AlarmEpisodeLoadResult.Empty -> null
            AlarmEpisodeLoadResult.Corrupt -> return@atomically false
            is AlarmEpisodeLoadResult.Active -> loaded.episode.also { episode ->
                if (episode.demo != demo) return@atomically false
            }
        }
        val snapshot = reading?.let {
            AlarmReadingSnapshot(
                glucoseMgDl = it.glucoseMgDl,
                sensorTimeEpochMs = it.sensorTimeEpochMs,
                phoneTimeEpochMs = it.phoneTimeEpochMs,
            )
        }
        val transition = AlarmEpisodePolicy.transition(
            previous = previous,
            activeKinds = changes.active,
            newlyOpenedKinds = changes.opened,
            nowEpochMs = nowEpochMs,
            snapshot = snapshot,
            demo = demo,
            nextEpisodeId = UUID.randomUUID().toString(),
        )
        val transitionedEpisode = transition.episode
        if (transitionedEpisode == null) {
            if (!alarmEpisodeStore.clear()) return@atomically false
            activeAlarmEpisodeId = null
            val repeatCancelled = alarmRepeatScheduler.cancel()
            val notificationCancelled = notifier.cancelAllAlarms()
            return@atomically repeatCancelled && notificationCancelled
        }
        var durableEpisode = if (transition.alertNow) {
            AlarmEpisodePolicy.markDeliveryPending(
                transitionedEpisode,
                nowEpochMs,
                AlarmRepeatScheduler.REPEAT_INTERVAL_MS,
            )
        } else {
            transitionedEpisode
        }
        val recoveryPrearmed = if (transition.alertNow && !durableEpisode.acknowledged) {
            alarmRepeatScheduler.scheduleReadinessRetryWithEmergencyFallback(
                durableEpisode,
                nowEpochMs,
            )
        } else {
            true
        }
        if (!alarmEpisodeStore.save(durableEpisode)) return@atomically false
        activeAlarmEpisodeId = durableEpisode.id
        val shown = notifier.showEpisode(durableEpisode, alert = transition.alertNow)
        val followUpScheduled = if (durableEpisode.acknowledged) {
            alarmRepeatScheduler.cancel()
        } else if (transition.alertNow && shown) {
            val delivered = AlarmEpisodePolicy.markAlerted(durableEpisode, nowEpochMs)
            if (alarmEpisodeStore.save(delivered)) {
                durableEpisode = delivered
                activeAlarmEpisodeId = delivered.id
            } else {
                reportAlarmDeliveryFailure(this@SensorForegroundService, ALARM_MARK_FAILED_MESSAGE)
            }
            recoveryPrearmed || alarmRepeatScheduler.scheduleWithEmergencyFallback(
                durableEpisode,
                nowEpochMs,
            )
        } else if (transition.alertNow) {
            recoveryPrearmed
        } else if (transition.rescheduleRepeat) {
            alarmRepeatScheduler.scheduleWithEmergencyFallback(durableEpisode, nowEpochMs)
        } else {
            true
        }
        if (!shown) {
            reportAlarmDeliveryFailure(this@SensorForegroundService, ALARM_SHOW_FAILED_MESSAGE)
        }
        if (!followUpScheduled) {
            reportAlarmDeliveryFailure(this@SensorForegroundService, ALARM_REPEAT_FAILED_MESSAGE)
        }
        if (
            AlarmKind.SIGNAL_LOSS in durableEpisode.activeKinds &&
            (durableEpisode.acknowledged || followUpScheduled)
        ) {
            val watchdogCleared = signalLossWatchdogStore.clear()
            val watchdogCancelled = signalLossWatchdogScheduler.cancel()
            if (!watchdogCleared || !watchdogCancelled) {
                reportAlarmDeliveryFailure(
                    this@SensorForegroundService,
                    SIGNAL_LOSS_WATCHDOG_CLEAR_FAILED_MESSAGE,
                )
            }
        }
        true
    }

    private suspend fun stopAllSessionsLocked(
        demoStopReason: AlarmEpisodeStopReason = AlarmEpisodeStopReason.MODE_SWITCH,
        releaseDemoStartClaim: Boolean = true,
    ) {
        stopDemoSessionLocked(demoStopReason, releaseDemoStartClaim)
        stopDiagnosticSessionLocked()
    }

    private suspend fun stopDemoSessionLocked(
        stopReason: AlarmEpisodeStopReason,
        releaseDemoStartClaim: Boolean,
    ) {
        val hadActiveDemo = activeDemoSideEffectGeneration != null || demoDriver != null
        collectionJob?.cancelAndJoin()
        collectionJob = null
        demoSessionGate.invalidate()
        if (releaseDemoStartClaim) demoStartRequestGate.release()
        activeDemoSideEffectGeneration = null
        activeDemoStateGeneration = null
        if (hadActiveDemo && AlarmEpisodeStopPolicy.clearDemoEpisode(stopReason)) {
            clearPersistedDemoAlarmEpisode()
        }
        monitoringStartedAtEpochMs = System.currentTimeMillis()
        synchronized(alarmLock) {
            alarms = AlarmPolicy(
                monitoringStartedAtEpochMs = monitoringStartedAtEpochMs,
            )
        }
        val driver = demoDriver
        demoDriver = null
        driver?.stop()
    }

    private fun clearPersistedDemoAlarmEpisode() {
        alarmEpisodeStore.atomically {
            val shouldClear = when (val loaded = alarmEpisodeStore.load()) {
                AlarmEpisodeLoadResult.Empty -> false
                AlarmEpisodeLoadResult.Corrupt -> true
                is AlarmEpisodeLoadResult.Active -> loaded.episode.demo
            }
            if (shouldClear) alarmEpisodeStore.clear()
            val shouldClearWatchdog = when (val loaded = signalLossWatchdogStore.load()) {
                SignalLossWatchdogLoadResult.Empty -> false
                SignalLossWatchdogLoadResult.Corrupt -> true
                is SignalLossWatchdogLoadResult.Active -> loaded.state.demo
            }
            if (shouldClearWatchdog) {
                signalLossWatchdogStore.clear()
                signalLossWatchdogScheduler.cancel()
            }
            activeAlarmEpisodeId = null
            alarmRepeatScheduler.cancel()
            notifier.cancelAllAlarms()
        }
    }

    private suspend fun stopDiagnosticSessionLocked() {
        diagnosticCollectionJob?.cancelAndJoin()
        diagnosticCollectionJob = null
        activeDiagnosticStateGeneration = null
        val driver = diagnosticDriver
        diagnosticDriver = null
        driver?.stop()
    }

    override fun onDestroy() {
        unregisterActiveInstance(this)
        destroyed.set(true)
        sessionRequestGate.invalidate()
        collectionJob?.cancel()
        diagnosticCollectionJob?.cancel()
        diagnosticDriver?.requestStop()
        scope.cancel()
        sessionTransitionScope.launch {
            sessionTransitionMutex.withLock {
                stopAllSessionsLocked(AlarmEpisodeStopReason.PROCESS_DESTROYED)
            }
            sessionTransitionScope.cancel()
        }
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun reloadAlarmSettingsForActiveSession(): Boolean {
        val sideEffectGeneration = activeDemoSideEffectGeneration ?: run {
            return false
        }
        val stateGeneration = activeDemoStateGeneration ?: return false
        return demoSessionGate.runIfCurrent(sideEffectGeneration) {
            if (!ensureDemoAlarmMonitoringReady()) return@runIfCurrent
            val state = AppState.state.value
            val latest = state.latest
            val nowEpochMs = System.currentTimeMillis()
            val latestValid = state.history.asSequence()
                .filter { it.quality == ReadingQuality.VALID }
                .maxByOrNull { minOf(it.sensorTimeEpochMs, it.phoneTimeEpochMs) }
            val thresholds = AlarmSettingsStore(this).load().thresholds
            val result = synchronized(alarmLock) {
                AlarmSettingsReloadPolicy.replace(
                    previousActive = state.activeAlarms,
                    latest = latest,
                    latestValidSensorTimeEpochMs = latestValid?.sensorTimeEpochMs ?: 0L,
                    latestValidPhoneTimeEpochMs = latestValid?.phoneTimeEpochMs ?: 0L,
                    thresholds = thresholds,
                    monitoringStartedAtEpochMs = monitoringStartedAtEpochMs,
                    nowEpochMs = nowEpochMs,
                ).also { replacement -> alarms = replacement.policy }
            }
            val changes = result.changes
            if (AppState.onDemoAlarmState(stateGeneration, changes.active)) {
                val persisted = alarmEpisodeStore.atomically {
                    rearmSignalLossWatchdogForSettings(
                        staleAfterMs = thresholds.staleAfterMs,
                        demo = true,
                        nowEpochMs = nowEpochMs,
                    ) && applyAlarmChanges(changes, latest, demo = true, nowEpochMs)
                }
                if (!persisted) {
                    failClosed(ALARM_EPISODE_PERSISTENCE_FAILED_MESSAGE)
                } else if (AlarmKind.SIGNAL_LOSS in changes.active) {
                    com.sladkaya.app.widget.GlucoseWidgetProvider.showNoFreshData(
                        this,
                        demoActive = true,
                    )
                } else if (latest != null) {
                    com.sladkaya.app.widget.GlucoseWidgetProvider.updateDemoAll(this, latest)
                }
            }
        }
    }

    private fun acknowledgeActiveAlarmsForCurrentSession(): Boolean {
        val episodeId = activeAlarmEpisodeId ?: return false
        return alarmEpisodeStore.atomically {
            when (val result = alarmEpisodeStore.acknowledge(episodeId)) {
                is AlarmEpisodeStoreAcknowledgement.Accepted -> {
                    activeAlarmEpisodeId = result.episode.id
                    alarmRepeatScheduler.cancel()
                    notifier.showEpisode(result.episode, alert = false)
                }
                AlarmEpisodeStoreAcknowledgement.Corrupt,
                AlarmEpisodeStoreAcknowledgement.Missing,
                AlarmEpisodeStoreAcknowledgement.PersistenceFailed,
                AlarmEpisodeStoreAcknowledgement.Stale,
                -> false
            }
        }
    }

    private fun onAlarmEpisodeAcknowledgedFromNotification(episodeId: String) {
        if (activeAlarmEpisodeId == episodeId) {
            activeAlarmEpisodeId = episodeId
        }
    }

    private fun isAlarmEpisodeLiveForCurrentDemo(episodeId: String): Boolean =
        !destroyed.get() &&
            activeDemoSideEffectGeneration != null &&
            activeAlarmEpisodeId == episodeId

    private fun isDemoSessionLiveForCurrentProcess(): Boolean =
        !destroyed.get() && activeDemoSideEffectGeneration != null

    private fun onSignalLossEpisodeOpenedForCurrentSession(episode: AlarmEpisode) {
        if (episode.demo) {
            val generation = activeDemoStateGeneration ?: return
            if (!isDemoSessionLiveForCurrentProcess()) return
            activeAlarmEpisodeId = episode.id
            AppState.onDemoAlarmState(generation, episode.activeKinds)
        } else {
            activeAlarmEpisodeId = episode.id
            AppState.onAlarmState(episode.activeKinds)
        }
    }

    companion object {
        private const val FOREGROUND_ID = 3_001
        private const val SETUP_REQUIRED_MESSAGE = "Требуется настройка датчика"
        private const val PERMISSION_REQUIRED_MESSAGE =
            "Нужен доступ к Bluetooth для безопасного запуска датчика"
        private const val SERVICE_START_BLOCKED_MESSAGE =
            "Android не разрешил запустить получение данных"
        private const val DIAGNOSTIC_RESUME_STATE_FAILED_MESSAGE =
            "Не удалось безопасно сохранить состояние диагностического запуска"
        private const val ALARM_EPISODE_RECOVERY_FAILED_MESSAGE =
            "Не удалось безопасно восстановить состояние локальной тревоги"
        private const val ALARM_EPISODE_CONFLICT_MESSAGE =
            "Активная локальная тревога относится к другому режиму"
        private const val ALARM_EPISODE_PERSISTENCE_FAILED_MESSAGE =
            "Не удалось сохранить состояние локальной тревоги"
        private const val ALARM_MARK_FAILED_MESSAGE =
            "Не удалось подтвердить сохранение доставленной тревоги"
        private const val ALARM_SHOW_FAILED_MESSAGE =
            "Android не подтвердил показ локальной тревоги"
        private const val ALARM_REPEAT_FAILED_MESSAGE =
            "Android не подтвердил следующий повтор локальной тревоги"
        private const val SIGNAL_LOSS_WATCHDOG_CLEAR_FAILED_MESSAGE =
            "Не удалось закрыть завершённый контроль свежести"
        private const val SIGNAL_LOSS_OLD_SLOT_CANCEL_FAILED_MESSAGE =
            "Android оставил резерв предыдущего контроля свежести"
        private const val CONFIGURED_SENSOR_NOT_CONNECTED_MESSAGE =
            "Подтверждённый датчик ещё не подключён к продуктовому потоку"

        private val activeInstanceLock = Any()
        private var activeInstance: WeakReference<SensorForegroundService>? = null

        fun start(context: Context): Boolean {
            if (!clearDiagnosticResumeState(context)) return false
            return startIfPermitted(context, SensorServiceActions.START)
        }

        fun startDemo(context: Context): Boolean {
            if (!clearDiagnosticResumeState(context)) return false
            return startIfPermitted(context, SensorServiceActions.START_DEMO)
        }

        fun startDiagnostic(context: Context): Boolean {
            val profile = PendingDiagnosticGs1OnboardingStateStore(context)
                .loadPendingDiagnosticProfile()
                ?: return false
            val fingerprint = DiagnosticResumeIdentity.fingerprint(profile)
            if (!DiagnosticSessionPreferenceStore(context).markRunning(fingerprint)) {
                showDiagnosticResumeStateFailure(context)
                return false
            }
            return startIfPermitted(context, SensorServiceActions.START_DIAGNOSTIC).also { started ->
                if (!started) DiagnosticSessionPreferenceStore(context).clear()
            }
        }

        fun stop(context: Context): Boolean {
            val cleared = clearDiagnosticResumeState(context)
            if (!SensorServiceStopPolicy.canStop(cleared)) return false
            clearPersistedDemoAlarmEpisode(context)
            context.stopService(Intent(context, SensorForegroundService::class.java))
            return true
        }

        private fun clearPersistedDemoAlarmEpisode(context: Context) {
            val store = AlarmEpisodePreferenceStore(context)
            store.atomically {
                val shouldClear = when (val loaded = store.load()) {
                    AlarmEpisodeLoadResult.Empty -> false
                    AlarmEpisodeLoadResult.Corrupt -> true
                    is AlarmEpisodeLoadResult.Active -> loaded.episode.demo
                }
                if (shouldClear) {
                    store.clear()
                    AlarmRepeatScheduler(context).cancel()
                    AlarmNotifier(context).cancelAllAlarms()
                }
                val watchdogStore = SignalLossWatchdogPreferenceStore(context)
                val shouldClearWatchdog = when (val loaded = watchdogStore.load()) {
                    SignalLossWatchdogLoadResult.Empty -> false
                    SignalLossWatchdogLoadResult.Corrupt -> true
                    is SignalLossWatchdogLoadResult.Active -> loaded.state.demo
                }
                if (shouldClearWatchdog) {
                    watchdogStore.clear()
                    SignalLossWatchdogScheduler(context).cancel()
                }
            }
        }

        private fun clearDiagnosticResumeState(context: Context): Boolean {
            if (DiagnosticSessionPreferenceStore(context).clear()) return true
            showDiagnosticResumeStateFailure(context)
            return false
        }

        private fun showDiagnosticResumeStateFailure(context: Context) {
            AppState.onSetupRequired(DIAGNOSTIC_RESUME_STATE_FAILED_MESSAGE)
            com.sladkaya.app.widget.GlucoseWidgetProvider.showSetupRequired(context)
        }

        fun reloadAlarmSettings(): Boolean {
            val instance = synchronized(activeInstanceLock) { activeInstance?.get() }
                ?: return false
            return instance.reloadAlarmSettingsForActiveSession()
        }

        fun acknowledgeActiveAlarms(): Boolean {
            val instance = synchronized(activeInstanceLock) { activeInstance?.get() }
                ?: return false
            return instance.acknowledgeActiveAlarmsForCurrentSession()
        }

        internal fun onAlarmEpisodeAcknowledged(episodeId: String) {
            val instance = synchronized(activeInstanceLock) { activeInstance?.get() }
                ?: return
            instance.onAlarmEpisodeAcknowledgedFromNotification(episodeId)
        }

        internal fun isAlarmEpisodeLive(episodeId: String): Boolean {
            val instance = synchronized(activeInstanceLock) { activeInstance?.get() }
                ?: return false
            return instance.isAlarmEpisodeLiveForCurrentDemo(episodeId)
        }

        internal fun isDemoSessionLive(): Boolean {
            val instance = synchronized(activeInstanceLock) { activeInstance?.get() }
                ?: return false
            return instance.isDemoSessionLiveForCurrentProcess()
        }

        internal fun onSignalLossEpisodeOpened(episode: AlarmEpisode) {
            val instance = synchronized(activeInstanceLock) { activeInstance?.get() }
            if (instance != null) {
                instance.onSignalLossEpisodeOpenedForCurrentSession(episode)
            } else if (!episode.demo) {
                AppState.onAlarmState(episode.activeKinds)
            }
        }

        private fun startIfPermitted(context: Context, action: String): Boolean {
            if (!RequiredPermissionPolicy.hasMandatoryBlePermissions(context)) {
                AppState.onSetupRequired(PERMISSION_REQUIRED_MESSAGE)
                com.sladkaya.app.widget.GlucoseWidgetProvider.showSetupRequired(context)
                return false
            }
            return runCatching {
                ContextCompat.startForegroundService(
                    context,
                    Intent(context, SensorForegroundService::class.java).setAction(action),
                )
            }.fold(
                onSuccess = { true },
                onFailure = {
                    AppState.onSetupRequired(SERVICE_START_BLOCKED_MESSAGE)
                    com.sladkaya.app.widget.GlucoseWidgetProvider.showSetupRequired(context)
                    false
                },
            )
        }

        private fun registerActiveInstance(instance: SensorForegroundService) {
            synchronized(activeInstanceLock) {
                activeInstance = WeakReference(instance)
            }
        }

        private fun unregisterActiveInstance(instance: SensorForegroundService) {
            synchronized(activeInstanceLock) {
                if (activeInstance?.get() === instance) {
                    activeInstance?.clear()
                    activeInstance = null
                }
            }
        }
    }
}
