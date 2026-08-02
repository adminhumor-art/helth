package com.sladkaya.app.onboarding

import com.sladkaya.core.model.SensorFamily
import com.sladkaya.sensor.sibionics.Gs1AdvertisementScanOutcome
import com.sladkaya.sensor.sibionics.Gs1AdvertisementScanner
import com.sladkaya.sensor.sibionics.Gs1MarketProfile
import com.sladkaya.sensor.sibionics.Gs1OnboardingActionResult
import com.sladkaya.sensor.sibionics.Gs1OnboardingOpenResult
import com.sladkaya.sensor.sibionics.Gs1OnboardingRejectionReason
import com.sladkaya.sensor.sibionics.Gs1OnboardingState
import com.sladkaya.sensor.sibionics.Gs1OnboardingStateMachine
import com.sladkaya.sensor.sibionics.Gs1OnboardingStateStore
import com.sladkaya.sensor.sibionics.Gs1PackageCodeInput
import java.util.concurrent.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

data class Gs1SensorSetupUiState(
    val onboarding: Gs1OnboardingState?,
    val scanning: Boolean = false,
    val message: String? = null,
    val technicalCode: String? = null,
    val canRetrySearch: Boolean = false,
)

/**
 * Joins the persisted code-first state machine with one bounded BLE scan.
 * It can create only a quarantined diagnostic profile.
 */
class Gs1SensorSetupCoordinator(
    private val stateStore: Gs1OnboardingStateStore,
    private val scanner: Gs1AdvertisementScanner,
    private val clearDraft: (() -> Boolean)? = null,
) {
    private var machine: Gs1OnboardingStateMachine? = null
    private val searchLock = Any()
    private var searchGeneration = 0L
    private var activeSearch: ActiveSearch? = null
    private val mutableState = MutableStateFlow(openMachine())
    val state = mutableState.asStateFlow()

    fun submitPackageCode(
        family: SensorFamily,
        input: Gs1PackageCodeInput,
        marketProfile: Gs1MarketProfile?,
    ): Boolean {
        val activeMachine = machine ?: return false
        return publishAction(activeMachine.submitPackageCode(family, input, marketProfile))
    }

    suspend fun search(): Boolean {
        val activeMachine = machine ?: return false
        val savedState = activeMachine.state
        if (savedState !is Gs1OnboardingState.Discovering &&
            savedState !is Gs1OnboardingState.ResolutionBlocked
        ) {
            return false
        }
        if (savedState is Gs1OnboardingState.ResolutionBlocked &&
            savedState.reason == Gs1OnboardingRejectionReason.AMBIGUOUS_CANDIDATES
        ) {
            return false
        }
        val search = beginSearch() ?: return false
        if (activeMachine.state != savedState) {
            finishSearch(search.generation)
            return false
        }
        mutableState.value = Gs1SensorSetupUiState(
            onboarding = savedState,
            scanning = true,
            message = "Ищем датчик рядом",
        )
        return try {
            val outcome = try {
                scanner.scan()
            } catch (cancelled: CancellationException) {
                if (isCurrentSearch(search.generation)) mutableState.value = present(savedState)
                throw cancelled
            } catch (_: Exception) {
                Gs1AdvertisementScanOutcome.PlatformScanFailure(errorCode = null)
            }
            if (!isCurrentSearch(search.generation) || activeMachine.state != savedState) {
                false
            } else when (outcome) {
                is Gs1AdvertisementScanOutcome.Success ->
                    publishAction(activeMachine.resolveAdvertisements(outcome.advertisements))
                Gs1AdvertisementScanOutcome.PermissionDenied -> publishScanFailure(
                    savedState,
                    message = "Разрешите поиск Bluetooth-устройств",
                    technicalCode = "BLUETOOTH_PERMISSION_REQUIRED",
                )
                Gs1AdvertisementScanOutcome.BluetoothUnavailable -> publishScanFailure(
                    savedState,
                    message = "На этом телефоне недоступен Bluetooth",
                    technicalCode = "BLUETOOTH_UNAVAILABLE",
                    retryable = false,
                )
                Gs1AdvertisementScanOutcome.BluetoothDisabled -> publishScanFailure(
                    savedState,
                    message = "Включите Bluetooth и повторите поиск",
                    technicalCode = "BLUETOOTH_DISABLED",
                )
                Gs1AdvertisementScanOutcome.LocationServicesDisabled -> publishScanFailure(
                    savedState,
                    message = "На этой версии Android включите геолокацию и повторите поиск",
                    technicalCode = "LOCATION_SERVICES_DISABLED",
                )
                is Gs1AdvertisementScanOutcome.PlatformScanFailure -> publishScanFailure(
                    savedState,
                    message = "Android не смог выполнить поиск; повторите попытку",
                    technicalCode = "BLE_SCAN_FAILED",
                )
                Gs1AdvertisementScanOutcome.Overflow -> publishScanFailure(
                    savedState,
                    message = "Рядом слишком много Bluetooth-устройств; перейдите в более спокойное место",
                    technicalCode = "BLE_SCAN_OVERFLOW",
                )
            }
        } finally {
            finishSearch(search.generation)
        }
    }

    fun cancelSearch() {
        val job = synchronized(searchLock) {
            searchGeneration = nextGeneration(searchGeneration)
            activeSearch?.job.also { activeSearch = null }
        }
        job?.cancel(CancellationException("Sensor search cancelled"))
        machine?.state?.let { mutableState.value = present(it) }
    }

    fun reset(): Boolean {
        cancelSearch()
        val activeMachine = machine
        if (activeMachine != null) return publishAction(activeMachine.reset())

        val cleared = runCatching { clearDraft?.invoke() == true }.getOrDefault(false)
        if (!cleared) return false
        mutableState.value = openMachine()
        return machine != null
    }

    private suspend fun beginSearch(): ActiveSearch? {
        val job = checkNotNull(currentCoroutineContext()[Job])
        return synchronized(searchLock) {
            if (activeSearch != null) return@synchronized null
            searchGeneration = nextGeneration(searchGeneration)
            ActiveSearch(
                generation = searchGeneration,
                job = job,
            ).also { activeSearch = it }
        }
    }

    private fun isCurrentSearch(generation: Long): Boolean = synchronized(searchLock) {
        activeSearch?.generation == generation && searchGeneration == generation
    }

    private fun finishSearch(generation: Long) = synchronized(searchLock) {
        if (activeSearch?.generation == generation) activeSearch = null
    }

    private fun nextGeneration(current: Long): Long =
        if (current == Long.MAX_VALUE) 1L else current + 1L

    private fun openMachine(): Gs1SensorSetupUiState = when (
        val opened = Gs1OnboardingStateMachine.open(stateStore)
    ) {
        is Gs1OnboardingOpenResult.Ready -> {
            machine = opened.machine
            present(opened.machine.state)
        }
        is Gs1OnboardingOpenResult.Failure -> {
            machine = null
            Gs1SensorSetupUiState(
                onboarding = null,
                message = "Сохранённая настройка повреждена и не будет использована",
                technicalCode = opened.error.name,
            )
        }
    }

    private fun publishAction(result: Gs1OnboardingActionResult): Boolean = when (result) {
        is Gs1OnboardingActionResult.Advanced -> {
            mutableState.value = present(result.state)
            true
        }
        is Gs1OnboardingActionResult.Rejected -> {
            mutableState.value = present(
                state = result.state,
                reason = result.reason,
            )
            false
        }
    }

    private fun publishScanFailure(
        savedState: Gs1OnboardingState,
        message: String,
        technicalCode: String,
        retryable: Boolean = true,
    ): Boolean {
        mutableState.value = Gs1SensorSetupUiState(
            onboarding = savedState,
            message = message,
            technicalCode = technicalCode,
            canRetrySearch = retryable,
        )
        return false
    }

    private fun present(
        state: Gs1OnboardingState,
        reason: Gs1OnboardingRejectionReason? = null,
    ): Gs1SensorSetupUiState {
        val effectiveReason = reason ?: when (state) {
            is Gs1OnboardingState.ProfileBlocked -> state.reason
            is Gs1OnboardingState.ResolutionBlocked -> state.reason
            else -> null
        }
        val message = if (state is Gs1OnboardingState.ProfileBlocked) {
            state.request.marketProfile.blockedUserMessage()
        } else {
            effectiveReason?.userMessage()
        }
        return Gs1SensorSetupUiState(
            onboarding = state,
            message = message,
            technicalCode = effectiveReason?.name,
            canRetrySearch = effectiveReason in RETRYABLE_RESOLUTION_REASONS,
        )
    }

    private fun Gs1OnboardingRejectionReason.userMessage(): String = when (this) {
        Gs1OnboardingRejectionReason.INVALID_PACKAGE_CODE_LENGTH ->
            "Код должен содержать ровно 8 символов"
        Gs1OnboardingRejectionReason.INVALID_PACKAGE_CODE_CHARACTER ->
            "В коде разрешены только латинские буквы и цифры"
        Gs1OnboardingRejectionReason.UNSUPPORTED_FAMILY -> "Эта модель датчика пока не поддерживается"
        Gs1OnboardingRejectionReason.MARKET_PROFILE_REQUIRED ->
            "Выберите регион официального приложения или упаковки"
        Gs1OnboardingRejectionReason.PROFILE_NOT_PHYSICALLY_VERIFIED ->
            "Этот региональный профиль ещё не подтверждён на физическом датчике"
        Gs1OnboardingRejectionReason.INVALID_TRANSITION -> "Завершите текущий этап или начните заново"
        Gs1OnboardingRejectionReason.NO_MATCHING_CANDIDATE ->
            "Датчик с этим кодом рядом не найден"
        Gs1OnboardingRejectionReason.MALFORMED_BLUETOOTH_ADDRESS ->
            "Android вернул некорректный адрес устройства"
        Gs1OnboardingRejectionReason.AMBIGUOUS_CANDIDATES ->
            "Найдено несколько похожих датчиков. Уберите другие датчики и начните настройку заново"
        Gs1OnboardingRejectionReason.TOO_MANY_CANDIDATES ->
            "Рядом слишком много Bluetooth-устройств"
        Gs1OnboardingRejectionReason.PROFILE_VALIDATION_FAILED ->
            "Найденный датчик не прошёл внутреннюю проверку"
        Gs1OnboardingRejectionReason.STORAGE_UNAVAILABLE ->
            "Не удалось безопасно сохранить настройку"
        Gs1OnboardingRejectionReason.STORAGE_CONFLICT ->
            "Настройка изменилась в другом процессе; откройте экран заново"
    }

    private companion object {
        val RETRYABLE_RESOLUTION_REASONS = setOf(
            Gs1OnboardingRejectionReason.NO_MATCHING_CANDIDATE,
            Gs1OnboardingRejectionReason.MALFORMED_BLUETOOTH_ADDRESS,
            Gs1OnboardingRejectionReason.TOO_MANY_CANDIDATES,
        )
    }
}

private fun Gs1MarketProfile.blockedUserMessage(): String = when (this) {
    Gs1MarketProfile.GLOBAL -> "Международный профиль готов к безопасной диагностике"
    Gs1MarketProfile.RUSSIAN ->
        "Российский / Hematonix профиль определён, но ещё не подтверждён на физическом датчике. Bluetooth-подключение не начато."
    Gs1MarketProfile.CHINESE ->
        "Китайский профиль готов к безопасной диагностике; внутренний способ обмена приложение определит само."
    Gs1MarketProfile.ECO_SPLIT ->
        "Для Sibionics 2 / Split нужны два DataMatrix: датчика и передатчика. Отдельный сценарий пока не запускается, Bluetooth-подключение не начато."
}

private data class ActiveSearch(
    val generation: Long,
    val job: Job,
)
