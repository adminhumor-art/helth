package com.sladkaya.sensor.sibionics

import com.sladkaya.sensor.sibionics.algorithm.AlgorithmCheckpoint
import com.sladkaya.sensor.sibionics.algorithm.AlgorithmError
import com.sladkaya.sensor.sibionics.algorithm.AlgorithmErrorCode
import com.sladkaya.sensor.sibionics.algorithm.AlgorithmInitializationMode
import com.sladkaya.sensor.sibionics.algorithm.AlgorithmInput
import com.sladkaya.sensor.sibionics.algorithm.AlgorithmOpenResult
import com.sladkaya.sensor.sibionics.algorithm.AlgorithmOutput
import com.sladkaya.sensor.sibionics.algorithm.AlgorithmProfile
import com.sladkaya.sensor.sibionics.algorithm.DecodedSensitivity
import com.sladkaya.sensor.sibionics.algorithm.NativeAlgorithmApi
import com.sladkaya.sensor.sibionics.algorithm.NativeAlgorithmContext
import com.sladkaya.sensor.sibionics.algorithm.NativeAlgorithmSnapshot
import com.sladkaya.sensor.sibionics.algorithm.SensitivityEncoding
import com.sladkaya.sensor.sibionics.algorithm.SensitivityToken
import com.sladkaya.sensor.sibionics.algorithm.SibionicsAlgorithmSession
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class Gs1GoldenReplayRunnerTest {
    @Test
    fun plannerAndRunnerReplayExactPacketsAcrossContextReopenWithoutProductReadings() {
        val trace = syntheticFixtureTrace()
        val decoder = RecordingVerifier(decodedPackets(trace))
        val nativeControl = ReplayNativeControl()
        val factory = RecordingSessionFactory(trace, nativeControl = nativeControl)
        val plan = Gs1GoldenReplayPlanner().plan(trace) as Gs1GoldenReplayPlanResult.Ready

        val result = runner(decoder, factory).run(plan.plan)

        result as Gs1GoldenReplayResult.Matched
        assertEquals(2, result.report.matchedSamples)
        assertEquals(2, result.report.contextsOpened)
        assertTrue(result.report.syntheticOnly)
        assertFalse(result.report.releaseEvidence)
        assertEquals(trace.notifications.map { it.packetSha256 }, decoder.packetHashes)
        assertEquals(listOf(null, 1), factory.restoredIndexes)
        assertEquals(listOf(1), nativeControl.restoredStateIndexes)
    }

    @Test
    fun referenceReplayWithoutPrivateHmacCapabilityFailsClosedBeforeDecoderOrNativeStep() {
        val trace = privateReferenceTrace()
        val decoder = RecordingVerifier(decodedPackets(trace))
        val nativeControl = ReplayNativeControl()
        val factory = RecordingSessionFactory(trace, nativeControl = nativeControl)
        val plan = (Gs1GoldenReplayPlanner().plan(trace) as Gs1GoldenReplayPlanResult.Ready).plan

        val result = Gs1GoldenReplayRunner(decoder, factory, sensitivityHmac = null).run(plan)

        result as Gs1GoldenReplayResult.Failed
        assertEquals(Gs1GoldenReplayFailure.SENSITIVITY_BINDING_UNAVAILABLE, result.failure)
        assertTrue(decoder.packetHashes.isEmpty())
        assertTrue(nativeControl.processedIndexes.isEmpty())
    }

    @Test
    fun privateReferenceReplayWithMatchingKeyCapabilityRemainsNonReleaseEvidence() {
        val trace = privateReferenceTrace()

        val result = runner(
            RecordingVerifier(decodedPackets(trace)),
            RecordingSessionFactory(trace),
        ).run(readyPlan(trace))

        result as Gs1GoldenReplayResult.Matched
        assertFalse(result.report.syntheticOnly)
        assertFalse(result.report.releaseEvidence)
    }

    @Test
    fun wrongExactSensitivityInputFailsHmacBindingEvenWhenMetadataOtherwiseMatches() {
        val trace = syntheticGoldenTrace()
        val factory = RecordingSessionFactory(
            trace,
            sensitivityInput = "synth001",
        )
        val plan = readyPlan(trace)

        val result = runner(RecordingVerifier(decodedPackets(trace)), factory).run(plan)

        result as Gs1GoldenReplayResult.Failed
        assertEquals(Gs1GoldenReplayFailure.SENSITIVITY_BINDING_MISMATCH, result.failure)
    }

    @Test
    fun externalCapabilityAndFactoryFailuresCannotEchoSensitivitySecrets() {
        val trace = syntheticGoldenTrace()
        val decoder = RecordingVerifier(decodedPackets(trace))
        val capabilityLeak = Gs1GoldenReplayRunner(
            decoder = decoder,
            sessionFactory = RecordingSessionFactory(trace),
            sensitivityHmac = Gs1GoldenSensitivityHmacCapability {
                error("capability leaked $SYNTHETIC_SENSITIVITY_INPUT and synthetic-golden-test-key-v1")
            },
        ).run(readyPlan(trace))
        val factoryLeak = Gs1GoldenReplayRunner(
            decoder = RecordingVerifier(decodedPackets(trace)),
            sessionFactory = Gs1GoldenReplaySessionFactory {
                error("factory leaked $SYNTHETIC_SENSITIVITY_INPUT")
            },
            sensitivityHmac = syntheticHmacCapability(),
        ).run(readyPlan(trace))
        val reportedFactoryLeak = Gs1GoldenReplayRunner(
            decoder = RecordingVerifier(decodedPackets(trace)),
            sessionFactory = Gs1GoldenReplaySessionFactory {
                Gs1GoldenReplaySessionOpenResult.Failure(
                    AlgorithmError(
                        AlgorithmErrorCode.NATIVE_CREATE_FAILED,
                        "reported $SYNTHETIC_SENSITIVITY_INPUT",
                    ),
                )
            },
            sensitivityHmac = syntheticHmacCapability(),
        ).run(readyPlan(trace))

        capabilityLeak as Gs1GoldenReplayResult.Failed
        factoryLeak as Gs1GoldenReplayResult.Failed
        reportedFactoryLeak as Gs1GoldenReplayResult.Failed
        assertEquals(Gs1GoldenReplayFailure.SENSITIVITY_BINDING_UNAVAILABLE, capabilityLeak.failure)
        assertEquals(Gs1GoldenReplayFailure.SESSION_OPEN_FAILED, factoryLeak.failure)
        assertEquals(Gs1GoldenReplayFailure.SESSION_OPEN_FAILED, reportedFactoryLeak.failure)
        listOf(capabilityLeak.detail, factoryLeak.detail, reportedFactoryLeak.detail).forEach { detail ->
            assertFalse(detail.contains(SYNTHETIC_SENSITIVITY_INPUT))
            assertFalse(detail.contains("synthetic-golden-test-key-v1"))
        }
    }

    @Test
    fun wrongInitializationEncodingOrCoefficientFailsBeforeFirstAlgorithmStep() {
        val trace = syntheticGoldenTrace()
        val cases = listOf(
            RecordingSessionFactory(trace, initializationMode = AlgorithmInitializationMode.FACTION),
            RecordingSessionFactory(trace, sensitivityEncoding = SensitivityEncoding.FACTION),
            RecordingSessionFactory(trace, sensitivityCoefficient = 1.4200002f),
        )

        cases.forEach { factory ->
            val result = runner(RecordingVerifier(decodedPackets(trace)), factory).run(readyPlan(trace))

            result as Gs1GoldenReplayResult.Failed
            assertEquals(Gs1GoldenReplayFailure.SENSITIVITY_METADATA_MISMATCH, result.failure)
            assertTrue(factory.nativeControl.processedIndexes.isEmpty())
        }
    }

    @Test
    fun decodedSampleMismatchStopsBeforeNativeSessionOpens() {
        val trace = syntheticGoldenTrace()
        val wrong = trace.notifications.associate { notification ->
            notification.packetSha256 to notification.samples.map { expected ->
                expected.decoded.copy(current = expected.decoded.current + 1)
            }
        }
        val factory = RecordingSessionFactory(trace)

        val result = runner(RecordingVerifier(wrong), factory).run(readyPlan(trace))

        result as Gs1GoldenReplayResult.Failed
        assertEquals(Gs1GoldenReplayFailure.DECODED_SAMPLE_MISMATCH, result.failure)
        assertTrue(factory.restoredIndexes.isEmpty())
    }

    @Test
    fun mutatedRawExpectationCannotTeachTheIndependentDecoderItsOwnAnswer() {
        val base = syntheticGoldenTrace()
        val first = base.notifications.first()
        val expected = first.samples.single()
        val mutated = base.copy(
            notifications = listOf(
                first.copy(
                    samples = listOf(
                        expected.copy(decoded = expected.decoded.copy(current = expected.decoded.current + 1)),
                    ),
                ),
                base.notifications.last(),
            ),
        )
        val factory = RecordingSessionFactory(base)

        val result = runner(
            RecordingVerifier(decodedPackets(base)),
            factory,
        ).run(readyPlan(mutated))

        result as Gs1GoldenReplayResult.Failed
        assertEquals(Gs1GoldenReplayFailure.DECODED_SAMPLE_MISMATCH, result.failure)
        assertTrue(factory.restoredIndexes.isEmpty())
    }

    @Test
    fun eachIndependentNativeOutputMutationFailsClosedAtExactSample() {
        val trace = syntheticGoldenTrace()
        val faults = listOf(
            ReplayNativeFault.NATIVE_GLUCOSE,
            ReplayNativeFault.TREND,
            ReplayNativeFault.GLUCOSE_WARNING,
            ReplayNativeFault.CURRENT_WARNING,
            ReplayNativeFault.TEMPERATURE_WARNING,
        )

        faults.forEach { fault ->
            val factory = RecordingSessionFactory(
                trace,
                nativeControl = ReplayNativeControl(fault = fault, faultIndex = 2),
            )
            val result = runner(RecordingVerifier(decodedPackets(trace)), factory).run(readyPlan(trace))

            result as Gs1GoldenReplayResult.Failed
            assertEquals("fault $fault", Gs1GoldenReplayFailure.NATIVE_DIAGNOSTIC_MISMATCH, result.failure)
            assertEquals("fault $fault", 2, result.sampleIndex)
        }
    }

    @Test
    fun outputIdentityAndInitializationMutationsAreComparedIndependently() {
        val trace = syntheticGoldenTrace()
        val mutations: List<(AlgorithmOutput) -> AlgorithmOutput> = listOf(
            { it.copy(index = it.index + 1) },
            { it.copy(sensorTimeEpochSeconds = it.sensorTimeEpochSeconds + 60L) },
            { it.copy(algorithmProfile = AlgorithmProfile.V115G) },
            { it.copy(algorithmVersion = "wrong-version") },
            { it.copy(initializationMode = AlgorithmInitializationMode.FACTION) },
        )

        mutations.forEach { mutation ->
            val result = runner(
                RecordingVerifier(decodedPackets(trace)),
                RecordingSessionFactory(trace, outputMutation = mutation),
            ).run(readyPlan(trace))

            result as Gs1GoldenReplayResult.Failed
            assertEquals(Gs1GoldenReplayFailure.NATIVE_DIAGNOSTIC_MISMATCH, result.failure)
            assertEquals(1, result.sampleIndex)
        }
    }

    @Test
    fun traceCannotRedefineThePinnedNativeVersionOrBinarySet() {
        val base = syntheticGoldenTrace()
        val cases = listOf(
            base.copy(algorithmVersion = "synthetic-mutated-version"),
            base.copy(algorithmBinarySetId = "synthetic-mutated-binary-set"),
        )

        cases.forEach { mutated ->
            val result = runner(
                RecordingVerifier(decodedPackets(base)),
                RecordingSessionFactory(mutated),
            ).run(readyPlan(mutated))

            result as Gs1GoldenReplayResult.Failed
            assertTrue(
                result.failure == Gs1GoldenReplayFailure.NATIVE_DIAGNOSTIC_MISMATCH ||
                    result.failure == Gs1GoldenReplayFailure.CHECKPOINT_INTEGRITY_MISMATCH,
            )
        }
    }

    @Test
    fun displayedOutputMutationInTraceFailsAgainstIndependentNativeFixture() {
        val base = syntheticGoldenTrace()
        val second = base.notifications[1]
        val expected = second.samples.single()
        val mutated = base.copy(
            notifications = listOf(
                base.notifications[0],
                second.copy(
                    samples = listOf(
                        expected.copy(
                            diagnostic = expected.diagnostic.copy(
                                displayedGlucoseMmolLBits = 9.9.toBits(),
                            ),
                        ),
                    ),
                ),
            ),
        )

        val result = runner(
            RecordingVerifier(decodedPackets(mutated)),
            RecordingSessionFactory(mutated),
        ).run(readyPlan(mutated))

        result as Gs1GoldenReplayResult.Failed
        assertEquals(Gs1GoldenReplayFailure.NATIVE_DIAGNOSTIC_MISMATCH, result.failure)
        assertEquals(2, result.sampleIndex)
    }

    @Test
    fun diagnosticErrorMutationInTraceFailsAgainstIndependentSuccessfulStep() {
        val base = syntheticGoldenTrace()
        val second = base.notifications[1]
        val expected = second.samples.single()
        val mutated = base.copy(
            notifications = listOf(
                base.notifications[0],
                second.copy(
                    samples = listOf(
                        expected.copy(
                            diagnostic = expected.diagnostic.copy(
                                algorithmErrorCode = AlgorithmErrorCode.INVALID_GLUCOSE,
                            ),
                        ),
                    ),
                ),
            ),
        )

        val result = runner(
            RecordingVerifier(decodedPackets(mutated)),
            RecordingSessionFactory(mutated),
        ).run(readyPlan(mutated))

        result as Gs1GoldenReplayResult.Failed
        assertEquals(Gs1GoldenReplayFailure.NATIVE_DIAGNOSTIC_MISMATCH, result.failure)
        assertEquals(2, result.sampleIndex)
    }

    @Test
    fun stateMutationAndRestoreFailureHaveIndependentFailClosedResults() {
        val trace = syntheticGoldenTrace()
        val stateMutation = runner(
            RecordingVerifier(decodedPackets(trace)),
            RecordingSessionFactory(
                trace,
                nativeControl = ReplayNativeControl(
                    fault = ReplayNativeFault.STATE,
                    faultIndex = 2,
                ),
            ),
        ).run(readyPlan(trace))
        val restoreFailure = runner(
            RecordingVerifier(decodedPackets(trace)),
            RecordingSessionFactory(
                trace,
                nativeControl = ReplayNativeControl(failRestore = true),
            ),
        ).run(readyPlan(trace))

        stateMutation as Gs1GoldenReplayResult.Failed
        assertEquals(Gs1GoldenReplayFailure.CHECKPOINT_INTEGRITY_MISMATCH, stateMutation.failure)
        assertEquals(2, stateMutation.sampleIndex)
        restoreFailure as Gs1GoldenReplayResult.Failed
        assertEquals(Gs1GoldenReplayFailure.SESSION_OPEN_FAILED, restoreFailure.failure)
        assertEquals(1, restoreFailure.attemptOrdinal)
    }

    @Test
    fun checkpointTokenOrInitializationMutationCannotPassOutputComparison() {
        val trace = syntheticGoldenTrace()
        val tokenMutation = runner(
            RecordingVerifier(decodedPackets(trace)),
            RecordingSessionFactory(
                trace,
                checkpointMutation = { checkpoint ->
                    checkpoint.copy(sensitivityToken = SensitivityToken.packageCode("WRONG001"))
                },
            ),
        ).run(readyPlan(trace))
        val initializationMutation = runner(
            RecordingVerifier(decodedPackets(trace)),
            RecordingSessionFactory(
                trace,
                checkpointMutation = { checkpoint ->
                    checkpoint.copy(initializationMode = AlgorithmInitializationMode.FACTION)
                },
            ),
        ).run(readyPlan(trace))

        tokenMutation as Gs1GoldenReplayResult.Failed
        assertEquals(Gs1GoldenReplayFailure.CHECKPOINT_INTEGRITY_MISMATCH, tokenMutation.failure)
        initializationMutation as Gs1GoldenReplayResult.Failed
        assertEquals(Gs1GoldenReplayFailure.CHECKPOINT_INTEGRITY_MISMATCH, initializationMutation.failure)
    }

    private fun runner(
        decoder: Gs1PacketVerifier,
        factory: Gs1GoldenReplaySessionFactory,
    ) = Gs1GoldenReplayRunner(
        decoder = decoder,
        sessionFactory = factory,
        sensitivityHmac = syntheticHmacCapability(),
    )

    private fun readyPlan(trace: Gs1GoldenTrace): Gs1GoldenReplayPlan =
        (Gs1GoldenReplayPlanner().plan(trace) as Gs1GoldenReplayPlanResult.Ready).plan

    private fun syntheticFixtureTrace(): Gs1GoldenTrace {
        val bytes = checkNotNull(javaClass.getResourceAsStream("/golden/gs1-synthetic-v1.trace"))
            .use { it.readBytes() }
        return (Gs1GoldenTraceCodec().decode(bytes) as Gs1GoldenTraceDecodeResult.Success).trace
    }

    private fun privateReferenceTrace(): Gs1GoldenTrace {
        val synthetic = syntheticGoldenTrace()
        return synthetic.copy(
            provenance = Gs1GoldenTraceProvenance.PRIVATE_REFERENCE_CAPTURE,
            privacyClassification = Gs1GoldenPrivacyClassification.PRIVATE_SENSITIVE_EVIDENCE,
            macIdentity = synthetic.macIdentity.copy(
                evidenceKind = Gs1GoldenIdentityEvidenceKind.MANUAL_CODE_AND_ADVERTISEMENT,
            ),
        )
    }
}

private fun decodedPackets(trace: Gs1GoldenTrace): Map<String, List<DecodedGs1RawSample>> =
    trace.notifications.associate { notification ->
        notification.packetSha256 to notification.samples.map { it.decoded }
    }

private fun syntheticHmacCapability() = Gs1GoldenSensitivityHmacCapability { message ->
    val mac = Mac.getInstance("HmacSHA256")
    mac.init(SecretKeySpec(SYNTHETIC_GOLDEN_HMAC_KEY, "HmacSHA256"))
    mac.doFinal(message)
}

private class RecordingVerifier(
    private val samplesByPacketHash: Map<String, List<DecodedGs1RawSample>>,
) : Gs1PacketVerifier {
    val packetHashes = mutableListOf<String>()

    override fun decode(encryptedPacket: ByteArray): Gs1VerifiedPacketResult {
        val hash = encryptedPacket.sha256()
        packetHashes += hash
        return Gs1VerifiedPacketResult.Success(
            samples = checkNotNull(samplesByPacketHash[hash]),
            nativeRecords = emptyList(),
            decrypted = true,
        )
    }
}

private class RecordingSessionFactory(
    private val trace: Gs1GoldenTrace,
    private val sensitivityInput: String = SYNTHETIC_SENSITIVITY_INPUT,
    private val initializationMode: AlgorithmInitializationMode = AlgorithmInitializationMode.STANDARD,
    private val sensitivityEncoding: SensitivityEncoding = SensitivityEncoding.NORMAL,
    private val sensitivityCoefficient: Float = SYNTHETIC_SENSITIVITY_COEFFICIENT,
    val nativeControl: ReplayNativeControl = ReplayNativeControl(),
    private val checkpointMutation: (AlgorithmCheckpoint) -> AlgorithmCheckpoint = { it },
    private val outputMutation: (AlgorithmOutput) -> AlgorithmOutput = { it },
) : Gs1GoldenReplaySessionFactory {
    val restoredIndexes = mutableListOf<Int?>()

    override fun open(checkpoint: AlgorithmCheckpoint?): Gs1GoldenReplaySessionOpenResult {
        restoredIndexes += checkpoint?.lastProcessedIndex
        val sensitivity = DecodedSensitivity(
            token = SensitivityToken.packageCode(sensitivityInput),
            coefficient = sensitivityCoefficient,
            encoding = sensitivityEncoding,
        )
        val native = ReplayNative(nativeControl)
        return when (
            val opened = SibionicsAlgorithmSession.open(
                profile = trace.algorithmProfile,
                sensitivityToken = sensitivity.token,
                initializationMode = AlgorithmInitializationMode.STANDARD,
                checkpoint = checkpoint,
                native = native,
            )
        ) {
            is AlgorithmOpenResult.Success -> Gs1GoldenReplaySessionOpenResult.Success(
                session = MutatingReplaySession(opened.session, checkpointMutation, outputMutation),
                sensitivity = sensitivity,
                initializationMode = initializationMode,
            )
            is AlgorithmOpenResult.Failure -> Gs1GoldenReplaySessionOpenResult.Failure(opened.error)
        }
    }
}

/** Test-only seam used to mutate one observed field independently of the trace. */
private class MutatingReplaySession(
    private val delegate: SibionicsAlgorithmSession,
    private val checkpointMutation: (AlgorithmCheckpoint) -> AlgorithmCheckpoint,
    private val outputMutation: (AlgorithmOutput) -> AlgorithmOutput,
) : Gs1GoldenReplayAlgorithmSession {
    override fun process(input: AlgorithmInput) = when (val result = delegate.process(input)) {
        is com.sladkaya.sensor.sibionics.algorithm.AlgorithmStepResult.Success ->
            result.copy(
                output = outputMutation(result.output),
                checkpoint = checkpointMutation(result.checkpoint),
            )
        is com.sladkaya.sensor.sibionics.algorithm.AlgorithmStepResult.Failure ->
            result.copy(
                checkpoint = result.checkpoint?.let(checkpointMutation),
                diagnosticOutput = result.diagnosticOutput?.let(outputMutation),
            )
    }

    override fun confirmPersisted(checkpoint: AlgorithmCheckpoint) = delegate.confirmPersisted(checkpoint)

    override fun close() = delegate.close()
}

private enum class ReplayNativeFault {
    NONE,
    NATIVE_GLUCOSE,
    TREND,
    GLUCOSE_WARNING,
    CURRENT_WARNING,
    TEMPERATURE_WARNING,
    STATE,
}

private class ReplayNativeControl(
    val fault: ReplayNativeFault = ReplayNativeFault.NONE,
    val faultIndex: Int = Int.MAX_VALUE,
    val failRestore: Boolean = false,
) {
    val processedIndexes = mutableListOf<Int>()
    val restoredStateIndexes = mutableListOf<Int?>()
}

private class ReplayNative(
    private val control: ReplayNativeControl,
) : NativeAlgorithmApi {
    override val profile = AlgorithmProfile.V116A
    override val binarySetId = "synthetic-binary-set-v1"
    override val algorithmVersion = "synthetic-native-v1"

    override fun createContext(): NativeAlgorithmContext = ReplayContext()

    override fun initialize(
        context: NativeAlgorithmContext,
        sensitivityToken: String,
        mode: AlgorithmInitializationMode,
    ): Int = 1

    override fun restoreState(context: NativeAlgorithmContext, state: ByteArray): Int {
        if (control.failRestore) return 0
        val restored = state.first().toInt()
        (context as ReplayContext).index = restored
        control.restoredStateIndexes += restored
        return 1
    }

    override fun process(
        context: NativeAlgorithmContext,
        input: AlgorithmInput,
    ): NativeAlgorithmSnapshot {
        control.processedIndexes += input.index
        (context as ReplayContext).index = input.index
        val faulty = input.index == control.faultIndex
        return NativeAlgorithmSnapshot(
            glucoseMmolL = 6.0 + input.index * 0.25 +
                if (faulty && control.fault == ReplayNativeFault.NATIVE_GLUCOSE) 0.5 else 0.0,
            trend = (if (input.index == 1) 1 else -2) +
                if (faulty && control.fault == ReplayNativeFault.TREND) 1 else 0,
            glucoseWarning = input.index +
                if (faulty && control.fault == ReplayNativeFault.GLUCOSE_WARNING) 1 else 0,
            currentWarning = input.index + 10 +
                if (faulty && control.fault == ReplayNativeFault.CURRENT_WARNING) 1 else 0,
            temperatureWarning = input.index + 20 +
                if (faulty && control.fault == ReplayNativeFault.TEMPERATURE_WARNING) 1 else 0,
        )
    }

    override fun exportState(context: NativeAlgorithmContext): ByteArray {
        val index = (context as ReplayContext).index
        val byte = if (index == control.faultIndex && control.fault == ReplayNativeFault.STATE) {
            index + 1
        } else {
            index
        }
        return ByteArray(profile.stateSize) { byte.toByte() }
    }

    override fun release(context: NativeAlgorithmContext): Int = 1
}

private class ReplayContext(var index: Int = 0) : NativeAlgorithmContext
