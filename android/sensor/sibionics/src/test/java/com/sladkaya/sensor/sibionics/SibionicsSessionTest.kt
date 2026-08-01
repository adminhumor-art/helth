package com.sladkaya.sensor.sibionics

import com.sladkaya.core.model.SensorFamily
import com.sladkaya.core.sensor.SensorConfiguration
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
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
    fun gs3CannotConstructTheGs1ProtocolSessionOrReachACommandCodec() {
        val commands = RecordingGs1CommandCodec()

        val failure = assertThrows(IllegalArgumentException::class.java) {
            SibionicsSession(
                SensorFamily.SIBIONICS_GS3,
                SensorConfiguration("sensor", protocolVariant = 4),
                gs1Commands = commands,
            )
        }

        assertTrue(failure.message.orEmpty().contains("GS1/GS1Sb"))
        assertTrue(commands.calls.isEmpty())
    }
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
