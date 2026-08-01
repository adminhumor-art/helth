package com.sladkaya.app.service

import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import android.os.SystemClock
import androidx.core.content.ContextCompat
import com.sladkaya.app.AppState
import com.sladkaya.app.RequiredPermissionPolicy
import com.sladkaya.app.settings.AlarmSettingsStore
import com.sladkaya.core.model.AlarmKind
import com.sladkaya.core.model.AlarmPolicy
import com.sladkaya.core.model.ReadingQuality
import com.sladkaya.core.sensor.SensorConfiguration
import com.sladkaya.sensor.simulator.SimulationScenario
import com.sladkaya.sensor.simulator.SimulatorDriver
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.launch
import java.lang.ref.WeakReference

class SensorForegroundService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val startPolicy = SensorServiceStartPolicy()
    private val demoSessionGate = DemoSessionGate()
    private val alarmLock = Any()
    private var demoDriver: SimulatorDriver? = null
    private var monitoringStartedAtEpochMs = System.currentTimeMillis()
    private var alarms = AlarmPolicy(
        monitoringStartedAtEpochMs = monitoringStartedAtEpochMs,
    )
    private var alarmReminders = AlarmReminderPolicy()
    private var activeDemoSideEffectGeneration: Long? = null
    private var activeDemoStateGeneration: Long? = null
    private lateinit var notifier: AlarmNotifier
    private var collectionJob: Job? = null
    private var foregroundStarted = false

    override fun onCreate() {
        super.onCreate()
        notifier = AlarmNotifier(this).also { it.createChannels() }
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
        val mode = startPolicy.select(
            action = intent?.action,
            hasConfirmedConfiguration = ConfirmedSensorConfigurationStore(this)
                .hasConfirmedConfiguration(),
        )
        when (mode) {
            SensorServiceStartMode.Demo -> startDemoSession()
            SensorServiceStartMode.SetupRequired -> failClosed(
                message = SETUP_REQUIRED_MESSAGE,
                startId = startId,
            )
            SensorServiceStartMode.ConfiguredSensor -> failClosed(
                message = CONFIGURED_SENSOR_NOT_CONNECTED_MESSAGE,
                startId = startId,
            )
        }
        return START_NOT_STICKY
    }

    private fun startDemoSession() {
        stopDemoSession()
        val sideEffectGeneration = demoSessionGate.activate()
        val stateGeneration = AppState.onDemoStarting()
        activeDemoSideEffectGeneration = sideEffectGeneration
        activeDemoStateGeneration = stateGeneration
        com.sladkaya.app.widget.GlucoseWidgetProvider.showDemoWaiting(this)
        monitoringStartedAtEpochMs = System.currentTimeMillis()
        synchronized(alarmLock) {
            alarms = AlarmPolicy(
                thresholds = AlarmSettingsStore(this).load().thresholds,
                monitoringStartedAtEpochMs = monitoringStartedAtEpochMs,
            )
            alarmReminders = AlarmReminderPolicy()
        }
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
                        val nowElapsedMs = SystemClock.elapsedRealtime()
                        val changes = synchronized(alarmLock) {
                            alarms.evaluate(reading, System.currentTimeMillis()).also { change ->
                                alarmReminders.onOpened(change.opened, nowElapsedMs)
                                alarmReminders.onClosed(change.closed)
                            }
                        }
                        if (AppState.onDemoReading(stateGeneration, reading, changes.active)) {
                            changes.opened.forEach { notifier.show(it, reading, demo = true) }
                            changes.closed.forEach(notifier::cancel)
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
                        val nowEpochMs = System.currentTimeMillis()
                        val nowElapsedMs = SystemClock.elapsedRealtime()
                        val (changes, remindersDue) = synchronized(alarmLock) {
                            val change = alarms.evaluateFreshness(nowEpochMs).also { evaluated ->
                                alarmReminders.onOpened(evaluated.opened, nowElapsedMs)
                                alarmReminders.onClosed(evaluated.closed)
                            }
                            val due = alarmReminders.due(change.active, nowElapsedMs)
                            alarmReminders.markSent(due, nowElapsedMs)
                            change to due
                        }
                        if (AppState.onDemoAlarmState(stateGeneration, changes.active)) {
                            changes.opened.forEach {
                                notifier.show(it, AppState.state.value.latest, demo = true)
                            }
                            remindersDue.forEach {
                                notifier.show(it, AppState.state.value.latest, demo = true)
                            }
                            changes.closed.forEach(notifier::cancel)
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

    private fun failClosed(message: String, startId: Int) {
        stopDemoSession()
        AppState.onSetupRequired(message)
        com.sladkaya.app.widget.GlucoseWidgetProvider.showSetupRequired(this)
        getSystemService(android.app.NotificationManager::class.java)
            .notify(FOREGROUND_ID, notifier.foregroundStatus(message))
        stopSelf(startId)
    }

    private fun stopDemoSession() {
        collectionJob?.cancel()
        collectionJob = null
        demoSessionGate.invalidate()
        activeDemoSideEffectGeneration = null
        activeDemoStateGeneration = null
        notifier.cancelAllAlarms()
        monitoringStartedAtEpochMs = System.currentTimeMillis()
        synchronized(alarmLock) {
            alarms = AlarmPolicy(
                monitoringStartedAtEpochMs = monitoringStartedAtEpochMs,
            )
            alarmReminders = AlarmReminderPolicy()
        }
        val driver = demoDriver
        demoDriver = null
        if (driver != null) {
            scope.launch(start = CoroutineStart.UNDISPATCHED) { driver.stop() }
        }
    }

    override fun onDestroy() {
        unregisterActiveInstance(this)
        stopDemoSession()
        scope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun reloadAlarmSettingsForActiveSession(): Boolean {
        val sideEffectGeneration = activeDemoSideEffectGeneration ?: run {
            return false
        }
        val stateGeneration = activeDemoStateGeneration ?: return false
        return demoSessionGate.runIfCurrent(sideEffectGeneration) {
            val state = AppState.state.value
            val latest = state.latest
            val nowEpochMs = System.currentTimeMillis()
            val nowElapsedMs = SystemClock.elapsedRealtime()
            val latestValid = state.history.asSequence()
                .filter { it.quality == ReadingQuality.VALID }
                .maxByOrNull { minOf(it.sensorTimeEpochMs, it.phoneTimeEpochMs) }
            val result = synchronized(alarmLock) {
                AlarmSettingsReloadPolicy.replace(
                    previousActive = state.activeAlarms,
                    latest = latest,
                    latestValidSensorTimeEpochMs = latestValid?.sensorTimeEpochMs ?: 0L,
                    latestValidPhoneTimeEpochMs = latestValid?.phoneTimeEpochMs ?: 0L,
                    thresholds = AlarmSettingsStore(this).load().thresholds,
                    monitoringStartedAtEpochMs = monitoringStartedAtEpochMs,
                    nowEpochMs = nowEpochMs,
                ).also { replacement ->
                    alarms = replacement.policy
                    alarmReminders.onOpened(replacement.changes.opened, nowElapsedMs)
                    alarmReminders.onClosed(replacement.changes.closed)
                }
            }
            val changes = result.changes
            if (AppState.onDemoAlarmState(stateGeneration, changes.active)) {
                changes.closed.forEach(notifier::cancel)
                changes.opened.forEach { notifier.show(it, latest, demo = true) }
                if (AlarmKind.SIGNAL_LOSS in changes.active) {
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
        val active = synchronized(alarmLock) {
            val current = AppState.state.value.activeAlarms
            if (current.isNotEmpty()) alarmReminders.acknowledge(current)
            current
        }
        active.forEach(notifier::cancel)
        return active.isNotEmpty()
    }

    companion object {
        private const val FOREGROUND_ID = 3_001
        private const val SETUP_REQUIRED_MESSAGE = "Требуется настройка датчика"
        private const val PERMISSION_REQUIRED_MESSAGE =
            "Нужен доступ к Bluetooth для безопасного запуска датчика"
        private const val SERVICE_START_BLOCKED_MESSAGE =
            "Android не разрешил запустить получение данных"
        private const val CONFIGURED_SENSOR_NOT_CONNECTED_MESSAGE =
            "Подтверждённый датчик ещё не подключён к продуктовому потоку"

        private val activeInstanceLock = Any()
        private var activeInstance: WeakReference<SensorForegroundService>? = null

        fun start(context: Context): Boolean = startIfPermitted(
            context = context,
            action = SensorServiceActions.START,
        )

        fun startDemo(context: Context): Boolean = startIfPermitted(
            context = context,
            action = SensorServiceActions.START_DEMO,
        )

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
