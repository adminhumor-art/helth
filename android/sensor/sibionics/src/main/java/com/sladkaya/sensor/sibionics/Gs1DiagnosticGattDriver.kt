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
import com.sladkaya.core.data.RoomSensorPacketIngressJournal
import com.sladkaya.core.data.SensorCoreRepository
import com.sladkaya.core.data.SensorPacketIngressJournal
import com.sladkaya.core.data.SensorPacketIngressMarkHandledResult
import com.sladkaya.core.data.SensorPacketIngressOutcomeRecord
import com.sladkaya.core.data.SensorPacketIngressOutcomeStatus
import com.sladkaya.core.model.ReadingQuality
import com.sladkaya.core.sensor.SensorConfiguration
import com.sladkaya.sensor.sibionics.datahandle.SibionicsDataHandle
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
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.flow.MutableStateFlow
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

/**
 * Android-only shell for GS1/GS1Sb diagnostics. It intentionally exposes no
 * product measurement stream.
 *
 * Every attempt owns one callback, GATT object, protocol session and bounded
 * mailbox. The stateful core is reached only through [Gs1DiagnosticRuntime].
 */
class Gs1DiagnosticGattDriver internal constructor(
    context: Context,
    factory: Gs1CoreFactory,
    private val ingressJournal: SensorPacketIngressJournal,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
    private val elapsedClock: () -> Long = SystemClock::elapsedRealtime,
) {
    constructor(context: Context) : this(
        context = context,
        factory = Gs1CoreFactory(SensorCoreRepository.create(context.applicationContext)),
        ingressJournal = RoomSensorPacketIngressJournal.create(context.applicationContext),
    )

    private val appContext = context.applicationContext
    private val bluetoothManager: BluetoothManager? =
        appContext.getSystemService(BluetoothManager::class.java)
    private val codec = SibionicsPacketCodec()
    private val dataHandle = SibionicsDataHandle()
    private val transportRegistry = Gs1GattTransportRegistry<BluetoothGatt>(
        disconnect = ::safeDisconnect,
        close = ::safeClose,
    )
    private val lifecycle = Mutex()
    private val active = AtomicReference<Attempt?>(null)
    private val desired = AtomicReference<DesiredConnection?>(null)
    private val reconnectGate = Gs1ReconnectGate()
    private val durableIngress = Gs1DurableIngress(ingressJournal)

    private val mutableState = MutableStateFlow<Gs1DiagnosticGattState>(Gs1DiagnosticGattState.Idle)
    val state: StateFlow<Gs1DiagnosticGattState> = mutableState.asStateFlow()

    private val mutableLatestDiagnostic = MutableStateFlow<Gs1DiagnosticReading?>(null)
    val latestDiagnostic: StateFlow<Gs1DiagnosticReading?> = mutableLatestDiagnostic.asStateFlow()

    private val coreRuntime = Gs1DiagnosticRuntime(
        scope = scope,
        opener = FactoryGs1RuntimeCoreOpener(factory),
        eventSink = ::onCoreEvent,
    )
    private val pendingIngressRecovery = Gs1PendingIngressRecovery(
        journal = ingressJournal,
        codec = codec,
        replay = coreRuntime::submitAndAwait,
    )

    suspend fun start(profile: Gs1DiagnosticActivationProfile) {
        lifecycle.withLock {
            desired.getAndSet(null)?.let { reconnectGate.stop(it.reconnectToken) }
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
                mutableState.value = Gs1DiagnosticGattState.Failed(
                    code = "BLUETOOTH_PERMISSION_REQUIRED",
                    detail = failure.message,
                    retryable = false,
                )
                return
            }
            mutableLatestDiagnostic.value = null
            mutableState.value = Gs1DiagnosticGattState.OpeningCore

            val core = when (val result = coreRuntime.start(profile)) {
                is Gs1RuntimeStartResult.Started -> result
                is Gs1RuntimeStartResult.Failed -> {
                    if (result.code == "PERSISTENCE_PENDING") {
                        mutableState.value = Gs1DiagnosticGattState.PersistencePending
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
            var coreTransferred = false
            try {
                val completedRecovery = when (
                    val recovery = pendingIngressRecovery.recover(
                        profile = profile,
                        generation = core.generation,
                        initialCoreCursor = core.initialNextIndex,
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
                    mutableState.value = Gs1DiagnosticGattState.Failed(
                        code = "BLUETOOTH_UNAVAILABLE",
                        retryable = false,
                    )
                    return
                }
                val enabled = try {
                    adapter.isEnabled
                } catch (failure: SecurityException) {
                    mutableState.value = Gs1DiagnosticGattState.Failed(
                        code = "BLUETOOTH_PERMISSION_REVOKED",
                        detail = failure.message,
                        retryable = false,
                    )
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
                    gs1Commands = OfficialGs1CommandCodec(dataHandle),
                    initialNextIndex = recoveryCursor,
                )
                val attempt = Attempt(
                    profile = profile,
                    transport = transport,
                    coreGeneration = core.generation,
                    session = session,
                    deadlinePolicy = initialDeadline.policy,
                    reconnectToken = requested.reconnectToken,
                )
                attempt.job = scope.launch { runAttempt(attempt) }
                active.set(attempt)
                coreTransferred = true
                mutableState.value = completedRecovery.blocked?.let { blocked ->
                    Gs1DiagnosticGattState.ConnectingForHistoryBackfill(
                        expectedIndex = recoveryCursor,
                        firstPendingIndex = blocked.firstIndex,
                        reason = blocked.disposition.name,
                    )
                } ?: Gs1DiagnosticGattState.Connecting
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
                if (!coreTransferred) coreRuntime.stop(expectedGeneration = core.generation)
            }
    }

    suspend fun stop() {
        lifecycle.withLock {
            desired.getAndSet(null)?.let { reconnectGate.stop(it.reconnectToken) }
            val attempt = active.getAndSet(null)
            val drained = if (attempt != null) {
                stopAttemptLocked(attempt)
            } else {
                coreRuntime.stop() == Gs1RuntimeStopResult.DRAINED
            }
            mutableState.value = if (drained) {
                Gs1DiagnosticGattState.Idle
            } else {
                Gs1DiagnosticGattState.PersistencePending
            }
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
                }
            }
        } finally {
            withContext(NonCancellable) {
                closeAttemptTransport(attempt)
                attempt.mailbox.cancel()
                val coreStop = coreRuntime.stop(expectedGeneration = attempt.coreGeneration)
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
                    mutableState.value = when {
                        coreStop == Gs1RuntimeStopResult.PERSISTENCE_PENDING ->
                            Gs1DiagnosticGattState.PersistencePending
                        reconnect != null -> Gs1DiagnosticGattState.Reconnecting(
                            attempt = reconnect.attempt,
                            delayMillis = reconnect.delayMillis,
                        )
                        else -> Gs1DiagnosticGattState.Failed(
                            code = failure.code,
                            detail = failure.detail,
                            retryable = failure.retryable,
                        )
                    }
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
                        mutableState.value = Gs1DiagnosticGattState.DiscoveringServices
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
                    mutableState.value = Gs1DiagnosticGattState.Authenticating
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
        mutableState.value = Gs1DiagnosticGattState.Subscribing
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
        val exactPacket = journaled.encryptedPacketCopy()
        return when (val packet = codec.decode(attempt.profile.family, exactPacket)) {
            is DecodedPacket.Gs1RawSamples -> {
                val protocol = attempt.session.onPacket(packet)
                if (protocol is SessionAction.Failure) {
                    failure("PROTOCOL_REJECTED_DATA", protocol.reason, false)
                } else {
                    when (coreRuntime.submit(attempt.coreGeneration, journaled)) {
                        Gs1RuntimeSubmission.ACCEPTED -> {
                            attempt.delayedWriteSequence = nextSequence(attempt.delayedWriteSequence)
                            null
                        }
                        Gs1RuntimeSubmission.OVERFLOW -> failure(
                            "CORE_MAILBOX_OVERFLOW", null, true,
                        )
                        Gs1RuntimeSubmission.STALE_GENERATION -> failure(
                            "STALE_CORE_GENERATION", null, false,
                        )
                        Gs1RuntimeSubmission.CLOSED -> failure("CORE_RUNTIME_CLOSED", null, true)
                    }
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
                        mutableState.value = Gs1DiagnosticGattState.RetryingIngressPersistence(retry)
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
                enqueueCommand(attempt, action.bytes)
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

    private fun onCoreEvent(event: Gs1DiagnosticRuntimeEvent) {
        val attempt = active.get() ?: return
        val generation = when (event) {
            is Gs1DiagnosticRuntimeEvent.Finalized -> event.generation
            is Gs1DiagnosticRuntimeEvent.Committed -> event.generation
            is Gs1DiagnosticRuntimeEvent.Failed -> event.generation
            is Gs1DiagnosticRuntimeEvent.RetryingPersistence -> event.generation
        }
        if (attempt.coreGeneration != generation) return
        offer(attempt, GattEvent.Core(event))
    }

    private suspend fun handleCoreEvent(
        attempt: Attempt,
        event: Gs1DiagnosticRuntimeEvent,
    ): GattEvent.Failure? = when (event) {
        is Gs1DiagnosticRuntimeEvent.Finalized -> persistLiveOutcome(event)

        is Gs1DiagnosticRuntimeEvent.RetryingPersistence -> {
            mutableState.value = Gs1DiagnosticGattState.RetryingPersistence(event.attempt)
            failure(
                code = "CORE_PERSISTENCE_PENDING",
                detail = "Native state is waiting for an exact durable commit",
                retryable = true,
            )
        }

        is Gs1DiagnosticRuntimeEvent.Committed -> {
            when (val confirmation = attempt.session.confirmDurablyCommitted(event.samples)) {
                is SessionAction.Failure -> failure(
                    "DURABLE_CURSOR_REJECTED",
                    confirmation.reason,
                    false,
                )
                else -> {
                    if (attempt.phase != GattPhase.STREAMING) {
                        attempt.phase = GattPhase.STREAMING
                        attempt.deadlinePolicy = attempt.deadlinePolicy.markStreaming()
                    }
                    val assessment = Gs1DiagnosticCommitPolicy.assess(
                        diagnostics = event.diagnostics,
                        committedSampleCount = event.samples.size,
                        issueCount = event.issues.size,
                    )
                    if (assessment.hasTransportProgress) {
                        armTransportSilenceWatchdog(attempt)
                    }
                    assessment.latest?.let { mutableLatestDiagnostic.value = it }
                    val issue = event.issues.lastOrNull()
                    mutableState.value = if (issue != null) {
                        Gs1DiagnosticGattState.DiagnosticDataRejected(
                            sequence = issue.sequence,
                            code = issue.code,
                            detail = issue.message,
                        )
                    } else if (assessment.hasFreshDiagnostic) {
                        reconnectGate.markStable(attempt.reconnectToken)
                        Gs1DiagnosticGattState.StreamingDiagnostic
                    } else {
                        Gs1DiagnosticGattState.DiagnosticDataNotFresh(
                            sequence = assessment.latest?.sequence,
                            quality = assessment.latest?.quality,
                        )
                    }
                    null
                }
            }
        }

        is Gs1DiagnosticRuntimeEvent.Failed -> failure(event.code, event.detail, false)
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
        closeAttemptTransport(attempt)
        return withTimeoutOrNull(ATTEMPT_STOP_TIMEOUT_MS) {
            attempt.job.join()
            true
        } == true
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
        mutableState.value = if (reconnect == null) {
            Gs1DiagnosticGattState.Failed(code, detail, retryable)
        } else {
            scheduleReconnect(reconnect)
            Gs1DiagnosticGattState.Reconnecting(reconnect.attempt, reconnect.delayMillis)
        }
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
        val attemptId: String = UUID.randomUUID().toString(),
        val mailbox: Channel<GattEvent> = Channel(GATT_MAILBOX_CAPACITY),
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
