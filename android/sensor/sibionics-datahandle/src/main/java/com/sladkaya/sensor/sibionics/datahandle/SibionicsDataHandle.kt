package com.sladkaya.sensor.sibionics.datahandle

import com.no.sisense.enanddecryption.CGMDataHandle130
import java.nio.charset.StandardCharsets

/**
 * GS1 protocol variants supported by the pinned official native datahandle.
 *
 * The application ID and registration material below are protocol constants
 * required by that native registration call. They are not user, account, or
 * deployment credentials and must never be written to logs or error messages.
 */
enum class DataHandleVariant(
    val protocolCode: Int,
    internal val pinnedProtocolApplicationId: String,
    internal val pinnedProtocolRegistrationMaterial: String,
) {
    GLOBAL_GS1(
        0,
        "com.sisensing.sijoy",
        "56CE249349040C94F8B4B2375A8752D5CBE7A17814B502D9132489C0BFDFC99F0CAC670E8CBB085AF1C780B3D282E3",
    ),
    RUSSIAN_GS1(
        1,
        "com.sisensing.rusibionics",
        "60B05FEB7C0A148DEED2B3375A8754D9D0E6A5751BCE02D9132489C0BFDFC99F0CAC670E8DA7115CEACF87B7DE8FD4612E1B7638C2",
    ),
    CHINESE_GS1(
        2,
        "com.sisensing.sisensingcgm",
        "4E8E1CAF43051F97EEC9C1475A8752D5C387D17A65B002D9132489C0BFDFC99F0CAC670E8CBB1150E6D581B7D08FC03404052C57AD58",
    ),
    ECO_GS1(
        3,
        "com.sisensing.eco",
        "068449FA5C1B1F97EEC9C1475A8752D5C387D17A65B002D9132489C0BFDFC99F0CAC670E9AB10D62FDE0B2B1E7",
    ),
}

enum class DataHandleError {
    INVALID_BLUETOOTH_ADDRESS,
    INVALID_TIME,
    INVALID_INDEX,
    INVALID_PACKET_SIZE,
    REGISTRATION_FAILED,
    NATIVE_CALL_FAILED,
    INVALID_NATIVE_RESULT,
    SPLIT_FAILED,
    UNEXPECTED_SPLIT_TYPE,
    MALFORMED_NATIVE_PAYLOAD,
    NATIVE_BUFFER_OVERFLOW,
}

sealed interface DataHandleCommandResult {
    data class Success(val bytes: ByteArray) : DataHandleCommandResult

    data class Failure(
        val error: DataHandleError,
        val nativeCode: Int? = null,
    ) : DataHandleCommandResult
}

sealed interface DataHandleSplitResult {
    data class Success(
        val recordCount: Int,
        val metadata: IntArray,
        val formattedPayload: ByteArray,
        val workspace: ByteArray,
        val decrypted: Boolean,
    ) : DataHandleSplitResult

    data class Failure(
        val error: DataHandleError,
        val nativeCode: Int? = null,
    ) : DataHandleSplitResult
}

data class Gs1NativeRecord(
    val index: Int,
    val temperature10: Int,
    val current10: Int,
    val dump: Int,
    val reindex: Int,
    val embeddedGlucose: Int,
    val trend: Int,
    val glucoseWarning: Int,
    val temperatureWarning: Int,
    val currentWarning: Int,
    val sensorTimeEpochSeconds: Long,
) {
    init {
        require(index in U16_RANGE)
        require(temperature10 in U16_RANGE)
        require(current10 in U16_RANGE)
        require(dump in U16_RANGE)
        require(reindex in U16_RANGE)
        require(embeddedGlucose in U16_RANGE)
        require(trend in 0..7)
        require(glucoseWarning in 0..3)
        require(temperatureWarning in 0..1)
        require(currentWarning in 0..1)
        require(sensorTimeEpochSeconds in U32_RANGE)
    }

    private companion object {
        val U16_RANGE = 0..0xffff
        val U32_RANGE = 0L..0xffff_ffffL
    }
}

sealed interface Gs1DataSplitResult {
    data class Success(
        val records: List<Gs1NativeRecord>,
        val decrypted: Boolean,
    ) : Gs1DataSplitResult

    data class Failure(
        val error: DataHandleError,
        val nativeCode: Int? = null,
    ) : Gs1DataSplitResult
}

class SibionicsDataHandle internal constructor(
    private val native: NativeDataHandleApi,
) {
    constructor() : this(JniNativeDataHandleApi)

    fun authentication(
        variant: DataHandleVariant,
        bluetoothAddress: String,
    ): DataHandleCommandResult = synchronized(nativeCallLock) {
        val reversedAddress = parseReversedAddress(bluetoothAddress)
            ?: return@synchronized DataHandleCommandResult.Failure(
                DataHandleError.INVALID_BLUETOOTH_ADDRESS,
            )
        val registrationResult = runCatching {
            val registrationMaterial = variant.pinnedProtocolRegistrationMaterial
                .toByteArray(StandardCharsets.US_ASCII)
            val protocolApplicationId = variant.pinnedProtocolApplicationId
                .toByteArray(StandardCharsets.US_ASCII)
            native.registerKey(
                registrationMaterial,
                registrationMaterial.size,
                protocolApplicationId,
            )
        }.getOrElse {
            return@synchronized DataHandleCommandResult.Failure(DataHandleError.NATIVE_CALL_FAILED)
        }
        if (registrationResult < 0) {
            return@synchronized DataHandleCommandResult.Failure(
                DataHandleError.REGISTRATION_FAILED,
                registrationResult,
            )
        }
        command(AUTHENTICATION_BUFFER_SIZE) { output ->
            native.applyAuthentication(
                command = 1,
                encrypted = true,
                value = 0,
                input = reversedAddress,
                output = output,
                outputLength = output.size,
            )
        }
    }

    fun activation(epochSeconds: Long): DataHandleCommandResult = synchronized(nativeCallLock) {
        if (!validEpochSeconds(epochSeconds)) {
            return@synchronized DataHandleCommandResult.Failure(DataHandleError.INVALID_TIME)
        }
        command(STANDARD_COMMAND_BUFFER_SIZE) { output ->
            native.activation(
                command = 0,
                encrypted = true,
                input = EMPTY_NATIVE_INPUT.copyOf(),
                unixTime = epochSeconds,
                value = 1_234,
                output = output,
                outputLength = output.size,
            )
        }
    }

    fun timeUpdate(epochSeconds: Long): DataHandleCommandResult = synchronized(nativeCallLock) {
        if (!validEpochSeconds(epochSeconds)) {
            return@synchronized DataHandleCommandResult.Failure(DataHandleError.INVALID_TIME)
        }
        command(STANDARD_COMMAND_BUFFER_SIZE) { output ->
            native.timeUpdate(
                command = 0,
                encrypted = true,
                input = EMPTY_NATIVE_INPUT.copyOf(),
                unixTime = epochSeconds,
                output = output,
                outputLength = output.size,
            )
        }
    }

    fun rawData(index: Int): DataHandleCommandResult = synchronized(nativeCallLock) {
        if (index !in 0..0xffff) {
            return@synchronized DataHandleCommandResult.Failure(DataHandleError.INVALID_INDEX)
        }
        command(RAW_COMMAND_BUFFER_SIZE) { output ->
            native.rawData(
                command = 0,
                encrypted = true,
                input = EMPTY_NATIVE_INPUT.copyOf(),
                value = index.toLong(),
                index = 0,
                output = output,
                outputLength = output.size,
            )
        }
    }

    fun reset(): DataHandleCommandResult = synchronized(nativeCallLock) {
        command(RESET_BUFFER_SIZE) { output ->
            native.reset(
                command = 0,
                encrypted = true,
                input = EMPTY_NATIVE_INPUT.copyOf(),
                value = 0,
                output = output,
                outputLength = output.size,
            )
        }
    }

    fun split(packet: ByteArray): DataHandleSplitResult = synchronized(nativeCallLock) {
        if (packet.isEmpty() || packet.size > MAX_PACKET_SIZE) {
            return@synchronized DataHandleSplitResult.Failure(DataHandleError.INVALID_PACKET_SIZE)
        }

        val first = runCatching { splitAttempt(packet, encrypted = true) }.getOrElse {
            return@synchronized DataHandleSplitResult.Failure(DataHandleError.NATIVE_CALL_FAILED)
        }
        val attempt: SplitAttempt
        if (first.code >= 0) {
            attempt = first
        } else {
            val second = runCatching { splitAttempt(packet, encrypted = false) }.getOrElse {
                return@synchronized DataHandleSplitResult.Failure(DataHandleError.NATIVE_CALL_FAILED)
            }
            if (second.code < 0) {
                return@synchronized DataHandleSplitResult.Failure(
                    DataHandleError.SPLIT_FAILED,
                    second.code,
                )
            }
            attempt = second
        }

        if (!attempt.canaryIntact || !attempt.workspaceIntact) {
            return@synchronized DataHandleSplitResult.Failure(DataHandleError.NATIVE_BUFFER_OVERFLOW)
        }
        if (attempt.code > MAX_PACKET_SIZE) {
            return@synchronized DataHandleSplitResult.Failure(
                DataHandleError.INVALID_NATIVE_RESULT,
                attempt.code,
            )
        }

        DataHandleSplitResult.Success(
            recordCount = attempt.code,
            metadata = attempt.metadata.copyOf(),
            formattedPayload = attempt.formattedPayload.copyOf(SPLIT_FORMATTED_PAYLOAD_SIZE),
            workspace = attempt.workspace.copyOf(),
            decrypted = attempt.encrypted,
        )
    }

    fun splitGs1Data(packet: ByteArray): Gs1DataSplitResult {
        val split = split(packet)
        if (split is DataHandleSplitResult.Failure) {
            return Gs1DataSplitResult.Failure(split.error, split.nativeCode)
        }
        split as DataHandleSplitResult.Success
        if (split.metadata[0] != GS1_DATA_METADATA || split.metadata[1] != 0) {
            return Gs1DataSplitResult.Failure(DataHandleError.UNEXPECTED_SPLIT_TYPE)
        }
        val maximumRecords = ((packet.size - GS1_PACKET_FIXED_BYTES) / GS1_RECORD_BYTES).coerceAtLeast(0)
        if (split.recordCount > maximumRecords) {
            return Gs1DataSplitResult.Failure(
                DataHandleError.INVALID_NATIVE_RESULT,
                split.recordCount,
            )
        }
        val records = parseGs1Json(split.formattedPayload, split.recordCount)
            ?: return Gs1DataSplitResult.Failure(DataHandleError.MALFORMED_NATIVE_PAYLOAD)
        return Gs1DataSplitResult.Success(records, split.decrypted)
    }

    private fun splitAttempt(packet: ByteArray, encrypted: Boolean): SplitAttempt {
        val metadata = IntArray(SPLIT_METADATA_SIZE)
        val formattedPayload = ByteArray(SPLIT_FORMATTED_PAYLOAD_SIZE + SPLIT_CANARY_SIZE)
        formattedPayload.fill(
            element = SPLIT_CANARY_BYTE,
            fromIndex = SPLIT_FORMATTED_PAYLOAD_SIZE,
            toIndex = formattedPayload.size,
        )
        val workspace = ByteArray(SPLIT_WORKSPACE_SIZE)
        val code = native.splitData(
            command = 0,
            packet = packet.copyOf(),
            metadata = metadata,
            formattedPayload = formattedPayload,
            encrypted = encrypted,
            workspace = workspace,
            workspaceLength = packet.size,
        )
        return SplitAttempt(
            code = code,
            metadata = metadata,
            formattedPayload = formattedPayload,
            workspace = workspace,
            encrypted = encrypted,
            canaryIntact = formattedPayload
                .copyOfRange(SPLIT_FORMATTED_PAYLOAD_SIZE, formattedPayload.size)
                .all { it == SPLIT_CANARY_BYTE },
            workspaceIntact = workspace.all { it == 0.toByte() },
        )
    }

    private fun parseGs1Json(payload: ByteArray, expectedCount: Int): List<Gs1NativeRecord>? {
        val end = payload.indexOf(0)
        if (expectedCount == 0) {
            return if (end == 0) emptyList() else null
        }
        if (end <= 0 || payload.copyOfRange(0, end).any { it.toInt() !in 0x20..0x7e }) return null
        val text = payload.copyOfRange(0, end).toString(StandardCharsets.US_ASCII)
        val records = ArrayList<Gs1NativeRecord>(expectedCount)
        var position = 0
        repeat(expectedCount) { recordIndex ->
            position = text.skipWhitespace(position)
            if (recordIndex > 0) {
                if (position >= text.length || text[position] != ',') return null
                position = text.skipWhitespace(position + 1)
            }
            val match = GS1_JSON_RECORD.find(text, position)
                ?.takeIf { it.range.first == position }
                ?: return null
            val values = runCatching { match.groupValues.drop(1).map(String::toLong) }.getOrNull()
                ?: return null
            val record = runCatching {
                Gs1NativeRecord(
                    index = values[0].toExactInt(),
                    temperature10 = values[1].toExactInt(),
                    current10 = values[2].toExactInt(),
                    dump = values[3].toExactInt(),
                    reindex = values[4].toExactInt(),
                    embeddedGlucose = values[5].toExactInt(),
                    trend = values[6].toExactInt(),
                    glucoseWarning = values[7].toExactInt(),
                    temperatureWarning = values[8].toExactInt(),
                    currentWarning = values[9].toExactInt(),
                    sensorTimeEpochSeconds = values[10],
                )
            }.getOrNull() ?: return null
            records += record
            position = match.range.last + 1
        }
        if (text.skipWhitespace(position) != text.length) return null
        if (!records.zipWithNext().all { (first, second) ->
                second.index == first.index + 1 &&
                    second.sensorTimeEpochSeconds == first.sensorTimeEpochSeconds + 60L &&
                    second.reindex == first.reindex - 1
            }
        ) return null
        return records
    }

    private inline fun command(
        capacity: Int,
        nativeCall: (ByteArray) -> Int,
    ): DataHandleCommandResult {
        val output = ByteArray(capacity)
        val length = runCatching { nativeCall(output) }.getOrElse {
            return DataHandleCommandResult.Failure(DataHandleError.NATIVE_CALL_FAILED)
        }
        if (length !in 1..capacity) {
            return DataHandleCommandResult.Failure(
                DataHandleError.INVALID_NATIVE_RESULT,
                length,
            )
        }
        return DataHandleCommandResult.Success(output.copyOf(length))
    }

    private fun parseReversedAddress(value: String): ByteArray? {
        val parts = value.split(':')
        if (parts.size != 6 || parts.any { it.length != 2 || it.any { char -> !char.isHexDigit() } }) {
            return null
        }
        return runCatching { parts.map { it.toInt(16).toByte() }.reversed().toByteArray() }
            .getOrNull()
    }

    private fun validEpochSeconds(value: Long): Boolean = value in 1..0xffff_ffffL

    private fun Char.isHexDigit(): Boolean = this in '0'..'9' || this in 'a'..'f' || this in 'A'..'F'

    companion object {
        const val BINARY_SET_ID = "sha256:13c2e96b3a590da34e85114ede0810279abb7142661bc8ccdad79f184663293b"
        private val nativeCallLock = Any()
        private val EMPTY_NATIVE_INPUT = ByteArray(2)
        private const val AUTHENTICATION_BUFFER_SIZE = 50
        private const val STANDARD_COMMAND_BUFFER_SIZE = 50
        private const val RAW_COMMAND_BUFFER_SIZE = 30
        private const val RESET_BUFFER_SIZE = 1_024
        private const val MAX_PACKET_SIZE = 250
        private const val SPLIT_METADATA_SIZE = 2
        private const val SPLIT_FORMATTED_PAYLOAD_SIZE = 7_168
        private const val SPLIT_CANARY_SIZE = 64
        private const val SPLIT_CANARY_BYTE = 0x5a.toByte()
        private const val SPLIT_WORKSPACE_SIZE = 2
        private const val GS1_DATA_METADATA = 0xC007
        private const val GS1_PACKET_FIXED_BYTES = 12
        private const val GS1_RECORD_BYTES = 8
        private val GS1_JSON_RECORD = Regex(
            """\{\s*"index"\s*:\s*(\d+)\s*,\s*"temp"\s*:\s*(\d+)\s*,\s*"current"\s*:\s*(\d+)\s*,\s*"dump"\s*:\s*(\d+)\s*,\s*"reindex"\s*:\s*(\d+)\s*,\s*"glouse"\s*:\s*(\d+)\s*,\s*"trend"\s*:\s*(\d+)\s*,\s*"gwarn"\s*:\s*(\d+)\s*,\s*"twarn"\s*:\s*(\d+)\s*,\s*"cwarn"\s*:\s*(\d+)\s*,\s*"itime"\s*:\s*(\d+)\s*\}""",
        )
    }
}

private data class SplitAttempt(
    val code: Int,
    val metadata: IntArray,
    val formattedPayload: ByteArray,
    val workspace: ByteArray,
    val encrypted: Boolean,
    val canaryIntact: Boolean,
    val workspaceIntact: Boolean,
)

private fun String.skipWhitespace(from: Int): Int {
    var position = from
    while (position < length && this[position].isWhitespace()) position += 1
    return position
}

private fun Long.toExactInt(): Int {
    require(this in Int.MIN_VALUE.toLong()..Int.MAX_VALUE.toLong())
    return toInt()
}

internal interface NativeDataHandleApi {
    fun registerKey(input: ByteArray, length: Int, output: ByteArray): Int

    fun applyAuthentication(
        command: Int,
        encrypted: Boolean,
        value: Int,
        input: ByteArray,
        output: ByteArray,
        outputLength: Int,
    ): Int

    fun activation(
        command: Int,
        encrypted: Boolean,
        input: ByteArray,
        unixTime: Long,
        value: Int,
        output: ByteArray,
        outputLength: Int,
    ): Int

    fun timeUpdate(
        command: Int,
        encrypted: Boolean,
        input: ByteArray,
        unixTime: Long,
        output: ByteArray,
        outputLength: Int,
    ): Int

    fun rawData(
        command: Int,
        encrypted: Boolean,
        input: ByteArray,
        value: Long,
        index: Int,
        output: ByteArray,
        outputLength: Int,
    ): Int

    fun reset(
        command: Int,
        encrypted: Boolean,
        input: ByteArray,
        value: Int,
        output: ByteArray,
        outputLength: Int,
    ): Int

    fun splitData(
        command: Int,
        packet: ByteArray,
        metadata: IntArray,
        formattedPayload: ByteArray,
        encrypted: Boolean,
        workspace: ByteArray,
        workspaceLength: Int,
    ): Int
}

private object JniNativeDataHandleApi : NativeDataHandleApi {
    init {
        System.loadLibrary("data-handle-lib")
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
        command,
        encrypted,
        value,
        input,
        output,
        outputLength,
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
        command,
        encrypted,
        input,
        unixTime,
        value,
        output,
        outputLength,
    )

    override fun timeUpdate(
        command: Int,
        encrypted: Boolean,
        input: ByteArray,
        unixTime: Long,
        output: ByteArray,
        outputLength: Int,
    ): Int = CGMDataHandle130.V120IsecUpdate(
        command,
        encrypted,
        input,
        unixTime,
        output,
        outputLength,
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
        command,
        encrypted,
        input,
        value,
        index,
        output,
        outputLength,
    )

    override fun reset(
        command: Int,
        encrypted: Boolean,
        input: ByteArray,
        value: Int,
        output: ByteArray,
        outputLength: Int,
    ): Int = CGMDataHandle130.V120Reset(
        command,
        encrypted,
        input,
        value,
        output,
        outputLength,
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
