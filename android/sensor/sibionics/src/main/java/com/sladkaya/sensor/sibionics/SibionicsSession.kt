package com.sladkaya.sensor.sibionics

import com.sladkaya.core.model.SensorFamily
import com.sladkaya.core.sensor.SensorConfiguration
import com.sladkaya.sensor.sibionics.datahandle.DataHandleCommandResult
import com.sladkaya.sensor.sibionics.datahandle.DataHandleVariant
import com.sladkaya.sensor.sibionics.datahandle.SibionicsDataHandle
import java.nio.ByteBuffer
import java.nio.ByteOrder

internal sealed interface SessionAction {
    data class Write(val bytes: ByteArray) : SessionAction
    data class WriteAfter(val delayMillis: Long, val bytes: ByteArray) : SessionAction
    data class Failure(val reason: String) : SessionAction
    data object None : SessionAction
}

internal sealed interface Gs1CommandResult {
    data class Success(val bytes: ByteArray) : Gs1CommandResult
    data class Failure(val reason: String) : Gs1CommandResult
}

internal interface Gs1CommandCodec {
    fun authentication(protocolVariant: Int, bluetoothAddress: String): Gs1CommandResult
    fun activation(epochSeconds: Long): Gs1CommandResult
    fun timeUpdate(epochSeconds: Long): Gs1CommandResult
    fun rawData(index: Int): Gs1CommandResult
}

internal class OfficialGs1CommandCodec(
    private val dataHandle: SibionicsDataHandle,
) : Gs1CommandCodec {
    override fun authentication(protocolVariant: Int, bluetoothAddress: String): Gs1CommandResult {
        val variant = DataHandleVariant.entries.firstOrNull {
            it.protocolCode == protocolVariant && it != DataHandleVariant.GS3
        } ?: return Gs1CommandResult.Failure("Unsupported GS1 protocol variant $protocolVariant")
        return dataHandle.authentication(variant, bluetoothAddress).toGs1CommandResult()
    }

    override fun activation(epochSeconds: Long): Gs1CommandResult =
        dataHandle.activation(epochSeconds).toGs1CommandResult()

    override fun timeUpdate(epochSeconds: Long): Gs1CommandResult =
        dataHandle.timeUpdate(epochSeconds).toGs1CommandResult()

    override fun rawData(index: Int): Gs1CommandResult =
        dataHandle.rawData(index).toGs1CommandResult()

    private fun DataHandleCommandResult.toGs1CommandResult(): Gs1CommandResult = when (this) {
        is DataHandleCommandResult.Success -> Gs1CommandResult.Success(bytes.copyOf())
        is DataHandleCommandResult.Failure -> Gs1CommandResult.Failure(
            buildString {
                append("Official command builder failed: ")
                append(error.name)
                nativeCode?.let { append(" ($it)") }
            },
        )
    }
}

/**
 * Pure protocol state machine. Physical readings remain diagnostic until this
 * sequence is matched against the manufacturer's application on real hardware.
 */
internal class SibionicsSession(
    private val family: SensorFamily,
    private val configuration: SensorConfiguration,
    private val gs1Commands: Gs1CommandCodec? = null,
    initialNextIndex: Int = 1,
    private val epochSeconds: () -> Long = { System.currentTimeMillis() / 1_000L },
) {
    private var nextIndex = initialNextIndex
    private var authenticationCommand: ByteArray? = null
    private var gs1Phase = Gs1Phase.CREATED

    internal val durableNextIndex: Int
        get() = nextIndex

    init {
        require(initialNextIndex in 1..0xffff)
    }

    fun initial(deviceAddress: String): SessionAction {
        val variant = when (family) {
            SensorFamily.SIBIONICS_GS3 -> configuration.protocolVariant ?: 4
            SensorFamily.SIBIONICS_GS1, SensorFamily.SIBIONICS_GS1SB -> configuration.protocolVariant
                ?: return SessionAction.Failure("SiBionics protocol variant is required for this regional model")
            else -> return SessionAction.Failure("Unsupported SiBionics family")
        }
        if (family == SensorFamily.SIBIONICS_GS3) {
            return runCatching { SessionAction.Write(SibionicsCommands.gs3Authentication(deviceAddress)) }
                .getOrElse { SessionAction.Failure(it.message ?: "GS3 authentication command could not be built") }
        }
        val commands = gs1Commands
            ?: return SessionAction.Failure("Official GS1 command codec is unavailable")
        if (gs1Phase != Gs1Phase.CREATED) {
            return failGs1("GS1 session has already been started")
        }
        return when (val result = commands.authentication(variant, deviceAddress)) {
            is Gs1CommandResult.Success -> {
                authenticationCommand = result.bytes.copyOf()
                gs1Phase = Gs1Phase.AUTHENTICATING
                SessionAction.Write(result.bytes)
            }
            is Gs1CommandResult.Failure -> failGs1(result.reason)
        }
    }

    fun onPacket(packet: DecodedPacket): SessionAction {
        if (family == SensorFamily.SIBIONICS_GS1 || family == SensorFamily.SIBIONICS_GS1SB) {
            return onGs1Packet(packet)
        }
        return when (packet) {
            is DecodedPacket.Acknowledgement -> onAcknowledgement(packet)
            is DecodedPacket.DeviceInformation -> onDeviceInformation(packet.subcommand)
            is DecodedPacket.Gs3GlucoseSamples -> {
                packet.values.maxOfOrNull { it.index }?.let { nextIndex = it + 1 }
                SessionAction.None
            }
            is DecodedPacket.Invalid -> SessionAction.Failure("Invalid sensor packet: ${packet.reason}")
            else -> SessionAction.None
        }
    }

    fun confirmDurablyCommitted(samples: List<DecodedGs1RawSample>): SessionAction {
        if (family != SensorFamily.SIBIONICS_GS1 && family != SensorFamily.SIBIONICS_GS1SB) {
            return SessionAction.Failure("Durable GS1 cursor cannot be used for this sensor family")
        }
        if (gs1Phase != Gs1Phase.RECEIVING_DATA) {
            return failGs1("GS1 samples were committed outside the data phase")
        }
        if (samples.isEmpty()) return SessionAction.None
        if (samples.first().index != nextIndex ||
            !samples.zipWithNext().all { (first, second) -> second.index == first.index + 1 }
        ) {
            return failGs1("Committed GS1 samples do not match the durable cursor")
        }
        nextIndex = samples.last().index + 1
        return SessionAction.None
    }

    private fun onGs1Packet(packet: DecodedPacket): SessionAction {
        if (gs1Phase == Gs1Phase.FAILED) {
            return SessionAction.Failure("GS1 protocol session is closed")
        }
        return when (packet) {
            is DecodedPacket.Acknowledgement -> onGs1Acknowledgement(packet)
            is DecodedPacket.Gs1RawSamples -> if (gs1Phase == Gs1Phase.RECEIVING_DATA) {
                SessionAction.None
            } else {
                failGs1("GS1 data arrived before the handshake completed")
            }
            is DecodedPacket.Invalid -> failGs1("Invalid sensor packet: ${packet.reason}")
            is DecodedPacket.Unsupported -> failGs1(
                "Unexpected GS1 command 0x${packet.command.toString(16)}",
            )
            else -> failGs1("Unexpected packet type in GS1 protocol session")
        }
    }

    private fun onGs1Acknowledgement(ack: DecodedPacket.Acknowledgement): SessionAction =
        when (gs1Phase) {
            Gs1Phase.AUTHENTICATING -> when (ack.command) {
                0x00 -> retryAuthentication()
                0x01 -> if (ack.status == 1 || ack.detail == 3) {
                    gs1Command(Gs1Phase.ACTIVATING) { activation(epochSeconds()) }
                } else {
                    retryAuthentication()
                }
                else -> failGs1("Unexpected acknowledgement while authenticating: ${ack.command}")
            }

            Gs1Phase.ACTIVATING -> if (ack.command == 0x07) {
                gs1Command(Gs1Phase.UPDATING_TIME) { timeUpdate(epochSeconds()) }
            } else {
                failGs1("Unexpected acknowledgement while activating: ${ack.command}")
            }

            Gs1Phase.UPDATING_TIME -> if (ack.command == 0x03) {
                gs1Command(Gs1Phase.RECEIVING_DATA) { rawData(nextIndex) }
            } else {
                failGs1("Unexpected acknowledgement while updating time: ${ack.command}")
            }

            Gs1Phase.RECEIVING_DATA -> if (ack.command == 0x08) {
                SessionAction.None
            } else {
                failGs1("Unexpected acknowledgement while receiving data: ${ack.command}")
            }

            Gs1Phase.CREATED -> failGs1("GS1 acknowledgement arrived before authentication")
            Gs1Phase.FAILED -> SessionAction.Failure("GS1 protocol session is closed")
        }

    private fun retryAuthentication(): SessionAction {
        val command = authenticationCommand
            ?: return failGs1("GS1 authentication command is unavailable")
        return SessionAction.WriteAfter(AUTHENTICATION_RETRY_DELAY_MS, command.copyOf())
    }

    private fun onAcknowledgement(ack: DecodedPacket.Acknowledgement): SessionAction {
        if (family == SensorFamily.SIBIONICS_GS3) {
            return when (ack.command) {
                0x01 -> bindUser(1)
                0x13 -> when {
                    ack.status == 2 -> SessionAction.Failure("GS3 rejected the account ID")
                    ack.status == 0 && ack.detail == 0 -> SessionAction.Write(SibionicsCommands.deviceInfo(1))
                    else -> bindUser(2)
                }
                0x0f -> SessionAction.Write(SibionicsCommands.timeUpdate(epochSeconds()))
                0x03 -> SessionAction.Write(SibionicsCommands.requestGs3(nextIndex))
                else -> SessionAction.None
            }
        }
        return SessionAction.Failure("GS1 acknowledgement reached the GS3 state machine")
    }

    private inline fun gs1Command(
        nextPhase: Gs1Phase,
        build: Gs1CommandCodec.() -> Gs1CommandResult,
    ): SessionAction {
        val commands = gs1Commands ?: return failGs1("Official GS1 command codec is unavailable")
        return when (val result = commands.build()) {
            is Gs1CommandResult.Success -> {
                gs1Phase = nextPhase
                SessionAction.Write(result.bytes)
            }
            is Gs1CommandResult.Failure -> failGs1(result.reason)
        }
    }

    private fun failGs1(reason: String): SessionAction.Failure {
        gs1Phase = Gs1Phase.FAILED
        return SessionAction.Failure(reason)
    }

    private fun onDeviceInformation(subcommand: Int): SessionAction {
        if (family != SensorFamily.SIBIONICS_GS3) return SessionAction.None
        return when (subcommand) {
            1 -> SessionAction.Write(SibionicsCommands.deviceInfo(7))
            7 -> SessionAction.Write(SibionicsCommands.gs3Activation(epochSeconds()))
            else -> SessionAction.None
        }
    }

    private fun bindUser(sequence: Int): SessionAction {
        val accountId = configuration.pairingPayload
        if (accountId == null || accountId.size != 12) {
            return SessionAction.Failure("GS3 requires a 12-byte account ID payload")
        }
        return SessionAction.Write(SibionicsCommands.bindUser(accountId, sequence))
    }

    private enum class Gs1Phase {
        CREATED,
        AUTHENTICATING,
        ACTIVATING,
        UPDATING_TIME,
        RECEIVING_DATA,
        FAILED,
    }

    private companion object {
        const val AUTHENTICATION_RETRY_DELAY_MS = 1_000L
    }
}

internal object SibionicsCommands {
    fun gs3Authentication(address: String): ByteArray {
        val addressBytes = parseAddress(address).reversedArray()
        val plain = ByteArray(26)
        plain[0] = 0x19
        plain[1] = 0x01
        addressBytes.copyInto(plain, 3)
        GS3_REGISTRATION_KEY.encodeToByteArray().copyInto(plain, 9)
        finishChecksum(plain)
        return Rc4.xor(plain)
    }

    fun timeUpdate(epochSeconds: Long): ByteArray = packet(7) {
        this[0] = 0x06
        this[1] = 0x03
        putU32(2, epochSeconds)
    }

    fun requestGs3(index: Int): ByteArray = request(index, 0x14)

    fun bindUser(accountId: ByteArray, sequence: Int): ByteArray = packet(16) {
        require(accountId.size == 12)
        this[0] = 0x0f
        this[1] = 0x13
        this[2] = sequence.toByte()
        accountId.copyInto(this, 3)
    }

    fun deviceInfo(number: Int): ByteArray = packet(4) {
        require(number in 1..12)
        this[0] = 0x03
        this[1] = 0xf0.toByte()
        this[2] = number.toByte()
    }

    fun gs3Activation(epochSeconds: Long): ByteArray = packet(7) {
        this[0] = 0x06
        this[1] = 0x0f
        putU32(2, epochSeconds)
    }

    private fun request(index: Int, command: Int): ByteArray = packet(7) {
        require(index in 0..0xffff)
        this[0] = 0x06
        this[1] = command.toByte()
        putU16(2, index)
        putU16(4, 0)
    }

    private inline fun packet(size: Int, fill: ByteArray.() -> Unit): ByteArray {
        val plain = ByteArray(size)
        plain.fill()
        finishChecksum(plain)
        return Rc4.xor(plain)
    }

    private fun finishChecksum(bytes: ByteArray) {
        bytes[bytes.lastIndex] = (-bytes.take(bytes.lastIndex).sum()).toByte()
    }

    private fun ByteArray.putU16(offset: Int, value: Int) {
        ByteBuffer.wrap(this, offset, 2).order(ByteOrder.LITTLE_ENDIAN).putShort(value.toShort())
    }

    private fun ByteArray.putU32(offset: Int, value: Long) {
        require(value in 0..0xffff_ffffL)
        ByteBuffer.wrap(this, offset, 4).order(ByteOrder.LITTLE_ENDIAN).putInt(value.toInt())
    }

    private fun parseAddress(value: String): ByteArray {
        val parts = value.split(':')
        require(parts.size == 6 && parts.all { it.length == 2 }) { "Invalid Bluetooth address" }
        return parts.map { it.toInt(16).toByte() }.toByteArray()
    }

    private const val GS3_REGISTRATION_KEY = "THE544U0TYITE461"
}
