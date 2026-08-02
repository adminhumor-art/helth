package com.sladkaya.app.onboarding

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
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.sladkaya.app.DiagnosticUiState
import com.sladkaya.app.DiagnosticReadingUiPolicy
import com.sladkaya.core.model.SensorFamily
import com.sladkaya.sensor.sibionics.Gs1MarketProfile
import com.sladkaya.sensor.sibionics.Gs1OnboardingState
import java.util.Locale
import kotlinx.coroutines.delay

private val SetupForest = Color(0xFF176B57)
private val SetupInk = Color(0xFF162A27)
private val SetupMuted = Color(0xFF637672)
private val SetupPaper = Color(0xFFF5F7F3)
private val SetupMint = Color(0xFFDCEEE7)
private val SetupDanger = Color(0xFFD34949)
private val SetupDangerSoft = Color(0xFFFFF0ED)

@Composable
fun SensorSetupScreen(
    setup: Gs1SensorSetupUiState,
    diagnostic: DiagnosticUiState,
    scannerMessage: String?,
    scannerSuggestedCode: String?,
    onBack: () -> Unit,
    onSubmitManual: (SensorFamily, Gs1MarketProfile, String) -> Unit,
    onScanDataMatrix: (SensorFamily, Gs1MarketProfile) -> Unit,
    onRetrySearch: () -> Unit,
    onStartDiagnostic: () -> Unit,
    onStopDiagnostic: () -> Unit,
    onReset: () -> Unit,
) {
    var family by rememberSaveable { mutableStateOf(SensorFamily.SIBIONICS_GS1) }
    var marketProfile by rememberSaveable { mutableStateOf<Gs1MarketProfile?>(null) }
    var code by rememberSaveable { mutableStateOf("") }
    var localInputError by rememberSaveable { mutableStateOf<String?>(null) }
    var localMarketError by rememberSaveable { mutableStateOf<String?>(null) }
    LaunchedEffect(setup.onboarding) {
        val request = when (val state = setup.onboarding) {
            is Gs1OnboardingState.ProfileBlocked -> state.request
            is Gs1OnboardingState.Discovering -> state.request
            is Gs1OnboardingState.ResolutionBlocked -> state.request
            else -> null
        }
        if (request != null) {
            family = request.family
            marketProfile = request.marketProfile
            code = request.packageCode
        } else if (setup.onboarding is Gs1OnboardingState.PendingDiagnostic) {
            family = setup.onboarding.profile.family
            marketProfile = setup.onboarding.profile.marketProfile
            code = setup.onboarding.profile.packageCode
        } else if (setup.onboarding == Gs1OnboardingState.AwaitingPackageCode) {
            marketProfile = null
            code = ""
            localInputError = null
            localMarketError = null
        }
    }
    LaunchedEffect(scannerSuggestedCode) {
        if (scannerSuggestedCode != null) {
            code = scannerSuggestedCode
            localInputError = null
        }
    }

    Surface(color = SetupPaper, modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier.fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 18.dp, vertical = 18.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Column {
                    Text("Подключение датчика", color = SetupInk, fontWeight = FontWeight.Bold, fontSize = 22.sp)
                    Text("GS1 и GS1Sb · безопасная диагностика", color = SetupMuted, fontSize = 11.sp)
                }
                OutlinedButton(onClick = onBack, shape = RoundedCornerShape(12.dp)) {
                    Text("Назад")
                }
            }

            when (val onboarding = setup.onboarding) {
                null -> RecoveryCard(setup, onReset)
                Gs1OnboardingState.AwaitingPackageCode -> {
                    FamilyCard(family = family, onFamilyChanged = { family = it })
                    MarketProfileCard(
                        selected = marketProfile,
                        error = localMarketError,
                        onSelected = {
                            marketProfile = it
                            localMarketError = null
                        },
                    )
                    CodeCard(
                        code = code,
                        error = localInputError ?: scannerMessage ?: setup.message,
                        isScannedCandidate = scannerSuggestedCode != null,
                        onCodeChanged = { next ->
                            if (next.length <= 8 && next.all(Char::isAsciiLetterOrDigit)) {
                                code = next
                                localInputError = null
                            } else {
                                localInputError = "Разрешены ровно 8 латинских букв или цифр"
                            }
                        },
                        onSubmit = {
                            val selectedMarket = marketProfile
                            if (selectedMarket == null) {
                                localMarketError = "Выберите регион упаковки или официального приложения"
                            } else if (code.length == 8) {
                                onSubmitManual(family, selectedMarket, code)
                            } else {
                                localInputError = "Введите все 8 символов"
                            }
                        },
                        onScan = {
                            val selectedMarket = marketProfile
                            if (selectedMarket == null) {
                                localMarketError = "Перед сканированием выберите регион"
                            } else {
                                onScanDataMatrix(family, selectedMarket)
                            }
                        },
                    )
                }
                is Gs1OnboardingState.ProfileBlocked -> ProfileBlockedCard(
                    setup = setup,
                    marketProfile = onboarding.request.marketProfile,
                    onReset = onReset,
                )
                is Gs1OnboardingState.Discovering -> SearchCard(
                    setup = setup,
                    family = onboarding.request.family,
                    marketProfile = onboarding.request.marketProfile,
                    onRetrySearch = onRetrySearch,
                    onReset = onReset,
                )
                is Gs1OnboardingState.ResolutionBlocked -> SearchCard(
                    setup = setup,
                    family = onboarding.request.family,
                    marketProfile = onboarding.request.marketProfile,
                    onRetrySearch = onRetrySearch,
                    onReset = onReset,
                )
                is Gs1OnboardingState.PendingDiagnostic -> DiagnosticCard(
                    family = onboarding.profile.family,
                    marketProfile = onboarding.profile.marketProfile,
                    deviceName = onboarding.profile.deviceName,
                    diagnostic = diagnostic,
                    onStart = onStartDiagnostic,
                    onStop = onStopDiagnostic,
                    onReset = onReset,
                )
            }

            Card(
                shape = RoundedCornerShape(18.dp),
                colors = CardDefaults.cardColors(containerColor = SetupDangerSoft),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(
                    "До сравнения с официальным приложением диагностические числа не включают график, тревоги, виджет, сайт или Telegram.",
                    color = SetupDanger,
                    fontSize = 12.sp,
                    lineHeight = 17.sp,
                    modifier = Modifier.padding(15.dp),
                )
            }
        }
    }
}

@Composable
private fun MarketProfileCard(
    selected: Gs1MarketProfile?,
    error: String?,
    onSelected: (Gs1MarketProfile) -> Unit,
) {
    SetupCard(title = "2. Регион датчика") {
        Text(
            "Выберите регион коробки или официального приложения. Код и название GS1/GS1Sb регион не определяют.",
            color = SetupMuted,
            fontSize = 12.sp,
        )
        Gs1MarketProfile.entries.forEach { profile ->
            MarketProfileButton(profile, selected, onSelected)
        }
        Text(
            error ?: selected?.diagnosticAvailabilityMessage()
                ?: "Выберите регион, указанный на коробке.",
            color = if (error != null) SetupDanger else SetupMuted,
            fontSize = 11.sp,
        )
    }
}

@Composable
private fun MarketProfileButton(
    value: Gs1MarketProfile,
    selected: Gs1MarketProfile?,
    onSelected: (Gs1MarketProfile) -> Unit,
) {
    val label = value.userLabel()
    if (value == selected) {
        Button(
            onClick = { onSelected(value) },
            colors = ButtonDefaults.buttonColors(containerColor = SetupForest),
            shape = RoundedCornerShape(12.dp),
            modifier = Modifier.fillMaxWidth(),
        ) { Text(label) }
    } else {
        OutlinedButton(
            onClick = { onSelected(value) },
            shape = RoundedCornerShape(12.dp),
            modifier = Modifier.fillMaxWidth(),
        ) { Text(label) }
    }
}

@Composable
private fun FamilyCard(
    family: SensorFamily,
    onFamilyChanged: (SensorFamily) -> Unit,
) {
    SetupCard(title = "1. Модель датчика") {
        Text(
            "Выберите надпись на коробке. Приложение не будет угадывать модель по похожему устройству.",
            color = SetupMuted,
            fontSize = 12.sp,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FamilyButton("GS1", SensorFamily.SIBIONICS_GS1, family, onFamilyChanged)
            FamilyButton("GS1Sb", SensorFamily.SIBIONICS_GS1SB, family, onFamilyChanged)
        }
        Text("GS3 подключается отдельным протоколом и здесь намеренно не подменяется GS1.", color = SetupMuted, fontSize = 11.sp)
    }
}

@Composable
private fun FamilyButton(
    label: String,
    value: SensorFamily,
    selected: SensorFamily,
    onSelected: (SensorFamily) -> Unit,
) {
    if (value == selected) {
        Button(
            onClick = { onSelected(value) },
            colors = ButtonDefaults.buttonColors(containerColor = SetupForest),
            shape = RoundedCornerShape(12.dp),
        ) { Text(label) }
    } else {
        OutlinedButton(onClick = { onSelected(value) }, shape = RoundedCornerShape(12.dp)) {
            Text(label)
        }
    }
}

@Composable
private fun CodeCard(
    code: String,
    error: String?,
    isScannedCandidate: Boolean,
    onCodeChanged: (String) -> Unit,
    onSubmit: () -> Unit,
    onScan: () -> Unit,
) {
    SetupCard(title = "3. Код с упаковки") {
        Text("Отсканируйте DataMatrix или введите 8 символов без изменения регистра.", color = SetupMuted, fontSize = 12.sp)
        OutlinedTextField(
            value = code,
            onValueChange = onCodeChanged,
            label = { Text("8 символов") },
            singleLine = true,
            isError = error != null,
            supportingText = error?.let { message -> ({ Text(message) }) },
            keyboardOptions = KeyboardOptions(
                capitalization = KeyboardCapitalization.None,
                autoCorrectEnabled = false,
                keyboardType = KeyboardType.Ascii,
            ),
            modifier = Modifier.fillMaxWidth(),
        )
        Button(
            onClick = onSubmit,
            enabled = code.length == 8,
            colors = ButtonDefaults.buttonColors(containerColor = SetupForest),
            shape = RoundedCornerShape(12.dp),
            modifier = Modifier.fillMaxWidth(),
        ) { Text(if (isScannedCandidate) "Подтвердить и искать" else "Продолжить") }
        OutlinedButton(onClick = onScan, shape = RoundedCornerShape(12.dp), modifier = Modifier.fillMaxWidth()) {
            Text("Сканировать DataMatrix")
        }
    }
}

@Composable
private fun SearchCard(
    setup: Gs1SensorSetupUiState,
    family: SensorFamily,
    marketProfile: Gs1MarketProfile,
    onRetrySearch: () -> Unit,
    onReset: () -> Unit,
) {
    SetupCard(title = "4. Поиск ${family.shortLabel()}") {
        Text("Регион: ${marketProfile.userLabel()}", color = SetupMuted, fontSize = 11.sp)
        Text(
            if (setup.scanning) "Идёт поиск датчика рядом…" else setup.message ?: "Код сохранён. Можно искать датчик.",
            color = if (setup.message != null) SetupDanger else SetupMuted,
            fontSize = 13.sp,
        )
        if (setup.technicalCode != null) {
            Text("Код проверки: ${setup.technicalCode}", color = SetupMuted, fontSize = 10.sp)
        }
        Button(
            onClick = onRetrySearch,
            enabled = !setup.scanning && (setup.canRetrySearch || setup.onboarding is Gs1OnboardingState.Discovering),
            colors = ButtonDefaults.buttonColors(containerColor = SetupForest),
            shape = RoundedCornerShape(12.dp),
            modifier = Modifier.fillMaxWidth(),
        ) { Text(if (setup.scanning) "Поиск…" else "Повторить поиск") }
        OutlinedButton(onClick = onReset, enabled = !setup.scanning, modifier = Modifier.fillMaxWidth()) {
            Text("Начать настройку заново")
        }
    }
}

@Composable
private fun ProfileBlockedCard(
    setup: Gs1SensorSetupUiState,
    marketProfile: Gs1MarketProfile,
    onReset: () -> Unit,
) {
    SetupCard(title = "Профиль сохранён безопасно") {
        Text(marketProfile.userLabel(), color = SetupInk, fontWeight = FontWeight.Bold, fontSize = 13.sp)
        Text(
            setup.message ?: "Этот профиль пока не допускается к Bluetooth-подключению.",
            color = SetupDanger,
            fontSize = 13.sp,
        )
        setup.technicalCode?.let { code ->
            Text("Код проверки: $code", color = SetupMuted, fontSize = 10.sp)
        }
        OutlinedButton(onClick = onReset, modifier = Modifier.fillMaxWidth()) {
            Text("Выбрать другой профиль")
        }
    }
}

@Composable
private fun DiagnosticCard(
    family: SensorFamily,
    marketProfile: Gs1MarketProfile,
    deviceName: String,
    diagnostic: DiagnosticUiState,
    onStart: () -> Unit,
    onStop: () -> Unit,
    onReset: () -> Unit,
) {
    val nowEpochMs by produceState(
        initialValue = System.currentTimeMillis(),
        key1 = diagnostic.latestReading,
    ) {
        while (true) {
            delay(30_000L)
            value = System.currentTimeMillis()
        }
    }
    val visibleReading = diagnostic.latestReading?.takeIf { reading ->
        diagnostic.readingAllowed && DiagnosticReadingUiPolicy.canDisplay(reading, nowEpochMs)
    }
    SetupCard(title = "Датчик найден: ${family.shortLabel()}") {
        Text("Регион: ${marketProfile.userLabel()}", color = SetupMuted, fontSize = 11.sp)
        Text("Bluetooth-имя: ${deviceName.take(24)}", color = SetupMuted, fontSize = 11.sp)
        Text(diagnostic.phaseLabel, color = SetupInk, fontWeight = FontWeight.Bold, fontSize = 13.sp)
        visibleReading?.let { reading ->
            Text(
                String.format(Locale.forLanguageTag("ru"), "%.1f", reading.glucoseMgDl / 18.0),
                color = SetupInk,
                fontWeight = FontWeight.SemiBold,
                fontSize = 58.sp,
            )
            Text(
                "ммоль/л · только диагностика · получено ${DiagnosticReadingUiPolicy.ageMinutes(reading, nowEpochMs)} мин назад",
                color = SetupDanger,
                fontSize = 11.sp,
            )
        }
        if (diagnostic.latestReading != null && visibleReading == null) {
            Text(
                "Последнее значение скрыто: оно не подтверждено как свежее и готовое.",
                color = SetupDanger,
                fontSize = 11.sp,
            )
        }
        diagnostic.technicalCode?.let { code ->
            Text("Код проверки: $code", color = SetupMuted, fontSize = 10.sp)
        }
        Button(
            onClick = onStart,
            colors = ButtonDefaults.buttonColors(containerColor = SetupForest),
            shape = RoundedCornerShape(12.dp),
            modifier = Modifier.fillMaxWidth(),
        ) { Text(if (diagnostic.active) "Повторить подключение" else "Запустить диагностику") }
        OutlinedButton(onClick = onStop, enabled = diagnostic.active, modifier = Modifier.fillMaxWidth()) {
            Text("Остановить диагностику")
        }
        OutlinedButton(onClick = onReset, modifier = Modifier.fillMaxWidth()) {
            Text("Подключить другой датчик")
        }
    }
}

@Composable
private fun RecoveryCard(setup: Gs1SensorSetupUiState, onReset: () -> Unit) {
    SetupCard(title = "Настройка остановлена безопасно") {
        Text(setup.message ?: "Не удалось открыть настройку", color = SetupDanger, fontSize = 13.sp)
        setup.technicalCode?.let { Text("Код проверки: $it", color = SetupMuted, fontSize = 10.sp) }
        Button(onClick = onReset, colors = ButtonDefaults.buttonColors(containerColor = SetupForest)) {
            Text("Очистить черновик и начать заново")
        }
    }
}

@Composable
private fun SetupCard(title: String, content: @Composable ColumnScope.() -> Unit) {
    Card(
        shape = RoundedCornerShape(22.dp),
        colors = CardDefaults.cardColors(containerColor = Color.White),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier.padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(11.dp),
        ) {
            Text(title, color = SetupInk, fontWeight = FontWeight.Bold, fontSize = 16.sp)
            content()
        }
    }
}

private fun SensorFamily.shortLabel(): String = when (this) {
    SensorFamily.SIBIONICS_GS1 -> "GS1"
    SensorFamily.SIBIONICS_GS1SB -> "GS1Sb"
    SensorFamily.SIBIONICS_GS3 -> "GS3"
    SensorFamily.SIMULATOR -> "Демо"
}

private fun Char.isAsciiLetterOrDigit(): Boolean =
    this in '0'..'9' || this in 'A'..'Z' || this in 'a'..'z'
