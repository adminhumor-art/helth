package com.sladkaya.sensor.sibionics.datahandle.ipc

import android.app.Service
import android.content.Intent
import android.os.Binder
import android.os.Bundle
import android.os.IBinder
import android.os.Process
import com.sladkaya.sensor.sibionics.datahandle.DataHandleBundle

abstract class PinnedDataHandleService : Service() {
    protected abstract val pinnedBundle: DataHandleBundle

    private val native by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        LocalJniNativeDataHandleApi(pinnedBundle)
    }
    private val callLock = Any()
    private val gateway = object : IDataHandleGatewayService.Stub() {
        override fun transact(operation: Int, request: Bundle?): Bundle {
            if (Binder.getCallingUid() != Process.myUid() || request == null) {
                return rejected()
            }
            return synchronized(callLock) {
                runCatching { dispatch(operation, request) }.getOrElse { rejected() }
            }
        }
    }

    override fun onBind(intent: Intent?): IBinder? {
        val requestedBundle = intent?.getStringExtra(DataHandleIpcContract.EXTRA_BUNDLE)
        return if (
            intent?.action == DataHandleIpcContract.ACTION_BIND &&
            requestedBundle == pinnedBundle.name
        ) {
            native
            gateway
        } else {
            null
        }
    }

    private fun dispatch(operation: Int, request: Bundle): Bundle = when (operation) {
        DataHandleIpcContract.REGISTER_KEY -> {
            val input = request.requiredBytes(
                DataHandleIpcContract.KEY_INPUT,
                DataHandleIpcContract.MAX_SMALL_BUFFER,
            )
            val length = request.requiredInt(
                DataHandleIpcContract.KEY_INPUT_LENGTH,
                1..input.size,
            )
            val output = request.requiredBytes(
                DataHandleIpcContract.KEY_OUTPUT,
                DataHandleIpcContract.MAX_SMALL_BUFFER,
            )
            success(native.registerKey(input, length, output), output = output)
        }
        DataHandleIpcContract.AUTHENTICATION -> withCommandBuffers(request) { input, output ->
            native.applyAuthentication(
                request.getInt(DataHandleIpcContract.KEY_COMMAND),
                request.getBoolean(DataHandleIpcContract.KEY_ENCRYPTED),
                request.getInt(DataHandleIpcContract.KEY_VALUE_INT),
                input,
                output,
                output.size,
            )
        }
        DataHandleIpcContract.ACTIVATION -> withCommandBuffers(request) { input, output ->
            native.activation(
                request.getInt(DataHandleIpcContract.KEY_COMMAND),
                request.getBoolean(DataHandleIpcContract.KEY_ENCRYPTED),
                input,
                request.getLong(DataHandleIpcContract.KEY_VALUE_LONG),
                request.getInt(DataHandleIpcContract.KEY_VALUE_INT),
                output,
                output.size,
            )
        }
        DataHandleIpcContract.TIME_UPDATE -> withCommandBuffers(request) { input, output ->
            native.timeUpdate(
                request.getInt(DataHandleIpcContract.KEY_COMMAND),
                request.getBoolean(DataHandleIpcContract.KEY_ENCRYPTED),
                input,
                request.getLong(DataHandleIpcContract.KEY_VALUE_LONG),
                output,
                output.size,
            )
        }
        DataHandleIpcContract.RAW_DATA -> withCommandBuffers(request) { input, output ->
            native.rawData(
                request.getInt(DataHandleIpcContract.KEY_COMMAND),
                request.getBoolean(DataHandleIpcContract.KEY_ENCRYPTED),
                input,
                request.getLong(DataHandleIpcContract.KEY_VALUE_LONG),
                request.getInt(DataHandleIpcContract.KEY_INDEX),
                output,
                output.size,
            )
        }
        DataHandleIpcContract.RESET -> withCommandBuffers(request) { input, output ->
            native.reset(
                request.getInt(DataHandleIpcContract.KEY_COMMAND),
                request.getBoolean(DataHandleIpcContract.KEY_ENCRYPTED),
                input,
                request.getInt(DataHandleIpcContract.KEY_VALUE_INT),
                output,
                output.size,
            )
        }
        DataHandleIpcContract.SPLIT -> {
            val packet = request.requiredBytes(
                DataHandleIpcContract.KEY_PACKET,
                DataHandleIpcContract.MAX_PACKET,
            )
            val workspaceLength = request.requiredInt(
                DataHandleIpcContract.KEY_WORKSPACE_LENGTH,
                packet.size..packet.size,
            )
            val metadata = IntArray(2)
            val formattedPayload = ByteArray(DataHandleIpcContract.MAX_FORMATTED_PAYLOAD)
            val workspace = ByteArray(2)
            val result = native.splitData(
                request.getInt(DataHandleIpcContract.KEY_COMMAND),
                packet,
                metadata,
                formattedPayload,
                request.getBoolean(DataHandleIpcContract.KEY_ENCRYPTED),
                workspace,
                workspaceLength,
            )
            success(result).apply {
                putIntArray(DataHandleIpcContract.KEY_METADATA, metadata)
                putByteArray(DataHandleIpcContract.KEY_FORMATTED_PAYLOAD, formattedPayload)
                putByteArray(DataHandleIpcContract.KEY_WORKSPACE, workspace)
            }
        }
        else -> rejected()
    }

    private inline fun withCommandBuffers(
        request: Bundle,
        call: (ByteArray, ByteArray) -> Int,
    ): Bundle {
        val input = request.requiredBytes(
            DataHandleIpcContract.KEY_INPUT,
            DataHandleIpcContract.MAX_SMALL_BUFFER,
            allowEmpty = true,
        )
        val outputLength = request.requiredInt(
            DataHandleIpcContract.KEY_OUTPUT_LENGTH,
            1..DataHandleIpcContract.MAX_SMALL_BUFFER,
        )
        val output = ByteArray(outputLength)
        return success(call(input, output), output = output)
    }

    private fun success(result: Int, output: ByteArray? = null) = Bundle().apply {
        putInt(DataHandleIpcContract.KEY_STATUS, DataHandleIpcContract.STATUS_OK)
        putInt(DataHandleIpcContract.KEY_RESULT, result)
        output?.let { putByteArray(DataHandleIpcContract.KEY_OUTPUT, it) }
    }

    private fun rejected() = Bundle().apply {
        putInt(DataHandleIpcContract.KEY_STATUS, DataHandleIpcContract.STATUS_REJECTED)
    }

    private fun Bundle.requiredBytes(
        key: String,
        maximum: Int,
        allowEmpty: Boolean = false,
    ): ByteArray {
        val value = getByteArray(key)?.copyOf() ?: throw IllegalArgumentException("missing")
        require(value.size <= maximum && (allowEmpty || value.isNotEmpty()))
        return value
    }

    private fun Bundle.requiredInt(key: String, range: IntRange): Int =
        getInt(key).also { require(it in range) }
}

class GlobalDataHandleService : PinnedDataHandleService() {
    override val pinnedBundle = DataHandleBundle.GLOBAL
}

class ChineseDataHandleService : PinnedDataHandleService() {
    override val pinnedBundle = DataHandleBundle.CHINESE
}
