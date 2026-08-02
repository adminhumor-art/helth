package com.sladkaya.sensor.sibionics.algorithm

import java.util.concurrent.CancellationException
import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Assert.assertTrue
import org.junit.Test

class SibionicsAlgorithmSessionTest {
    @Test
    fun v116aRejectsTruncatedStateBeforeAnyNativeCall() {
        val native = RecordingNativeAlgorithmApi(AlgorithmProfile.V116A)

        val result = SibionicsAlgorithmSession.open(
            profile = AlgorithmProfile.V116A,
            sensitivityToken = SensitivityToken.packageCode("ABCDEFGH"),
            initializationMode = AlgorithmInitializationMode.STANDARD,
            checkpoint = checkpoint(
                profile = AlgorithmProfile.V116A,
                state = ByteArray(AlgorithmProfile.V116A.stateSize - 1),
            ),
            native = native,
        )

        assertEquals(
            AlgorithmErrorCode.STATE_SIZE_MISMATCH,
            (result as AlgorithmOpenResult.Failure).error.code,
        )
        assertTrue(native.calls.isEmpty())
    }

    @Test
    fun rejectsStateWithWrongHashBeforeAnyNativeCall() {
        val native = RecordingNativeAlgorithmApi(AlgorithmProfile.V116A)
        val state = ByteArray(AlgorithmProfile.V116A.stateSize) { it.toByte() }
        val saved = checkpoint(AlgorithmProfile.V116A, state).copy(nativeStateSha256 = "0".repeat(64))

        val result = SibionicsAlgorithmSession.open(
            profile = AlgorithmProfile.V116A,
            sensitivityToken = saved.sensitivityToken,
            initializationMode = saved.initializationMode,
            checkpoint = saved,
            native = native,
        )

        assertEquals(
            AlgorithmErrorCode.STATE_HASH_MISMATCH,
            (result as AlgorithmOpenResult.Failure).error.code,
        )
        assertTrue(native.calls.isEmpty())
    }

    @Test
    fun rejectsUnsupportedCheckpointSchemaBeforeAnyNativeCall() {
        val native = RecordingNativeAlgorithmApi(AlgorithmProfile.V116A)
        val saved = checkpoint(
            profile = AlgorithmProfile.V116A,
            state = ByteArray(AlgorithmProfile.V116A.stateSize),
        ).copy(schemaVersion = SibionicsAlgorithmSession.CHECKPOINT_SCHEMA_VERSION + 1)

        val result = SibionicsAlgorithmSession.open(
            profile = AlgorithmProfile.V116A,
            sensitivityToken = saved.sensitivityToken,
            initializationMode = saved.initializationMode,
            checkpoint = saved,
            native = native,
        )

        assertEquals(
            AlgorithmErrorCode.NATIVE_STATE_FAILED,
            (result as AlgorithmOpenResult.Failure).error.code,
        )
        assertTrue(native.calls.isEmpty())
    }

    @Test
    fun validCheckpointIsInitializedThenRestored() {
        val native = RecordingNativeAlgorithmApi(AlgorithmProfile.V116A)
        val saved = checkpoint(
            profile = AlgorithmProfile.V116A,
            state = ByteArray(AlgorithmProfile.V116A.stateSize) { (it * 31).toByte() },
        )

        val result = SibionicsAlgorithmSession.open(
            profile = AlgorithmProfile.V116A,
            sensitivityToken = saved.sensitivityToken,
            initializationMode = saved.initializationMode,
            checkpoint = saved,
            native = native,
        )

        assertTrue(result is AlgorithmOpenResult.Success)
        assertEquals(
            listOf("create", "init:STANDARD:ABCDEFGH", "restore:${saved.nativeState.size}"),
            native.calls,
        )
    }

    @Test
    fun factionInitializationIsRejectedWhenNativeProfileDoesNotDeclareIt() {
        val native = RecordingNativeAlgorithmApi(AlgorithmProfile.V116A)

        val result = SibionicsAlgorithmSession.open(
            profile = AlgorithmProfile.V116A,
            sensitivityToken = SensitivityToken.packageCode("ABCDEFGH"),
            initializationMode = AlgorithmInitializationMode.FACTION,
            checkpoint = null,
            native = native,
        )

        assertEquals(
            AlgorithmErrorCode.UNSUPPORTED_INITIALIZATION_MODE,
            (result as AlgorithmOpenResult.Failure).error.code,
        )
        assertTrue(native.calls.isEmpty())
    }

    @Test
    fun declaredFactionInitializationUsesExactModeAndSurvivesCheckpointRestore() {
        val native = RecordingNativeAlgorithmApi(
            profile = AlgorithmProfile.V116A,
            initializationModes = setOf(
                AlgorithmInitializationMode.STANDARD,
                AlgorithmInitializationMode.FACTION,
            ),
        )
        val saved = checkpoint(
            profile = AlgorithmProfile.V116A,
            state = ByteArray(AlgorithmProfile.V116A.stateSize) { (it * 13).toByte() },
            initializationMode = AlgorithmInitializationMode.FACTION,
        )

        val result = SibionicsAlgorithmSession.open(
            profile = AlgorithmProfile.V116A,
            sensitivityToken = saved.sensitivityToken,
            initializationMode = AlgorithmInitializationMode.FACTION,
            checkpoint = saved,
            native = native,
        )

        assertTrue(result is AlgorithmOpenResult.Success)
        assertEquals(
            listOf("create", "init:FACTION:ABCDEFGH", "restore:${saved.nativeState.size}"),
            native.calls,
        )
    }

    @Test
    fun blankOrUnknownNativeVersionFailsBeforeContextCreation() {
        listOf("", "   ", "unknown", "UNKNOWN").forEach { invalidVersion ->
            val native = RecordingNativeAlgorithmApi(
                profile = AlgorithmProfile.V116A,
                version = invalidVersion,
            )

            val result = SibionicsAlgorithmSession.open(
                profile = AlgorithmProfile.V116A,
                sensitivityToken = SensitivityToken.packageCode("ABCDEFGH"),
                initializationMode = AlgorithmInitializationMode.STANDARD,
                checkpoint = null,
                native = native,
            )

            assertEquals(
                invalidVersion,
                AlgorithmErrorCode.NATIVE_METADATA_FAILED,
                (result as AlgorithmOpenResult.Failure).error.code,
            )
            assertTrue(native.calls.isEmpty())
        }
    }

    @Test
    fun checkpointFromAnotherAlgorithmVersionFailsBeforeContextCreation() {
        val native = RecordingNativeAlgorithmApi(AlgorithmProfile.V116A)
        val saved = checkpoint(
            profile = AlgorithmProfile.V116A,
            state = ByteArray(AlgorithmProfile.V116A.stateSize),
        ).copy(algorithmVersion = "foreign-version")

        val result = SibionicsAlgorithmSession.open(
            profile = AlgorithmProfile.V116A,
            sensitivityToken = saved.sensitivityToken,
            initializationMode = saved.initializationMode,
            checkpoint = saved,
            native = native,
        )

        assertEquals(
            AlgorithmErrorCode.ALGORITHM_VERSION_MISMATCH,
            (result as AlgorithmOpenResult.Failure).error.code,
        )
        assertTrue(native.calls.isEmpty())
    }

    @Test
    fun freshContextCannotStartInTheMiddleWithoutHistoryOrCheckpoint() {
        val native = RecordingNativeAlgorithmApi(AlgorithmProfile.V116A)
        val session = openFresh(native)

        val result = session.process(input(index = 65, signal = 6.0))

        assertEquals(
            AlgorithmErrorCode.INITIAL_HISTORY_REQUIRED,
            (result as AlgorithmStepResult.Failure).error.code,
        )
        assertEquals(0, native.calls.count { it.startsWith("process:") })
    }

    @Test
    fun restoredContextRejectsWrongSensorTimeBeforeCallingNative() {
        val native = RecordingNativeAlgorithmApi(AlgorithmProfile.V116A)
        val saved = checkpoint(
            profile = AlgorithmProfile.V116A,
            state = native.exportedState,
            lastProcessedIndex = 64,
            lastSensorTimeEpochSeconds = 1_700_000_000L,
        )
        val opened = SibionicsAlgorithmSession.open(
            profile = AlgorithmProfile.V116A,
            sensitivityToken = saved.sensitivityToken,
            initializationMode = saved.initializationMode,
            checkpoint = saved,
            native = native,
        ) as AlgorithmOpenResult.Success
        native.calls.clear()

        val result = opened.session.process(
            input(index = 65, signal = 6.0).copy(sensorTimeEpochSeconds = 1_700_000_120L),
        )

        assertEquals(
            AlgorithmErrorCode.NON_SEQUENTIAL_SENSOR_TIME,
            (result as AlgorithmStepResult.Failure).error.code,
        )
        assertTrue(native.calls.isEmpty())
    }

    @Test
    fun restoredV115ContextAcceptsIndividuallyClampedNondecreasingTime() {
        val native = RecordingNativeAlgorithmApi(AlgorithmProfile.V115G)
        val saved = checkpoint(
            profile = AlgorithmProfile.V115G,
            state = native.exportedState,
            lastProcessedIndex = 1,
            lastSensorTimeEpochSeconds = 1_700_000_000L,
        )
        val opened = SibionicsAlgorithmSession.open(
            profile = AlgorithmProfile.V115G,
            sensitivityToken = saved.sensitivityToken,
            initializationMode = saved.initializationMode,
            checkpoint = saved,
            native = native,
        ) as AlgorithmOpenResult.Success
        native.calls.clear()

        val result = opened.session.process(
            input(index = 2, signal = 6.0).copy(
                sensorTimeEpochSeconds = saved.lastSensorTimeEpochSeconds,
            ),
        )

        assertTrue(result is AlgorithmStepResult.Success)
        assertEquals(listOf("process:2", "state"), native.calls)
    }

    @Test
    fun v116aAdvancesNativeEveryMinuteButUsesFiveMinuteAnchors() {
        val native = RecordingNativeAlgorithmApi(AlgorithmProfile.V116A).apply {
            results += NativeAlgorithmSnapshot(6.0, trend = 2)
            results += NativeAlgorithmSnapshot(9.0, trend = 3)
        }
        val session = openRestoredBefore(native, nextIndex = 10)

        val anchor = session.process(input(index = 10, signal = 5.0)) as AlgorithmStepResult.Success
        assertTrue(session.confirmPersisted(anchor.checkpoint) is AlgorithmCommitResult.Success)
        val betweenAnchors = session.process(input(index = 11, signal = 5.2)) as AlgorithmStepResult.Success

        assertEquals(6.0, anchor.output.glucoseMmolL, 0.0001)
        assertEquals(6.2, betweenAnchors.output.glucoseMmolL, 0.0001)
        assertEquals(3, betweenAnchors.output.trend)
        assertEquals(2, native.calls.count { it.startsWith("process:") })
        assertEquals(2, native.calls.count { it == "state" })
        assertEquals(1.0, betweenAnchors.checkpoint.displayOffsetMmolL, 0.0001)
    }

    @Test
    fun v115gUsesEveryValidNativeResultAsAnAnchor() {
        val native = RecordingNativeAlgorithmApi(AlgorithmProfile.V115G).apply {
            results += NativeAlgorithmSnapshot(6.0, trend = 1)
            results += NativeAlgorithmSnapshot(6.7, trend = 2)
        }
        val session = openFresh(native)

        val first = session.process(input(index = 1, signal = 5.0)) as AlgorithmStepResult.Success
        assertTrue(session.confirmPersisted(first.checkpoint) is AlgorithmCommitResult.Success)
        val second = session.process(input(index = 2, signal = 5.2)) as AlgorithmStepResult.Success

        assertEquals(6.7, second.output.glucoseMmolL, 0.0001)
        assertEquals(1.5, second.checkpoint.displayOffsetMmolL, 0.0001)
    }

    @Test
    fun nonSequentialIndexIsRejectedWithoutCallingNative() {
        val native = RecordingNativeAlgorithmApi(AlgorithmProfile.V116A).apply {
            results += NativeAlgorithmSnapshot(6.0, trend = 1)
        }
        val session = openFresh(native)
        val first = session.process(input(index = 1, signal = 5.0)) as AlgorithmStepResult.Success
        assertTrue(session.confirmPersisted(first.checkpoint) is AlgorithmCommitResult.Success)
        val callsBeforeGap = native.calls.toList()

        val result = session.process(input(index = 3, signal = 5.2))

        assertEquals(
            AlgorithmErrorCode.NON_SEQUENTIAL_INDEX,
            (result as AlgorithmStepResult.Failure).error.code,
        )
        assertEquals(callsBeforeGap, native.calls)
    }

    @Test
    fun closeReleasesNativeContextOnlyOnce() {
        val native = RecordingNativeAlgorithmApi(AlgorithmProfile.V116A)
        val session = openFresh(native)

        session.close()
        session.close()

        assertEquals(1, native.calls.count { it == "release" })
    }

    @Test
    fun nativeProcessExceptionInvalidatesSessionAndReleasesContextOnlyOnce() {
        val native = RecordingNativeAlgorithmApi(AlgorithmProfile.V116A).apply {
            processFailure = IllegalStateException("process failed")
            releaseFailure = IllegalStateException("release failed")
        }
        val session = openFresh(native)

        val failed = session.process(input(index = 1, signal = 5.0))
        val repeated = session.process(input(index = 1, signal = 5.0))
        session.close()

        assertEquals(
            AlgorithmErrorCode.NATIVE_PROCESS_FAILED,
            (failed as AlgorithmStepResult.Failure).error.code,
        )
        assertEquals(
            AlgorithmErrorCode.CLOSED,
            (repeated as AlgorithmStepResult.Failure).error.code,
        )
        assertEquals(1, native.calls.count { it.startsWith("process:") })
        assertEquals(1, native.calls.count { it == "release" })
    }

    @Test
    fun nativeStateExportExceptionInvalidatesSessionAndReleasesContextOnlyOnce() {
        val native = RecordingNativeAlgorithmApi(AlgorithmProfile.V116A).apply {
            exportFailure = IllegalStateException("state export failed")
        }
        val session = openFresh(native)

        val failed = session.process(input(index = 1, signal = 5.0))
        val repeated = session.process(input(index = 1, signal = 5.0))
        session.close()

        assertEquals(
            AlgorithmErrorCode.NATIVE_STATE_FAILED,
            (failed as AlgorithmStepResult.Failure).error.code,
        )
        assertEquals(
            AlgorithmErrorCode.CLOSED,
            (repeated as AlgorithmStepResult.Failure).error.code,
        )
        assertEquals(1, native.calls.count { it == "state" })
        assertEquals(1, native.calls.count { it == "release" })
    }

    @Test
    fun cancellationDuringNativeProcessClosesPossiblyMutatedContext() {
        val native = RecordingNativeAlgorithmApi(AlgorithmProfile.V116A).apply {
            processFailure = CancellationException("cancelled")
        }
        val session = openFresh(native)

        try {
            session.process(input(index = 1, signal = 5.0))
            fail("CancellationException expected")
        } catch (_: CancellationException) {
            Unit
        }

        val repeated = session.process(input(index = 1, signal = 5.0))
        assertEquals(AlgorithmErrorCode.CLOSED, (repeated as AlgorithmStepResult.Failure).error.code)
        assertEquals(1, native.calls.count { it == "release" })
    }

    @Test
    fun cancellationDuringStateExportClosesAlreadyMutatedContext() {
        val native = RecordingNativeAlgorithmApi(AlgorithmProfile.V116A).apply {
            exportFailure = CancellationException("cancelled")
        }
        val session = openFresh(native)

        try {
            session.process(input(index = 1, signal = 5.0))
            fail("CancellationException expected")
        } catch (_: CancellationException) {
            Unit
        }

        val repeated = session.process(input(index = 1, signal = 5.0))
        assertEquals(AlgorithmErrorCode.CLOSED, (repeated as AlgorithmStepResult.Failure).error.code)
        assertEquals(1, native.calls.count { it == "release" })
    }

    @Test
    fun invalidExportedStateInvalidatesSessionAndReleasesContextOnlyOnce() {
        val native = RecordingNativeAlgorithmApi(AlgorithmProfile.V116A).apply {
            exportedState = ByteArray(AlgorithmProfile.V116A.stateSize - 1)
        }
        val session = openFresh(native)

        val failed = session.process(input(index = 1, signal = 5.0))
        val repeated = session.process(input(index = 1, signal = 5.0))
        session.close()

        assertEquals(
            AlgorithmErrorCode.NATIVE_STATE_FAILED,
            (failed as AlgorithmStepResult.Failure).error.code,
        )
        assertEquals(
            AlgorithmErrorCode.CLOSED,
            (repeated as AlgorithmStepResult.Failure).error.code,
        )
        assertEquals(1, native.calls.count { it == "state" })
        assertEquals(1, native.calls.count { it == "release" })
    }

    @Test
    fun nextInputWaitsUntilReturnedCheckpointIsPersisted() {
        val native = RecordingNativeAlgorithmApi(AlgorithmProfile.V116A).apply {
            results += NativeAlgorithmSnapshot(6.0, trend = 1)
            results += NativeAlgorithmSnapshot(6.1, trend = 1)
        }
        val session = openFresh(native)
        val first = session.process(input(index = 1, signal = 5.0)) as AlgorithmStepResult.Success
        val callsAfterFirst = native.calls.toList()

        val blocked = session.process(input(index = 2, signal = 5.1))

        assertEquals(
            AlgorithmErrorCode.CHECKPOINT_COMMIT_REQUIRED,
            (blocked as AlgorithmStepResult.Failure).error.code,
        )
        assertEquals(callsAfterFirst, native.calls)
        assertTrue(session.confirmPersisted(first.checkpoint) is AlgorithmCommitResult.Success)
        assertTrue(session.process(input(index = 2, signal = 5.1)) is AlgorithmStepResult.Success)
    }

    @Test
    fun wrongCheckpointAcknowledgementClosesMutatedSession() {
        val native = RecordingNativeAlgorithmApi(AlgorithmProfile.V116A).apply {
            results += NativeAlgorithmSnapshot(6.0, trend = 1)
        }
        val session = openFresh(native)
        val first = session.process(input(index = 1, signal = 5.0)) as AlgorithmStepResult.Success

        val confirmation = session.confirmPersisted(
            first.checkpoint.copy(lastProcessedIndex = first.checkpoint.lastProcessedIndex + 1),
        )
        val repeated = session.process(input(index = 2, signal = 5.1))

        assertEquals(
            AlgorithmErrorCode.CHECKPOINT_COMMIT_MISMATCH,
            (confirmation as AlgorithmCommitResult.Failure).error.code,
        )
        assertEquals(
            AlgorithmErrorCode.CLOSED,
            (repeated as AlgorithmStepResult.Failure).error.code,
        )
        assertEquals(1, native.calls.count { it == "release" })
    }

    @Test
    fun outOfRangeAnchorIsRejectedInsteadOfBeingReplacedByPlausibleRawSignal() {
        val native = RecordingNativeAlgorithmApi(AlgorithmProfile.V116A).apply {
            results += NativeAlgorithmSnapshot(
                glucoseMmolL = 40.0,
                trend = -3,
                glucoseWarning = 7,
                currentWarning = 8,
                temperatureWarning = 9,
            )
        }
        val session = openRestoredBefore(native, nextIndex = 10)

        val failed = session.process(input(index = 10, signal = 6.0)) as AlgorithmStepResult.Failure

        assertEquals(AlgorithmErrorCode.INVALID_GLUCOSE, failed.error.code)
        assertEquals(10, failed.checkpoint?.lastProcessedIndex)
        assertEquals(40.0, failed.diagnosticOutput?.nativeGlucoseMmolL ?: 0.0, 0.0001)
        assertEquals(40.0, failed.diagnosticOutput?.glucoseMmolL ?: 0.0, 0.0001)
        assertEquals(34.0, failed.checkpoint?.displayOffsetMmolL ?: 0.0, 0.0001)
        assertEquals(-3, failed.diagnosticOutput?.trend)
        assertEquals(7, failed.diagnosticOutput?.warnings?.glucose)
        assertTrue(session.confirmPersisted(requireNotNull(failed.checkpoint)) is AlgorithmCommitResult.Success)
    }

    @Test
    fun v115gAlsoRejectsHighNativeCandidateWithoutRawFallback() {
        val native = RecordingNativeAlgorithmApi(AlgorithmProfile.V115G).apply {
            results += NativeAlgorithmSnapshot(glucoseMmolL = 30.0, trend = 1)
        }
        val session = openFresh(native)

        val failed = session.process(input(index = 1, signal = 6.0)) as AlgorithmStepResult.Failure

        assertEquals(AlgorithmErrorCode.INVALID_GLUCOSE, failed.error.code)
        assertEquals(30.0, failed.diagnosticOutput?.glucoseMmolL ?: 0.0, 0.0001)
        assertEquals(24.0, failed.checkpoint?.displayOffsetMmolL ?: 0.0, 0.0001)
    }

    @Test
    fun nonFiniteNativeOutputClosesSessionWithoutPublishingOrAdvancingCheckpoint() {
        val native = RecordingNativeAlgorithmApi(AlgorithmProfile.V116A).apply {
            results += NativeAlgorithmSnapshot(glucoseMmolL = Double.NaN, trend = 0)
        }
        val session = openFresh(native)

        val failed = session.process(input(index = 1, signal = 6.0)) as AlgorithmStepResult.Failure
        val repeated = session.process(input(index = 1, signal = 6.0)) as AlgorithmStepResult.Failure

        assertEquals(AlgorithmErrorCode.NON_FINITE_NATIVE_OUTPUT, failed.error.code)
        assertEquals(null, failed.checkpoint)
        assertEquals(null, failed.diagnosticOutput)
        assertEquals(AlgorithmErrorCode.CLOSED, repeated.error.code)
        assertEquals(1, native.calls.count { it == "release" })
    }

    private fun openFresh(native: RecordingNativeAlgorithmApi): SibionicsAlgorithmSession {
        val result = SibionicsAlgorithmSession.open(
            profile = native.profile,
            sensitivityToken = SensitivityToken.packageCode("ABCDEFGH"),
            initializationMode = AlgorithmInitializationMode.STANDARD,
            checkpoint = null,
            native = native,
        )
        return (result as AlgorithmOpenResult.Success).session
    }

    private fun openRestoredBefore(
        native: RecordingNativeAlgorithmApi,
        nextIndex: Int,
    ): SibionicsAlgorithmSession {
        val nextTime = input(nextIndex, signal = 1.0).sensorTimeEpochSeconds
        val saved = checkpoint(
            profile = native.profile,
            state = native.exportedState,
            lastProcessedIndex = nextIndex - 1,
            lastSensorTimeEpochSeconds = nextTime - 60L,
        )
        val result = SibionicsAlgorithmSession.open(
            profile = native.profile,
            sensitivityToken = saved.sensitivityToken,
            initializationMode = saved.initializationMode,
            checkpoint = saved,
            native = native,
        )
        return (result as AlgorithmOpenResult.Success).session
    }

    private fun input(index: Int, signal: Double) = AlgorithmInput(
        index = index,
        sensorTimeEpochSeconds = 1_700_000_000L + index * 60L,
        signal = signal,
        temperatureCelsius = 32.1,
        historyDistance = 0,
    )

    private fun checkpoint(
        profile: AlgorithmProfile,
        state: ByteArray,
        lastProcessedIndex: Int = 9,
        lastSensorTimeEpochSeconds: Long = 1_700_000_000L,
        initializationMode: AlgorithmInitializationMode = AlgorithmInitializationMode.STANDARD,
    ): AlgorithmCheckpoint = AlgorithmCheckpoint(
        profile = profile,
        binarySetId = "test-${profile.name}",
        sensitivityToken = SensitivityToken.packageCode("ABCDEFGH"),
        initializationMode = initializationMode,
        lastProcessedIndex = lastProcessedIndex,
        lastSensorTimeEpochSeconds = lastSensorTimeEpochSeconds,
        nativeState = state,
        nativeStateSha256 = sha256(state),
        displayOffsetMmolL = 0.0,
        schemaVersion = SibionicsAlgorithmSession.CHECKPOINT_SCHEMA_VERSION,
        algorithmVersion = "test-${profile.name}-1",
    )
}

private class TestNativeContext : NativeAlgorithmContext

private class RecordingNativeAlgorithmApi(
    override val profile: AlgorithmProfile,
    private val initializationModes: Set<AlgorithmInitializationMode> =
        setOf(AlgorithmInitializationMode.STANDARD),
    private val version: String = "test-${profile.name}-1",
) : NativeAlgorithmApi {
    override val binarySetId: String = "test-${profile.name}"
    override val supportedInitializationModes: Set<AlgorithmInitializationMode>
        get() = initializationModes
    override val algorithmVersion: String
        get() = version
    val calls = mutableListOf<String>()
    val results = ArrayDeque<NativeAlgorithmSnapshot>()
    var processFailure: Throwable? = null
    var exportFailure: Throwable? = null
    var releaseFailure: Throwable? = null
    var exportedState = ByteArray(profile.stateSize) { (it * 17).toByte() }

    override fun createContext(): NativeAlgorithmContext {
        calls += "create"
        return TestNativeContext()
    }

    override fun initialize(
        context: NativeAlgorithmContext,
        sensitivityToken: String,
        mode: AlgorithmInitializationMode,
    ): Int {
        calls += "init:${mode.name}:$sensitivityToken"
        return 1
    }

    override fun restoreState(context: NativeAlgorithmContext, state: ByteArray): Int {
        calls += "restore:${state.size}"
        return 1
    }

    override fun process(context: NativeAlgorithmContext, input: AlgorithmInput): NativeAlgorithmSnapshot {
        calls += "process:${input.index}"
        processFailure?.let { throw it }
        return results.removeFirstOrNull() ?: NativeAlgorithmSnapshot(input.signal, trend = 0)
    }

    override fun exportState(context: NativeAlgorithmContext): ByteArray {
        calls += "state"
        exportFailure?.let { throw it }
        return exportedState.copyOf()
    }

    override fun release(context: NativeAlgorithmContext): Int {
        calls += "release"
        releaseFailure?.let { throw it }
        return 1
    }
}
