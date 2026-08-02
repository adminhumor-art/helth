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
            initialWireProfile = Gs1WireProfile.V120,
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
            initialWireProfile = Gs1WireProfile.V120,
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
            initialWireProfile = Gs1WireProfile.V120,
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
            initialWireProfile = Gs1WireProfile.V120,
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
            initialWireProfile = Gs1WireProfile.V120,
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
            initialWireProfile = Gs1WireProfile.V120,
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
            initialWireProfile = Gs1WireProfile.V120,
        )

        assertTrue(session.initial("01:23:45:67:89:AB") is SessionAction.Failure)
    }

    @Test
    fun unresolvedChineseSessionStartsWithExactV115ProbeAndNoNativeAuth() {
        val commands = RecordingGs1CommandCodec()
        var commandProviderCalls = 0
        val session = SibionicsSession(
            SensorFamily.SIBIONICS_GS1,
            SensorConfiguration("sensor", protocolVariant = 2),
            gs1CommandProvider = {
                commandProviderCalls += 1
                commands
            },
            initialNextIndex = 41,
            initialWireProfile = Gs1WireProfile.UNRESOLVED,
        )

        val initial = session.initial("AA:BB:CC:DD:EE:FF") as SessionAction.Write

        assertArrayEquals(
            Gs1V115WireCodec.request(41, "AA:BB:CC:DD:EE:FF"),
            initial.bytes,
        )
        assertTrue(commands.calls.isEmpty())
        assertEquals(0, commandProviderCalls)
        assertEquals(Gs1WireProfile.UNRESOLVED, session.wireProfile)

        session.confirmWireProfile(Gs1WireProfile.V120)
        assertEquals(1, commandProviderCalls)
    }

    @Test
    fun durableV120ResolutionStartsTheExistingHandshakeWhileV115StartsDataPhase() {
        val commands = RecordingGs1CommandCodec()
        val v120 = SibionicsSession(
            SensorFamily.SIBIONICS_GS1,
            SensorConfiguration("sensor", protocolVariant = 2),
            gs1Commands = commands,
            initialWireProfile = Gs1WireProfile.UNRESOLVED,
        )
        v120.initial("AA:BB:CC:DD:EE:FF")

        val auth = v120.confirmWireProfile(Gs1WireProfile.V120) as SessionAction.Write

        assertArrayEquals(byteArrayOf(0x01), auth.bytes)
        assertEquals(listOf("auth:2:AA:BB:CC:DD:EE:FF"), commands.calls)

        val v115 = SibionicsSession(
            SensorFamily.SIBIONICS_GS1,
            SensorConfiguration("sensor", protocolVariant = 2),
            gs1Commands = RecordingGs1CommandCodec(),
            initialWireProfile = Gs1WireProfile.UNRESOLVED,
        )
        v115.initial("AA:BB:CC:DD:EE:FF")
        assertEquals(SessionAction.None, v115.confirmWireProfile(Gs1WireProfile.V115))
        assertEquals(Gs1WireProfile.V115, v115.wireProfile)
    }

    @Test
    fun alreadyBoundV115StartsWithCursorRequestAndRejectsAnOppositeSwitch() {
        val session = SibionicsSession(
            SensorFamily.SIBIONICS_GS1,
            SensorConfiguration("sensor", protocolVariant = 2),
            initialNextIndex = 77,
            initialWireProfile = Gs1WireProfile.V115,
        )

        val request = session.initial("AA:BB:CC:DD:EE:FF") as SessionAction.Write
        val opposite = session.confirmWireProfile(Gs1WireProfile.V120)

        assertArrayEquals(Gs1V115WireCodec.request(77, "AA:BB:CC:DD:EE:FF"), request.bytes)
        assertTrue(opposite is SessionAction.Failure)
    }

    @Test
    fun v115WritesOneInitialHistoryRequestThenReliesOnSensorDrivenNotifications() {
        val session = SibionicsSession(
            SensorFamily.SIBIONICS_GS1,
            SensorConfiguration("sensor", protocolVariant = 2),
            initialNextIndex = 77,
            initialWireProfile = Gs1WireProfile.V115,
        )

        val initial = session.initial("AA:BB:CC:DD:EE:FF")
        val firstCommit = session.confirmDurablyCommitted(
            listOf(DecodedGs1RawSample(77, now, 50, 321, 0)),
        )
        val nextCommit = session.confirmDurablyCommitted(
            listOf(DecodedGs1RawSample(78, now + 60, 51, 322, 0)),
        )

        assertTrue(initial is SessionAction.Write)
        assertEquals(SessionAction.None, firstCommit)
        assertEquals(SessionAction.None, nextCommit)
        assertEquals(79, session.durableNextIndex)
        assertTrue(session.initial("AA:BB:CC:DD:EE:FF") is SessionAction.Failure)
    }

    @Test
    fun gs1SbDeviceConfigFourWritesOneNativeResetAndResetAckIsNonTerminal() {
        val commands = RecordingGs1CommandCodec()
        val session = receivingSession(SensorFamily.SIBIONICS_GS1SB, commands)

        val reset = session.onPacket(DecodedPacket.DeviceInformation(subcommand = 4))
        val duplicateBeforeAck = session.onPacket(
            DecodedPacket.DeviceInformation(subcommand = 4),
        )
        val resetAck = session.onPacket(
            DecodedPacket.Acknowledgement(command = 0x0b, status = 0, detail = 0),
        )
        val dataAfterReset = session.onPacket(
            DecodedPacket.Gs1RawSamples(
                listOf(DecodedGs1RawSample(1, now, 50, 321, 0)),
            ),
        )

        assertTrue(reset is SessionAction.Write)
        assertTrue((reset as SessionAction.Write).refreshTransportSilenceDeadline)
        assertEquals(SessionAction.None, duplicateBeforeAck)
        assertEquals(SessionAction.None, resetAck)
        assertEquals(SessionAction.None, dataAfterReset)
        assertEquals(1, commands.calls.count { it == "reset" })
    }

    @Test
    fun unknownDeviceConfigSubcommandFailsClosedWithoutReset() {
        val commands = RecordingGs1CommandCodec()
        val session = receivingSession(SensorFamily.SIBIONICS_GS1, commands)

        val result = session.onPacket(DecodedPacket.DeviceInformation(subcommand = 3))

        assertTrue(result is SessionAction.Failure)
        assertTrue("reset" !in commands.calls)
    }

    @Test
    fun gs3CannotConstructTheGs1ProtocolSessionOrReachACommandCodec() {
        val commands = RecordingGs1CommandCodec()

        val failure = assertThrows(IllegalArgumentException::class.java) {
            SibionicsSession(
                SensorFamily.SIBIONICS_GS3,
                SensorConfiguration("sensor", protocolVariant = 4),
                gs1Commands = commands,
                initialWireProfile = Gs1WireProfile.V120,
            )
        }

        assertTrue(failure.message.orEmpty().contains("GS1/GS1Sb"))
        assertTrue(commands.calls.isEmpty())
    }

    private fun receivingSession(
        family: SensorFamily,
        commands: RecordingGs1CommandCodec,
    ): SibionicsSession = SibionicsSession(
        family,
        SensorConfiguration("sensor", protocolVariant = 0),
        gs1Commands = commands,
        initialWireProfile = Gs1WireProfile.V120,
        epochSeconds = { now },
    ).also { session ->
        session.initial("01:23:45:67:89:AB")
        session.onPacket(DecodedPacket.Acknowledgement(0x01, 1, 0))
        session.onPacket(DecodedPacket.Acknowledgement(0x07, 0, 0))
        session.onPacket(DecodedPacket.Acknowledgement(0x03, 0, 0))
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

    override fun reset(): Gs1CommandResult = success("reset", 0x0b)

    private fun success(call: String, marker: Int): Gs1CommandResult {
        calls += call
        return Gs1CommandResult.Success(byteArrayOf(marker.toByte()))
    }
}
