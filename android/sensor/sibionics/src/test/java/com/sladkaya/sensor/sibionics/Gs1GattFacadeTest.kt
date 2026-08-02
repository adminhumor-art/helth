package com.sladkaya.sensor.sibionics

import com.sladkaya.core.data.SensorPacketIngressRecord
import com.sladkaya.core.model.GlucoseReading
import com.sladkaya.core.model.ReadingQuality
import com.sladkaya.core.model.SensorFamily
import java.security.MessageDigest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class Gs1GattFacadeTest {
    @Test
    fun diagnosticFacadeKeepsItsApiAndProductFacadeExposesNoDiagnosticStream() {
        val diagnosticMethods = Gs1DiagnosticGattDriver::class.java.declaredMethods
            .mapTo(mutableSetOf(), java.lang.reflect.Method::getName)
        val productMethods = Gs1ProductGattDriver::class.java.declaredMethods
            .mapTo(mutableSetOf(), java.lang.reflect.Method::getName)

        assertTrue("getState" in diagnosticMethods)
        assertTrue("getLatestDiagnostic" in diagnosticMethods)
        assertTrue("start" in diagnosticMethods)
        assertTrue("stop" in diagnosticMethods)
        assertTrue("requestStop" in diagnosticMethods)
        assertTrue("getState" in productMethods)
        assertTrue("getCommittedPublicationBatches" in productMethods)
        assertFalse("getLatestDiagnostic" in productMethods)
    }

    @Test
    fun productOutputReleasesEveryPublicationInTheConfirmedBatchAndPreservesOrder() = runBlocking {
        val output = Gs1ProductGattOutput()
        val gate = Gs1GattDurableCommitGate(output)
        val first = publication(sequence = 46, eventId = "event-46")
        val second = publication(sequence = 47, eventId = "event-47")
        val event = committed(publications = listOf(first, second))

        val result = async { gate.dispatch(event) { SessionAction.None } }
        val emitted = output.committedPublicationBatches.first()

        assertFalse(result.isCompleted)
        assertEquals(listOf(first, second), emitted.publications)
        assertTrue(emitted.acknowledgeDurablyApplied())
        assertTrue(result.await() is Gs1GattDurableCommitResult.Accepted)
    }

    @Test
    fun productOutputEmitsNothingWhenDurableCursorConfirmationFails() = runBlocking {
        val output = Gs1ProductGattOutput()
        val gate = Gs1GattDurableCommitGate(output)

        val result = gate.dispatch(
            committed(publications = listOf(publication(46, "event-46"))),
        ) {
            SessionAction.Failure("cursor mismatch")
        }

        assertEquals(
            Gs1GattDurableCommitResult.Rejected(
                code = "DURABLE_CURSOR_REJECTED",
                detail = "cursor mismatch",
                retryable = false,
            ),
            result,
        )
        assertNull(withTimeoutOrNull(25) { output.committedPublicationBatches.first() })
    }

    @Test
    fun productOutputNeverTurnsDiagnosticValuesIntoPublications() = runBlocking {
        val output = Gs1ProductGattOutput()
        val gate = Gs1GattDurableCommitGate(output)
        val diagnostic = Gs1DiagnosticReading(
            eventId = "diagnostic-only",
            sensorId = SENSOR_ID,
            sensorFamily = SensorFamily.SIBIONICS_GS1SB,
            sensorTimeEpochMs = 1_700_000_000_000L,
            phoneTimeEpochMs = 1_700_000_001_000L,
            glucoseMgDl = 104,
            trendMgDlPerMinute = -1.3,
            quality = ReadingQuality.VALID,
            sequence = 46,
        )

        val result = gate.dispatch(
            committed(diagnostics = listOf(diagnostic), publications = emptyList()),
        ) { SessionAction.None }

        assertTrue(result is Gs1GattDurableCommitResult.Accepted)
        assertNull(withTimeoutOrNull(25) { output.committedPublicationBatches.first() })
    }

    @Test
    fun separateConfirmedBatchesAreNeverCollapsedOrOverwritten() = runBlocking {
        val output = Gs1ProductGattOutput()
        val gate = Gs1GattDurableCommitGate(output)
        val firstBatch = listOf(publication(46, "event-46"), publication(47, "event-47"))
        val secondBatch = listOf(publication(48, "event-48"))

        val firstDispatch = async {
            gate.dispatch(committed(publications = firstBatch)) { SessionAction.None }
        }
        val firstDelivered = output.committedPublicationBatches.first()
        assertEquals(firstBatch, firstDelivered.publications)
        firstDelivered.acknowledgeDurablyApplied()
        assertTrue(firstDispatch.await() is Gs1GattDurableCommitResult.Accepted)

        val secondDispatch = async {
            gate.dispatch(committed(publications = secondBatch)) { SessionAction.None }
        }
        val secondDelivered = output.committedPublicationBatches.first()
        assertEquals(secondBatch, secondDelivered.publications)
        secondDelivered.acknowledgeDurablyApplied()
        assertTrue(secondDispatch.await() is Gs1GattDurableCommitResult.Accepted)
    }

    @Test
    fun productCommitCannotCompleteBeforeDurableLocalEffectsAck() = runBlocking {
        val output = Gs1ProductGattOutput()
        val gate = Gs1GattDurableCommitGate(output)

        val dispatch = async {
            gate.dispatch(committed(publications = listOf(publication(46, "event-46")))) {
                SessionAction.None
            }
        }
        val delivered = output.committedPublicationBatches.first()

        assertFalse(dispatch.isCompleted)
        delivered.acknowledgeDurablyApplied()
        assertTrue(dispatch.await() is Gs1GattDurableCommitResult.Accepted)
    }

    @Test
    fun rejectedLocalEffectsLeaveTheProductCommitUnaccepted() = runBlocking {
        val output = Gs1ProductGattOutput()
        val gate = Gs1GattDurableCommitGate(output)

        val dispatch = async {
            gate.dispatch(committed(publications = listOf(publication(46, "event-46")))) {
                SessionAction.None
            }
        }
        val delivered = output.committedPublicationBatches.first()
        delivered.rejectDurableApplication(
            Gs1ProductLocalEffectsFailureCode.STORAGE_UNAVAILABLE,
        )

        assertEquals(
            Gs1GattDurableCommitResult.Rejected(
                code = "STORAGE_UNAVAILABLE",
                retryable = true,
            ),
            dispatch.await(),
        )
    }

    @Test
    fun withheldProductAckIsAbortedAndLateAckCannotResurrectIt() = runBlocking {
        val output = Gs1ProductGattOutput(applicationTimeoutMillis = 5_000)
        val gate = Gs1GattDurableCommitGate(output)
        val dispatch = async {
            gate.dispatch(committed(publications = listOf(publication(46, "event-46")))) {
                SessionAction.None
            }
        }
        val delivered = output.committedPublicationBatches.first()

        output.abortPendingApplications(Gs1ProductLocalEffectsFailureCode.APPLICATION_STOPPED)

        assertEquals(
            Gs1GattDurableCommitResult.Rejected(
                code = "APPLICATION_STOPPED",
                retryable = false,
            ),
            withTimeout(500) { dispatch.await() },
        )
        assertFalse(delivered.acknowledgeDurablyApplied())
    }

    @Test
    fun abortBreaksBothAwaitingAckAndAProducerBlockedOnTheFullBuffer() = runBlocking {
        val output = Gs1ProductGattOutput(
            bufferCapacity = 1,
            applicationTimeoutMillis = 5_000,
        )
        val gate = Gs1GattDurableCommitGate(output)
        val first = async {
            gate.dispatch(committed(publications = listOf(publication(46, "event-46")))) {
                SessionAction.None
            }
        }
        val blocked = async {
            gate.dispatch(committed(publications = listOf(publication(47, "event-47")))) {
                SessionAction.None
            }
        }
        delay(25)
        assertFalse(blocked.isCompleted)

        output.abortPendingApplications(Gs1ProductLocalEffectsFailureCode.APPLICATION_STOPPED)

        val expected = Gs1GattDurableCommitResult.Rejected(
            code = "APPLICATION_STOPPED",
            retryable = false,
        )
        assertEquals(expected, withTimeout(500) { first.await() })
        assertEquals(expected, withTimeout(500) { blocked.await() })
    }

    @Test
    fun stopBeforeProductApplicationRegistrationRejectsWithoutPublishing() = runBlocking {
        val output = Gs1ProductGattOutput(applicationTimeoutMillis = 5_000)
        val gate = Gs1GattDurableCommitGate(output)
        output.abortPendingApplications(Gs1ProductLocalEffectsFailureCode.APPLICATION_STOPPED)

        val result = gate.dispatch(
            committed(publications = listOf(publication(46, "event-46"))),
        ) { SessionAction.None }

        assertEquals(
            Gs1GattDurableCommitResult.Rejected(
                code = "APPLICATION_STOPPED",
                retryable = false,
            ),
            result,
        )
        assertNull(withTimeoutOrNull(25) { output.committedPublicationBatches.first() })
    }

    @Test
    fun missingProductAckTimesOutAsRetryableAndLateAckStaysRejected() = runBlocking {
        val output = Gs1ProductGattOutput(applicationTimeoutMillis = 25)
        val gate = Gs1GattDurableCommitGate(output)
        val dispatch = async {
            gate.dispatch(committed(publications = listOf(publication(46, "event-46")))) {
                SessionAction.None
            }
        }
        val delivered = output.committedPublicationBatches.first()

        assertEquals(
            Gs1GattDurableCommitResult.Rejected(
                code = "LOCAL_EFFECTS_TIMEOUT",
                retryable = true,
            ),
            withTimeout(500) { dispatch.await() },
        )
        assertFalse(delivered.acknowledgeDurablyApplied())
    }

    @Test
    fun storageConflictIsNonRetryable() = runBlocking {
        val output = Gs1ProductGattOutput()
        val gate = Gs1GattDurableCommitGate(output)
        val dispatch = async {
            gate.dispatch(committed(publications = listOf(publication(46, "event-46")))) {
                SessionAction.None
            }
        }
        val delivered = output.committedPublicationBatches.first()
        delivered.rejectDurableApplication(Gs1ProductLocalEffectsFailureCode.STORAGE_CONFLICT)

        assertEquals(
            Gs1GattDurableCommitResult.Rejected(
                code = "STORAGE_CONFLICT",
                retryable = false,
            ),
            dispatch.await(),
        )
    }

    @Test
    fun committedEventFailsWhenItsAttemptCannotAcceptDelivery() = runBlocking {
        val event = committed(publications = emptyList())
        val unavailableTargets = listOf(
            null to false,
            2L to true,
            1L to false,
        )

        unavailableTargets.forEach { (generation, accepting) ->
            val failure = runCatching {
                deliverGs1CoreEventOrFailCommitted(
                    event = event,
                    activeGeneration = generation,
                    accepting = accepting,
                    deliver = {},
                )
            }.exceptionOrNull()

            assertTrue(failure is Gs1CommittedDeliveryUnavailableException)
        }
    }

    @Test
    fun closedAttemptMailboxFailsCommittedDelivery() = runBlocking {
        val mailbox = Channel<Unit>(capacity = 1)
        mailbox.close()

        val failure = runCatching {
            deliverGs1CoreEventOrFailCommitted(
                event = committed(publications = emptyList()),
                activeGeneration = 1L,
                accepting = true,
                deliver = { mailbox.send(Unit) },
            )
        }.exceptionOrNull()

        assertTrue(failure is Gs1CommittedDeliveryUnavailableException)
    }

    @Test
    fun failedEventDeliveryCannotMaskTheOriginalRuntimeFailure() = runBlocking {
        deliverGs1CoreEventOrFailCommitted(
            event = Gs1DiagnosticRuntimeEvent.Failed(
                generation = 1L,
                code = "ORIGINAL_FAILURE",
                detail = "original state",
            ),
            activeGeneration = null,
            accepting = false,
            deliver = { error("must not deliver without an active attempt") },
        )

        Unit
    }

    @Test
    fun fullProductBufferBackpressuresInsteadOfDroppingOrGrowingWithoutBound() = runBlocking {
        val output = Gs1ProductGattOutput(bufferCapacity = 1)
        val gate = Gs1GattDurableCommitGate(output)
        val firstBatch = listOf(publication(46, "event-46"))
        val secondBatch = listOf(publication(47, "event-47"))

        val firstDispatch = async {
            gate.dispatch(committed(publications = firstBatch)) { SessionAction.None }
        }
        val blockedSecond = async {
            gate.dispatch(committed(publications = secondBatch)) { SessionAction.None }
        }
        delay(25)

        assertFalse(blockedSecond.isCompleted)
        val deliveredFirst = output.committedPublicationBatches.first()
        assertEquals(firstBatch, deliveredFirst.publications)
        deliveredFirst.acknowledgeDurablyApplied()
        assertTrue(firstDispatch.await() is Gs1GattDurableCommitResult.Accepted)

        val deliveredSecond = output.committedPublicationBatches.first()
        assertEquals(secondBatch, deliveredSecond.publications)
        deliveredSecond.acknowledgeDurablyApplied()
        assertTrue(blockedSecond.await() is Gs1GattDurableCommitResult.Accepted)
    }

    private fun committed(
        diagnostics: List<Gs1DiagnosticReading> = emptyList(),
        publications: List<Gs1ProductPublication>,
    ) = Gs1DiagnosticRuntimeEvent.Committed(
        generation = 1,
        ingress = ingress(),
        samples = listOf(sample(index = 46)),
        diagnostics = diagnostics,
        publications = publications,
        validatedTransportEnvelope = true,
    )

    private fun ingress(): SensorPacketIngressRecord {
        val packet = byteArrayOf(1)
        return SensorPacketIngressRecord(
            ingressId = "gatt-test:0",
            sensorId = SENSOR_ID,
            sensorFamily = SensorFamily.SIBIONICS_GS1SB,
            bluetoothAddress = "AA:BB:CC:DD:EE:FF",
            attemptId = "gatt-test",
            ordinal = 0,
            receivedAtEpochMs = 1_700_000_001_000L + 46L * 60_000L,
            encryptedPacket = packet,
            packetSha256 = MessageDigest.getInstance("SHA-256")
                .digest(packet)
                .joinToString("") { byte -> "%02x".format(byte) },
        )
    }

    private fun publication(sequence: Long, eventId: String) = Gs1ProductPublication(
        reading = GlucoseReading(
            eventId = eventId,
            sensorId = SENSOR_ID,
            sensorFamily = SensorFamily.SIBIONICS_GS1SB,
            sensorTimeEpochMs = 1_700_000_000_000L + sequence * 60_000L,
            phoneTimeEpochMs = 1_700_000_001_000L + sequence * 60_000L,
            glucoseMgDl = 104,
            trendMgDlPerMinute = -1.3,
            quality = ReadingQuality.VALID,
            sequence = sequence,
        ),
        approvalId = "ab".repeat(32),
        publicationBindingId = "cd".repeat(32),
    )

    private fun sample(index: Int) = DecodedGs1RawSample(
        index = index,
        sensorTimeEpochSeconds = 1_700_000_000L + index * 60L,
        current = 50,
        temperature = 100,
        reindex = 0,
    )

    private companion object {
        const val SENSOR_ID = "SENSOR-001"
    }
}
