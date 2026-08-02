package com.sladkaya.sensor.sibionics.datahandle.ipc

import com.no.sisense.enanddecryption.CGMDataHandle130
import com.sladkaya.sensor.sibionics.datahandle.DataHandleBundle
import com.sladkaya.sensor.sibionics.datahandle.NativeDataHandleApi

/** Exists only inside a dedicated data-handle service process. */
internal class LocalJniNativeDataHandleApi(bundle: DataHandleBundle) : NativeDataHandleApi {
    init {
        System.loadLibrary(bundle.libraryName)
    }

    override fun registerKey(input: ByteArray, length: Int, output: ByteArray): Int =
        CGMDataHandle130.v120RegisterKey(input, length, output)

    override fun applyAuthentication(
        command: Int,
        encrypted: Boolean,
        value: Int,
        input: ByteArray,
        output: ByteArray,
        outputLength: Int,
    ): Int = CGMDataHandle130.V120ApplyAuthentication(
        command, encrypted, value, input, output, outputLength,
    )

    override fun activation(
        command: Int,
        encrypted: Boolean,
        input: ByteArray,
        unixTime: Long,
        value: Int,
        output: ByteArray,
        outputLength: Int,
    ): Int = CGMDataHandle130.V120Activation(
        command, encrypted, input, unixTime, value, output, outputLength,
    )

    override fun timeUpdate(
        command: Int,
        encrypted: Boolean,
        input: ByteArray,
        unixTime: Long,
        output: ByteArray,
        outputLength: Int,
    ): Int = CGMDataHandle130.V120IsecUpdate(
        command, encrypted, input, unixTime, output, outputLength,
    )

    override fun rawData(
        command: Int,
        encrypted: Boolean,
        input: ByteArray,
        value: Long,
        index: Int,
        output: ByteArray,
        outputLength: Int,
    ): Int = CGMDataHandle130.V120RawData(
        command, encrypted, input, value, index, output, outputLength,
    )

    override fun reset(
        command: Int,
        encrypted: Boolean,
        input: ByteArray,
        value: Int,
        output: ByteArray,
        outputLength: Int,
    ): Int = CGMDataHandle130.V120Reset(
        command, encrypted, input, value, output, outputLength,
    )

    override fun splitData(
        command: Int,
        packet: ByteArray,
        metadata: IntArray,
        formattedPayload: ByteArray,
        encrypted: Boolean,
        workspace: ByteArray,
        workspaceLength: Int,
    ): Int = CGMDataHandle130.V120SpiltData(
        command,
        packet,
        metadata,
        formattedPayload,
        encrypted,
        workspace,
        workspaceLength,
    )
}
