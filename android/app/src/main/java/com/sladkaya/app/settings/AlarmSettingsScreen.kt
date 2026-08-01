package com.sladkaya.app.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.sladkaya.core.model.AlarmThresholds
import java.util.Locale

private val Forest = Color(0xFF176B57)
private val Ink = Color(0xFF162A27)
private val Muted = Color(0xFF637672)
private val Paper = Color(0xFFF5F7F3)
private val Danger = Color(0xFFD34949)
private val DangerSoft = Color(0xFFFFF0ED)

@Composable
internal fun AlarmSettingsScreen(
    initial: LoadedAlarmSettings,
    onBack: () -> Unit,
    onSave: (AlarmThresholds) -> Boolean,
    onTestAlarm: () -> Boolean,
    onCancelTestAlarm: () -> Unit,
) {
    var draft by remember(initial.thresholds) { mutableStateOf(initial.thresholds) }
    var saveFailed by remember { mutableStateOf(false) }
    var testFailed by remember { mutableStateOf(false) }
    var testStarted by remember { mutableStateOf(false) }
    Column(
        modifier = Modifier.fillMaxSize()
            .background(Paper)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 18.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedButton(onClick = onBack, shape = RoundedCornerShape(12.dp)) {
                Text("Назад")
            }
            Column(Modifier.padding(start = 14.dp)) {
                Text("Настройки тревог", color = Ink, fontSize = 22.sp, fontWeight = FontWeight.Bold)
                Text("Сохраняются только на этом телефоне", color = Muted, fontSize = 11.sp)
            }
        }

        if (initial.recoveredFromCorruption) {
            Text(
                "Повреждённые настройки не применены. Восстановлены безопасные значения по умолчанию.",
                color = Danger,
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.fillMaxWidth()
                    .background(DangerSoft, RoundedCornerShape(14.dp))
                    .padding(13.dp),
            )
        }

        ThresholdCard(
            title = "Низкое значение",
            value = draft.lowMgDl,
            onDecrease = { draft = AlarmSettingsEditor.adjustLow(draft, -1) },
            onIncrease = { draft = AlarmSettingsEditor.adjustLow(draft, 1) },
        )
        ThresholdCard(
            title = "Высокое значение",
            value = draft.highMgDl,
            onDecrease = { draft = AlarmSettingsEditor.adjustHigh(draft, -1) },
            onIncrease = { draft = AlarmSettingsEditor.adjustHigh(draft, 1) },
        )

        SettingsCard {
            Text("Нет свежих данных", color = Ink, fontWeight = FontWeight.Bold, fontSize = 15.sp)
            Text("Подать тревогу через", color = Muted, fontSize = 11.sp)
            Spacer(Modifier.height(10.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                OutlinedButton(
                    onClick = { draft = AlarmSettingsEditor.adjustStaleAfter(draft, -1) },
                    shape = RoundedCornerShape(12.dp),
                ) {
                    Text("−", fontSize = 22.sp)
                }
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        (draft.staleAfterMs / 60_000L).toString(),
                        color = Ink,
                        fontWeight = FontWeight.Bold,
                        fontSize = 34.sp,
                    )
                    Text("минут", color = Muted, fontSize = 11.sp)
                }
                OutlinedButton(
                    onClick = { draft = AlarmSettingsEditor.adjustStaleAfter(draft, 1) },
                    shape = RoundedCornerShape(12.dp),
                ) {
                    Text("+", fontSize = 22.sp)
                }
            }
        }

        SettingsCard {
            Text("Проверка тревоги", color = Ink, fontWeight = FontWeight.Bold, fontSize = 15.sp)
            Text(
                "Тревоги низкого и высокого значения, быстрого изменения и потери данных нельзя отключить внутри приложения.",
                color = Muted,
                fontSize = 12.sp,
                lineHeight = 17.sp,
            )
            Text(
                "Канал Android должен иметь высокий приоритет и звук. Режим «Не беспокоить», фактическую громкость и работу на этом телефоне можно проверить только тестом.",
                color = Muted,
                fontSize = 11.sp,
            )
            OutlinedButton(
                onClick = {
                    testStarted = onTestAlarm()
                    testFailed = !testStarted
                },
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
            ) {
                Text("Проверить звук тревоги")
            }
            Text(
                "Тест не содержит значения глюкозы и исчезнет через 10 секунд.",
                color = Muted,
                fontSize = 10.sp,
            )
            if (testFailed) {
                Text(
                    "Тест заблокирован настройками уведомлений Android.",
                    color = Danger,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                )
            } else if (testStarted) {
                Text(
                    "Тест отправлен. Убедитесь, что телефон действительно подал звук.",
                    color = Forest,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                )
                OutlinedButton(
                    onClick = {
                        onCancelTestAlarm()
                        testStarted = false
                    },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                ) {
                    Text("Остановить тест")
                }
            }
        }

        if (saveFailed) {
            Text("Не удалось сохранить настройки. Изменения не применены.", color = Danger, fontSize = 12.sp)
        }
        Button(
            onClick = {
                saveFailed = !onSave(draft)
            },
            modifier = Modifier.fillMaxWidth().height(54.dp),
            colors = ButtonDefaults.buttonColors(containerColor = Forest),
            shape = RoundedCornerShape(16.dp),
        ) {
            Text("Сохранить", fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
private fun ThresholdCard(
    title: String,
    value: Int,
    onDecrease: () -> Unit,
    onIncrease: () -> Unit,
) {
    SettingsCard {
        Text(title, color = Ink, fontWeight = FontWeight.Bold, fontSize = 15.sp)
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            OutlinedButton(onClick = onDecrease, shape = RoundedCornerShape(12.dp)) {
                Text("−", fontSize = 22.sp)
            }
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(formatMmol(value), color = Ink, fontWeight = FontWeight.Bold, fontSize = 34.sp)
                Text("ммоль/л · $value мг/дл", color = Muted, fontSize = 11.sp)
            }
            OutlinedButton(onClick = onIncrease, shape = RoundedCornerShape(12.dp)) {
                Text("+", fontSize = 22.sp)
            }
        }
    }
}

@Composable
private fun SettingsCard(content: @Composable ColumnScope.() -> Unit) {
    Card(
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = Color.White),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier.padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(7.dp),
            content = content,
        )
    }
}

private fun formatMmol(mgDl: Int): String =
    String.format(Locale.forLanguageTag("ru"), "%.1f", mgDl / 18.0)
