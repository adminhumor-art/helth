package com.sladkaya.app.service

import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import androidx.core.content.ContextCompat
import com.sladkaya.app.AppState
import com.sladkaya.core.model.AlarmPolicy
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

class SensorForegroundService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val startPolicy = SensorServiceStartPolicy()
    private val demoSessionGate = DemoSessionGate()
    private var demoDriver: SimulatorDriver? = null
    private var alarms = AlarmPolicy()
    private lateinit var notifier: AlarmNotifier
    private var collectionJob: Job? = null

    override fun onCreate() {
        super.onCreate()
        notifier = AlarmNotifier(this).also { it.createChannels() }
        startForeground(FOREGROUND_ID, notifier.foregroundStatus("Проверка настройки датчика"))
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
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
        alarms = AlarmPolicy()
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
                        val changes = alarms.evaluate(reading)
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
                        val changes = alarms.evaluateFreshness(System.currentTimeMillis())
                        if (AppState.onDemoAlarmState(stateGeneration, changes.active)) {
                            changes.opened.forEach {
                                notifier.show(it, AppState.state.value.latest, demo = true)
                            }
                            changes.closed.forEach(notifier::cancel)
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
        notifier.cancelAllAlarms()
        alarms = AlarmPolicy()
        val driver = demoDriver
        demoDriver = null
        if (driver != null) {
            scope.launch(start = CoroutineStart.UNDISPATCHED) { driver.stop() }
        }
    }

    override fun onDestroy() {
        stopDemoSession()
        scope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        private const val FOREGROUND_ID = 3_001
        private const val SETUP_REQUIRED_MESSAGE = "Требуется настройка датчика"
        private const val CONFIGURED_SENSOR_NOT_CONNECTED_MESSAGE =
            "Подтверждённый датчик ещё не подключён к продуктовому потоку"

        fun start(context: Context) {
            ContextCompat.startForegroundService(
                context,
                Intent(context, SensorForegroundService::class.java)
                    .setAction(SensorServiceActions.START),
            )
        }

        fun startDemo(context: Context) {
            ContextCompat.startForegroundService(
                context,
                Intent(context, SensorForegroundService::class.java)
                    .setAction(SensorServiceActions.START_DEMO),
            )
        }
    }
}
