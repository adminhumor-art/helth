package com.sladkaya.app

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.sladkaya.app.service.AlarmNotifier
import com.sladkaya.app.service.SensorForegroundService
import com.sladkaya.app.settings.AlarmSettingsScreen
import com.sladkaya.app.settings.AlarmSettingsStore
import com.sladkaya.app.settings.LoadedAlarmSettings
import com.sladkaya.core.model.AlarmKind
import com.sladkaya.core.model.AlarmThresholds
import com.sladkaya.core.model.GlucoseReading
import com.sladkaya.core.sensor.SensorDriverState
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.delay

private val Forest = Color(0xFF176B57)
private val Ink = Color(0xFF162A27)
private val Muted = Color(0xFF637672)
private val Paper = Color(0xFFF5F7F3)
private val Danger = Color(0xFFD34949)
private val DangerSoft = Color(0xFFFFF0ED)
private val MintSoft = Color(0xFFDCEEE7)
private val Warning = Color(0xFF8A6200)
private val WarningSoft = Color(0xFFFFF3CF)

private enum class RequestedServiceStart {
    Sensor,
    Demo,
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        AlarmNotifier(this).createChannels()
        setContent {
            MaterialTheme {
                var permissionRevision by remember { mutableIntStateOf(0) }
                var pendingStart by remember { mutableStateOf<RequestedServiceStart?>(null) }
                fun startPermitted(mode: RequestedServiceStart) {
                    when (mode) {
                        RequestedServiceStart.Sensor -> SensorForegroundService.start(this@MainActivity)
                        RequestedServiceStart.Demo -> SensorForegroundService.startDemo(this@MainActivity)
                    }
                }
                fun showPermissionRequired() {
                    AppState.onSetupRequired("Нужен доступ к Bluetooth для получения данных")
                    com.sladkaya.app.widget.GlucoseWidgetProvider.showSetupRequired(this@MainActivity)
                }
                val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
                    permissionRevision += 1
                    val requested = pendingStart
                    pendingStart = null
                    if (requested != null) {
                        if (RequiredPermissionPolicy.hasMandatoryBlePermissions(this@MainActivity)) {
                            startPermitted(requested)
                        } else {
                            showPermissionRequired()
                        }
                    }
                }
                fun requestStart(mode: RequestedServiceStart) {
                    val missing = RequiredPermissionPolicy.missingPermissions(this@MainActivity)
                    if (RequiredPermissionPolicy.hasMandatoryBlePermissions(this@MainActivity)) {
                        startPermitted(mode)
                        if (missing.isNotEmpty()) launcher.launch(missing.toTypedArray())
                    } else {
                        pendingStart = mode
                        launcher.launch(missing.toTypedArray())
                    }
                }
                LaunchedEffect(Unit) {
                    requestStart(RequestedServiceStart.Sensor)
                }
                SladkayaScreen(
                    onStart = { requestStart(RequestedServiceStart.Sensor) },
                    onStartDemo = { requestStart(RequestedServiceStart.Demo) },
                    permissionRevision = permissionRevision,
                )
            }
        }
    }
}

@Composable
private fun SladkayaScreen(
    onStart: () -> Unit,
    onStartDemo: () -> Unit,
    permissionRevision: Int,
) {
    val context = LocalContext.current
    val state by AppState.state.collectAsStateWithLifecycle()
    val settingsStore = remember { AlarmSettingsStore(context) }
    val alarmNotifier = remember { AlarmNotifier(context).also(AlarmNotifier::createChannels) }
    var loadedSettings by remember { mutableStateOf(settingsStore.load()) }
    var settingsOpen by remember { mutableStateOf(false) }
    val nowEpochMs by produceState(System.currentTimeMillis()) {
        while (true) {
            delay(30_000L)
            value = System.currentTimeMillis()
        }
    }
    val freshness = ReadingFreshnessPolicy.evaluate(
        latest = state.latest,
        nowEpochMs = nowEpochMs,
        staleAfterMs = loadedSettings.thresholds.staleAfterMs,
    )
    val notificationCapability = remember(context, permissionRevision, nowEpochMs) {
        readAlarmNotificationCapability(context)
    }
    val blePermissionsGranted = remember(context, permissionRevision, nowEpochMs) {
        RequiredPermissionPolicy.hasMandatoryBlePermissions(context)
    }
    if (settingsOpen) {
        AlarmSettingsScreen(
            initial = loadedSettings,
            onBack = { settingsOpen = false },
            onSave = { thresholds ->
                if (!settingsStore.save(thresholds)) {
                    false
                } else {
                    loadedSettings = LoadedAlarmSettings(
                        thresholds = thresholds,
                        recoveredFromCorruption = false,
                    )
                    com.sladkaya.app.widget.GlucoseWidgetProvider.refreshAll(context)
                    SensorForegroundService.reloadAlarmSettings()
                    settingsOpen = false
                    true
                }
            },
            onTestAlarm = alarmNotifier::showTest,
            onCancelTestAlarm = alarmNotifier::cancelTest,
        )
        return
    }
    Surface(color = Paper, modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 18.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Header(state, freshness, onSettings = { settingsOpen = true })
            BlePermissionWarning(blePermissionsGranted)
            AlarmNotificationWarning(notificationCapability)
            CurrentGlucoseCard(
                state = state,
                freshness = freshness,
                onAcknowledgeAlarm = SensorForegroundService::acknowledgeActiveAlarms,
            )
            HistoryCard(state.history, loadedSettings.thresholds)
            ConnectionCard(state.driverState, state.simulatorMode, onStart, onStartDemo)
            Text(
                if (state.simulatorMode) {
                    "ДЕМО: используются только тестовые данные. Их нельзя использовать для решений о лечении."
                } else {
                    "Без подтверждённой настройки датчика приложение не показывает значения глюкозы."
                },
                color = Muted, fontSize = 11.sp, lineHeight = 16.sp, textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 6.dp),
            )
        }
    }
}

@Composable
private fun BlePermissionWarning(granted: Boolean) {
    if (granted) return
    val context = LocalContext.current
    Card(
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = DangerSoft),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier.padding(15.dp),
            verticalArrangement = Arrangement.spacedBy(9.dp),
        ) {
            Text(
                "Получение данных остановлено",
                color = Danger,
                fontWeight = FontWeight.Bold,
                fontSize = 13.sp,
            )
            Text(
                "Без разрешения Bluetooth приложение не запускает датчик, демо и локальные тревоги.",
                color = Muted,
                fontSize = 11.sp,
            )
            OutlinedButton(
                onClick = {
                    context.startActivity(
                        Intent(
                            Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                            Uri.parse("package:${context.packageName}"),
                        ),
                    )
                },
                shape = RoundedCornerShape(12.dp),
            ) {
                Text("Открыть разрешения Android", fontSize = 11.sp)
            }
        }
    }
}

@Composable
private fun AlarmNotificationWarning(capability: AlarmNotificationCapability) {
    if (capability == AlarmNotificationCapability.AVAILABLE ||
        capability == AlarmNotificationCapability.INITIALIZING
    ) {
        return
    }
    val context = LocalContext.current
    Card(
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = DangerSoft),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier.padding(15.dp),
            verticalArrangement = Arrangement.spacedBy(9.dp),
        ) {
            Text(
                "Звуковые тревоги заблокированы Android",
                color = Danger,
                fontWeight = FontWeight.Bold,
                fontSize = 13.sp,
            )
            Text(
                "Тревога будет видна в открытом приложении, но телефон может не подать звук.",
                color = Muted,
                fontSize = 11.sp,
            )
            OutlinedButton(
                onClick = {
                    val action = if (capability == AlarmNotificationCapability.BLOCKED_CHANNEL) {
                        Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS
                    } else {
                        Settings.ACTION_APP_NOTIFICATION_SETTINGS
                    }
                    val intent = Intent(action)
                        .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
                    if (capability == AlarmNotificationCapability.BLOCKED_CHANNEL) {
                        intent.putExtra(Settings.EXTRA_CHANNEL_ID, AlarmNotifier.ALARM_CHANNEL)
                    }
                    context.startActivity(intent)
                },
                shape = RoundedCornerShape(12.dp),
            ) {
                Text("Открыть настройки Android", fontSize = 11.sp)
            }
        }
    }
}

@Composable
private fun Header(
    state: GlucoseUiState,
    freshness: ReadingFreshness,
    onSettings: () -> Unit,
) {
    val badge = freshnessBadge(freshness, state.simulatorMode)
    Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.size(39.dp).background(Forest, RoundedCornerShape(13.dp)), contentAlignment = Alignment.Center) {
                Text("С", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 18.sp)
            }
            Spacer(Modifier.width(11.dp))
            Column(Modifier.weight(1f)) {
                Text("Сладкая", color = Ink, fontWeight = FontWeight.Bold, fontSize = 21.sp)
                Text("Лёгкий контроль глюкозы", color = Muted, fontSize = 11.sp)
            }
            OutlinedButton(onClick = onSettings, shape = RoundedCornerShape(12.dp)) {
                Text("Настройки", fontSize = 10.sp)
            }
        }
        Row(
            modifier = Modifier.align(Alignment.End)
                .background(badge.background, RoundedCornerShape(100.dp))
                .padding(horizontal = 10.dp, vertical = 7.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.size(7.dp).background(badge.foreground, CircleShape))
            Spacer(Modifier.width(6.dp))
            Text(badge.label, color = badge.foreground, fontWeight = FontWeight.Bold, fontSize = 11.sp)
        }
    }
}

@Composable
private fun CurrentGlucoseCard(
    state: GlucoseUiState,
    freshness: ReadingFreshness,
    onAcknowledgeAlarm: () -> Boolean,
) {
    val reading = state.latest
    val critical = state.activeAlarms.any { it == AlarmKind.LOW || it == AlarmKind.HIGH }
    var alarmAcknowledged by remember(state.activeAlarms) { mutableStateOf(false) }
    Card(
        shape = RoundedCornerShape(26.dp),
        colors = CardDefaults.cardColors(containerColor = Color.White),
        elevation = CardDefaults.cardElevation(defaultElevation = 5.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(22.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text("Текущее значение", color = Muted, fontSize = 13.sp, fontWeight = FontWeight.Medium)
                Text(sourceBadge(state), color = Forest, fontSize = 9.sp, fontWeight = FontWeight.Bold,
                    modifier = Modifier.background(MintSoft, RoundedCornerShape(20.dp)).padding(horizontal = 9.dp, vertical = 5.dp))
            }
            if (reading == null) {
                Text("—", color = Ink, fontSize = 72.sp, fontWeight = FontWeight.SemiBold)
                Text("Ожидание первого измерения", color = Muted, fontSize = 13.sp)
            } else {
                Row(verticalAlignment = Alignment.Bottom) {
                    Text(formatMmol(reading), color = if (critical) Danger else Ink, fontSize = 74.sp, lineHeight = 74.sp, fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.width(10.dp))
                    Column(Modifier.padding(bottom = 10.dp)) {
                        Text("ммоль/л", color = Muted, fontSize = 12.sp)
                        Text("${reading.glucoseMgDl} мг/дл", color = Muted, fontSize = 11.sp)
                    }
                    Spacer(Modifier.weight(1f))
                    Text(trendArrow(reading.trendMgDlPerMinute), color = if (critical) Danger else Forest, fontSize = 46.sp, modifier = Modifier.padding(bottom = 3.dp))
                }
                Text(
                    "${trendText(reading.trendMgDlPerMinute)} · обновлено ${SimpleDateFormat("HH:mm", Locale.forLanguageTag("ru")).format(Date(reading.sensorTimeEpochMs))}",
                    color = if (critical) Danger else Forest, fontSize = 13.sp, fontWeight = FontWeight.Bold,
                )
                freshnessMessage(freshness)?.let { message ->
                    val warningColor = if (freshness == ReadingFreshness.NOT_READY) Warning else Danger
                    val warningBackground = if (freshness == ReadingFreshness.NOT_READY) WarningSoft else DangerSoft
                    Text(
                        message,
                        color = warningColor,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.fillMaxWidth()
                            .background(warningBackground, RoundedCornerShape(12.dp))
                            .padding(horizontal = 12.dp, vertical = 9.dp),
                    )
                }
            }
            if (state.activeAlarms.isNotEmpty()) {
                Column(
                    modifier = Modifier.fillMaxWidth().background(DangerSoft, RoundedCornerShape(16.dp)).padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("!", color = Color.White, fontWeight = FontWeight.Bold, modifier = Modifier.size(28.dp).background(Danger, CircleShape).padding(top = 3.dp), textAlign = TextAlign.Center)
                        Spacer(Modifier.width(11.dp))
                        Column {
                            Text(alarmTitle(state.activeAlarms), color = Danger, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                            Text("Локальная тревога активна", color = Muted, fontSize = 11.sp)
                        }
                    }
                    OutlinedButton(
                        onClick = { alarmAcknowledged = onAcknowledgeAlarm() },
                        enabled = !alarmAcknowledged,
                        shape = RoundedCornerShape(12.dp),
                    ) {
                        Text(
                            if (alarmAcknowledged) {
                                "Повтор звука остановлен"
                            } else {
                                "Остановить повтор звука"
                            },
                            fontSize = 11.sp,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun HistoryCard(
    history: List<GlucoseReading>,
    thresholds: AlarmThresholds,
) {
    Card(shape = RoundedCornerShape(22.dp), colors = CardDefaults.cardColors(containerColor = Color.White), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(18.dp)) {
            Text("Последние значения", color = Ink, fontWeight = FontWeight.Bold, fontSize = 16.sp)
            Text("Диапазон тревог показан зелёным", color = Muted, fontSize = 10.sp)
            Spacer(Modifier.height(16.dp))
            GlucoseChart(
                history = history.takeLast(48),
                thresholds = thresholds,
                modifier = Modifier.fillMaxWidth().height(190.dp),
            )
        }
    }
}

@Composable
private fun GlucoseChart(
    history: List<GlucoseReading>,
    thresholds: AlarmThresholds,
    modifier: Modifier = Modifier,
) {
    val series = GlucoseChartPolicy.build(history, thresholds)
    val scale = GlucoseChartScalePolicy.build(series, thresholds)
    Canvas(modifier) {
        val min = scale.minMgDl
        val max = scale.maxMgDl
        fun y(value: Float) = size.height - ((value - min) / (max - min)).coerceIn(0f, 1f) * size.height
        val highY = y(thresholds.highMgDl.toFloat())
        val lowY = y(thresholds.lowMgDl.toFloat())
        drawRect(
            color = MintSoft.copy(alpha = 0.7f),
            topLeft = Offset(0f, highY),
            size = androidx.compose.ui.geometry.Size(size.width, lowY - highY),
        )
        (1..4).map { index -> min + (max - min) * index / 5f }.forEach { tick ->
            drawLine(color = Muted.copy(alpha = 0.14f), start = Offset(0f, y(tick)), end = Offset(size.width, y(tick)), strokeWidth = 1.dp.toPx())
        }
        if (series.points.isNotEmpty()) {
            series.connections.forEach { connection ->
                val first = series.points[connection.fromIndex]
                val second = series.points[connection.toIndex]
                drawLine(
                    color = if (second.outsideAlarmRange) Danger else Forest,
                    start = Offset(first.xFraction * size.width, y(first.reading.glucoseMgDl.toFloat())),
                    end = Offset(second.xFraction * size.width, y(second.reading.glucoseMgDl.toFloat())),
                    strokeWidth = 3.dp.toPx(), cap = StrokeCap.Round,
                )
            }
            series.points.forEach { point ->
                drawCircle(
                    color = if (point.outsideAlarmRange) Danger else Forest,
                    radius = 2.dp.toPx(),
                    center = Offset(point.xFraction * size.width, y(point.reading.glucoseMgDl.toFloat())),
                )
            }
            val last = series.points.last()
            drawCircle(
                color = if (last.outsideAlarmRange) Danger else Forest,
                radius = 5.dp.toPx(),
                center = Offset(last.xFraction * size.width, y(last.reading.glucoseMgDl.toFloat())),
            )
            drawCircle(
                color = Color.White,
                radius = 5.dp.toPx(),
                center = Offset(last.xFraction * size.width, y(last.reading.glucoseMgDl.toFloat())),
                style = Stroke(2.dp.toPx()),
            )
        }
    }
}

private data class FreshnessBadge(
    val label: String,
    val foreground: Color,
    val background: Color,
)

private fun freshnessBadge(freshness: ReadingFreshness, simulatorMode: Boolean): FreshnessBadge =
    when (freshness) {
        ReadingFreshness.MISSING -> FreshnessBadge("Нет данных", Muted, Color.White)
        ReadingFreshness.FRESH -> FreshnessBadge(
            if (simulatorMode) "Демо" else "Данные свежие",
            Forest,
            MintSoft,
        )
        ReadingFreshness.NOT_READY -> FreshnessBadge("Датчик готовится", Warning, WarningSoft)
        ReadingFreshness.STALE -> FreshnessBadge("Данные устарели", Danger, DangerSoft)
        ReadingFreshness.CLOCK_MISMATCH -> FreshnessBadge("Проверьте время", Danger, DangerSoft)
    }

private fun freshnessMessage(freshness: ReadingFreshness): String? = when (freshness) {
    ReadingFreshness.NOT_READY -> "Датчик ещё не выдал готовое значение для тревог"
    ReadingFreshness.STALE -> "Последние данные устарели — проверьте датчик и телефон"
    ReadingFreshness.CLOCK_MISMATCH -> "Время измерения не совпадает со временем телефона"
    ReadingFreshness.MISSING,
    ReadingFreshness.FRESH,
    -> null
}

@Composable
private fun ConnectionCard(
    driverState: SensorDriverState,
    simulatorMode: Boolean,
    onStart: () -> Unit,
    onStartDemo: () -> Unit,
) {
    Card(shape = RoundedCornerShape(22.dp), colors = CardDefaults.cardColors(containerColor = Color.White), modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.padding(18.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(42.dp).background(MintSoft, RoundedCornerShape(14.dp)), contentAlignment = Alignment.Center) {
                Text("◉", color = Forest, fontSize = 20.sp)
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text("Источник данных", color = Ink, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                Text(driverState.label(simulatorMode), color = Muted, fontSize = 11.sp)
            }
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Button(onClick = onStart, colors = ButtonDefaults.buttonColors(containerColor = Forest), shape = RoundedCornerShape(12.dp)) {
                    Text("Проверить", fontSize = 10.sp)
                }
                OutlinedButton(onClick = onStartDemo, shape = RoundedCornerShape(12.dp)) {
                    Text("Демо", fontSize = 10.sp)
                }
            }
        }
    }
}

private fun formatMmol(reading: GlucoseReading): String = String.format(Locale.forLanguageTag("ru"), "%.1f", reading.glucoseMmolL)

private fun sourceBadge(state: GlucoseUiState): String = when {
    state.simulatorMode || state.latest?.sensorFamily == com.sladkaya.core.model.SensorFamily.SIMULATOR -> "ДЕМО"
    state.latest != null -> "ДАТЧИК"
    else -> "НЕТ ДАННЫХ"
}

private fun trendArrow(rate: Double): String = when {
    rate <= -3 -> "↓↓"
    rate <= -1 -> "↓"
    rate >= 3 -> "↑↑"
    rate >= 1 -> "↑"
    else -> "→"
}

private fun trendText(rate: Double): String = when {
    rate <= -3 -> "Быстро снижается"
    rate <= -1 -> "Снижается"
    rate >= 3 -> "Быстро повышается"
    rate >= 1 -> "Повышается"
    else -> "Стабильно"
}

private fun alarmTitle(alarms: Set<AlarmKind>): String = when {
    AlarmKind.LOW in alarms -> "Низкое значение"
    AlarmKind.HIGH in alarms -> "Высокое значение"
    AlarmKind.RAPID_FALL in alarms -> "Быстрое снижение"
    AlarmKind.RAPID_RISE in alarms -> "Быстрый рост"
    else -> "Нет свежих данных"
}

private fun SensorDriverState.label(simulatorMode: Boolean): String = when (this) {
    SensorDriverState.Idle -> "Остановлен"
    SensorDriverState.Scanning -> "Поиск датчика"
    is SensorDriverState.Connecting -> "Подключение к ${deviceName ?: "датчику"}"
    SensorDriverState.Authenticating -> "Авторизация"
    SensorDriverState.Streaming -> if (simulatorMode) "Демо-поток активен" else "Поток данных активен"
    is SensorDriverState.WaitingForData -> "Ожидание данных"
    is SensorDriverState.Failure -> message
}
