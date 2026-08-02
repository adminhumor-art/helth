package com.sladkaya.sensor.sibionics

import com.sladkaya.core.data.AtomicSensorCoreRecord
import com.sladkaya.core.data.SensorAlgorithmCheckpointRecord
import com.sladkaya.core.data.SensorCoreCommitResult
import com.sladkaya.core.data.SensorCoreStore
import com.sladkaya.core.data.SensorFailureCommitResult
import com.sladkaya.core.data.SensorIngestionFailureRecord
import com.sladkaya.core.model.SensorFamily
import com.sladkaya.core.sensor.SensorConfiguration
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
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
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
    fun factionEncodingUsesExactInitAndRemainsPinnedInTheBindingTuple() = runBlocking {
        val native = FactoryNative()
        val configuration = configuration()
        val store = FactoryStore()
        val factionBinding = binding(configuration, Gs1WireProfile.V120)
            .copy(sensitivityEncoding = "FACTION")
        val factory = Gs1CoreFactory(
            store = store,
            decodeSensitivity = { token ->
                SensitivityDecodeResult.Success(DecodedSensitivity(token, 1.58f, SensitivityEncoding.FACTION))
            },
            nativeProvider = { native },
        )

        val result = factory.open(
            configuration,
            factionBinding,
        )

        assertTrue(result is Gs1CoreOpenResult.Success)
        assertEquals(listOf("create", "init:FACTION:ABCDEFGH"), native.calls)
        result as Gs1CoreOpenResult.Success
        val processed = result.coordinator.process(
            encryptedPacket = byteArrayOf(1),
            sample = DecodedGs1RawSample(
                index = 1,
                sensorTimeEpochSeconds = 1_900_000_000L,
                current = 50,
                temperature = 321,
                reindex = 0,
            ),
            receivedAtEpochMs = 1_900_000_000_000L,
        )
        assertTrue(processed.toString(), processed is Gs1ProcessingResult.Diagnostic)
        assertEquals("FACTION", store.records.single().checkpoint.sensitivityEncoding)
        assertEquals("FACTION", store.records.single().checkpoint.initializationMode)

        val restoredNative = FactoryNative()
        val restoredFactory = Gs1CoreFactory(
            store = FactoryStore(
                savedCheckpoint = store.records.single().checkpoint,
                initialProtocolBinding = factionBinding,
            ),
            decodeSensitivity = { token ->
                SensitivityDecodeResult.Success(
                    DecodedSensitivity(token, 1.58f, SensitivityEncoding.FACTION),
                )
            },
            nativeProvider = { restoredNative },
        )
        val reopened = restoredFactory.open(configuration, factionBinding)
            as Gs1CoreOpenResult.Success
        assertEquals(2, reopened.nextSensorIndex)
        assertEquals(
            listOf("create", "init:FACTION:ABCDEFGH", "restore:2480"),
            restoredNative.calls,
        )
    }

    @Test
    fun v115gFactionCommitAndReopenKeepTheExactDurableTuple() = runBlocking {
        val configuration = configuration(
            transportVariant = 2,
            wireProfile = Gs1WireProfile.V115,
        )
        val factionBinding = binding(configuration, Gs1WireProfile.V115)
            .copy(sensitivityEncoding = "FACTION")
        val native = FactoryNative(AlgorithmProfile.V115G)
        val store = FactoryStore(initialProtocolBinding = factionBinding)
        val factory = Gs1CoreFactory(
            store = store,
            decodeSensitivity = { token ->
                SensitivityDecodeResult.Success(
                    DecodedSensitivity(token, 1.58f, SensitivityEncoding.FACTION),
                )
            },
            nativeProvider = { native },
        )
        val opened = factory.open(configuration, factionBinding) as Gs1CoreOpenResult.Success

        val processed = opened.coordinator.process(
            encryptedPacket = byteArrayOf(1),
            sample = DecodedGs1RawSample(
                index = 1,
                sensorTimeEpochSeconds = 1_900_000_000L,
                current = 50,
                temperature = 321,
                reindex = 0,
            ),
            receivedAtEpochMs = 1_900_000_000_000L,
        )

        assertTrue(processed.toString(), processed is Gs1ProcessingResult.Diagnostic)
        val checkpoint = store.records.single().checkpoint
        assertEquals("V115G", checkpoint.algorithmProfile)
        assertEquals("FACTION", checkpoint.sensitivityEncoding)
        assertEquals("FACTION", checkpoint.initializationMode)

        val restoredNative = FactoryNative(AlgorithmProfile.V115G)
        val restoredFactory = Gs1CoreFactory(
            store = FactoryStore(
                savedCheckpoint = checkpoint,
                initialProtocolBinding = factionBinding,
            ),
            decodeSensitivity = { token ->
                SensitivityDecodeResult.Success(
                    DecodedSensitivity(token, 1.58f, SensitivityEncoding.FACTION),
                )
            },
            nativeProvider = { restoredNative },
        )
        val reopened = restoredFactory.open(configuration, factionBinding)
            as Gs1CoreOpenResult.Success
        assertEquals(2, reopened.nextSensorIndex)
        assertEquals(
            listOf("create", "init:FACTION:ABCDEFGH", "restore:2336"),
            restoredNative.calls,
        )
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
    fun checkpointFromAnotherAlgorithmVersionCannotRestoreNativeState() = runBlocking {
        val native = FactoryNative()
        val token = SensitivityToken.packageCode("ABCDEFGH")
        val sensitivity = DecodedSensitivity(token, 1.42f, SensitivityEncoding.NORMAL)
        val saved = checkpoint(native, sensitivity).copy(algorithmVersion = "foreign-version")
        val factory = Gs1CoreFactory(
            store = FactoryStore(savedCheckpoint = saved),
            decodeSensitivity = { SensitivityDecodeResult.Success(sensitivity) },
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
                override suspend fun bindProtocol(
                    record: com.sladkaya.core.data.SensorProtocolBindingRecord,
                ): com.sladkaya.core.data.SensorProtocolBindingCommitResult =
                    com.sladkaya.core.data.SensorProtocolBindingCommitResult.Bound

                override suspend fun protocolBinding(
                    sensorId: String,
                ): com.sladkaya.core.data.SensorProtocolBindingRecord? = null

                override suspend fun protocolBindingByBluetoothAddress(
                    bluetoothAddress: String,
                ): com.sladkaya.core.data.SensorProtocolBindingRecord? = null

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

    @Test
    fun chineseRuntimeCannotTouchCheckpointOrNativeBeforeDurableBinding() = runBlocking {
        val store = FactoryStore()
        val native = FactoryNative(AlgorithmProfile.V115G)
        val factory = Gs1CoreFactory(
            store = store,
            decodeSensitivity = { token ->
                SensitivityDecodeResult.Success(
                    DecodedSensitivity(token, 1.42f, SensitivityEncoding.NORMAL),
                )
            },
            nativeProvider = { native },
        )

        val profile = (Gs1DiagnosticActivationProfile.validate(
            sensorId = "sensor-a",
            family = SensorFamily.SIBIONICS_GS1,
            bluetoothAddress = "AA:BB:CC:DD:EE:FF",
            transportVariant = 2,
            packageCode = "ABCDEFGH",
        ) as Gs1DiagnosticActivationProfileValidation.Valid).profile
        val result = FactoryGs1RuntimeCoreOpener(factory).open(profile)

        assertTrue(result is Gs1RuntimeCoreOpenResult.Success)
        assertEquals(
            Gs1WireProfile.UNRESOLVED,
            (result as Gs1RuntimeCoreOpenResult.Success).lease.wireProfile,
        )
        assertEquals(0, store.checkpointCalls)
        assertTrue(native.calls.isEmpty())
        result.lease.close()
    }

    @Test
    fun globalRuntimeAlsoDefersStatefulCoreUntilValidatedSensorData() = runBlocking {
        val store = FactoryStore()
        val requestedProfiles = mutableListOf<AlgorithmProfile>()
        val factory = Gs1CoreFactory(
            store = store,
            decodeSensitivity = { token ->
                SensitivityDecodeResult.Success(
                    DecodedSensitivity(token, 1.42f, SensitivityEncoding.NORMAL),
                )
            },
            nativeProvider = { requested ->
                requestedProfiles += requested
                FactoryNative(requested)
            },
        )

        val opened = FactoryGs1RuntimeCoreOpener(factory).open(activationProfile(0))
            as Gs1RuntimeCoreOpenResult.Success

        assertEquals(Gs1WireProfile.V120, opened.lease.wireProfile)
        assertEquals(0, store.checkpointCalls)
        assertTrue(requestedProfiles.isEmpty())
        opened.lease.close()
    }

    @Test
    fun exactChallengeBindsV120BeforeOpeningOnlyV116A() = runBlocking {
        val store = FactoryStore()
        val requestedProfiles = mutableListOf<AlgorithmProfile>()
        val factory = Gs1CoreFactory(
            store = store,
            decodeSensitivity = { token ->
                SensitivityDecodeResult.Success(
                    DecodedSensitivity(token, 1.42f, SensitivityEncoding.NORMAL),
                )
            },
            nativeProvider = { requested ->
                requestedProfiles += requested
                FactoryNative(requested)
            },
        )
        val lease = (FactoryGs1RuntimeCoreOpener(factory).open(activationProfile(2))
            as Gs1RuntimeCoreOpenResult.Success).lease

        val result = lease.ingest(
            DurablyJournaledGs1Packet(
                ingressId = "challenge",
                receivedAtEpochMs = 1_700_000_000_000L,
                encryptedPacket = byteArrayOf(
                    0x23,
                    0xf7.toByte(),
                    0x6f,
                    0xd9.toByte(),
                    0xf4.toByte(),
                ),
            ),
        ) as Gs1PacketProcessingResult.Completed

        assertEquals(Gs1WireProfile.V120, result.resolvedWireProfile)
        assertEquals(listOf(AlgorithmProfile.V116A), requestedProfiles)
        assertEquals("V120", store.savedProtocolBinding?.wireProfile)
        assertEquals(1, store.checkpointCalls)
        lease.close()
    }

    @Test
    fun factionSensitivityIsBoundBeforeExactFactionInitAndCheckpointing() = runBlocking {
        val store = FactoryStore()
        val native = FactoryNative()
        val factory = Gs1CoreFactory(
            store = store,
            decodeSensitivity = { token ->
                SensitivityDecodeResult.Success(
                    DecodedSensitivity(token, 1.58f, SensitivityEncoding.FACTION),
                )
            },
            nativeProvider = { native },
        )
        val lease = (FactoryGs1RuntimeCoreOpener(factory).open(activationProfile(2))
            as Gs1RuntimeCoreOpenResult.Success).lease

        val result = lease.ingest(
            DurablyJournaledGs1Packet(
                ingressId = "faction-challenge",
                receivedAtEpochMs = 1_700_000_000_000L,
                encryptedPacket = byteArrayOf(
                    0x23,
                    0xf7.toByte(),
                    0x6f,
                    0xd9.toByte(),
                    0xf4.toByte(),
                ),
            ),
        ) as Gs1PacketProcessingResult.Completed

        assertEquals(Gs1WireProfile.V120, result.resolvedWireProfile)
        assertEquals("FACTION", store.savedProtocolBinding?.sensitivityEncoding)
        assertEquals(listOf("create", "init:FACTION:ABCDEFGH"), native.calls)
        assertEquals(1, store.checkpointCalls)
        lease.close()
    }

    @Test
    fun validatedV115EnvelopeBindsAndProcessesOnlyThroughV115G() = runBlocking {
        val store = FactoryStore()
        val requestedProfiles = mutableListOf<AlgorithmProfile>()
        val factory = Gs1CoreFactory(
            store = store,
            decodeSensitivity = { token ->
                SensitivityDecodeResult.Success(
                    DecodedSensitivity(token, 1.42f, SensitivityEncoding.NORMAL),
                )
            },
            nativeProvider = { requested ->
                requestedProfiles += requested
                FactoryNative(requested)
            },
        )
        val lease = (FactoryGs1RuntimeCoreOpener(factory).open(activationProfile(2))
            as Gs1RuntimeCoreOpenResult.Success).lease

        val result = lease.ingest(
            DurablyJournaledGs1Packet(
                ingressId = "v115-data",
                receivedAtEpochMs = 1_700_000_000_999L,
                encryptedPacket = v115Response(index = 1),
            ),
        ) as Gs1PacketProcessingResult.Completed

        assertEquals(Gs1WireProfile.V115, result.resolvedWireProfile)
        assertEquals(listOf(AlgorithmProfile.V115G), requestedProfiles)
        assertEquals("V115", store.savedProtocolBinding?.wireProfile)
        assertEquals("GS1_V115", store.records.single().checkpoint.transportProtocol)
        assertEquals("GS1_V115_WIRE_V1", store.records.single().checkpoint.transportCodecId)
        lease.close()
    }

    @Test
    fun firstEmptyV115EnvelopeReachesStreamingWatchdogWithoutMedicalData() = runBlocking {
        val store = FactoryStore()
        val factory = Gs1CoreFactory(
            store = store,
            decodeSensitivity = { token ->
                SensitivityDecodeResult.Success(
                    DecodedSensitivity(token, 1.42f, SensitivityEncoding.NORMAL),
                )
            },
            nativeProvider = { requested -> FactoryNative(requested) },
        )
        val committedEvent = CompletableDeferred<Gs1DiagnosticRuntimeEvent.Committed>()
        val runtime = Gs1DiagnosticRuntime(
            scope = this,
            opener = FactoryGs1RuntimeCoreOpener(factory),
            eventSink = { event ->
                if (event is Gs1DiagnosticRuntimeEvent.Committed) committedEvent.complete(event)
            },
        )
        val profile = activationProfile(2)
        val started = runtime.start(profile) as Gs1RuntimeStartResult.Started
        val session = SibionicsSession(
            family = profile.family,
            configuration = SensorConfiguration(
                sensorId = profile.sensorId,
                bluetoothAddress = profile.bluetoothAddress,
                protocolVariant = profile.transportVariant,
            ),
            initialNextIndex = started.initialNextIndex,
            initialWireProfile = started.wireProfile,
        )
        assertTrue(session.initial(profile.bluetoothAddress) is SessionAction.Write)

        try {
            val outcome = runtime.submitAndAwait(
                started.generation,
                DurablyJournaledGs1Packet(
                    ingressId = "empty-v115-first-bind",
                    receivedAtEpochMs = 1_700_000_000_000L,
                    encryptedPacket = v115EmptyResponse(),
                ),
            ) as Gs1RuntimeAwaitResult.Processed
            val completed = outcome.result as Gs1PacketProcessingResult.Completed
            assertEquals(Gs1WireProfile.V115, completed.resolvedWireProfile)
            assertTrue(completed.validatedTransportEnvelope)
            assertEquals(SessionAction.None, session.confirmWireProfile(Gs1WireProfile.V115))

            val event = withTimeout(1_000L) { committedEvent.await() }
            assertEquals(SessionAction.None, session.confirmDurablyCommitted(event.samples))
            val assessment = Gs1DiagnosticCommitPolicy.assess(
                diagnostics = event.diagnostics,
                committedSampleCount = event.samples.size,
                issueCount = event.issues.size,
                validatedTransportEnvelope = event.validatedTransportEnvelope,
            )
            val progress = Gs1DiagnosticCommitProgressPolicy.plan(
                alreadyStreaming = false,
                assessment = assessment,
            )

            assertTrue(progress.markStreaming)
            assertTrue(progress.armSilenceWatchdog)
            assertTrue(event.samples.isEmpty())
            assertTrue(event.diagnostics.isEmpty())
            assertTrue(store.records.isEmpty())
        } finally {
            runtime.stop(started.generation)
        }
    }

    @Test
    fun exactV115BindingSelectsOnlyV115GAndPinsItsTransportTuple() = runBlocking {
        val store = FactoryStore()
        val native = FactoryNative(AlgorithmProfile.V115G)
        val factory = Gs1CoreFactory(
            store = store,
            decodeSensitivity = { token ->
                SensitivityDecodeResult.Success(
                    DecodedSensitivity(token, 1.42f, SensitivityEncoding.NORMAL),
                )
            },
            nativeProvider = { requested ->
                assertEquals(AlgorithmProfile.V115G, requested)
                native
            },
        )
        val configuration = configuration(
            transportVariant = 2,
            wireProfile = Gs1WireProfile.V115,
        )

        val result = factory.open(configuration, binding(configuration, Gs1WireProfile.V115))

        assertTrue(result is Gs1CoreOpenResult.Success)
        assertTrue(native.calls.contains("init:STANDARD:ABCDEFGH"))
    }

    @Test
    fun oppositeBindingTupleFailsBeforeCheckpointOrNative() = runBlocking {
        val store = FactoryStore()
        val native = FactoryNative(AlgorithmProfile.V115G)
        val factory = Gs1CoreFactory(
            store = store,
            decodeSensitivity = { token ->
                SensitivityDecodeResult.Success(
                    DecodedSensitivity(token, 1.42f, SensitivityEncoding.NORMAL),
                )
            },
            nativeProvider = { native },
        )
        val configuration = configuration(
            transportVariant = 2,
            wireProfile = Gs1WireProfile.V115,
        )

        val result = factory.open(configuration, binding(configuration, Gs1WireProfile.V120))

        assertEquals(
            Gs1CoreOpenError.PROTOCOL_BINDING_MISMATCH,
            (result as Gs1CoreOpenResult.Failure).error,
        )
        assertEquals(0, store.checkpointCalls)
        assertTrue(native.calls.isEmpty())
    }

    private fun configuration(
        packageCode: String = "ABCDEFGH",
        transportVariant: Int = 0,
        bluetoothAddress: String = "AA:BB:CC:DD:EE:FF",
        wireProfile: Gs1WireProfile = Gs1WireProfile.V120,
    ) = Gs1CoreConfiguration(
        sensorId = "sensor-a",
        family = SensorFamily.SIBIONICS_GS1,
        bluetoothAddress = bluetoothAddress,
        transportVariant = transportVariant,
        packageCode = packageCode,
        wireProfile = wireProfile,
    )

    private fun activationProfile(transportVariant: Int): Gs1DiagnosticActivationProfile =
        (Gs1DiagnosticActivationProfile.validate(
            sensorId = "sensor-a",
            family = SensorFamily.SIBIONICS_GS1,
            bluetoothAddress = "AA:BB:CC:DD:EE:FF",
            transportVariant = transportVariant,
            packageCode = "ABCDEFGH",
        ) as Gs1DiagnosticActivationProfileValidation.Valid).profile

    private fun v115Response(index: Int): ByteArray {
        val fields = listOf(index, 300, 20, 1_000, 0, 0, 0)
        val record = fields.flatMap { value ->
            listOf((value ushr 8).toByte(), value.toByte())
        }.toByteArray()
        val body = byteArrayOf(0xaa.toByte(), 0x55, 0x09, 0x01) + record
        return body + (-body.sum()).toByte()
    }

    private fun v115EmptyResponse(): ByteArray {
        val body = byteArrayOf(0xaa.toByte(), 0x55, 0x09, 0x00)
        return body + (-body.sum()).toByte()
    }

    private suspend fun Gs1CoreFactory.open(
        configuration: Gs1CoreConfiguration,
    ): Gs1CoreOpenResult = open(
        configuration,
        binding(configuration, configuration.wireProfile),
    )

    private fun binding(
        configuration: Gs1CoreConfiguration,
        wireProfile: Gs1WireProfile,
    ): com.sladkaya.core.data.SensorProtocolBindingRecord {
        val spec = Gs1WireProfiles.requireResolved(wireProfile)
        return com.sladkaya.core.data.SensorProtocolBindingRecord(
            sensorId = configuration.sensorId,
            bluetoothAddress = configuration.bluetoothAddress,
            sensorFamily = configuration.family,
            transportVariant = configuration.transportVariant,
            sensitivityToken = configuration.packageCode.takeIf { it.length == 8 } ?: "ABCDEFGH",
            wireProfile = wireProfile.name,
            transportProtocol = spec.transportProtocol,
            transportCodecId = spec.transportCodecId,
            algorithmProfile = spec.algorithmProfile.name,
            sensitivityEncoding = "NORMAL",
            evidenceKind = "TEST_EVIDENCE",
            evidenceSha256 = "ab".repeat(32),
            schemaVersion = 1,
        )
    }

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
            transportCodecId = SibionicsDataHandle.BINARY_SET_ID,
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
    initialProtocolBinding: com.sladkaya.core.data.SensorProtocolBindingRecord? = null,
) : SensorCoreStore {
    var checkpointCalls = 0
    val records = mutableListOf<AtomicSensorCoreRecord>()
    var savedProtocolBinding = initialProtocolBinding

    override suspend fun bindProtocol(
        record: com.sladkaya.core.data.SensorProtocolBindingRecord,
    ): com.sladkaya.core.data.SensorProtocolBindingCommitResult {
        val current = savedProtocolBinding
        return when {
            current == null -> {
                savedProtocolBinding = record
                com.sladkaya.core.data.SensorProtocolBindingCommitResult.Bound
            }
            current == record -> com.sladkaya.core.data.SensorProtocolBindingCommitResult.AlreadyBound
            else -> com.sladkaya.core.data.SensorProtocolBindingCommitResult.Conflict("immutable")
        }
    }

    override suspend fun protocolBinding(
        sensorId: String,
    ): com.sladkaya.core.data.SensorProtocolBindingRecord? =
        savedProtocolBinding?.takeIf { it.sensorId == sensorId }

    override suspend fun protocolBindingByBluetoothAddress(
        bluetoothAddress: String,
    ): com.sladkaya.core.data.SensorProtocolBindingRecord? =
        savedProtocolBinding?.takeIf { it.bluetoothAddress == bluetoothAddress }

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

private class FactoryNative(
    override val profile: AlgorithmProfile = AlgorithmProfile.V116A,
    override val algorithmVersion: String = "test-${profile.name}-version",
) : NativeAlgorithmApi {
    override val binarySetId = "test-${profile.name}-binary-set"
    override val supportedInitializationModes = AlgorithmInitializationMode.entries.toSet()
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
