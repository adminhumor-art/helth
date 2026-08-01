package com.sladkaya.sensor.sibionics

import com.sladkaya.core.data.AtomicSensorCoreRecord
import com.sladkaya.core.data.SensorAlgorithmCheckpointRecord
import com.sladkaya.core.data.SensorCoreCommitResult
import com.sladkaya.core.data.SensorCoreStore
import com.sladkaya.core.data.SensorFailureCommitResult
import com.sladkaya.core.data.SensorIngestionFailureRecord
import com.sladkaya.core.model.ReadingQuality
import com.sladkaya.core.model.SensorFamily
import com.sladkaya.sensor.sibionics.algorithm.AlgorithmCheckpoint
import com.sladkaya.sensor.sibionics.algorithm.AlgorithmInitializationMode
import com.sladkaya.sensor.sibionics.algorithm.AlgorithmOpenResult
import com.sladkaya.sensor.sibionics.algorithm.AlgorithmProfile
import com.sladkaya.sensor.sibionics.algorithm.DecodedSensitivity
import com.sladkaya.sensor.sibionics.algorithm.NativeAlgorithmApi
import com.sladkaya.sensor.sibionics.algorithm.NativeAlgorithmContext
import com.sladkaya.sensor.sibionics.algorithm.NativeAlgorithmSnapshot
import com.sladkaya.sensor.sibionics.algorithm.SensitivityToken
import com.sladkaya.sensor.sibionics.algorithm.SensitivityEncoding
import com.sladkaya.sensor.sibionics.algorithm.SibionicsAlgorithmSession
import kotlinx.coroutines.runBlocking
import java.util.concurrent.CancellationException
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.fail
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class Gs1ProcessingCoordinatorTest {
    @Test
    fun commitsRawDiagnosticResultAndCheckpointBeforeAdvancingSession() = runBlocking {
        val native = FakeNative().apply {
            results += NativeAlgorithmSnapshot(6.0, trend = -2, glucoseWarning = 1)
            results += NativeAlgorithmSnapshot(6.1, trend = -1)
        }
        val store = FakeStore()
        val coordinator = coordinator(native, store, firstIndex = 65)
        val packet = byteArrayOf(4, 8, 15, 16, 23, 42)

        val first = coordinator.process(packet, sample(index = 65, current = 50, reindex = 0))
        val second = coordinator.process(packet, sample(index = 66, current = 51, reindex = 0))

        assertTrue(first is Gs1ProcessingResult.Diagnostic)
        assertTrue(second is Gs1ProcessingResult.Diagnostic)
        assertEquals(listOf(65, 66), native.processedIndices)
        val saved = store.records.first()
        assertArrayEquals(packet, saved.raw.packetCopy())
        assertEquals(50, saved.raw.currentRaw)
        assertEquals(321, saved.raw.temperatureRaw)
        assertEquals(6.0, saved.result.nativeGlucoseMmolL, 0.0001)
        assertEquals(6.0, saved.result.displayedGlucoseMmolL, 0.0001)
        assertEquals(1, saved.result.glucoseWarning)
        assertFalse(saved.result.publishable)
        assertFalse(saved.result.alarmEligible)
        assertNull(saved.measurement)
        assertEquals(65, saved.checkpoint.sequence)
        assertArrayEquals(native.exportedState, saved.checkpoint.stateCopy())
    }

    @Test
    fun warmupAndHistoryRemainExplicitDiagnosticQualities() = runBlocking {
        val native = FakeNative().apply {
            results += NativeAlgorithmSnapshot(5.5, trend = 0)
            results += NativeAlgorithmSnapshot(5.6, trend = 0)
        }
        val store = FakeStore()
        val coordinator = coordinator(native, store, firstIndex = 60)

        val warmup = coordinator.process(byteArrayOf(1), sample(index = 60, reindex = 0))
        val history = coordinator.process(byteArrayOf(2), sample(index = 61, reindex = 3))

        assertEquals(
            ReadingQuality.WARMING_UP,
            (warmup as Gs1ProcessingResult.Diagnostic).candidate.quality,
        )
        assertEquals(
            ReadingQuality.DEGRADED,
            (history as Gs1ProcessingResult.Diagnostic).candidate.quality,
        )
        assertEquals(listOf(false, false), store.records.map { it.result.alarmEligible })
    }

    @Test
    fun uncertainDatabaseOutcomeCanBeRetriedWithoutCallingNativeTwice() = runBlocking {
        val native = FakeNative().apply { results += NativeAlgorithmSnapshot(6.0, trend = 1) }
        val store = FakeStore(failAfterFirstSave = true)
        val coordinator = coordinator(native, store, firstIndex = 61)

        val firstAttempt = coordinator.process(byteArrayOf(7), sample(index = 61))
        val retry = coordinator.retryPendingCommit()

        assertTrue(firstAttempt is Gs1ProcessingResult.PersistenceUnavailable)
        assertTrue(retry is Gs1ProcessingResult.Diagnostic)
        assertEquals(listOf(61), native.processedIndices)
        assertEquals(2, store.commitAttempts)
        assertEquals(1, store.records.size)
    }

    @Test
    fun storageConflictClosesTheMutatedNativeSession() = runBlocking {
        val native = FakeNative().apply { results += NativeAlgorithmSnapshot(6.0, trend = 1) }
        val coordinator = coordinator(
            native,
            FakeStore(result = SensorCoreCommitResult.Conflict("different state")),
            firstIndex = 61,
        )

        val conflict = coordinator.process(byteArrayOf(7), sample(index = 61))
        val repeated = coordinator.process(byteArrayOf(8), sample(index = 62))

        assertTrue(conflict is Gs1ProcessingResult.StorageConflict)
        assertTrue(repeated is Gs1ProcessingResult.Closed)
        assertEquals(1, native.releaseCount)
        assertEquals(listOf(61), native.processedIndices)
    }

    @Test
    fun invalidGlucosePersistsDiagnosticStateWithoutPublishingMeasurement() = runBlocking {
        val native = FakeNative().apply {
            results += NativeAlgorithmSnapshot(40.0, trend = -3, glucoseWarning = 9)
            results += NativeAlgorithmSnapshot(6.0, trend = 0)
        }
        val store = FakeStore()
        val coordinator = coordinator(native, store, firstIndex = 61)

        val rejected = coordinator.process(byteArrayOf(7), sample(index = 61, current = 500))
        val next = coordinator.process(byteArrayOf(8), sample(index = 62, current = 50))

        assertTrue(rejected is Gs1ProcessingResult.Rejected)
        assertEquals("INVALID_GLUCOSE", (rejected as Gs1ProcessingResult.Rejected).code)
        assertTrue(rejected.checkpointCommitted)
        assertTrue(next is Gs1ProcessingResult.Diagnostic)
        val diagnostic = store.records.first()
        assertFalse(diagnostic.result.publishable)
        assertFalse(diagnostic.result.alarmEligible)
        assertEquals("INVALID_GLUCOSE", diagnostic.result.algorithmErrorCode)
        assertEquals(40.0, diagnostic.result.nativeGlucoseMmolL, 0.0001)
        assertEquals(50.0, diagnostic.result.displayedGlucoseMmolL, 0.0001)
        assertNull(diagnostic.measurement)
        assertEquals(listOf(61, 62), native.processedIndices)
    }

    @Test
    fun diagnosticModeCommitsStateButCannotCreateProductMeasurementOrAlarmEvent() = runBlocking {
        val native = FakeNative().apply {
            results += NativeAlgorithmSnapshot(6.0, trend = -2, glucoseWarning = 1)
        }
        val store = FakeStore()
        val coordinator = coordinator(native = native, store = store, firstIndex = 65)

        val result = coordinator.process(
            byteArrayOf(4, 8, 15, 16, 23, 42),
            sample(index = 65, current = 50, reindex = 0),
        )

        val diagnostic = result as Gs1ProcessingResult.Diagnostic
        assertEquals(108, diagnostic.candidate.glucoseMgDl)
        assertEquals(ReadingQuality.VALID, diagnostic.candidate.quality)
        val saved = store.records.single()
        assertFalse(saved.result.publishable)
        assertFalse(saved.result.alarmEligible)
        assertNull(saved.measurement)
        assertEquals(65, saved.checkpoint.sequence)
    }

    @Test
    fun freshContextRefusesLateIndexUntilHistoryIsReplayed() = runBlocking {
        val native = FakeNative().apply { results += NativeAlgorithmSnapshot(6.0, trend = 0) }
        val store = FakeStore()
        val coordinator = coordinator(native, store, firstIndex = 1)

        val result = coordinator.process(byteArrayOf(1), sample(index = 65))

        assertEquals(
            "INITIAL_HISTORY_REQUIRED",
            (result as Gs1ProcessingResult.Rejected).code,
        )
        assertTrue(native.processedIndices.isEmpty())
        assertTrue(store.records.isEmpty())
        assertEquals("INITIAL_HISTORY_REQUIRED", store.failures.single().failureCode)
        assertFalse(store.failures.single().nativeStateMayHaveChanged)
    }

    @Test
    fun diagnosticQualityUsesStrict330SecondFreshnessBoundary() = runBlocking {
        val current = sample(index = 65, reindex = 0)
        val currentTimeMs = current.sensorTimeEpochSeconds * 1_000L

        val freshNative = FakeNative().apply { results += NativeAlgorithmSnapshot(6.0, trend = 0) }
        val freshStore = FakeStore()
        val fresh = coordinator(
            freshNative,
            freshStore,
            firstIndex = 65,
            phoneClock = { currentTimeMs + 329_999L },
        ).process(byteArrayOf(1), current) as Gs1ProcessingResult.Diagnostic

        val staleNative = FakeNative().apply { results += NativeAlgorithmSnapshot(6.0, trend = 0) }
        val staleStore = FakeStore()
        val stale = coordinator(
            staleNative,
            staleStore,
            firstIndex = 65,
            phoneClock = { currentTimeMs + 330_000L },
        ).process(byteArrayOf(2), current) as Gs1ProcessingResult.Diagnostic

        assertEquals(ReadingQuality.VALID, fresh.candidate.quality)
        assertEquals(ReadingQuality.DEGRADED, stale.candidate.quality)
        assertFalse(staleStore.records.single().result.alarmEligible)
    }

    @Test
    fun futureTimestampIsStoredAsDegradedDiagnostic() = runBlocking {
        val current = sample(index = 65, reindex = 0)
        val native = FakeNative().apply { results += NativeAlgorithmSnapshot(6.0, trend = 0) }
        val store = FakeStore()
        val coordinator = coordinator(
            native,
            store,
            firstIndex = 65,
            phoneClock = { current.sensorTimeEpochSeconds * 1_000L - 1L },
        )

        val result = coordinator.process(byteArrayOf(1), current) as Gs1ProcessingResult.Diagnostic

        assertEquals(ReadingQuality.DEGRADED, result.candidate.quality)
        assertFalse(store.records.single().result.alarmEligible)
    }

    @Test
    fun invalidPacketEnvelopeIsRejectedBeforeNativeStateCanChange() = runBlocking {
        val native = FakeNative().apply { results += NativeAlgorithmSnapshot(6.0, trend = 0) }
        val store = FakeStore()
        val coordinator = coordinator(native, store, firstIndex = 1)

        val result = coordinator.process(byteArrayOf(), sample(index = 1))

        assertEquals("INVALID_PACKET", (result as Gs1ProcessingResult.Rejected).code)
        assertFalse(result.checkpointCommitted)
        assertTrue(native.processedIndices.isEmpty())
        assertTrue(store.records.isEmpty())
        val failure = store.failures.single()
        assertEquals("INVALID_PACKET", failure.failureCode)
        assertArrayEquals(byteArrayOf(), failure.packetCopy())
        assertFalse(failure.nativeStateMayHaveChanged)
    }

    @Test
    fun repeatedSameFailureAtAnotherPhoneTimeDoesNotGrowJournal() = runBlocking {
        var phoneTime = sample(index = 1).sensorTimeEpochSeconds * 1_000L + 1_000L
        val native = FakeNative()
        val store = FakeStore()
        val coordinator = coordinator(
            native = native,
            store = store,
            firstIndex = 1,
            phoneClock = { phoneTime },
        )

        val first = coordinator.process(byteArrayOf(), sample(index = 1))
        phoneTime += 5_000L
        val repeated = coordinator.process(byteArrayOf(), sample(index = 1))

        assertEquals("INVALID_PACKET", (first as Gs1ProcessingResult.Rejected).code)
        assertEquals("INVALID_PACKET", (repeated as Gs1ProcessingResult.Rejected).code)
        assertEquals(1, store.failures.size)
        assertEquals(2, store.failureAttempts)
    }

    @Test
    fun nonFiniteNativeOutputIsJournaledAsPossiblyMutatedAndClosesSession() = runBlocking {
        val native = FakeNative().apply {
            results += NativeAlgorithmSnapshot(Double.NaN, trend = 0)
        }
        val store = FakeStore()
        val coordinator = coordinator(native, store, firstIndex = 1)

        val failed = coordinator.process(byteArrayOf(7), sample(index = 1))
        val repeated = coordinator.process(byteArrayOf(8), sample(index = 2))

        assertEquals("NON_FINITE_NATIVE_OUTPUT", (failed as Gs1ProcessingResult.Rejected).code)
        assertTrue(repeated is Gs1ProcessingResult.Closed)
        assertTrue(store.failures.single().nativeStateMayHaveChanged)
        assertEquals(1, native.releaseCount)
        assertTrue(store.records.isEmpty())
    }

    @Test
    fun uncertainFailureJournalWriteIsRetriedWithoutEnteringNative() = runBlocking {
        val native = FakeNative().apply { results += NativeAlgorithmSnapshot(6.0, trend = 0) }
        val store = FakeStore(failAfterFirstFailureSave = true)
        val coordinator = coordinator(native, store, firstIndex = 1)

        val uncertain = coordinator.process(byteArrayOf(), sample(index = 1))
        val blocked = coordinator.process(byteArrayOf(7), sample(index = 1))
        val retry = coordinator.retryPendingCommit()

        assertTrue(uncertain is Gs1ProcessingResult.PersistenceUnavailable)
        assertTrue(blocked is Gs1ProcessingResult.PersistenceUnavailable)
        assertEquals("INVALID_PACKET", (retry as Gs1ProcessingResult.Rejected).code)
        assertEquals(2, store.failureAttempts)
        assertEquals(1, store.failures.size)
        assertTrue(native.processedIndices.isEmpty())
    }

    @Test
    fun cancellationWhileJournalingKeepsExactEvidenceRetryable() = runBlocking {
        val native = FakeNative()
        val store = FakeStore(cancelAfterFirstFailureSave = true)
        val coordinator = coordinator(native, store, firstIndex = 1)

        try {
            coordinator.process(byteArrayOf(), sample(index = 1))
            fail("CancellationException expected")
        } catch (_: CancellationException) {
            // Cancellation remains cancellation; the pending record is retained.
        }

        val retry = coordinator.retryPendingCommit()

        assertEquals("INVALID_PACKET", (retry as Gs1ProcessingResult.Rejected).code)
        assertEquals(2, store.failureAttempts)
        assertEquals(1, store.failures.size)
        assertTrue(native.processedIndices.isEmpty())
    }

    @Test
    fun failureJournalConflictClosesCoordinatorBeforeNativeWork() = runBlocking {
        val native = FakeNative().apply { results += NativeAlgorithmSnapshot(6.0, trend = 0) }
        val store = FakeStore(
            failureJournalResult = SensorFailureCommitResult.Conflict("different evidence"),
        )
        val coordinator = coordinator(native, store, firstIndex = 1)

        val conflict = coordinator.process(byteArrayOf(), sample(index = 1))
        val repeated = coordinator.process(byteArrayOf(7), sample(index = 1))

        assertTrue(conflict is Gs1ProcessingResult.StorageConflict)
        assertTrue(repeated is Gs1ProcessingResult.Closed)
        assertEquals(1, native.releaseCount)
        assertTrue(native.processedIndices.isEmpty())
    }

    @Test
    fun cancellationAfterCoreWriteRetainsPendingCheckpointWithoutSecondNativeCall() = runBlocking {
        val native = FakeNative().apply { results += NativeAlgorithmSnapshot(6.0, trend = 0) }
        val store = FakeStore(cancelAfterFirstCoreSave = true)
        val coordinator = coordinator(native, store, firstIndex = 1)

        try {
            coordinator.process(byteArrayOf(7), sample(index = 1))
            fail("CancellationException expected")
        } catch (_: CancellationException) {
            // The pending checkpoint must survive cancellation.
        }

        val retry = coordinator.retryPendingCommit()

        assertTrue(retry is Gs1ProcessingResult.Diagnostic)
        assertEquals(listOf(1), native.processedIndices)
        assertEquals(2, store.commitAttempts)
        assertEquals(1, store.records.size)
    }

    private fun coordinator(
        native: FakeNative,
        store: FakeStore,
        firstIndex: Int,
        phoneClock: () -> Long = { sample(65).sensorTimeEpochSeconds * 1_000L + 100_000L },
    ): Gs1ProcessingCoordinator {
        val first = sample(firstIndex)
        val checkpoint = if (firstIndex == 1) {
            null
        } else {
            AlgorithmCheckpoint(
                profile = AlgorithmProfile.V116A,
                binarySetId = native.binarySetId,
                sensitivityToken = SensitivityToken.packageCode("ABCDEFGH"),
                initializationMode = AlgorithmInitializationMode.STANDARD,
                lastProcessedIndex = firstIndex - 1,
                lastSensorTimeEpochSeconds = first.sensorTimeEpochSeconds - 60L,
                nativeState = native.exportedState.copyOf(),
                nativeStateSha256 = native.exportedState.sha256(),
                displayOffsetMmolL = 0.0,
                schemaVersion = SibionicsAlgorithmSession.CHECKPOINT_SCHEMA_VERSION,
                algorithmVersion = native.algorithmVersion,
            )
        }
        val opened = SibionicsAlgorithmSession.open(
            profile = AlgorithmProfile.V116A,
            sensitivityToken = SensitivityToken.packageCode("ABCDEFGH"),
            initializationMode = AlgorithmInitializationMode.STANDARD,
            checkpoint = checkpoint,
            native = native,
        ) as AlgorithmOpenResult.Success
        return Gs1ProcessingCoordinator(
            sensorId = "sensor-a",
            bluetoothAddress = "AA:BB:CC:DD:EE:FF",
            family = SensorFamily.SIBIONICS_GS1,
            transportVariant = 0,
            algorithm = opened.session,
            sensitivity = DecodedSensitivity(
                token = SensitivityToken.packageCode("ABCDEFGH"),
                coefficient = 1.42f,
                encoding = SensitivityEncoding.NORMAL,
            ),
            store = store,
            phoneClock = phoneClock,
        )
    }

    private fun sample(
        index: Int,
        current: Int = 50,
        reindex: Int = 0,
    ) = DecodedGs1RawSample(
        index = index,
        sensorTimeEpochSeconds = 1_700_000_000L + index * 60L,
        current = current,
        temperature = 321,
        reindex = reindex,
    )

    private fun ByteArray.sha256(): String = java.security.MessageDigest.getInstance("SHA-256")
        .digest(this)
        .joinToString("") { "%02x".format(it.toInt() and 0xff) }
}

private class FakeStore(
    private val result: SensorCoreCommitResult = SensorCoreCommitResult.Committed,
    private val failAfterFirstSave: Boolean = false,
    private val cancelAfterFirstCoreSave: Boolean = false,
    private val failureJournalResult: SensorFailureCommitResult = SensorFailureCommitResult.Committed,
    private val failAfterFirstFailureSave: Boolean = false,
    private val cancelAfterFirstFailureSave: Boolean = false,
) : SensorCoreStore {
    val records = mutableListOf<AtomicSensorCoreRecord>()
    val failures = mutableListOf<SensorIngestionFailureRecord>()
    var commitAttempts = 0
    var failureAttempts = 0

    override suspend fun commit(record: AtomicSensorCoreRecord): SensorCoreCommitResult {
        commitAttempts += 1
        val existing = records.firstOrNull { it.raw.eventId == record.raw.eventId }
        if (existing == null) records += record
        if (cancelAfterFirstCoreSave && commitAttempts == 1) throw CancellationException("cancelled")
        if (failAfterFirstSave && commitAttempts == 1) error("database response was lost")
        return if (existing == null) result else SensorCoreCommitResult.AlreadyCommitted
    }

    override suspend fun checkpoint(sensorId: String): SensorAlgorithmCheckpointRecord? = null

    override suspend fun checkpointByBluetoothAddress(
        bluetoothAddress: String,
    ): SensorAlgorithmCheckpointRecord? = null

    override suspend fun recordFailure(
        record: SensorIngestionFailureRecord,
    ): SensorFailureCommitResult {
        failureAttempts += 1
        if (failureJournalResult is SensorFailureCommitResult.Conflict) return failureJournalResult
        val existing = failures.firstOrNull { it.failureId == record.failureId }
        if (existing == null) failures += record
        if (cancelAfterFirstFailureSave && failureAttempts == 1) throw CancellationException("cancelled")
        if (failAfterFirstFailureSave && failureAttempts == 1) error("database response was lost")
        return if (existing == null) {
            failureJournalResult
        } else {
            SensorFailureCommitResult.AlreadyCommitted
        }
    }
}

private class FakeNative : NativeAlgorithmApi {
    override val profile = AlgorithmProfile.V116A
    override val binarySetId = "v116a-test"
    override val algorithmVersion = "1.1.6A-test"
    val results = ArrayDeque<NativeAlgorithmSnapshot>()
    val processedIndices = mutableListOf<Int>()
    val exportedState = ByteArray(profile.stateSize) { (it * 13).toByte() }
    var releaseCount = 0

    override fun createContext(): NativeAlgorithmContext = FakeContext

    override fun initialize(
        context: NativeAlgorithmContext,
        sensitivityToken: String,
        mode: AlgorithmInitializationMode,
    ): Int = 1

    override fun restoreState(context: NativeAlgorithmContext, state: ByteArray): Int = 1

    override fun process(
        context: NativeAlgorithmContext,
        input: com.sladkaya.sensor.sibionics.algorithm.AlgorithmInput,
    ): NativeAlgorithmSnapshot {
        processedIndices += input.index
        return results.removeFirst()
    }

    override fun exportState(context: NativeAlgorithmContext): ByteArray = exportedState.copyOf()

    override fun release(context: NativeAlgorithmContext): Int {
        releaseCount += 1
        return 1
    }
}

private data object FakeContext : NativeAlgorithmContext
