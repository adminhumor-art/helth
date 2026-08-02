package com.sladkaya.sensor.sibionics.datahandle

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Bundle
import android.os.IBinder
import com.sladkaya.sensor.sibionics.datahandle.ipc.DataHandleIpcContract
import com.sladkaya.sensor.sibionics.datahandle.ipc.IDataHandleGatewayService
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume

enum class DataHandleGatewayOpenError {
    UNSUPPORTED_TRANSPORT_VARIANT,
    SERVICE_BIND_REJECTED,
    SERVICE_BIND_TIMEOUT,
    SERVICE_IDENTITY_MISMATCH,
}

sealed interface DataHandleGatewayOpenResult {
    data class Success(val gateway: DataHandleGateway) : DataHandleGatewayOpenResult
    data class Failure(val error: DataHandleGatewayOpenError) : DataHandleGatewayOpenResult
}

object RemoteDataHandleGatewayConnector {
    suspend fun connect(
        context: Context,
        transportVariant: Int,
    ): DataHandleGatewayOpenResult {
        val bundle = DataHandleBundles.resolve(transportVariant)
            ?: return DataHandleGatewayOpenResult.Failure(
                DataHandleGatewayOpenError.UNSUPPORTED_TRANSPORT_VARIANT,
            )
        val applicationContext = context.applicationContext
        val bound = withTimeoutOrNull(BIND_TIMEOUT_MILLIS) {
            bind(applicationContext, bundle)
        } ?: return DataHandleGatewayOpenResult.Failure(
            DataHandleGatewayOpenError.SERVICE_BIND_TIMEOUT,
        )
        return when (bound) {
            is BoundResult.Failure -> DataHandleGatewayOpenResult.Failure(bound.error)
            is BoundResult.Success -> {
                val native = RemoteNativeDataHandleApi(bound.service)
                DataHandleGatewayOpenResult.Success(
                    SibionicsDataHandle(bundle, native, bound.close),
                )
            }
        }
    }

    private suspend fun bind(
        context: Context,
        bundle: DataHandleBundle,
    ): BoundResult = suspendCancellableCoroutine { continuation ->
        val delivered = AtomicBoolean(false)
        lateinit var connection: ServiceConnection
        val lease = ServiceBindingLease {
            runCatching { context.unbindService(connection) }
        }
        val close = lease::close
        connection = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName, service: IBinder) {
                if (!delivered.compareAndSet(false, true) || !continuation.isActive) {
                    close()
                    return
                }
                if (name.className != bundle.serviceClassName) {
                    close()
                    continuation.resume(
                        BoundResult.Failure(
                            DataHandleGatewayOpenError.SERVICE_IDENTITY_MISMATCH,
                        ),
                    )
                    return
                }
                continuation.resume(
                    BoundResult.Success(
                        service = IDataHandleGatewayService.Stub.asInterface(service),
                        close = close,
                    ),
                )
            }

            override fun onServiceDisconnected(name: ComponentName) = Unit

            override fun onNullBinding(name: ComponentName) {
                if (delivered.compareAndSet(false, true) && continuation.isActive) {
                    close()
                    continuation.resume(
                        BoundResult.Failure(DataHandleGatewayOpenError.SERVICE_BIND_REJECTED),
                    )
                }
            }

            override fun onBindingDied(name: ComponentName) = onNullBinding(name)
        }
        continuation.invokeOnCancellation { close() }
        val intent = Intent(DataHandleIpcContract.ACTION_BIND).apply {
            component = ComponentName(context.packageName, bundle.serviceClassName)
            putExtra(DataHandleIpcContract.EXTRA_BUNDLE, bundle.name)
        }
        val accepted = runCatching {
            context.bindService(intent, connection, Context.BIND_AUTO_CREATE)
        }.getOrDefault(false)
        if (accepted) {
            lease.accepted()
        } else if (delivered.compareAndSet(false, true) && continuation.isActive) {
            continuation.resume(
                BoundResult.Failure(DataHandleGatewayOpenError.SERVICE_BIND_REJECTED),
            )
        }
    }

    private sealed interface BoundResult {
        data class Success(
            val service: IDataHandleGatewayService,
            val close: () -> Unit,
        ) : BoundResult

        data class Failure(val error: DataHandleGatewayOpenError) : BoundResult
    }

    private const val BIND_TIMEOUT_MILLIS = 5_000L
}

internal class ServiceBindingLease(
    private val unbind: () -> Unit,
) {
    private val accepted = AtomicBoolean(false)
    private val closeRequested = AtomicBoolean(false)
    private val released = AtomicBoolean(false)

    fun accepted() {
        accepted.set(true)
        releaseIfReady()
    }

    fun close() {
        closeRequested.set(true)
        releaseIfReady()
    }

    private fun releaseIfReady() {
        if (accepted.get() && closeRequested.get() && released.compareAndSet(false, true)) {
            unbind()
        }
    }
}

private class RemoteNativeDataHandleApi(
    private val service: IDataHandleGatewayService,
) : NativeDataHandleApi {
    override fun registerKey(input: ByteArray, length: Int, output: ByteArray): Int =
        call(
            DataHandleIpcContract.REGISTER_KEY,
            Bundle().apply {
                putByteArray(DataHandleIpcContract.KEY_INPUT, input.copyOf())
                putInt(DataHandleIpcContract.KEY_INPUT_LENGTH, length)
                putByteArray(DataHandleIpcContract.KEY_OUTPUT, output.copyOf())
            },
            output,
        )

    override fun applyAuthentication(
        command: Int,
        encrypted: Boolean,
        value: Int,
        input: ByteArray,
        output: ByteArray,
        outputLength: Int,
    ): Int = commandCall(
        operation = DataHandleIpcContract.AUTHENTICATION,
        command = command,
        encrypted = encrypted,
        input = input,
        output = output,
        outputLength = outputLength,
    ) { putInt(DataHandleIpcContract.KEY_VALUE_INT, value) }

    override fun activation(
        command: Int,
        encrypted: Boolean,
        input: ByteArray,
        unixTime: Long,
        value: Int,
        output: ByteArray,
        outputLength: Int,
    ): Int = commandCall(
        operation = DataHandleIpcContract.ACTIVATION,
        command = command,
        encrypted = encrypted,
        input = input,
        output = output,
        outputLength = outputLength,
    ) {
        putLong(DataHandleIpcContract.KEY_VALUE_LONG, unixTime)
        putInt(DataHandleIpcContract.KEY_VALUE_INT, value)
    }

    override fun timeUpdate(
        command: Int,
        encrypted: Boolean,
        input: ByteArray,
        unixTime: Long,
        output: ByteArray,
        outputLength: Int,
    ): Int = commandCall(
        operation = DataHandleIpcContract.TIME_UPDATE,
        command = command,
        encrypted = encrypted,
        input = input,
        output = output,
        outputLength = outputLength,
    ) { putLong(DataHandleIpcContract.KEY_VALUE_LONG, unixTime) }

    override fun rawData(
        command: Int,
        encrypted: Boolean,
        input: ByteArray,
        value: Long,
        index: Int,
        output: ByteArray,
        outputLength: Int,
    ): Int = commandCall(
        operation = DataHandleIpcContract.RAW_DATA,
        command = command,
        encrypted = encrypted,
        input = input,
        output = output,
        outputLength = outputLength,
    ) {
        putLong(DataHandleIpcContract.KEY_VALUE_LONG, value)
        putInt(DataHandleIpcContract.KEY_INDEX, index)
    }

    override fun reset(
        command: Int,
        encrypted: Boolean,
        input: ByteArray,
        value: Int,
        output: ByteArray,
        outputLength: Int,
    ): Int = commandCall(
        operation = DataHandleIpcContract.RESET,
        command = command,
        encrypted = encrypted,
        input = input,
        output = output,
        outputLength = outputLength,
    ) { putInt(DataHandleIpcContract.KEY_VALUE_INT, value) }

    override fun splitData(
        command: Int,
        packet: ByteArray,
        metadata: IntArray,
        formattedPayload: ByteArray,
        encrypted: Boolean,
        workspace: ByteArray,
        workspaceLength: Int,
    ): Int {
        val response = transact(
            DataHandleIpcContract.SPLIT,
            Bundle().apply {
                putInt(DataHandleIpcContract.KEY_COMMAND, command)
                putByteArray(DataHandleIpcContract.KEY_PACKET, packet.copyOf())
                putBoolean(DataHandleIpcContract.KEY_ENCRYPTED, encrypted)
                putInt(DataHandleIpcContract.KEY_WORKSPACE_LENGTH, workspaceLength)
            },
        )
        response.requireIntArray(DataHandleIpcContract.KEY_METADATA, metadata.size)
            .copyInto(metadata)
        response.requireByteArray(
            DataHandleIpcContract.KEY_FORMATTED_PAYLOAD,
            formattedPayload.size,
        ).copyInto(formattedPayload)
        response.requireByteArray(DataHandleIpcContract.KEY_WORKSPACE, workspace.size)
            .copyInto(workspace)
        return response.getInt(DataHandleIpcContract.KEY_RESULT)
    }

    private inline fun commandCall(
        operation: Int,
        command: Int,
        encrypted: Boolean,
        input: ByteArray,
        output: ByteArray,
        outputLength: Int,
        extras: Bundle.() -> Unit,
    ): Int = call(
        operation,
        Bundle().apply {
            putInt(DataHandleIpcContract.KEY_COMMAND, command)
            putBoolean(DataHandleIpcContract.KEY_ENCRYPTED, encrypted)
            putByteArray(DataHandleIpcContract.KEY_INPUT, input.copyOf())
            putInt(DataHandleIpcContract.KEY_OUTPUT_LENGTH, outputLength)
            extras()
        },
        output,
    )

    private fun call(operation: Int, request: Bundle, output: ByteArray): Int {
        val response = transact(operation, request)
        response.requireByteArray(DataHandleIpcContract.KEY_OUTPUT, output.size).copyInto(output)
        return response.getInt(DataHandleIpcContract.KEY_RESULT)
    }

    private fun transact(operation: Int, request: Bundle): Bundle {
        val response = service.transact(operation, request)
            ?: throw IllegalStateException("Native gateway returned no response")
        require(
            response.getInt(DataHandleIpcContract.KEY_STATUS, -1) ==
                DataHandleIpcContract.STATUS_OK,
        ) { "Native gateway rejected the request" }
        return response
    }

    private fun Bundle.requireByteArray(key: String, expectedSize: Int): ByteArray =
        requireNotNull(getByteArray(key)).also { require(it.size == expectedSize) }

    private fun Bundle.requireIntArray(key: String, expectedSize: Int): IntArray =
        requireNotNull(getIntArray(key)).also { require(it.size == expectedSize) }
}
