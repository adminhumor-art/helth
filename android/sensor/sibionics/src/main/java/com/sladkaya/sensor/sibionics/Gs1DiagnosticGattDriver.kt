package com.sladkaya.sensor.sibionics

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.BluetoothDevice
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.SystemClock
import androidx.core.content.ContextCompat
import com.sladkaya.core.data.CommittedSensorIngressReader
import com.sladkaya.core.data.RoomCommittedSensorIngressReader
import com.sladkaya.core.data.RoomSensorPacketIngressJournal
import com.sladkaya.core.data.SensorCoreRepository
import com.sladkaya.core.data.SensorPacketIngressJournal
import com.sladkaya.core.data.SensorPacketIngressMarkHandledResult
import com.sladkaya.core.data.SensorPacketIngressOutcomeRecord
import com.sladkaya.core.data.SensorPacketIngressOutcomeStatus
import com.sladkaya.core.data.LocalSensorBindingRepository
import com.sladkaya.core.model.ReadingQuality
import com.sladkaya.core.sensor.SensorConfiguration
import com.sladkaya.sensor.sibionics.datahandle.DataHandleGateway
import com.sladkaya.sensor.sibionics.datahandle.DataHandleGatewayOpenResult
import com.sladkaya.sensor.sibionics.datahandle.RemoteDataHandleGatewayConnector
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

sealed interface Gs1DiagnosticGattState {
    data object Idle : Gs1DiagnosticGattState
    data object OpeningCore : Gs1DiagnosticGattState
    data object Connecting : Gs1DiagnosticGattState
    data class ConnectingForHistoryBackfill(
        val expectedIndex: Int,
        val firstPendingIndex: Int?,
        val reason: String,
    ) : Gs1DiagnosticGattState
    data object DiscoveringServices : Gs1DiagnosticGattState
    data object Subscribing : Gs1DiagnosticGattState
    data object Authenticating : Gs1DiagnosticGattState
    data class RetryingPersistence(val attempt: Int) : Gs1DiagnosticGattState
    data class RetryingIngressPersistence(val attempt: Int) : Gs1DiagnosticGattState
    data class Reconnecting(val attempt: Int, val delayMillis: Long) : Gs1DiagnosticGattState
    data object PersistencePending : Gs1DiagnosticGattState
    data object StreamingDiagnostic : Gs1DiagnosticGattState
    data class DiagnosticDataNotFresh(
        val sequence: Long?,
        val quality: ReadingQuality?,
    ) : Gs1DiagnosticGattState
    data class DiagnosticDataRejected(
        val sequence: Int,
        val code: String,
        val detail: String,
    ) : Gs1DiagnosticGattState
    data class Failed(
        val code: String,
        val detail: String? = null,
        val retryable: Boolean,
    ) : Gs1DiagnosticGattState
}

sealed interface Gs1ProductGattState {
    data object Idle : Gs1ProductGattState
    data object OpeningCore : Gs1ProductGattState
    data object Connecting : Gs1ProductGattState
    data class ConnectingForHistoryBackfill(
        val expectedIndex: Int,
        val firstPendingIndex: Int?,
        val reason: String,
    ) : Gs1ProductGattState
    data object DiscoveringServices : Gs1ProductGattState
    data object Subscribing : Gs1ProductGattState
    data object Authenticating : Gs1ProductGattState
    data class RetryingPersistence(val attempt: Int) : Gs1ProductGattState
    data class RetryingIngressPersistence(val attempt: Int) : Gs1ProductGattState
    data class Reconnecting(val attempt: Int, val delayMillis: Long) : Gs1ProductGattState
    data object PersistencePending : Gs1ProductGattState
    data object Streaming : Gs1ProductGattState
    data class WaitingForPublishableReading(val sequence: Long?) : Gs1ProductGattState
    data class DataRejected(
        val sequence: Int,
        val code: String,
        val detail: String,
    ) : Gs1ProductGattState
    data class Failed(
        val code: String,
        val detail: String? = null,
        val retryable: Boolean,
    ) : Gs1ProductGattState
}

private sealed interface Gs1GattEngineState {
    data object Idle : Gs1GattEngineState
    data object OpeningCore : Gs1GattEngineState
    data object Connecting : Gs1GattEngineState
    data class ConnectingForHistoryBackfill(
        val expectedIndex: Int,
        val firstPendingIndex: Int?,
        val reason: String,
    ) : Gs1GattEngineState
    data object DiscoveringServices : Gs1GattEngineState
    data object Subscribing : Gs1GattEngineState
    data object Authenticating : Gs1GattEngineState
    data class RetryingPersistence(val attempt: Int) : Gs1GattEngineState
    data class RetryingIngressPersistence(val attempt: Int) : Gs1GattEngineState
    data class Reconnecting(val attempt: Int, val delayMillis: Long) : Gs1GattEngineState
    data object PersistencePending : Gs1GattEngineState
    data object Streaming : Gs1GattEngineState
    data class DataNotFresh(
        val sequence: Long?,
        val quality: ReadingQuality?,
    ) : Gs1GattEngineState
    data class DataRejected(
        val sequence: Int,
        val code: String,
        val detail: String,
    ) : Gs1GattEngineState
    data class Failed(
        val code: String,
        val detail: String? = null,
        val retryable: Boolean,
    ) : Gs1GattEngineState
}

private class Gs1GattStatePublisher<T>(
    initial: T,
    private val map: (Gs1GattEngineState) -> T,
) {
    private val mutable = MutableStateFlow(initial)
    val state: StateFlow<T> = mutable.asStateFlow()

    fun publish(value: Gs1GattEngineState) {
        mutable.value = map(value)
    }
}

/** Diagnostic facade retained for onboarding and physical validation. */
class Gs1DiagnosticGattDriver internal constructor(
    context: Context,
    factory: Gs1CoreFactory,
    ingressJournal: SensorPacketIngressJournal,
    committedIngressReader: CommittedSensorIngressReader =
        RoomCommittedSensorIngressReader.create(context.applicationContext),
    scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
    elapsedClock: () -> Long = SystemClock::elapsedRealtime,
) {
    constructor(context: Context) : this(
        context = context,
        factory = Gs1CoreFactory(SensorCoreRepository.create(context.applicationContext)),
        ingressJournal = RoomSensorPacketIngressJournal.create(context.applicationContext),
    )

    private val committedOutput = Gs1DiagnosticGattOutput()
    private val statePublisher = Gs1GattStatePublisher<Gs1DiagnosticGattState>(
        initial = Gs1DiagnosticGattState.Idle,
        map = { it.toDiagnosticState() },
    )
    private val engine = Gs1GattEngine(
        context = context,
        opener = FactoryGs1RuntimeCoreOpener(factory),
        ingressJournal = ingressJournal,
        committedIngressReader = committedIngressReader,
        committedOutput = committedOutput,
        publishState = statePublisher::publish,
        scope = scope,
        elapsedClock = elapsedClock,
    )

    val state: StateFlow<Gs1DiagnosticGattState> = statePublisher.state
    val latestDiagnostic: StateFlow<Gs1DiagnosticReading?> = committedOutput.latestDiagnostic

    suspend fun start(profile: Gs1DiagnosticActivationProfile) = engine.start(profile)
    suspend fun stop() = engine.stop()
    fun requestStop() = engine.requestStop()
}

/** Product facade over the same Bluetooth engine; it never exposes diagnostics. */
class Gs1ProductGattDriver internal constructor(
    context: Context,
    factory: Gs1CoreFactory,
    configurationReader: Gs1ProductConfigurationReader,
    ingressJournal: SensorPacketIngressJournal,
    committedIngressReader: CommittedSensorIngressReader =
        RoomCommittedSensorIngressReader.create(context.applicationContext),
    scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
    elapsedClock: () -> Long = SystemClock::elapsedRealtime,
) {
    constructor(context: Context) : this(
        context = context,
        factory = Gs1CoreFactory(SensorCoreRepository.create(context.applicationContext)),
        configurationReader = Gs1ProductConfigurationReader {
            LocalSensorBindingRepository.create(context.applicationContext).active()
        },
        ingressJournal = RoomSensorPacketIngressJournal.create(context.applicationContext),
    )

    private val committedOutput = Gs1ProductGattOutput()
    private val statePublisher = Gs1GattStatePublisher<Gs1ProductGattState>(
        initial = Gs1ProductGattState.Idle,
        map = { it.toProductState() },
    )
    private val engine = Gs1GattEngine(
        context = context,
        opener = FactoryGs1ApprovedRuntimeCoreOpener(
            factory = factory,
            permitIssuer = Gs1ProductPermitIssuer(configurationReader),
        ),
        ingressJournal = ingressJournal,
        committedIngressReader = committedIngressReader,
        committedOutput = committedOutput,
        publishState = statePublisher::publish,
        scope = scope,
        elapsedClock = elapsedClock,
    )

    val state: StateFlow<Gs1ProductGattState> = statePublisher.state
    val committedPublicationBatches: Flow<Gs1ProductPublicationBatch> =
        committedOutput.committedPublicationBatches

    suspend fun start(profile: Gs1DiagnosticActivationProfile) = engine.start(profile)
    suspend fun stop() = engine.stop()
    fun requestStop() = engine.requestStop()
}

private fun Gs1GattEngineState.toDiagnosticState(): Gs1DiagnosticGattState = when (this) {
    Gs1GattEngineState.Idle -> Gs1DiagnosticGattState.Idle
    Gs1GattEngineState.OpeningCore -> Gs1DiagnosticGattState.OpeningCore
    Gs1GattEngineState.Connecting -> Gs1DiagnosticGattState.Connecting
    is Gs1GattEngineState.ConnectingForHistoryBackfill ->
        Gs1DiagnosticGattState.ConnectingForHistoryBackfill(expectedIndex, firstPendingIndex, reason)
    Gs1GattEngineState.DiscoveringServices -> Gs1DiagnosticGattState.DiscoveringServices
    Gs1GattEngineState.Subscribing -> Gs1DiagnosticGattState.Subscribing
    Gs1GattEngineState.Authenticating -> Gs1DiagnosticGattState.Authenticating
    is Gs1GattEngineState.RetryingPersistence -> Gs1DiagnosticGattState.RetryingPersistence(attempt)
    is Gs1GattEngineState.RetryingIngressPersistence ->
        Gs1DiagnosticGattState.RetryingIngressPersistence(attempt)
    is Gs1GattEngineState.Reconnecting ->
        Gs1DiagnosticGattState.Reconnecting(attempt, delayMillis)
    Gs1GattEngineState.PersistencePending -> Gs1DiagnosticGattState.PersistencePending
    Gs1GattEngineState.Streaming -> Gs1DiagnosticGattState.StreamingDiagnostic
    is Gs1GattEngineState.DataNotFresh ->
        Gs1DiagnosticGattState.DiagnosticDataNotFresh(sequence, quality)
    is Gs1GattEngineState.DataRejected ->
        Gs1DiagnosticGattState.DiagnosticDataRejected(sequence, code, detail)
    is Gs1GattEngineState.Failed -> Gs1DiagnosticGattState.Failed(code, detail, retryable)
}

private fun Gs1GattEngineState.toProductState(): Gs1ProductGattState = when (this) {
    Gs1GattEngineState.Idle -> Gs1ProductGattState.Idle
    Gs1GattEngineState.OpeningCore -> Gs1ProductGattState.OpeningCore
    Gs1GattEngineState.Connecting -> Gs1ProductGattState.Connecting
    is Gs1GattEngineState.ConnectingForHistoryBackfill ->
        Gs1ProductGattState.ConnectingForHistoryBackfill(expectedIndex, firstPendingIndex, reason)
    Gs1GattEngineState.DiscoveringServices -> Gs1ProductGattState.DiscoveringServices
    Gs1GattEngineState.Subscribing -> Gs1ProductGattState.Subscribing
    Gs1GattEngineState.Authenticating -> Gs1ProductGattState.Authenticating
    is Gs1GattEngineState.RetryingPersistence -> Gs1ProductGattState.RetryingPersistence(attempt)
    is Gs1GattEngineState.RetryingIngressPersistence ->
        Gs1ProductGattState.RetryingIngressPersistence(attempt)
    is Gs1GattEngineState.Reconnecting -> Gs1ProductGattState.Reconnecting(attempt, delayMillis)
    Gs1GattEngineState.PersistencePending -> Gs1ProductGattState.PersistencePending
    Gs1GattEngineState.Streaming -> Gs1ProductGattState.Streaming
    is Gs1GattEngineState.DataNotFresh -> Gs1ProductGattState.WaitingForPublishableReading(sequence)
    is Gs1GattEngineState.DataRejected -> Gs1ProductGattState.DataRejected(sequence, code, detail)
    is Gs1GattEngineState.Failed -> Gs1ProductGattState.Failed(code, detail, retryable)
}

/**
 * Every attempt owns one callback, GATT object, protocol session and bounded
 * mailbox. The stateful core is reached only through [Gs1DiagnosticRuntime].
 */
private class Gs1GattEngine(
    context: Context,
    opener: Gs1RuntimeCoreOpener,
    private val ingressJournal: SensorPacketIngressJournal,
    private val committedIngressReader: CommittedSensorIngressReader,
    private val committedOutput: Gs1GattCommittedOutput,
    private val publishState: (Gs1GattEngineState) -> Unit,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
    private val elapsedClock: () -> Long = SystemClock::elapsedRealtime,
) {
    private val appContext = context.applicationContext
    private val bluetoothManager: BluetoothManager? =
        appContext.getSystemService(BluetoothManager::class.java)
    private val codec = SibionicsPacketCodec()
    private val transportRegistry = Gs1GattTransportRegistry<BluetoothGatt>(
        disconnect = ::safeDisconnect,
        close = ::safeClose,
    )
    private val lifecycle = Mutex()
    private val active = AtomicReference<Attempt?>(null)
    private val desired = AtomicReference<DesiredConnection?>(null)
    private val reconnectGate = Gs1ReconnectGate()
    private val durableIngress = Gs1DurableIngress(ingressJournal)
    private val durableCommitGate = Gs1GattDurableCommitGate(committedOutput)
    private val committedEventValidator =
        Gs1CommittedIngressEventValidator(committedIngressReader)
    private val unresolvedLiveSettler = Gs1UnresolvedLiveSettler(
        journal = ingressJournal,
        committedEventValidator = committedEventValidator,
    )

    private val coreRuntime = Gs1DiagnosticRuntime(
        scope = scope,
        opener = opener,
        eventSink = ::onCoreEvent,
    )
    private val pendingIngressRecovery = Gs1PendingIngressRecovery(
        journal = ingressJournal,
        committedIngressReader = committedIngressReader,
        codec = codec,
        replay = coreRuntime::submitAndAwait,
        onValidatedCommit = { event ->
            val roomEvent = when (val validated = committedEventValidator.validate(event)) {
                is Gs1CommittedIngressEventValidation.Accepted -> validated.event
                is Gs1CommittedIngressEventValidation.Failed -> throw
                    Gs1CommittedDeliveryUnavailableException(
                        code = validated.code,
                        detail = validated.detail,
                        retryable = validated.retryable,
                    )
            }
            when (val delivered = durableCommitGate.dispatchValidatedRecovery(roomEvent)) {
                is Gs1GattDurableCommitResult.Accepted -> Unit
                is Gs1GattDurableCommitResult.Rejected -> throw
                    Gs1CommittedDeliveryUnavailableException(
                        code = delivered.code,
                        detail = delivered.detail,
                        retryable = delivered.retryable,
                    )
            }
        },
    )
    private val liveIngressDuplicateGate = Gs1LiveIngressDuplicateGate(
        journal = ingressJournal,
        committedIngressReader = committedIngressReader,
        codec = codec,
    )

    suspend fun start(profile: Gs1DiagnosticActivationProfile) {
        lifecycle.withLock {
            committedOutput.abortPendingApplications(
                Gs1ProductLocalEffectsFailureCode.APPLICATION_STOPPED,
            )
            desired.getAndSet(null)?.let {
                it.stopRequested.set(true)
                reconnectGate.stop(it.reconnectToken)
            }
            val requested = DesiredConnection(
                profile = profile,
                reconnectToken = reconnectGate.begin(),
            )
            desired.set(requested)
            active.getAndSet(null)?.let { stopAttemptLocked(it) }
            startAttemptLocked(requested)
        }
    }

    private suspend fun startAttemptLocked(requested: DesiredConnection) {
            val profile = requested.profile
            try {
                requirePermissions()
            } catch (failure: Exception) {
                publishState(Gs1GattEngineState.Failed(
                    code = "BLUETOOTH_PERMISSION_REQUIRED",
                    detail = failure.message,
                    retryable = false,
                ))
                return
            }
            committedOutput.resetAttempt()
            if (requested.stopRequested.get() || desired.get() !== requested) {
                committedOutput.abortPendingApplications(
                    Gs1ProductLocalEffectsFailureCode.APPLICATION_STOPPED,
                )
                return
            }
            publishState(Gs1GattEngineState.OpeningCore)

            val dataHandle = when (
                val opened = RemoteDataHandleGatewayConnector.connect(
                    appContext,
                    profile.transportVariant,
                )
            ) {
                is DataHandleGatewayOpenResult.Success -> opened.gateway
                is DataHandleGatewayOpenResult.Failure -> {
                    publishPreAttemptFailure(
                        requested = requested,
                        code = "DATAHANDLE_GATEWAY_UNAVAILABLE",
                        detail = opened.error.name,
                        retryable = true,
                    )
                    return
                }
            }
            val dataHandleBinding = try {
                Gs1V120DataHandleBinding.bind(profile.transportVariant, dataHandle)
            } catch (invalid: IllegalArgumentException) {
                dataHandle.close()
                publishPreAttemptFailure(
                    requested = requested,
                    code = "DATAHANDLE_BUNDLE_MISMATCH",
                    detail = invalid.message,
                    retryable = false,
                )
                return
            }

            val core = when (
                val result = coreRuntime.start(profile, dataHandleBinding.packetVerifier)
            ) {
                is Gs1RuntimeStartResult.Started -> result
                is Gs1RuntimeStartResult.Failed -> {
                    dataHandle.close()
                    if (result.code == "PERSISTENCE_PENDING") {
                        publishState(Gs1GattEngineState.PersistencePending)
                        reconnectGate.onRetryableFailure(requested.reconnectToken)?.let(::scheduleReconnect)
                    } else {
                        publishPreAttemptFailure(
                            requested = requested,
                            code = result.code,
                            detail = result.detail,
                            retryable = result.code == "STORAGE_UNAVAILABLE",
                        )
                    }
                    return
                }
            }
            if (requested.stopRequested.get() || desired.get() !== requested) {
                committedOutput.abortPendingApplications(
                    Gs1ProductLocalEffectsFailureCode.APPLICATION_STOPPED,
                )
                coreRuntime.stop(expectedGeneration = core.generation)
                dataHandle.close()
                return
            }
            var coreTransferred = false
            try {
                val completedRecovery = when (
                    val recovery = pendingIngressRecovery.recover(
                        profile = profile,
                        generation = core.generation,
                        initialCoreCursor = core.initialNextIndex,
                        initialWireProfile = core.wireProfile,
                    )
                ) {
                    is Gs1PendingIngressRecoveryResult.Completed -> recovery
                    is Gs1PendingIngressRecoveryResult.Failed -> {
                        publishPreAttemptFailure(
                            requested = requested,
                            code = recovery.code,
                            detail = recovery.detail,
                            retryable = recovery.retryable,
                        )
                        return
                    }
                }
                val recoveryCursor = completedRecovery.finalCoreCursor
                val adapter = bluetoothManager?.adapter
                if (adapter == null) {
                    publishState(Gs1GattEngineState.Failed(
                        code = "BLUETOOTH_UNAVAILABLE",
                        retryable = false,
                    ))
                    return
                }
                val enabled = try {
                    adapter.isEnabled
                } catch (failure: SecurityException) {
                    publishState(Gs1GattEngineState.Failed(
                        code = "BLUETOOTH_PERMISSION_REVOKED",
                        detail = failure.message,
                        retryable = false,
                    ))
                    return
                }
                if (!enabled) {
                    publishPreAttemptFailure(
                        requested = requested,
                        code = "BLUETOOTH_DISABLED",
                        detail = null,
                        retryable = true,
                    )
                    return
                }

                val transport = transportRegistry.begin(profile)
                val token = transport.token
                val initialDeadline = Gs1GattDeadlinePolicy.begin(
                    generation = token.generation,
                    nowElapsedMillis = elapsedClock(),
                )
                val session = SibionicsSession(
                    family = profile.family,
                    configuration = SensorConfiguration(
                        sensorId = profile.sensorId,
                        bluetoothAddress = profile.bluetoothAddress,
                        protocolVariant = profile.transportVariant,
                    ),
                    gs1CommandProvider = { dataHandleBinding.commandCodec },
                    initialNextIndex = recoveryCursor,
                    initialWireProfile = completedRecovery.finalWireProfile,
                )
                val attempt = Attempt(
                    profile = profile,
                    transport = transport,
                    coreGeneration = core.generation,
                    session = session,
                    deadlinePolicy = initialDeadline.policy,
                    reconnectToken = requested.reconnectToken,
                    dataHandle = dataHandle,
                )
                attempt.job = scope.launch { runAttempt(attempt) }
                active.set(attempt)
                coreTransferred = true
                publishState(completedRecovery.blocked?.let { blocked ->
                    Gs1GattEngineState.ConnectingForHistoryBackfill(
                        expectedIndex = recoveryCursor,
                        firstPendingIndex = blocked.firstIndex,
                        reason = blocked.disposition.name,
                    )
                } ?: Gs1GattEngineState.Connecting)
                scheduleDeadline(attempt, initialDeadline.deadline)

                val device = try {
                    adapter.getRemoteDevice(profile.bluetoothAddress)
                } catch (failure: SecurityException) {
                    failOffer(attempt, "BLUETOOTH_PERMISSION_REVOKED", failure.message, false)
                    return
                } catch (failure: IllegalArgumentException) {
                    failOffer(attempt, "INVALID_BLUETOOTH_ADDRESS", failure.message, false)
                    return
                }
                val callback = callback(attempt)
                val gatt = try {
                    connect(device, callback)
                } catch (failure: SecurityException) {
                    failOffer(attempt, "BLUETOOTH_PERMISSION_REVOKED", failure.message, false)
                    return
                } catch (failure: Exception) {
                    failOffer(attempt, "GATT_CONNECT_FAILED", failure.message, true)
                    return
                }
                val reportedBluetoothAddress = try {
                    gatt.device.address
                } catch (failure: SecurityException) {
                    transportRegistry.releaseIfUnowned(transport, gatt)
                    failOffer(attempt, "BLUETOOTH_PERMISSION_REVOKED", failure.message, false)
                    return
                }
                if (!transportRegistry.bindConnectResult(
                        lease = transport,
                        gatt = gatt,
                        reportedBluetoothAddress = reportedBluetoothAddress,
                    )
                ) {
                    failOffer(attempt, "GATT_IDENTITY_CONFLICT", null, false)
                }
            } finally {
                if (!coreTransferred) {
                    coreRuntime.stop(expectedGeneration = core.generation)
                    dataHandle.close()
                }
            }
    }

    suspend fun stop() {
        // Abort local-effects delivery before waiting for the lifecycle mutex.
        // Recovery may be holding that mutex while waiting for this exact ack.
        requestStop()
        lifecycle.withLock {
            // Closes the narrow race where stop started before start published
            // its desired connection.
            requestStop()
            val attempt = active.getAndSet(null)
            val drained = if (attempt != null) {
                stopAttemptLocked(attempt)
            } else {
                coreRuntime.stop() == Gs1RuntimeStopResult.DRAINED
            }
            publishState(if (drained) {
                Gs1GattEngineState.Idle
            } else {
                Gs1GattEngineState.PersistencePending
            })
        }
    }

    /**
     * Synchronously revokes reconnect intent and closes the current GATT transport.
     * The suspend [stop] call must still follow to drain and persist native state.
     */
    fun requestStop() {
        committedOutput.abortPendingApplications(
            Gs1ProductLocalEffectsFailureCode.APPLICATION_STOPPED,
        )
        desired.getAndSet(null)?.let {
            it.stopRequested.set(true)
            reconnectGate.stop(it.reconnectToken)
        }
        active.get()?.let { attempt ->
            attempt.stopRequested.set(true)
            closeAttemptTransport(attempt)
        }
    }

    @SuppressLint("MissingPermission")
    private fun connect(
        device: BluetoothDevice,
        callback: BluetoothGattCallback,
    ): BluetoothGatt = device.connectGatt(
        appContext,
        false,
        callback,
        BluetoothDevice.TRANSPORT_LE,
    )

    private fun callback(attempt: Attempt) = object : BluetoothGattCallback() {
        @SuppressLint("MissingPermission")
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            offerCallback(attempt, gatt, GattEvent.Connection(status, newState))
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            offerCallback(attempt, gatt, GattEvent.ServicesDiscovered(status))
        }

        override fun onDescriptorWrite(
            gatt: BluetoothGatt,
            descriptor: BluetoothGattDescriptor,
            status: Int,
        ) {
            if (!acceptCallback(attempt, gatt)) return
            if (descriptor.uuid != SibionicsUuids.CLIENT_CHARACTERISTIC_CONFIGURATION ||
                descriptor.characteristic.uuid != SibionicsUuids.NOTIFY
            ) {
                failOffer(attempt, "UNEXPECTED_GATT_DESCRIPTOR", descriptor.uuid.toString(), false)
                return
            }
            offer(attempt, GattEvent.SubscriptionWritten(status))
        }

        override fun onCharacteristicWrite(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            status: Int,
        ) {
            if (!acceptCallback(attempt, gatt)) return
            if (characteristic.uuid != SibionicsUuids.WRITE) {
                failOffer(attempt, "UNEXPECTED_GATT_WRITE_ENDPOINT", characteristic.uuid.toString(), false)
                return
            }
            offer(attempt, GattEvent.CommandWritten(status))
        }

        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray,
        ) {
            if (!acceptCallback(attempt, gatt)) return
            if (characteristic.uuid != SibionicsUuids.NOTIFY) {
                failOffer(attempt, "UNEXPECTED_GATT_NOTIFY_ENDPOINT", characteristic.uuid.toString(), false)
                return
            }
            offer(attempt, GattEvent.Notification(value.copyOf()))
        }

        @Suppress("OVERRIDE_DEPRECATION", "DEPRECATION")
        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
        ) {
            val value = characteristic.value?.copyOf() ?: return
            onCharacteristicChanged(gatt, characteristic, value)
        }
    }

    @SuppressLint("MissingPermission")
    private fun offerCallback(attempt: Attempt, gatt: BluetoothGatt, event: GattEvent) {
        if (acceptCallback(attempt, gatt)) offer(attempt, event)
    }

    @SuppressLint("MissingPermission")
    private fun acceptCallback(attempt: Attempt, gatt: BluetoothGatt): Boolean {
        val address = try {
            gatt.device.address
        } catch (_: SecurityException) {
            transportRegistry.releaseIfUnowned(attempt.transport, gatt)
            failOffer(attempt, "BLUETOOTH_PERMISSION_REVOKED", null, false)
            return false
        }
        if (!transportRegistry.acceptCallback(attempt.transport, gatt, address)) {
            // A stale callback is not allowed to mutate any current state.
            return false
        }
        return true
    }

    private fun offer(attempt: Attempt, event: GattEvent) {
        if (!attempt.accepting.get()) return
        val result = attempt.mailbox.trySend(event)
        if (result.isSuccess || result.isClosed) return
        failOffer(
            attempt,
            code = "GATT_MAILBOX_OVERFLOW",
            detail = "Bluetooth callbacks outran the diagnostic actor",
            retryable = true,
        )
    }

    @SuppressLint("MissingPermission")
    private fun failOffer(
        attempt: Attempt,
        code: String,
        detail: String?,
        retryable: Boolean,
    ) {
        val failure = GattEvent.Failure(code, detail, retryable)
        attempt.terminalFailure.offer(failure)
        closeAttemptTransport(attempt)
        attempt.mailbox.trySend(failure)
    }

    private suspend fun runAttempt(attempt: Attempt) {
        var terminal: GattEvent.Failure? = null
        try {
            for (event in attempt.mailbox) {
                terminal = terminal ?: attempt.terminalFailure.current()
                if (terminal == null) {
                    val localFailure = handleEvent(attempt, event)
                    val winningFailure = attempt.terminalFailure.resolve(localFailure)
                    if (winningFailure != null) {
                        terminal = winningFailure
                        closeAttemptTransport(attempt)
                    }
                } else if (event is GattEvent.Notification) {
                    val persisted = persistNotification(attempt, event.bytes)
                    if (persisted is Gs1DurableIngressResult.Failed) {
                        val ingressFailure = failure(
                            persisted.code,
                            persisted.detail,
                            persisted.retryable,
                        )
                        terminal = attempt.terminalFailure.offer(ingressFailure)
                    }
                } else if (event is GattEvent.Core &&
                    event.value is Gs1DiagnosticRuntimeEvent.Committed
                ) {
                    event.value.rejectSettlement(
                        "APPLICATION_STOPPED",
                        "GATT attempt ended before committed output settlement",
                    )
                }
            }
        } finally {
            withContext(NonCancellable) {
                closeAttemptTransport(attempt)
                attempt.mailbox.cancel()
                val coreStop = coreRuntime.stop(expectedGeneration = attempt.coreGeneration)
                attempt.dataHandle.close()
                if (!attempt.stopRequested.get() && active.get() === attempt) {
                    val failure = attempt.terminalFailure.current() ?: terminal ?: GattEvent.Failure(
                        code = "GATT_ATTEMPT_ENDED",
                        detail = null,
                        retryable = true,
                    )
                    val stillDesired = desired.get()?.reconnectToken == attempt.reconnectToken
                    val reconnect = if (stillDesired && failure.retryable) {
                        reconnectGate.onRetryableFailure(attempt.reconnectToken)
                    } else {
                        null
                    }
                    publishState(when {
                        coreStop == Gs1RuntimeStopResult.PERSISTENCE_PENDING ->
                            Gs1GattEngineState.PersistencePending
                        reconnect != null -> Gs1GattEngineState.Reconnecting(
                            attempt = reconnect.attempt,
                            delayMillis = reconnect.delayMillis,
                        )
                        else -> Gs1GattEngineState.Failed(
                            code = failure.code,
                            detail = failure.detail,
                            retryable = failure.retryable,
                        )
                    })
                    if (reconnect != null) scheduleReconnect(reconnect)
                }
                active.compareAndSet(attempt, null)
            }
        }
    }

    @SuppressLint("MissingPermission")
    private suspend fun handleEvent(attempt: Attempt, event: GattEvent): GattEvent.Failure? =
        when (event) {
            is GattEvent.Connection -> when (event.newState) {
                BluetoothProfile.STATE_CONNECTED -> {
                    if (attempt.phase != GattPhase.CONNECTING) {
                        failure("OUT_OF_PHASE_CONNECTION", null, false)
                    } else {
                        attempt.phase = GattPhase.DISCOVERING
                        publishState(Gs1GattEngineState.DiscoveringServices)
                        enterDeadline(attempt, Gs1GattDeadlinePhase.DISCOVERING)
                        val gatt = transportRegistry.current(attempt.transport)
                            ?: return failure("GATT_UNBOUND", null, false)
                        if (event.status != BluetoothGatt.GATT_SUCCESS || !gatt.discoverServices()) {
                            failure("SERVICE_DISCOVERY_DID_NOT_START", event.status.toString(), true)
                        } else null
                    }
                }

                BluetoothProfile.STATE_DISCONNECTED -> failure(
                    "SENSOR_DISCONNECTED",
                    event.status.toString(),
                    true,
                )

                else -> null
            }

            is GattEvent.ServicesDiscovered -> {
                if (attempt.phase != GattPhase.DISCOVERING) {
                    failure("OUT_OF_PHASE_SERVICES", null, false)
                } else if (event.status != BluetoothGatt.GATT_SUCCESS) {
                    failure("SERVICE_DISCOVERY_FAILED", event.status.toString(), true)
                } else {
                    enterDeadline(attempt, Gs1GattDeadlinePhase.SUBSCRIBING)
                    subscribe(attempt)
                }
            }

            is GattEvent.SubscriptionWritten -> {
                if (attempt.phase != GattPhase.SUBSCRIBING) {
                    failure("OUT_OF_PHASE_SUBSCRIPTION", null, false)
                } else if (event.status != BluetoothGatt.GATT_SUCCESS) {
                    failure("SUBSCRIPTION_FAILED", event.status.toString(), true)
                } else {
                    attempt.phase = GattPhase.AUTHENTICATING
                    publishState(Gs1GattEngineState.Authenticating)
                    enterDeadline(attempt, Gs1GattDeadlinePhase.HANDSHAKE)
                    execute(attempt, attempt.session.initial(attempt.profile.bluetoothAddress))
                }
            }

            is GattEvent.CommandWritten -> {
                val success = event.status == BluetoothGatt.GATT_SUCCESS
                when (val result = attempt.commandArbiter.onWriteCallback(success)) {
                    is Gs1GattCommandArbiterResult.StartWrite -> startPhysicalWrite(attempt, result.bytes)
                    Gs1GattCommandArbiterResult.Idle -> {
                        if (attempt.phase != GattPhase.STREAMING) {
                            enterDeadline(attempt, Gs1GattDeadlinePhase.HANDSHAKE)
                        }
                        null
                    }
                    is Gs1GattCommandArbiterResult.Failed -> failure(
                        code = if (success) result.code else "COMMAND_WRITE_FAILED",
                        detail = if (success) null else event.status.toString(),
                        retryable = !success,
                    )
                    is Gs1GattCommandArbiterResult.Rejected -> failure(
                        "COMMAND_ARBITER_REJECTED_CALLBACK",
                        result.code,
                        false,
                    )
                    Gs1GattCommandArbiterResult.Queued,
                    is Gs1GattCommandArbiterResult.Cleared,
                    -> failure("COMMAND_ARBITER_INVALID_TRANSITION", null, false)
                }
            }

            is GattEvent.Notification -> processNotification(attempt, event.bytes)

            is GattEvent.Core -> handleCoreEvent(attempt, event.value)

            is GattEvent.DeadlineFired -> when (
                val decision = attempt.deadlinePolicy.onTimerFired(event.token)
            ) {
                Gs1GattTimerDecision.Stale -> null
                is Gs1GattTimerDecision.TerminalTimeout -> failure(
                    code = "GATT_${decision.phase.name}_TIMEOUT",
                    detail = null,
                    retryable = true,
                )
            }

            is GattEvent.StreamFreshnessExpired -> when (
                attempt.streamFreshnessPolicy.onTimerFired(event.token)
            ) {
                Gs1StreamFreshnessDecision.Stale -> null
                Gs1StreamFreshnessDecision.Silent -> failure(
                    code = "SENSOR_STREAM_SILENT",
                    detail = "No durably committed transport data arrived before the silence deadline",
                    retryable = true,
                )
            }

            is GattEvent.DelayedCommand -> {
                if (event.sequence != attempt.delayedWriteSequence) {
                    null
                } else {
                    enqueueCommand(attempt, event.bytes)
                }
            }

            is GattEvent.Failure -> event
        }

    @SuppressLint("MissingPermission")
    private fun subscribe(attempt: Attempt): GattEvent.Failure? {
        val gatt = transportRegistry.current(attempt.transport)
            ?: return failure("GATT_UNBOUND", null, false)
        val service = gatt.getService(SibionicsUuids.SERVICE)
            ?: return failure("GS1_SERVICE_MISSING", null, false)
        val notify = service.getCharacteristic(SibionicsUuids.NOTIFY)
            ?: return failure("GS1_NOTIFY_MISSING", null, false)
        val write = service.getCharacteristic(SibionicsUuids.WRITE)
            ?: return failure("GS1_WRITE_MISSING", null, false)
        val descriptor = notify.getDescriptor(SibionicsUuids.CLIENT_CHARACTERISTIC_CONFIGURATION)
            ?: return failure("GS1_CCCD_MISSING", null, false)
        if (!gatt.setCharacteristicNotification(notify, true)) {
            return failure("LOCAL_NOTIFICATION_ENABLE_FAILED", null, true)
        }
        attempt.writeCharacteristic.set(write)
        attempt.phase = GattPhase.SUBSCRIBING
        publishState(Gs1GattEngineState.Subscribing)
        val started = if (Build.VERSION.SDK_INT >= 33) {
            gatt.writeDescriptor(descriptor, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)
        } else {
            @Suppress("DEPRECATION")
            descriptor.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
            @Suppress("DEPRECATION")
            if (gatt.writeDescriptor(descriptor)) {
                GATT_OPERATION_SUCCESS
            } else {
                GATT_OPERATION_FAILED
            }
        }
        return if (started == GATT_OPERATION_SUCCESS) null else {
            failure("SUBSCRIPTION_DID_NOT_START", started.toString(), true)
        }
    }

    private suspend fun processNotification(
        attempt: Attempt,
        encrypted: ByteArray,
    ): GattEvent.Failure? {
        if (attempt.phase != GattPhase.AUTHENTICATING &&
            attempt.phase != GattPhase.STREAMING
        ) {
            return failure("OUT_OF_PHASE_NOTIFICATION", null, false)
        }
        val journaled = when (val persisted = persistNotification(attempt, encrypted)) {
            is Gs1DurableIngressResult.Stored -> persisted.packet
            is Gs1DurableIngressResult.Failed -> return failure(
                persisted.code,
                persisted.detail,
                persisted.retryable,
            )
        }
        attempt.terminalFailure.current()?.let { return it }
        var packetForCore = journaled
        when (
            val duplicate = liveIngressDuplicateGate.resolve(
                profile = attempt.profile,
                currentCoreCursor = attempt.session.durableNextIndex,
                wireProfile = attempt.session.wireProfile,
                currentIngress = journaled.ingress,
            )
        ) {
            Gs1LiveIngressDuplicateResult.NotDuplicate -> Unit
            is Gs1LiveIngressDuplicateResult.VerifiedSuffix -> {
                packetForCore = journaled.withVerifiedCommittedPrefix(duplicate)
            }
            Gs1LiveIngressDuplicateResult.Handled -> {
                if (attempt.phase != GattPhase.STREAMING) {
                    attempt.phase = GattPhase.STREAMING
                    attempt.deadlinePolicy = attempt.deadlinePolicy.markStreaming()
                }
                reconnectGate.markStable(attempt.reconnectToken)
                armTransportSilenceWatchdog(attempt)
                publishState(Gs1GattEngineState.Streaming)
                return null
            }
            is Gs1LiveIngressDuplicateResult.Failed -> return failure(
                duplicate.code,
                duplicate.detail,
                duplicate.retryable,
            )
        }
        val exactPacket = packetForCore.encryptedPacketCopy()
        return when (attempt.session.wireProfile) {
            Gs1WireProfile.UNRESOLVED -> processUnresolvedNotification(attempt, packetForCore)
            Gs1WireProfile.V115 -> processV115Notification(attempt, packetForCore)
            Gs1WireProfile.V120 -> processV120Notification(attempt, packetForCore, exactPacket)
        }
    }

    private suspend fun processUnresolvedNotification(
        attempt: Attempt,
        journaled: DurablyJournaledGs1Packet,
    ): GattEvent.Failure? = when (
        val awaited = coreRuntime.submitAndAwait(attempt.coreGeneration, journaled)
    ) {
        Gs1RuntimeAwaitResult.StaleGeneration -> failure("STALE_CORE_GENERATION", null, false)
        Gs1RuntimeAwaitResult.Closed -> failure("CORE_RUNTIME_CLOSED", null, true)
        is Gs1RuntimeAwaitResult.Processed -> when (
            val settled = unresolvedLiveSettler.settle(
                generation = attempt.coreGeneration,
                session = attempt.session,
                ingress = journaled.ingress,
                result = awaited.result,
                settleCommitted = { roomEvent ->
                    durableCommitGate.dispatch(
                        event = roomEvent,
                        confirmDurableCursor = attempt.session::confirmDurablyCommitted,
                    )
                },
            )
        ) {
            is Gs1UnresolvedLiveSettlementResult.Failed -> failure(
                settled.code,
                settled.detail,
                settled.retryable,
            )
            is Gs1UnresolvedLiveSettlementResult.Completed -> {
                settled.presentation?.let { presentation ->
                    applyCommittedPresentation(attempt, presentation)
                } ?: execute(attempt, settled.nextProtocolAction)
            }
        }
    }

    private suspend fun processV115Notification(
        attempt: Attempt,
        journaled: DurablyJournaledGs1Packet,
    ): GattEvent.Failure? {
        val exactPacket = journaled.encryptedPacketCopy()
        if (Gs1V115WireCodec.isV120Challenge(exactPacket)) {
            return failure(
                "WIRE_PROFILE_MISMATCH",
                "V120 challenge arrived after V115 was durably bound",
                false,
            )
        }
        val decoded = Gs1V115WireCodec.decode(exactPacket, journaled.receivedAtEpochMs)
        if (decoded is Gs1V115DecodeResult.Failure) {
            return persistLiveOutcome(
                Gs1DiagnosticRuntimeEvent.Finalized(
                    generation = attempt.coreGeneration,
                    ingressId = journaled.ingressId,
                    receivedAtEpochMs = journaled.receivedAtEpochMs,
                    disposition = Gs1RuntimeIngressDisposition.QUARANTINED,
                    detail = "V115_${decoded.error.name}",
                ),
            ) ?: failure("INVALID_V115_SENSOR_PACKET", decoded.error.name, false)
        }
        return submitToCore(attempt, journaled)
    }

    private suspend fun processV120Notification(
        attempt: Attempt,
        journaled: DurablyJournaledGs1Packet,
        exactPacket: ByteArray,
    ): GattEvent.Failure? = when (
        val packet = codec.decode(attempt.profile.family, exactPacket)
    ) {
            is DecodedPacket.Gs1RawSamples -> {
                val protocol = attempt.session.onPacket(packet)
                if (protocol is SessionAction.Failure) {
                    failure("PROTOCOL_REJECTED_DATA", protocol.reason, false)
                } else {
                    submitToCore(attempt, journaled)
                }
            }

            is DecodedPacket.Invalid -> {
                persistLiveOutcome(
                    Gs1DiagnosticRuntimeEvent.Finalized(
                        generation = attempt.coreGeneration,
                        ingressId = journaled.ingressId,
                        receivedAtEpochMs = journaled.receivedAtEpochMs,
                        disposition = Gs1RuntimeIngressDisposition.QUARANTINED,
                        detail = packet.reason,
                    ),
                ) ?: failure("INVALID_SENSOR_PACKET", packet.reason, true)
            }
            else -> {
                val protocolFailure = execute(attempt, attempt.session.onPacket(packet))
                if (protocolFailure != null) {
                    protocolFailure
                } else {
                    persistLiveOutcome(
                        Gs1DiagnosticRuntimeEvent.Finalized(
                            generation = attempt.coreGeneration,
                            ingressId = journaled.ingressId,
                            receivedAtEpochMs = journaled.receivedAtEpochMs,
                            disposition = Gs1RuntimeIngressDisposition.NON_DATA,
                        ),
                    )
                }
            }
        }

    private fun submitToCore(
        attempt: Attempt,
        journaled: DurablyJournaledGs1Packet,
    ): GattEvent.Failure? = when (coreRuntime.submit(attempt.coreGeneration, journaled)) {
        Gs1RuntimeSubmission.ACCEPTED -> {
            attempt.delayedWriteSequence = nextSequence(attempt.delayedWriteSequence)
            null
        }
        Gs1RuntimeSubmission.OVERFLOW -> failure("CORE_MAILBOX_OVERFLOW", null, true)
        Gs1RuntimeSubmission.STALE_GENERATION -> failure("STALE_CORE_GENERATION", null, false)
        Gs1RuntimeSubmission.CLOSED -> failure("CORE_RUNTIME_CLOSED", null, true)
    }

    private suspend fun persistNotification(
        attempt: Attempt,
        encrypted: ByteArray,
    ): Gs1DurableIngressResult {
        val ordinal = attempt.nextIngressOrdinal.getAndIncrement()
        val captured = when (
            val result = durableIngress.capture(
                profile = attempt.profile,
                attemptId = attempt.attemptId,
                ordinal = ordinal,
                encryptedPacket = encrypted,
            )
        ) {
            is Gs1IngressCaptureResult.Ready -> result.pending
            is Gs1IngressCaptureResult.Failed -> return Gs1DurableIngressResult.Failed(
                result.code,
                result.detail,
                result.retryable,
            )
        }
        var retry = 0
        while (true) {
            if (attempt.stopRequested.get() || !currentCoroutineContext().isActive) {
                return Gs1DurableIngressResult.Failed(
                    code = "INGRESS_PERSISTENCE_INTERRUPTED",
                    detail = "Stop requested; the sensor transport is closed",
                    retryable = true,
                )
            }
            when (val persisted = durableIngress.persist(captured)) {
                is Gs1DurableIngressResult.Stored -> return persisted
                is Gs1DurableIngressResult.Failed -> {
                    if (!persisted.retryable) return persisted
                    retry += 1
                    if (retry == 1) {
                        attempt.terminalFailure.offer(
                            failure(persisted.code, persisted.detail, retryable = true),
                        )
                        closeAttemptTransport(attempt)
                    }
                    if (active.get() === attempt) {
                        publishState(Gs1GattEngineState.RetryingIngressPersistence(retry))
                    }
                    delay(INGRESS_RETRY_DELAY_MS)
                }
            }
        }
    }

    private suspend fun execute(attempt: Attempt, action: SessionAction): GattEvent.Failure? =
        when (action) {
            is SessionAction.Write -> {
                attempt.delayedWriteSequence = nextSequence(attempt.delayedWriteSequence)
                val plan = Gs1SessionWritePlanPolicy.plan(
                    streaming = attempt.phase == GattPhase.STREAMING,
                    action = action,
                )
                val failure = if (plan.enqueue) {
                    enqueueCommand(attempt, action.bytes)
                } else {
                    failure("EMPTY_PROTOCOL_COMMAND", null, false)
                }
                if (failure == null && plan.armTransportSilenceWatchdogAfterEnqueue) {
                    armTransportSilenceWatchdog(attempt)
                }
                failure
            }
            is SessionAction.WriteAfter -> {
                when (val retry = attempt.deadlinePolicy.requestAuthRetry(elapsedClock())) {
                    is Gs1GattAuthRetryDecision.Terminal -> failure(
                        code = retry.code,
                        detail = null,
                        retryable = retry.retryable,
                    )
                    is Gs1GattAuthRetryDecision.Allowed -> {
                        if (action.delayMillis != retry.delayMillis) {
                            failure(
                                code = "AUTH_RETRY_DELAY_MISMATCH",
                                detail = "protocol=${action.delayMillis}, policy=${retry.delayMillis}",
                                retryable = false,
                            )
                        } else {
                            attempt.deadlinePolicy = retry.policy
                            val sequence = nextSequence(attempt.delayedWriteSequence)
                            attempt.delayedWriteSequence = sequence
                            val bytes = action.bytes.copyOf()
                            scope.launch {
                                delay(retry.delayMillis)
                                offer(attempt, GattEvent.DelayedCommand(sequence, bytes))
                            }
                            null
                        }
                    }
                }
            }
            is SessionAction.Failure -> failure("PROTOCOL_FAILURE", action.reason, false)
            SessionAction.None -> null
        }

    private fun enqueueCommand(attempt: Attempt, bytes: ByteArray): GattEvent.Failure? =
        when (val result = attempt.commandArbiter.enqueue(bytes)) {
            is Gs1GattCommandArbiterResult.StartWrite -> startPhysicalWrite(attempt, result.bytes)
            Gs1GattCommandArbiterResult.Queued -> null
            is Gs1GattCommandArbiterResult.Rejected -> failure(
                "COMMAND_ARBITER_REJECTED_WRITE",
                result.code,
                false,
            )
            Gs1GattCommandArbiterResult.Idle,
            is Gs1GattCommandArbiterResult.Failed,
            is Gs1GattCommandArbiterResult.Cleared,
            -> failure("COMMAND_ARBITER_INVALID_TRANSITION", null, false)
        }

    @SuppressLint("MissingPermission")
    private fun startPhysicalWrite(attempt: Attempt, bytes: ByteArray): GattEvent.Failure? {
        val gatt = transportRegistry.current(attempt.transport)
            ?: return failCurrentWrite(attempt, "GATT_UNBOUND", null, false)
        val characteristic = attempt.writeCharacteristic.get()
            ?: return failCurrentWrite(attempt, "GS1_WRITE_MISSING", null, false)
        characteristic.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
        val started = if (Build.VERSION.SDK_INT >= 33) {
            gatt.writeCharacteristic(
                characteristic,
                bytes.copyOf(),
                BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT,
            )
        } else {
            @Suppress("DEPRECATION")
            characteristic.value = bytes.copyOf()
            @Suppress("DEPRECATION")
            if (gatt.writeCharacteristic(characteristic)) {
                GATT_OPERATION_SUCCESS
            } else {
                GATT_OPERATION_FAILED
            }
        }
        if (started != GATT_OPERATION_SUCCESS) {
            return failCurrentWrite(
                attempt = attempt,
                code = "COMMAND_WRITE_DID_NOT_START",
                detail = started.toString(),
                retryable = true,
            )
        }
        if (attempt.phase != GattPhase.STREAMING) {
            enterDeadline(attempt, Gs1GattDeadlinePhase.COMMAND_WRITE)
        }
        return null
    }

    private fun failCurrentWrite(
        attempt: Attempt,
        code: String,
        detail: String?,
        retryable: Boolean,
    ): GattEvent.Failure {
        attempt.commandArbiter.onWriteCallback(success = false)
        return failure(code, detail, retryable)
    }

    private suspend fun onCoreEvent(event: Gs1DiagnosticRuntimeEvent) {
        val attempt = active.get()
        deliverGs1CoreEventOrFailCommitted(
            event = event,
            activeGeneration = attempt?.coreGeneration,
            accepting = attempt?.accepting?.get() == true,
        ) {
            // Runtime events are already durable and must not compete with
            // callback trySend capacity. Backpressure keeps their exact order.
            checkNotNull(attempt).mailbox.send(GattEvent.Core(event))
        }
    }

    private suspend fun handleCoreEvent(
        attempt: Attempt,
        event: Gs1DiagnosticRuntimeEvent,
    ): GattEvent.Failure? = when (event) {
        is Gs1DiagnosticRuntimeEvent.Finalized -> persistLiveOutcome(event)

        is Gs1DiagnosticRuntimeEvent.RetryingPersistence -> {
            publishState(Gs1GattEngineState.RetryingPersistence(event.attempt))
            failure(
                code = "CORE_PERSISTENCE_PENDING",
                detail = "Native state is waiting for an exact durable commit",
                retryable = true,
            )
        }

        is Gs1DiagnosticRuntimeEvent.Committed -> {
            val roomEvent = when (val validated = committedEventValidator.validate(event)) {
                is Gs1CommittedIngressEventValidation.Accepted -> validated.event
                is Gs1CommittedIngressEventValidation.Failed -> {
                    event.rejectSettlement(validated.code, validated.detail)
                    return failure(validated.code, validated.detail, validated.retryable)
                }
            }
            when (
                val gated = durableCommitGate.dispatch(
                    event = roomEvent,
                    confirmDurableCursor = attempt.session::confirmDurablyCommitted,
                )
            ) {
                is Gs1GattDurableCommitResult.Rejected -> {
                    event.rejectSettlement(gated.code, gated.detail)
                    failure(
                        gated.code,
                        gated.detail,
                        gated.retryable,
                    )
                }
                is Gs1GattDurableCommitResult.Accepted -> {
                    event.acknowledgeDurablySettled()
                    applyCommittedPresentation(attempt, gated.presentation)
                }
            }
        }

        is Gs1DiagnosticRuntimeEvent.Failed -> failure(event.code, event.detail, false)
    }

    private fun applyCommittedPresentation(
        attempt: Attempt,
        presentation: Gs1GattCommittedPresentation,
    ): GattEvent.Failure? {
        val progressPlan = Gs1DiagnosticCommitProgressPolicy.plan(
            alreadyStreaming = attempt.phase == GattPhase.STREAMING,
            assessment = Gs1DiagnosticCommitAssessment(
                latest = null,
                hasTransportProgress = presentation.hasTransportProgress,
                hasFreshDiagnostic = presentation.hasFreshOutput,
            ),
        )
        if (!presentation.hasTransportProgress) return null
        if (progressPlan.markStreaming) {
            attempt.phase = GattPhase.STREAMING
            attempt.deadlinePolicy = attempt.deadlinePolicy.markStreaming()
        }
        if (progressPlan.armSilenceWatchdog) {
            armTransportSilenceWatchdog(attempt)
        }
        val issue = presentation.issue
        publishState(if (issue != null) {
            Gs1GattEngineState.DataRejected(
                sequence = issue.sequence,
                code = issue.code,
                detail = issue.message,
            )
        } else if (presentation.hasFreshOutput) {
            reconnectGate.markStable(attempt.reconnectToken)
            Gs1GattEngineState.Streaming
        } else {
            Gs1GattEngineState.DataNotFresh(
                sequence = presentation.latestSequence,
                quality = presentation.latestQuality,
            )
        })
        return null
    }

    private suspend fun persistLiveOutcome(
        event: Gs1DiagnosticRuntimeEvent.Finalized,
    ): GattEvent.Failure? {
        val status = when (event.disposition) {
            Gs1RuntimeIngressDisposition.CORE_COMMITTED ->
                SensorPacketIngressOutcomeStatus.CORE_COMMITTED
            Gs1RuntimeIngressDisposition.QUARANTINED ->
                SensorPacketIngressOutcomeStatus.QUARANTINED
            Gs1RuntimeIngressDisposition.NON_DATA ->
                SensorPacketIngressOutcomeStatus.NON_DATA
            Gs1RuntimeIngressDisposition.UNRESOLVED -> return null
        }
        val outcome = SensorPacketIngressOutcomeRecord(
            ingressId = event.ingressId,
            status = status,
            handledAtEpochMs = event.receivedAtEpochMs,
            detail = event.detail?.take(MAX_OUTCOME_DETAIL_CHARS),
        )
        return try {
            when (val marked = ingressJournal.markHandled(outcome)) {
                SensorPacketIngressMarkHandledResult.MarkedHandled,
                SensorPacketIngressMarkHandledResult.AlreadyHandled,
                -> null

                is SensorPacketIngressMarkHandledResult.Conflict -> failure(
                    code = "INGRESS_OUTCOME_CONFLICT",
                    detail = marked.reason,
                    retryable = false,
                )
            }
        } catch (cancelled: java.util.concurrent.CancellationException) {
            throw cancelled
        } catch (storage: Exception) {
            failure(
                code = "INGRESS_OUTCOME_STORAGE_UNAVAILABLE",
                detail = storage.message,
                retryable = true,
            )
        }
    }

    @SuppressLint("MissingPermission")
    private suspend fun stopAttemptLocked(attempt: Attempt): Boolean {
        attempt.stopRequested.set(true)
        committedOutput.abortPendingApplications(
            Gs1ProductLocalEffectsFailureCode.APPLICATION_STOPPED,
        )
        closeAttemptTransport(attempt)
        val drained = withTimeoutOrNull(ATTEMPT_STOP_TIMEOUT_MS) {
            attempt.job.join()
            true
        } == true
        if (!drained) {
            attempt.job.cancel()
            attempt.job.join()
        }
        return drained
    }

    @SuppressLint("MissingPermission")
    private fun closeAttemptTransport(attempt: Attempt) {
        attempt.accepting.set(false)
        attempt.commandArbiter.close()
        transportRegistry.close(attempt.transport)
        attempt.mailbox.close()
    }

    private fun enterDeadline(attempt: Attempt, phase: Gs1GattDeadlinePhase) {
        val transition = attempt.deadlinePolicy.enter(phase, elapsedClock())
        attempt.deadlinePolicy = transition.policy
        scheduleDeadline(attempt, transition.deadline)
    }

    private fun scheduleDeadline(attempt: Attempt, deadline: Gs1GattDeadline) {
        scope.launch {
            delay(deadline.delayMillis)
            offer(attempt, GattEvent.DeadlineFired(deadline.token))
        }
    }

    private fun armTransportSilenceWatchdog(attempt: Attempt) {
        val transition = attempt.streamFreshnessPolicy.arm(elapsedClock())
        attempt.streamFreshnessPolicy = transition.policy
        scope.launch {
            delay(transition.deadline.delayMillis)
            offer(attempt, GattEvent.StreamFreshnessExpired(transition.deadline.token))
        }
    }

    private fun scheduleReconnect(plan: Gs1ReconnectPlan) {
        scope.launch {
            delay(plan.delayMillis)
            lifecycle.withLock {
                val requested = desired.get()
                if (requested?.reconnectToken != plan.token || active.get() != null) {
                    return@withLock
                }
                if (reconnectGate.consumeIfCurrent(plan)) {
                    startAttemptLocked(requested)
                }
            }
        }
    }

    private fun publishPreAttemptFailure(
        requested: DesiredConnection,
        code: String,
        detail: String?,
        retryable: Boolean,
    ) {
        val reconnect = if (retryable && desired.get() === requested) {
            reconnectGate.onRetryableFailure(requested.reconnectToken)
        } else {
            null
        }
        publishState(if (reconnect == null) {
            Gs1GattEngineState.Failed(code, detail, retryable)
        } else {
            scheduleReconnect(reconnect)
            Gs1GattEngineState.Reconnecting(reconnect.attempt, reconnect.delayMillis)
        })
    }

    private fun nextSequence(value: Long): Long =
        if (value == Long.MAX_VALUE) 1L else value + 1L

    private fun requirePermissions() {
        if (Build.VERSION.SDK_INT >= 31) {
            check(
                ContextCompat.checkSelfPermission(appContext, Manifest.permission.BLUETOOTH_CONNECT) ==
                    PackageManager.PERMISSION_GRANTED,
            ) { "BLUETOOTH_CONNECT permission is required" }
        }
    }

    @SuppressLint("MissingPermission")
    private fun safeDisconnect(gatt: BluetoothGatt) {
        runCatching { gatt.disconnect() }
    }

    @SuppressLint("MissingPermission")
    private fun safeClose(gatt: BluetoothGatt) {
        runCatching { gatt.close() }
    }

    private fun failure(
        code: String,
        detail: String?,
        retryable: Boolean,
    ) = GattEvent.Failure(code, detail, retryable)

    private class Attempt(
        val profile: Gs1DiagnosticActivationProfile,
        val transport: Gs1GattTransportLease<BluetoothGatt>,
        val reconnectToken: Gs1ReconnectToken,
        val coreGeneration: Long,
        val session: SibionicsSession,
        val dataHandle: DataHandleGateway,
        val attemptId: String = UUID.randomUUID().toString(),
        val mailbox: Channel<GattEvent> = Channel(
            capacity = GATT_MAILBOX_CAPACITY,
            onUndeliveredElement = { event ->
                val committed = (event as? GattEvent.Core)?.value
                    as? Gs1DiagnosticRuntimeEvent.Committed
                committed?.rejectSettlement(
                    "APPLICATION_STOPPED",
                    "GATT mailbox closed before committed output settlement",
                )
            },
        ),
        val accepting: AtomicBoolean = AtomicBoolean(true),
        val stopRequested: AtomicBoolean = AtomicBoolean(false),
        val writeCharacteristic: AtomicReference<BluetoothGattCharacteristic?> = AtomicReference(null),
        val commandArbiter: Gs1GattCommandArbiter = Gs1GattCommandArbiter(),
        val nextIngressOrdinal: AtomicLong = AtomicLong(0L),
        val terminalFailure: FirstTerminalCause<GattEvent.Failure> = FirstTerminalCause(),
        var deadlinePolicy: Gs1GattDeadlinePolicy,
        var streamFreshnessPolicy: Gs1StreamFreshnessPolicy =
            Gs1StreamFreshnessPolicy.begin(transport.token.generation),
        var delayedWriteSequence: Long = 0L,
        var phase: GattPhase = GattPhase.CONNECTING,
    ) {
        lateinit var job: Job
    }

    private data class DesiredConnection(
        val profile: Gs1DiagnosticActivationProfile,
        val reconnectToken: Gs1ReconnectToken,
        val stopRequested: AtomicBoolean = AtomicBoolean(false),
    )

    private enum class GattPhase {
        CONNECTING,
        DISCOVERING,
        SUBSCRIBING,
        AUTHENTICATING,
        STREAMING,
    }

    private sealed interface GattEvent {
        data class Connection(val status: Int, val newState: Int) : GattEvent
        data class ServicesDiscovered(val status: Int) : GattEvent
        data class SubscriptionWritten(val status: Int) : GattEvent
        data class CommandWritten(val status: Int) : GattEvent
        data class Notification(val bytes: ByteArray) : GattEvent
        data class Core(val value: Gs1DiagnosticRuntimeEvent) : GattEvent
        data class DeadlineFired(val token: Gs1GattDeadlineToken) : GattEvent
        data class StreamFreshnessExpired(val token: Gs1StreamFreshnessToken) : GattEvent
        data class DelayedCommand(
            val sequence: Long,
            val bytes: ByteArray,
        ) : GattEvent
        data class Failure(
            val code: String,
            val detail: String?,
            val retryable: Boolean,
        ) : GattEvent
    }

    private companion object {
        const val GATT_MAILBOX_CAPACITY = 64
        const val GATT_OPERATION_SUCCESS = 0
        const val GATT_OPERATION_FAILED = -1
        const val INGRESS_RETRY_DELAY_MS = 500L
        const val ATTEMPT_STOP_TIMEOUT_MS = 2_500L
        const val MAX_OUTCOME_DETAIL_CHARS = 512
    }
}
