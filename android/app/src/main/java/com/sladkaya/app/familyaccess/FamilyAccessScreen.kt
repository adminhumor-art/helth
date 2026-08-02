package com.sladkaya.app.familyaccess

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import java.util.Locale
import kotlinx.coroutines.launch

private val FamilyForest = Color(0xFF176B57)
private val FamilyInk = Color(0xFF162A27)
private val FamilyMuted = Color(0xFF637672)
private val FamilyPaper = Color(0xFFF5F7F3)
private val FamilyMint = Color(0xFFDCEEE7)
private val FamilyDanger = Color(0xFFD34949)
private val FamilyDangerSoft = Color(0xFFFFF0ED)

/** Ready-to-wire Compose entry point. The caller owns only navigation and the coordinator. */
@Composable
internal fun FamilyAccessRoute(
    coordinator: FamilyAccessCoordinator,
    onBack: () -> Unit,
    initialOrigin: String = "",
) {
    val state by coordinator.state.collectAsStateWithLifecycle()
    val installationRequest by coordinator.installationRequest.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    LaunchedEffect(coordinator) {
        coordinator.load()
    }
    FamilyAccessScreen(
        state = state,
        installationRequest = installationRequest,
        onBack = onBack,
        initialOrigin = initialOrigin,
        onRetryIdentity = { scope.launch { coordinator.load() } },
        onConnect = { origin, activationCode ->
            scope.launch { coordinator.connect(origin, activationCode) }
        },
        onCopyInstallationRequest = { request ->
            copyInstallationRequest(context, request)
        },
    )
}

@Composable
internal fun FamilyAccessScreen(
    state: FamilyAccessUiState,
    installationRequest: FamilyInstallationRequest?,
    onBack: () -> Unit,
    onRetryIdentity: () -> Unit,
    onConnect: (origin: String, activationCode: String) -> Unit,
    onCopyInstallationRequest: (FamilyInstallationRequest) -> Boolean,
    initialOrigin: String = "",
) {
    var origin by remember(initialOrigin) { mutableStateOf(initialOrigin) }
    // The one-time code is intentionally not saveable across Activity/process recreation.
    var activationCode by remember { mutableStateOf("") }
    LaunchedEffect(state is FamilyAccessUiState.Connected) {
        if (state is FamilyAccessUiState.Connected) activationCode = ""
    }

    val deviceId = state.deviceIdOrNull()
    val connecting = state is FamilyAccessUiState.Connecting
    val connected = state is FamilyAccessUiState.Connected

    Column(
        modifier = Modifier.fillMaxSize()
            .background(FamilyPaper)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 18.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    "Семейный доступ",
                    color = FamilyInk,
                    fontSize = 22.sp,
                    fontWeight = FontWeight.Bold,
                )
                Text("Сайт и уведомления Telegram", color = FamilyMuted, fontSize = 11.sp)
            }
            OutlinedButton(onClick = onBack, shape = RoundedCornerShape(12.dp)) {
                Text("Назад")
            }
        }

        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(18.dp),
            colors = CardDefaults.cardColors(containerColor = FamilyMint),
        ) {
            Text(
                "Интернет нужен только для сайта и Telegram. Датчик, график, локальная тревога и виджет на телефоне работают независимо.",
                color = FamilyInk,
                fontSize = 12.sp,
                lineHeight = 17.sp,
                modifier = Modifier.padding(15.dp),
            )
        }

        when {
            state is FamilyAccessUiState.LoadingIdentity -> LoadingCard()
            deviceId == null -> IdentityFailureCard(
                failure = (state as FamilyAccessUiState.Failed).reason,
                onRetry = onRetryIdentity,
            )
            else -> {
                DeviceIdCard(deviceId)
                if (installationRequest != null) {
                    InstallationRequestCard(
                        request = installationRequest,
                        onCopy = onCopyInstallationRequest,
                    )
                }

                if (connected) {
                    ConnectedCard()
                } else {
                    OutlinedTextField(
                        value = origin,
                        onValueChange = { if (it.length <= MAX_ORIGIN_CHARS) origin = it },
                        modifier = Modifier.fillMaxWidth(),
                        enabled = !connecting,
                        singleLine = true,
                        label = { Text("Адрес семейного сервера") },
                        placeholder = { Text("https://family.example.ru") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                    )
                    OutlinedTextField(
                        value = activationCode,
                        onValueChange = { next ->
                            if (next.length <= MAX_ACTIVATION_CODE_CHARS) {
                                activationCode = next.uppercase(Locale.ROOT)
                            }
                        },
                        modifier = Modifier.fillMaxWidth(),
                        enabled = !connecting,
                        singleLine = true,
                        label = { Text("Одноразовый код SLK1") },
                        placeholder = { Text("SLK1-XXXX-XXXX-…") },
                        keyboardOptions = KeyboardOptions(
                            capitalization = KeyboardCapitalization.Characters,
                            keyboardType = KeyboardType.Ascii,
                        ),
                    )

                    if (state is FamilyAccessUiState.Failed) {
                        FailureCard(state.reason)
                    }

                    Button(
                        onClick = { onConnect(origin, activationCode) },
                        enabled = !connecting,
                        modifier = Modifier.fillMaxWidth().height(54.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = FamilyForest),
                        shape = RoundedCornerShape(16.dp),
                    ) {
                        if (connecting) {
                            CircularProgressIndicator(
                                modifier = Modifier.height(22.dp),
                                color = Color.White,
                                strokeWidth = 2.dp,
                            )
                            Text("  Подключаем…", fontWeight = FontWeight.Bold)
                        } else {
                            Text("Подключить", fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun InstallationRequestCard(
    request: FamilyInstallationRequest,
    onCopy: (FamilyInstallationRequest) -> Boolean,
) {
    var copied by remember(request) { mutableStateOf<Boolean?>(null) }
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = Color.White),
    ) {
        Column(
            modifier = Modifier.padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                "Код этого телефона для владельца",
                color = FamilyInk,
                fontWeight = FontWeight.Bold,
                fontSize = 15.sp,
            )
            Text(
                "Скопируйте код целиком. Он нужен владельцу семьи, чтобы создать одноразовый код подключения.",
                color = FamilyMuted,
                fontSize = 11.sp,
                lineHeight = 16.sp,
            )
            SelectionContainer {
                Text(
                    request.value,
                    color = FamilyInk,
                    fontSize = 11.sp,
                    lineHeight = 15.sp,
                )
            }
            OutlinedButton(
                onClick = { copied = onCopy(request) },
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
            ) {
                Text("Скопировать код телефона")
            }
            when (copied) {
                true -> Text("Код скопирован", color = FamilyForest, fontSize = 11.sp)
                false -> Text(
                    "Не удалось открыть буфер обмена. Выделите код вручную.",
                    color = FamilyDanger,
                    fontSize = 11.sp,
                )
                null -> Unit
            }
        }
    }
}

@Composable
private fun LoadingCard() {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = Color.White),
    ) {
        Row(
            modifier = Modifier.padding(18.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            CircularProgressIndicator(
                modifier = Modifier.height(24.dp),
                color = FamilyForest,
                strokeWidth = 2.dp,
            )
            Text("Готовим защищённый ID телефона…", color = FamilyInk)
        }
    }
}

@Composable
private fun DeviceIdCard(deviceId: String) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = Color.White),
    ) {
        Column(
            modifier = Modifier.padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(7.dp),
        ) {
            Text("ID этой установки", color = FamilyMuted, fontSize = 11.sp)
            SelectionContainer {
                Text(deviceId, color = FamilyInk, fontWeight = FontWeight.Bold, fontSize = 14.sp)
            }
            Text(
                "ID нужен для диагностики установки. Для подключения передавайте код телефона ниже.",
                color = FamilyMuted,
                fontSize = 11.sp,
            )
        }
    }
}

@Composable
private fun ConnectedCard() {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = FamilyMint),
    ) {
        Column(
            modifier = Modifier.padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(
                "Семейный доступ подключён",
                color = FamilyForest,
                fontSize = 17.sp,
                fontWeight = FontWeight.Bold,
            )
            Text(
                "Телефон сохранил доступ в защищённом хранилище.",
                color = FamilyInk,
                fontSize = 12.sp,
            )
        }
    }
}

@Composable
private fun IdentityFailureCard(
    failure: FamilyAccessFailure,
    onRetry: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        FailureCard(failure)
        OutlinedButton(
            onClick = onRetry,
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(12.dp),
        ) {
            Text("Повторить")
        }
    }
}

@Composable
private fun FailureCard(failure: FamilyAccessFailure) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = FamilyDangerSoft),
    ) {
        Text(
            failure.userMessage(),
            color = FamilyDanger,
            fontSize = 12.sp,
            lineHeight = 17.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(14.dp),
        )
    }
}

private fun FamilyAccessUiState.deviceIdOrNull(): String? = when (this) {
    FamilyAccessUiState.LoadingIdentity -> null
    is FamilyAccessUiState.Ready -> deviceId
    is FamilyAccessUiState.Connecting -> deviceId
    is FamilyAccessUiState.Connected -> deviceId
    is FamilyAccessUiState.Failed -> deviceId
}

internal fun FamilyAccessFailure.userMessage(): String = when (this) {
    FamilyAccessFailure.InvalidHttpsOrigin ->
        "Введите полный безопасный адрес, начинающийся с https://, без пути после домена."
    FamilyAccessFailure.InvalidActivationCode ->
        "Проверьте одноразовый код: он должен начинаться с SLK1 и содержать все группы символов."
    FamilyAccessFailure.DeviceSecurityUnavailable ->
        "Android сейчас не даёт доступ к защищённому хранилищу. Разблокируйте телефон и повторите."
    FamilyAccessFailure.DeviceStorageUnavailable ->
        "Не удалось сохранить постоянный ID этой установки. Освободите место и повторите."
    FamilyAccessFailure.DeviceIdentityCorrupted ->
        "Защищённый ID этой установки повреждён. Не создавайте новый код — нужна проверка установки."
    FamilyAccessFailure.ProvisioningRequestRejected ->
        "Сервер не принял запрос подключения. Проверьте адрес и обновите приложение сервера."
    FamilyAccessFailure.ActivationRejected ->
        "Код не принят: он неверный, уже использован, истёк или создан для другого телефона."
    FamilyAccessFailure.NetworkUnavailable ->
        "Нет связи с сервером. Проверьте интернет и повторите. Датчик и локальные тревоги продолжают работать."
    FamilyAccessFailure.ServerTemporarilyUnavailable ->
        "Сервер временно недоступен. Повторите подключение позже."
    FamilyAccessFailure.SecureConnectionBlocked ->
        "Не удалось установить защищённое HTTPS-соединение. Проверьте адрес, дату и время телефона."
    FamilyAccessFailure.ServerResponseInvalid ->
        "Сервер вернул неподдерживаемый ответ. Подключение не сохранено."
    FamilyAccessFailure.CredentialSecurityUnavailable ->
        "Android не смог защитить доступ к серверу. Разблокируйте телефон и повторите."
    FamilyAccessFailure.CredentialStorageUnavailable ->
        "Не удалось надёжно сохранить доступ к серверу. Освободите место и запросите новый код."
    FamilyAccessFailure.UnexpectedFailure ->
        "Подключение не завершено из-за внутренней ошибки. Повторите попытку."
}

private fun copyInstallationRequest(
    context: Context,
    request: FamilyInstallationRequest,
): Boolean = try {
    val clipboard = context.getSystemService(ClipboardManager::class.java) ?: return false
    clipboard.setPrimaryClip(
        ClipData.newPlainText("Код телефона Сладкая", request.value),
    )
    true
} catch (_: RuntimeException) {
    false
}

private const val MAX_ORIGIN_CHARS = 512
private const val MAX_ACTIVATION_CODE_CHARS = 64
