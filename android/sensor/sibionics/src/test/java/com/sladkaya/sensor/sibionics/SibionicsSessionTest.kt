package com.sladkaya.sensor.sibionics

import com.sladkaya.core.model.SensorFamily
import com.sladkaya.core.sensor.SensorConfiguration
import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SibionicsSessionTest {
    private val now = 1_700_000_000L

    @Test
    fun gs1UsesCommandCodecInsteadOfReimplementingNativeCommandBytes() {
        val commands = RecordingGs1CommandCodec()
        val session = SibionicsSession(
            SensorFamily.SIBIONICS_GS1,
            SensorConfiguration("sensor", protocolVariant = 1),
            gs1Commands = commands,
            epochSeconds = { now },
        )

        assertTrue(session.initial("01:23:45:67:89:AB") is SessionAction.Write)
        assertTrue(session.onPacket(DecodedPacket.Acknowledgement(0x01, 1, 0)) is SessionAction.Write)
        assertTrue(session.onPacket(DecodedPacket.Acknowledgement(0x07, 0, 0)) is SessionAction.Write)
        assertTrue(session.onPacket(DecodedPacket.Acknowledgement(0x03, 0, 0)) is SessionAction.Write)

        assertEquals(
            listOf(
                "auth:1:01:23:45:67:89:AB",
                "activation:$now",
                "time:$now",
                "raw:1",
            ),
            commands.calls,
        )
    }

    @Test
    fun gs1SessionRunsAuthenticationActivationTimeAndDataRequest() {
        val commands = RecordingGs1CommandCodec()
        val session = SibionicsSession(
            SensorFamily.SIBIONICS_GS1SB,
            SensorConfiguration("sensor", protocolVariant = 1),
            gs1Commands = commands,
            epochSeconds = { now },
        )

        assertTrue(session.initial("01:23:45:67:89:AB") is SessionAction.Write)
        assertTrue(session.onPacket(DecodedPacket.Acknowledgement(0x01, 1, 0)) is SessionAction.Write)
        assertTrue(session.onPacket(DecodedPacket.Acknowledgement(0x07, 0, 0)) is SessionAction.Write)
        assertTrue(session.onPacket(DecodedPacket.Acknowledgement(0x03, 0, 0)) is SessionAction.Write)
        assertEquals("raw:1", commands.calls.last())
    }

    @Test
    fun restoredGs1SessionRequestsExactlyTheIndexAfterDurableCheckpoint() {
        val commands = RecordingGs1CommandCodec()
        val session = SibionicsSession(
            SensorFamily.SIBIONICS_GS1,
            SensorConfiguration("sensor", protocolVariant = 0),
            gs1Commands = commands,
            initialNextIndex = 41,
            epochSeconds = { now },
        )

        session.initial("01:23:45:67:89:AB")
        session.onPacket(DecodedPacket.Acknowledgement(0x01, 1, 0))
        session.onPacket(DecodedPacket.Acknowledgement(0x07, 0, 0))
        session.onPacket(DecodedPacket.Acknowledgement(0x03, 0, 0))

        assertEquals("raw:41", commands.calls.last())
    }

    @Test
    fun gs1AuthenticationRetryIsDelayedAndReusesTheExactCommand() {
        val commands = RecordingGs1CommandCodec()
        val session = SibionicsSession(
            SensorFamily.SIBIONICS_GS1,
            SensorConfiguration("sensor", protocolVariant = 0),
            gs1Commands = commands,
            epochSeconds = { now },
        )
        val initial = session.initial("01:23:45:67:89:AB") as SessionAction.Write

        val retry = session.onPacket(
            DecodedPacket.Acknowledgement(command = 0x00, status = 0, detail = 0),
        ) as SessionAction.WriteAfter

        assertEquals(1_000L, retry.delayMillis)
        assertArrayEquals(initial.bytes, retry.bytes)
        assertEquals(listOf("auth:0:01:23:45:67:89:AB"), commands.calls)
    }

    @Test
    fun gs1RejectsOutOfPhaseAcknowledgementWithoutSendingAnotherCommand() {
        val commands = RecordingGs1CommandCodec()
        val session = SibionicsSession(
            SensorFamily.SIBIONICS_GS1,
            SensorConfiguration("sensor", protocolVariant = 0),
            gs1Commands = commands,
            epochSeconds = { now },
        )
        session.initial("01:23:45:67:89:AB")

        val result = session.onPacket(DecodedPacket.Acknowledgement(0x03, 0, 0))

        assertTrue(result is SessionAction.Failure)
        assertEquals(listOf("auth:0:01:23:45:67:89:AB"), commands.calls)
    }

    @Test
    fun receivedGs1SamplesCannotAdvanceCursorUntilDurableCommitIsConfirmed() {
        val commands = RecordingGs1CommandCodec()
        val session = SibionicsSession(
            SensorFamily.SIBIONICS_GS1,
            SensorConfiguration("sensor", protocolVariant = 0),
            gs1Commands = commands,
            initialNextIndex = 41,
            epochSeconds = { now },
        )
        session.initial("01:23:45:67:89:AB")
        session.onPacket(DecodedPacket.Acknowledgement(0x01, 1, 0))
        session.onPacket(DecodedPacket.Acknowledgement(0x07, 0, 0))
        session.onPacket(DecodedPacket.Acknowledgement(0x03, 0, 0))

        session.onPacket(
            DecodedPacket.Gs1RawSamples(
                listOf(DecodedGs1RawSample(41, now, 50, 321, 0)),
            ),
        )
        assertEquals(41, session.durableNextIndex)

        session.confirmDurablyCommitted(
            listOf(DecodedGs1RawSample(41, now, 50, 321, 0)),
        )
        assertEquals(42, session.durableNextIndex)
    }

    @Test
    fun gs1RequiresExplicitRegionalVariant() {
        val session = SibionicsSession(
            SensorFamily.SIBIONICS_GS1,
            SensorConfiguration("sensor"),
            gs1Commands = RecordingGs1CommandCodec(),
        )

        assertTrue(session.initial("01:23:45:67:89:AB") is SessionAction.Failure)
    }

    @Test
    fun gs3SessionRunsBindDeviceInfoActivationTimeAndDataRequest() {
        val account = ByteArray(12) { (it + 1).toByte() }
        val session = SibionicsSession(
            SensorFamily.SIBIONICS_GS3,
            SensorConfiguration("sensor", pairingPayload = account),
            epochSeconds = { now },
        )

        assertCommand(session.initial("01:23:45:67:89:AB"), 0x01)
        val bind = assertCommand(session.onPacket(DecodedPacket.Acknowledgement(0x01, 0, 0)), 0x13)
        assertEquals(1, bind[2].u8())
        assertArrayEquals(account, bind.copyOfRange(3, 15))
        val infoOne = assertCommand(session.onPacket(DecodedPacket.Acknowledgement(0x13, 0, 0)), 0xf0)
        assertEquals(1, infoOne[2].u8())
        val infoSeven = assertCommand(session.onPacket(DecodedPacket.DeviceInformation(1)), 0xf0)
        assertEquals(7, infoSeven[2].u8())
        val activation = assertCommand(session.onPacket(DecodedPacket.DeviceInformation(7)), 0x0f)
        assertEquals(now, activation.u32le(2))
        assertCommand(session.onPacket(DecodedPacket.Acknowledgement(0x0f, 0, 0)), 0x03)
        val request = assertCommand(session.onPacket(DecodedPacket.Acknowledgement(0x03, 0, 0)), 0x14)
        assertEquals(1, request.u16le(2))
    }

    @Test
    fun sampleAdvancesNextRequestedIndex() {
        val account = ByteArray(12)
        val session = SibionicsSession(
            SensorFamily.SIBIONICS_GS3,
            SensorConfiguration("sensor", pairingPayload = account),
        )
        session.onPacket(DecodedPacket.Gs3GlucoseSamples(listOf(DecodedGs3GlucoseSample(25, now, 100))))

        val request = assertCommand(session.onPacket(DecodedPacket.Acknowledgement(0x03, 0, 0)), 0x14)
        assertEquals(26, request.u16le(2))
    }

    private fun assertCommand(action: SessionAction, command: Int): ByteArray {
        assertTrue(action is SessionAction.Write)
        val plain = Rc4.xor((action as SessionAction.Write).bytes)
        assertEquals(command, plain[1].u8())
        assertChecksum(plain)
        return plain
    }

    private fun assertChecksum(bytes: ByteArray) {
        assertEquals(0, bytes.sumOf { it.u8() } and 0xff)
    }

    private fun Byte.u8(): Int = toInt() and 0xff

    private fun ByteArray.u16le(offset: Int): Int =
        ByteBuffer.wrap(this, offset, 2).order(ByteOrder.LITTLE_ENDIAN).short.toInt() and 0xffff

    private fun ByteArray.u32le(offset: Int): Long =
        ByteBuffer.wrap(this, offset, 4).order(ByteOrder.LITTLE_ENDIAN).int.toLong() and 0xffff_ffffL
}

private class RecordingGs1CommandCodec : Gs1CommandCodec {
    val calls = mutableListOf<String>()

    override fun authentication(protocolVariant: Int, bluetoothAddress: String): Gs1CommandResult =
        success("auth:$protocolVariant:$bluetoothAddress", 0x01)

    override fun activation(epochSeconds: Long): Gs1CommandResult =
        success("activation:$epochSeconds", 0x07)

    override fun timeUpdate(epochSeconds: Long): Gs1CommandResult =
        success("time:$epochSeconds", 0x03)

    override fun rawData(index: Int): Gs1CommandResult =
        success("raw:$index", 0x08)

    private fun success(call: String, marker: Int): Gs1CommandResult {
        calls += call
        return Gs1CommandResult.Success(byteArrayOf(marker.toByte()))
    }
}
