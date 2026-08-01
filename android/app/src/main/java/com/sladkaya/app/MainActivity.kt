package com.sladkaya.app

import android.Manifest
import android.os.Build
import android.os.Bundle
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
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.sladkaya.app.service.SensorForegroundService
import com.sladkaya.core.model.AlarmKind
import com.sladkaya.core.model.GlucoseReading
import com.sladkaya.core.sensor.SensorDriverState
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val Forest = Color(0xFF176B57)
private val Ink = Color(0xFF162A27)
private val Muted = Color(0xFF637672)
private val Paper = Color(0xFFF5F7F3)
private val Danger = Color(0xFFD34949)
private val DangerSoft = Color(0xFFFFF0ED)
private val MintSoft = Color(0xFFDCEEE7)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { }
                LaunchedEffect(Unit) {
                    launcher.launch(requiredPermissions())
                    SensorForegroundService.start(this@MainActivity)
                }
                SladkayaScreen(
                    onStart = { SensorForegroundService.start(this@MainActivity) },
                    onStartDemo = { SensorForegroundService.startDemo(this@MainActivity) },
                )
            }
        }
    }

    private fun requiredPermissions(): Array<String> = buildList {
        if (Build.VERSION.SDK_INT >= 31) {
            add(Manifest.permission.BLUETOOTH_SCAN)
            add(Manifest.permission.BLUETOOTH_CONNECT)
        }
        if (Build.VERSION.SDK_INT >= 33) add(Manifest.permission.POST_NOTIFICATIONS)
    }.toTypedArray()
}

@Composable
private fun SladkayaScreen(
    onStart: () -> Unit,
    onStartDemo: () -> Unit,
) {
    val state by AppState.state.collectAsStateWithLifecycle()
    Surface(color = Paper, modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 18.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Header(state)
            CurrentGlucoseCard(state)
            HistoryCard(state.history)
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
private fun Header(state: GlucoseUiState) {
    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(39.dp).background(Forest, RoundedCornerShape(13.dp)), contentAlignment = Alignment.Center) {
            Text("С", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 18.sp)
        }
        Spacer(Modifier.width(11.dp))
        Column(Modifier.weight(1f)) {
            Text("Сладкая", color = Ink, fontWeight = FontWeight.Bold, fontSize = 21.sp)
            Text("Лёгкий контроль глюкозы", color = Muted, fontSize = 11.sp)
        }
        Row(
            modifier = Modifier.background(MintSoft, RoundedCornerShape(100.dp)).padding(horizontal = 10.dp, vertical = 7.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.size(7.dp).background(Forest, CircleShape))
            Spacer(Modifier.width(6.dp))
            Text(if (state.latest == null) "Запуск" else "На связи", color = Forest, fontWeight = FontWeight.Bold, fontSize = 11.sp)
        }
    }
}

@Composable
private fun CurrentGlucoseCard(state: GlucoseUiState) {
    val reading = state.latest
    val critical = state.activeAlarms.any { it == AlarmKind.LOW || it == AlarmKind.HIGH }
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
            }
            if (state.activeAlarms.isNotEmpty()) {
                Row(
                    modifier = Modifier.fillMaxWidth().background(DangerSoft, RoundedCornerShape(16.dp)).padding(14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("!", color = Color.White, fontWeight = FontWeight.Bold, modifier = Modifier.size(28.dp).background(Danger, CircleShape).padding(top = 3.dp), textAlign = TextAlign.Center)
                    Spacer(Modifier.width(11.dp))
                    Column {
                        Text(alarmTitle(state.activeAlarms), color = Danger, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                        Text("Локальная тревога активна", color = Muted, fontSize = 11.sp)
                    }
                }
            }
        }
    }
}

@Composable
private fun HistoryCard(history: List<GlucoseReading>) {
    Card(shape = RoundedCornerShape(22.dp), colors = CardDefaults.cardColors(containerColor = Color.White), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(18.dp)) {
            Text("Последние значения", color = Ink, fontWeight = FontWeight.Bold, fontSize = 16.sp)
            Text("Целевой диапазон показан зелёным", color = Muted, fontSize = 10.sp)
            Spacer(Modifier.height(16.dp))
            GlucoseChart(history.takeLast(48), Modifier.fillMaxWidth().height(190.dp))
        }
    }
}

@Composable
private fun GlucoseChart(history: List<GlucoseReading>, modifier: Modifier = Modifier) {
    Canvas(modifier) {
        val min = 40f
        val max = 300f
        fun y(value: Float) = size.height - ((value - min) / (max - min)).coerceIn(0f, 1f) * size.height
        drawRect(color = MintSoft.copy(alpha = 0.7f), topLeft = Offset(0f, y(180f)), size = androidx.compose.ui.geometry.Size(size.width, y(70f) - y(180f)))
        listOf(50f, 100f, 150f, 200f, 250f).forEach { tick ->
            drawLine(color = Muted.copy(alpha = 0.14f), start = Offset(0f, y(tick)), end = Offset(size.width, y(tick)), strokeWidth = 1.dp.toPx())
        }
        if (history.size > 1) {
            history.zipWithNext().forEachIndexed { index, pair ->
                val x1 = index.toFloat() / (history.size - 1) * size.width
                val x2 = (index + 1).toFloat() / (history.size - 1) * size.width
                val danger = pair.second.glucoseMgDl <= 70 || pair.second.glucoseMgDl >= 250
                drawLine(
                    color = if (danger) Danger else Forest,
                    start = Offset(x1, y(pair.first.glucoseMgDl.toFloat())),
                    end = Offset(x2, y(pair.second.glucoseMgDl.toFloat())),
                    strokeWidth = 3.dp.toPx(), cap = StrokeCap.Round,
                )
            }
            val last = history.last()
            drawCircle(
                color = if (last.glucoseMgDl <= 70 || last.glucoseMgDl >= 250) Danger else Forest,
                radius = 5.dp.toPx(), center = Offset(size.width, y(last.glucoseMgDl.toFloat())),
            )
            drawCircle(color = Color.White, radius = 5.dp.toPx(), center = Offset(size.width, y(last.glucoseMgDl.toFloat())), style = Stroke(2.dp.toPx()))
        }
    }
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
