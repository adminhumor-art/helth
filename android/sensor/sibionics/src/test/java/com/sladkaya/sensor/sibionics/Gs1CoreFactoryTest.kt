package com.sladkaya.sensor.sibionics

import com.sladkaya.core.data.AtomicSensorCoreRecord
import com.sladkaya.core.data.SensorAlgorithmCheckpointRecord
import com.sladkaya.core.data.SensorCoreCommitResult
import com.sladkaya.core.data.SensorCoreStore
import com.sladkaya.core.data.SensorFailureCommitResult
import com.sladkaya.core.data.SensorIngestionFailureRecord
import com.sladkaya.core.model.SensorFamily
import com.sladkaya.sensor.sibionics.algorithm.AlgorithmInitializationMode
import com.sladkaya.sensor.sibionics.algorithm.AlgorithmInput
import com.sladkaya.sensor.sibionics.algorithm.AlgorithmProfile
import com.sladkaya.sensor.sibionics.algorithm.DecodedSensitivity
import com.sladkaya.sensor.sibionics.algorithm.NativeAlgorithmApi
import com.sladkaya.sensor.sibionics.algorithm.NativeAlgorithmContext
import com.sladkaya.sensor.sibionics.algorithm.NativeAlgorithmSnapshot
import com.sladkaya.sensor.sibionics.algorithm.SensitivityDecodeResult
import com.sladkaya.sensor.sibionics.algorithm.SensitivityEncoding
import com.sladkaya.sensor.sibionics.algorithm.SensitivityToken
import com.sladkaya.sensor.sibionics.algorithm.SibionicsAlgorithmSession
import com.sladkaya.sensor.sibionics.datahandle.SibionicsDataHandle
import java.security.MessageDigest
import java.util.concurrent.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class Gs1CoreFactoryTest {
    @Test
    fun validPackageCodeIsDecodedAndPassedUnchangedToNormalInit() = runBlocking {
        val native = FactoryNative()
        var decodedToken: SensitivityToken? = null
        val factory = Gs1CoreFactory(
            store = FactoryStore(),
            decodeSensitivity = { token ->
                decodedToken = token
                SensitivityDecodeResult.Success(DecodedSensitivity(token, 1.42f, SensitivityEncoding.NORMAL))
            },
            nativeProvider = { native },
        )

        val result = factory.open(configuration(packageCode = "aB12cd34"))

        assertTrue(result is Gs1CoreOpenResult.Success)
        assertEquals(1, (result as Gs1CoreOpenResult.Success).nextSensorIndex)
        assertEquals("aB12cd34", decodedToken?.value)
        assertEquals(listOf("create", "init:STANDARD:aB12cd34"), native.calls)
    }

    @Test
    fun factoryCanOnlyProduceDiagnosticResultsWithoutMeasurementsOrAlarms() = runBlocking {
        val native = FactoryNative()
        val store = FactoryStore()
        val factory = Gs1CoreFactory(
            store = store,
            decodeSensitivity = { token ->
                SensitivityDecodeResult.Success(
                    DecodedSensitivity(token, 1.42f, SensitivityEncoding.NORMAL),
                )
            },
            nativeProvider = { native },
        )
        val opened = factory.open(configuration()) as Gs1CoreOpenResult.Success

        val result = opened.coordinator.process(
            encryptedPacket = byteArrayOf(1),
            sample = DecodedGs1RawSample(
                index = 1,
                sensorTimeEpochSeconds = 1_900_000_000L,
                current = 50,
                temperature = 321,
                reindex = 0,
            ),
        )

        assertTrue(result is Gs1ProcessingResult.Diagnostic)
        assertEquals(false, store.records.single().result.publishable)
        assertEquals(false, store.records.single().result.alarmEligible)
        assertEquals(null, store.records.single().measurement)
    }

    @Test
    fun malformedPackageCodeStopsBeforeStorageDecoderAndNative() = runBlocking {
        val store = FactoryStore()
        val native = FactoryNative()
        var decoderCalls = 0
        val factory = Gs1CoreFactory(
            store = store,
            decodeSensitivity = {
                decoderCalls += 1
                error("must not be called")
            },
            nativeProvider = { native },
        )

        val result = factory.open(configuration(packageCode = "bad"))

        assertEquals(
            Gs1CoreOpenError.INVALID_PACKAGE_CODE,
            (result as Gs1CoreOpenResult.Failure).error,
        )
        assertEquals(0, decoderCalls)
        assertEquals(0, store.checkpointCalls)
        assertTrue(native.calls.isEmpty())
    }

    @Test
    fun factionOnlyEncodingIsRecordedAsUnsupportedNotUsedAsInitBranch() = runBlocking {
        val native = FactoryNative()
        val factory = Gs1CoreFactory(
            store = FactoryStore(),
            decodeSensitivity = { token ->
                SensitivityDecodeResult.Success(DecodedSensitivity(token, 1.58f, SensitivityEncoding.FACTION))
            },
            nativeProvider = { native },
        )

        val result = factory.open(configuration())

        assertEquals(
            Gs1CoreOpenError.UNSUPPORTED_SENSITIVITY_ENCODING,
            (result as Gs1CoreOpenResult.Failure).error,
        )
        assertTrue(native.calls.isEmpty())
    }

    @Test
    fun exactStoredCheckpointIsRestoredAfterInit() = runBlocking {
        val native = FactoryNative()
        val token = SensitivityToken.packageCode("ABCDEFGH")
        val sensitivity = DecodedSensitivity(token, 1.42f, SensitivityEncoding.NORMAL)
        val store = FactoryStore(savedCheckpoint = checkpoint(native, sensitivity))
        val factory = Gs1CoreFactory(
            store = store,
            decodeSensitivity = { SensitivityDecodeResult.Success(sensitivity) },
            nativeProvider = { native },
        )

        val result = factory.open(configuration())

        assertTrue(result is Gs1CoreOpenResult.Success)
        assertEquals(41, (result as Gs1CoreOpenResult.Success).nextSensorIndex)
        assertEquals(listOf("create", "init:STANDARD:ABCDEFGH", "restore:2480"), native.calls)
    }

    @Test
    fun maximumCommittedIndexIsReportedAsSensorExhaustionNotCorruptMetadata() = runBlocking {
        val native = FactoryNative()
        val token = SensitivityToken.packageCode("ABCDEFGH")
        val sensitivity = DecodedSensitivity(token, 1.42f, SensitivityEncoding.NORMAL)
        val saved = checkpoint(native, sensitivity).copy(sequence = 0xffff)
        val factory = Gs1CoreFactory(
            store = FactoryStore(savedCheckpoint = saved),
            decodeSensitivity = { SensitivityDecodeResult.Success(sensitivity) },
            nativeProvider = { native },
        )

        val result = factory.open(configuration()) as Gs1CoreOpenResult.Failure

        assertEquals(Gs1CoreOpenError.SENSOR_SEQUENCE_EXHAUSTED, result.error)
        assertTrue(native.calls.isEmpty())
    }

    @Test
    fun changedCalibrationCannotRestoreOldNativeState() = runBlocking {
        val native = FactoryNative()
        val token = SensitivityToken.packageCode("ABCDEFGH")
        val saved = checkpoint(native, DecodedSensitivity(token, 1.42f, SensitivityEncoding.NORMAL))
        val factory = Gs1CoreFactory(
            store = FactoryStore(savedCheckpoint = saved),
            decodeSensitivity = {
                SensitivityDecodeResult.Success(DecodedSensitivity(token, 1.43f, SensitivityEncoding.NORMAL))
            },
            nativeProvider = { native },
        )

        val result = factory.open(configuration())

        assertEquals(
            Gs1CoreOpenError.CHECKPOINT_CALIBRATION_MISMATCH,
            (result as Gs1CoreOpenResult.Failure).error,
        )
        assertTrue(native.calls.isEmpty())
    }

    @Test
    fun checkpointFromAnotherTransportVariantCannotReachNative() = runBlocking {
        val native = FactoryNative()
        val token = SensitivityToken.packageCode("ABCDEFGH")
        val sensitivity = DecodedSensitivity(token, 1.42f, SensitivityEncoding.NORMAL)
        val saved = checkpoint(native, sensitivity).copy(transportVariant = 1)
        val factory = Gs1CoreFactory(
            store = FactoryStore(savedCheckpoint = saved),
            decodeSensitivity = { SensitivityDecodeResult.Success(sensitivity) },
            nativeProvider = { native },
        )

        val result = factory.open(configuration(transportVariant = 0))

        assertEquals(
            Gs1CoreOpenError.CHECKPOINT_CALIBRATION_MISMATCH,
            (result as Gs1CoreOpenResult.Failure).error,
        )
        assertTrue(native.calls.isEmpty())
    }

    @Test
    fun checkpointFromAnotherPhysicalBluetoothSensorCannotReachNative() = runBlocking {
        val native = FactoryNative()
        val token = SensitivityToken.packageCode("ABCDEFGH")
        val sensitivity = DecodedSensitivity(token, 1.42f, SensitivityEncoding.NORMAL)
        val saved = checkpoint(native, sensitivity).copy(bluetoothAddress = "AA:BB:CC:DD:EE:00")
        val factory = Gs1CoreFactory(
            store = FactoryStore(savedCheckpoint = saved),
            decodeSensitivity = { SensitivityDecodeResult.Success(sensitivity) },
            nativeProvider = { native },
        )

        val result = factory.open(configuration(bluetoothAddress = "AA:BB:CC:DD:EE:FF"))

        assertEquals(
            Gs1CoreOpenError.CHECKPOINT_PHYSICAL_IDENTITY_MISMATCH,
            (result as Gs1CoreOpenResult.Failure).error,
        )
        assertTrue(native.calls.isEmpty())
    }

    @Test
    fun physicalBluetoothSensorAlreadyBoundToAnotherLogicalIdCannotReachNative() = runBlocking {
        val native = FactoryNative()
        val token = SensitivityToken.packageCode("ABCDEFGH")
        val sensitivity = DecodedSensitivity(token, 1.42f, SensitivityEncoding.NORMAL)
        val physical = checkpoint(native, sensitivity).copy(sensorId = "different-logical-id")
        val factory = Gs1CoreFactory(
            store = FactoryStore(physicalCheckpoint = physical),
            decodeSensitivity = { SensitivityDecodeResult.Success(sensitivity) },
            nativeProvider = { native },
        )

        val result = factory.open(configuration()) as Gs1CoreOpenResult.Failure

        assertEquals(Gs1CoreOpenError.CHECKPOINT_PHYSICAL_IDENTITY_MISMATCH, result.error)
        assertTrue(native.calls.isEmpty())
    }

    @Test(expected = CancellationException::class)
    fun coroutineCancellationIsNeverConvertedIntoStorageFailure() {
        runBlocking {
            val store = object : SensorCoreStore {
                override suspend fun commit(record: AtomicSensorCoreRecord): SensorCoreCommitResult =
                    SensorCoreCommitResult.Committed

                override suspend fun checkpoint(sensorId: String): SensorAlgorithmCheckpointRecord? =
                    throw CancellationException("cancelled")

                override suspend fun checkpointByBluetoothAddress(
                    bluetoothAddress: String,
                ): SensorAlgorithmCheckpointRecord? = null

                override suspend fun recordFailure(
                    record: SensorIngestionFailureRecord,
                ): SensorFailureCommitResult = SensorFailureCommitResult.Committed
            }
            val factory = Gs1CoreFactory(
                store = store,
                decodeSensitivity = { token ->
                    SensitivityDecodeResult.Success(DecodedSensitivity(token, 1.42f, SensitivityEncoding.NORMAL))
                },
                nativeProvider = { FactoryNative() },
            )

            factory.open(configuration())
        }
    }

    @Test
    fun missingSensitivityLibraryReturnsControlledFailure() = runBlocking {
        val factory = Gs1CoreFactory(
            store = FactoryStore(),
            decodeSensitivity = { throw UnsatisfiedLinkError("missing sensitivity ABI") },
            nativeProvider = { FactoryNative() },
        )

        val result = factory.open(configuration())

        assertEquals(
            Gs1CoreOpenError.SENSITIVITY_DECODE_FAILED,
            (result as Gs1CoreOpenResult.Failure).error,
        )
    }

    @Test
    fun missingAlgorithmLibraryReturnsControlledFailure() = runBlocking {
        val factory = Gs1CoreFactory(
            store = FactoryStore(),
            decodeSensitivity = { token ->
                SensitivityDecodeResult.Success(DecodedSensitivity(token, 1.42f, SensitivityEncoding.NORMAL))
            },
            nativeProvider = { throw UnsatisfiedLinkError("missing algorithm ABI") },
        )

        val result = factory.open(configuration())

        assertEquals(
            Gs1CoreOpenError.NATIVE_LOAD_FAILED,
            (result as Gs1CoreOpenResult.Failure).error,
        )
    }

    @Test
    fun transportVariantWithoutVerifiedAlgorithmPairStopsBeforeNativeWork() = runBlocking {
        var decoderCalls = 0
        val factory = Gs1CoreFactory(
            store = FactoryStore(),
            decodeSensitivity = {
                decoderCalls += 1
                error("must not run")
            },
            nativeProvider = { FactoryNative() },
        )

        val result = factory.open(configuration(transportVariant = 1))

        assertEquals(
            Gs1CoreOpenError.UNSUPPORTED_TRANSPORT_VARIANT,
            (result as Gs1CoreOpenResult.Failure).error,
        )
        assertEquals(0, decoderCalls)
    }

    private fun configuration(
        packageCode: String = "ABCDEFGH",
        transportVariant: Int = 0,
        bluetoothAddress: String = "AA:BB:CC:DD:EE:FF",
    ) = Gs1CoreConfiguration(
        sensorId = "sensor-a",
        family = SensorFamily.SIBIONICS_GS1,
        bluetoothAddress = bluetoothAddress,
        transportVariant = transportVariant,
        packageCode = packageCode,
    )

    private fun checkpoint(
        native: FactoryNative,
        sensitivity: DecodedSensitivity,
    ): SensorAlgorithmCheckpointRecord {
        val state = native.state.copyOf()
        return SensorAlgorithmCheckpointRecord(
            sensorId = "sensor-a",
            bluetoothAddress = "AA:BB:CC:DD:EE:FF",
            sensorFamily = SensorFamily.SIBIONICS_GS1,
            transportVariant = 0,
            transportProtocol = "GS1_V120",
            dataHandleBinarySetId = SibionicsDataHandle.BINARY_SET_ID,
            sequence = 40,
            sensorTimeEpochMs = 1_700_000_000_000L,
            algorithmProfile = AlgorithmProfile.V116A.name,
            algorithmVersion = native.algorithmVersion,
            binarySetId = native.binarySetId,
            sensitivityToken = sensitivity.token.value,
            sensitivityTokenSource = sensitivity.token.source.name,
            sensitivityCoefficient = sensitivity.coefficient.toDouble(),
            sensitivityEncoding = sensitivity.encoding.name,
            initializationMode = AlgorithmInitializationMode.STANDARD.name,
            state = state,
            stateSha256 = state.sha256(),
            displayOffsetMmolL = 0.5,
            schemaVersion = SibionicsAlgorithmSession.CHECKPOINT_SCHEMA_VERSION,
        )
    }

    private fun ByteArray.sha256(): String = MessageDigest.getInstance("SHA-256")
        .digest(this)
        .joinToString("") { "%02x".format(it.toInt() and 0xff) }
}

private class FactoryStore(
    private val savedCheckpoint: SensorAlgorithmCheckpointRecord? = null,
    private val physicalCheckpoint: SensorAlgorithmCheckpointRecord? = savedCheckpoint,
) : SensorCoreStore {
    var checkpointCalls = 0
    val records = mutableListOf<AtomicSensorCoreRecord>()

    override suspend fun commit(record: AtomicSensorCoreRecord): SensorCoreCommitResult {
        records += record
        return SensorCoreCommitResult.Committed
    }

    override suspend fun checkpoint(sensorId: String): SensorAlgorithmCheckpointRecord? {
        checkpointCalls += 1
        return savedCheckpoint
    }

    override suspend fun checkpointByBluetoothAddress(
        bluetoothAddress: String,
    ): SensorAlgorithmCheckpointRecord? = physicalCheckpoint

    override suspend fun recordFailure(
        record: SensorIngestionFailureRecord,
    ): SensorFailureCommitResult = SensorFailureCommitResult.Committed
}

private class FactoryNative : NativeAlgorithmApi {
    override val profile = AlgorithmProfile.V116A
    override val binarySetId = "v116a-test"
    override val algorithmVersion = "1.1.6A-test"
    val state = ByteArray(profile.stateSize) { (it * 7).toByte() }
    val calls = mutableListOf<String>()

    override fun createContext(): NativeAlgorithmContext {
        calls += "create"
        return FactoryContext
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

    override fun process(context: NativeAlgorithmContext, input: AlgorithmInput) =
        NativeAlgorithmSnapshot(6.0, 0)

    override fun exportState(context: NativeAlgorithmContext): ByteArray = state.copyOf()

    override fun release(context: NativeAlgorithmContext): Int = 1
}

private data object FactoryContext : NativeAlgorithmContext
