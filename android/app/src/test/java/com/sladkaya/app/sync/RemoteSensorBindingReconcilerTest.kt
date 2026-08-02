package com.sladkaya.app.sync

import com.sladkaya.core.data.ActiveLocalSensorBinding
import com.sladkaya.core.data.LocalSensorBindingActivationResult
import com.sladkaya.core.data.LocalSensorBindingStore
import com.sladkaya.core.data.PhysicalSensorApprovalRecord
import com.sladkaya.core.data.ProductPublicationBindingRecord
import com.sladkaya.core.model.SensorFamily
import java.util.concurrent.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class RemoteSensorBindingReconcilerTest {
    @Test
    fun noCredentialDoesNotReadOrChangeTheLocalSensor() = runBlocking {
        val sensorBindings = RecordingSensorBindingStore(
            activeFailure = AssertionError("sensor store must not be read"),
        )
        val clock = RecordingClock()

        val result = reconciler(
            credentials = UploadCredentialProvider { CredentialLoadResult.NotProvisioned },
            sensorBindings = sensorBindings,
            clock = clock,
        ).reconcile()

        assertEquals(RemoteSensorBindingReconcileResult.NoCredential, result)
        assertEquals(0, sensorBindings.activeCalls)
        assertEquals(0, sensorBindings.activateRemoteCalls)
        assertEquals(0, clock.calls)
    }

    @Test
    fun savedCredentialWaitsForTheFirstLocalSensorAndIsAlwaysClosed() = runBlocking {
        val credential = runtimeCredential()
        val sensorBindings = RecordingSensorBindingStore(activeValue = null) {
            assertCredentialClosed(credential)
        }
        val clock = RecordingClock()

        val result = reconciler(
            credentials = UploadCredentialProvider {
                CredentialLoadResult.Available(credential)
            },
            sensorBindings = sensorBindings,
            clock = clock,
        ).reconcile()

        assertEquals(RemoteSensorBindingReconcileResult.PendingSensor, result)
        assertCredentialClosed(credential)
        assertEquals(0, sensorBindings.activateRemoteCalls)
        assertEquals(0, clock.calls)
    }

    @Test
    fun matchingRemoteBindingIsAlreadyBoundWithoutReadingTheClock() = runBlocking {
        val metadata = metadata()
        val remote = publicationBinding(metadata, CREATED_AT)
        val active = activeBinding(remote = remote)
        val sensorBindings = RecordingSensorBindingStore(activeValue = active)
        val clock = RecordingClock()

        val result = reconciler(
            credentials = provider(metadata),
            sensorBindings = sensorBindings,
            clock = clock,
        ).reconcile()

        assertEquals(RemoteSensorBindingReconcileResult.AlreadyBound, result)
        assertEquals(0, sensorBindings.activateRemoteCalls)
        assertEquals(0, clock.calls)
    }

    @Test
    fun localBindingBecomesRemoteUsingOnlyCredentialMetadata() = runBlocking {
        val metadata = metadata()
        val active = activeBinding()
        val credential = runtimeCredential(metadata)
        val sensorBindings = RecordingSensorBindingStore(
            activeValue = active,
            activationResult = LocalSensorBindingActivationResult.Activated,
            beforeActive = { assertCredentialClosed(credential) },
        )
        val clock = RecordingClock(NOW)

        val result = reconciler(
            credentials = UploadCredentialProvider {
                CredentialLoadResult.Available(credential)
            },
            sensorBindings = sensorBindings,
            clock = clock,
        ).reconcile()

        val activated = result as RemoteSensorBindingReconcileResult.Bound
        val binding = checkNotNull(sensorBindings.activatedBinding)
        assertEquals(
            binding.remotePublicationBindingId,
            activated.remotePublicationBindingId,
        )
        assertEquals(active.publicationBindingId, binding.publicationBindingId)
        assertEquals(active.approval.approvalId, binding.approvalId)
        assertMetadataEquals(metadata, binding)
        assertEquals(NOW, binding.createdAtEpochMs)
        assertEquals(null, sensorBindings.expectedPreviousRemotePublicationBindingId)
        assertSame(active, sensorBindings.activeValue)
        assertCredentialClosed(credential)
        assertEquals(1, clock.calls)
    }

    @Test
    fun changedCredentialMetadataRotatesTheExistingRemoteBinding() = runBlocking {
        val oldMetadata = metadata(credentialRevision = 1L)
        val newMetadata = metadata(
            credentialId = "credential-2",
            backendBindingId = "backend-2",
            credentialRevision = 2L,
        )
        val oldRemote = publicationBinding(oldMetadata, CREATED_AT)
        val active = activeBinding(remote = oldRemote)
        val sensorBindings = RecordingSensorBindingStore(
            activeValue = active,
            activationResult = LocalSensorBindingActivationResult.Activated,
        )

        val result = reconciler(
            credentials = provider(newMetadata),
            sensorBindings = sensorBindings,
            clock = RecordingClock(NOW),
        ).reconcile()

        val rotated = result as RemoteSensorBindingReconcileResult.Rotated
        val binding = checkNotNull(sensorBindings.activatedBinding)
        assertEquals(
            binding.remotePublicationBindingId,
            rotated.remotePublicationBindingId,
        )
        assertEquals(active.publicationBindingId, binding.publicationBindingId)
        assertMetadataEquals(newMetadata, binding)
        assertEquals(
            oldRemote.remotePublicationBindingId,
            sensorBindings.expectedPreviousRemotePublicationBindingId,
        )
        assertSame(active, sensorBindings.activeValue)
    }

    @Test
    fun activationConflictIsTypedAndLeavesTheExistingSensorBindingUntouched() = runBlocking {
        val active = activeBinding()
        val sensorBindings = RecordingSensorBindingStore(
            activeValue = active,
            activationResult = LocalSensorBindingActivationResult.Conflict("binding changed"),
        )

        val result = reconciler(
            credentials = provider(metadata()),
            sensorBindings = sensorBindings,
            clock = RecordingClock(),
        ).reconcile()

        assertEquals(
            RemoteSensorBindingReconcileResult.Failed(
                RemoteSensorBindingReconcileFailure.BindingConflict("binding changed"),
            ),
            result,
        )
        assertSame(active, sensorBindings.activeValue)
    }

    @Test
    fun concurrentEquivalentActivationIsReReadAsAlreadyBound() = runBlocking {
        val metadata = metadata()
        val concurrentlyActivated = activeBinding(
            remote = publicationBinding(metadata, CREATED_AT),
        )
        lateinit var sensorBindings: RecordingSensorBindingStore
        sensorBindings = RecordingSensorBindingStore(
            activeValue = activeBinding(),
            activationResult = LocalSensorBindingActivationResult.Conflict("binding changed"),
            afterActivate = { sensorBindings.activeValue = concurrentlyActivated },
        )

        val result = reconciler(
            credentials = provider(metadata),
            sensorBindings = sensorBindings,
            clock = RecordingClock(NOW),
        ).reconcile()

        assertEquals(RemoteSensorBindingReconcileResult.AlreadyBound, result)
        assertEquals(2, sensorBindings.activeCalls)
        assertSame(concurrentlyActivated, sensorBindings.activeValue)
    }

    @Test
    fun sensorStorageExceptionIsTypedAndNeverEndsOrReplacesTheLocalBinding() = runBlocking {
        val active = activeBinding()
        val sensorBindings = RecordingSensorBindingStore(
            activeValue = active,
            activationFailure = IllegalStateException("database unavailable"),
        )

        val result = reconciler(
            credentials = provider(metadata()),
            sensorBindings = sensorBindings,
            clock = RecordingClock(),
        ).reconcile()

        assertEquals(
            RemoteSensorBindingReconcileResult.Failed(
                RemoteSensorBindingReconcileFailure.StorageUnavailable(
                    RemoteSensorBindingStorageOperation.Activate,
                ),
            ),
            result,
        )
        assertEquals(0, sensorBindings.endCalls)
        assertSame(active, sensorBindings.activeValue)
    }

    @Test
    fun sensorReadExceptionIsDistinguishedFromActivationStorageFailure() = runBlocking {
        val sensorBindings = RecordingSensorBindingStore(
            activeFailure = IllegalStateException("database unavailable"),
        )
        val clock = RecordingClock()

        val result = reconciler(
            credentials = provider(metadata()),
            sensorBindings = sensorBindings,
            clock = clock,
        ).reconcile()

        assertEquals(
            RemoteSensorBindingReconcileResult.Failed(
                RemoteSensorBindingReconcileFailure.StorageUnavailable(
                    RemoteSensorBindingStorageOperation.ReadSensor,
                ),
            ),
            result,
        )
        assertEquals(0, sensorBindings.activateRemoteCalls)
        assertEquals(0, clock.calls)
    }

    @Test
    fun alreadyActiveRaceIsAStableBoundResult() = runBlocking {
        val sensorBindings = RecordingSensorBindingStore(
            activeValue = activeBinding(),
            activationResult = LocalSensorBindingActivationResult.AlreadyActive,
        )

        val result = reconciler(
            credentials = provider(metadata()),
            sensorBindings = sensorBindings,
            clock = RecordingClock(),
        ).reconcile()

        assertTrue(result is RemoteSensorBindingReconcileResult.Bound)
        assertEquals(1, sensorBindings.activateRemoteCalls)
    }

    @Test
    fun invalidClockValueIsTypedBeforeAnyStorageMutation() = runBlocking {
        val sensorBindings = RecordingSensorBindingStore(activeValue = activeBinding())
        val result = RemoteSensorBindingReconciler(
            credentials = provider(metadata()),
            sensorBindings = sensorBindings,
            nowEpochMs = { 0L },
        ).reconcile()

        assertEquals(
            RemoteSensorBindingReconcileResult.Failed(
                RemoteSensorBindingReconcileFailure.InvalidBinding,
            ),
            result,
        )
        assertEquals(0, sensorBindings.activateRemoteCalls)
    }

    @Test
    fun cancellationAfterCredentialLoadStillClosesTheDecryptedToken() {
        val credential = runtimeCredential()
        val failure = runCatching {
            runBlocking {
                RemoteSensorBindingReconciler(
                    credentials = UploadCredentialProvider {
                        CredentialLoadResult.Available(credential)
                    },
                    sensorBindings = RecordingSensorBindingStore(
                        activeFailure = CancellationException("stopped"),
                    ),
                ).reconcile()
            }
        }.exceptionOrNull()

        assertTrue(failure is CancellationException)
        assertCredentialClosed(credential)
    }

    @Test
    fun everyCredentialLoadFailureIsTypedAndDoesNotReadTheSensor() = runBlocking {
        val cases = listOf(
            CredentialLoadResult.MissingKey to
                RemoteSensorBindingReconcileFailure.CredentialMissingKey,
            CredentialLoadResult.KeyUnavailable to
                RemoteSensorBindingReconcileFailure.CredentialKeyUnavailable,
            CredentialLoadResult.Corrupted to
                RemoteSensorBindingReconcileFailure.CredentialCorrupted,
        )

        cases.forEach { (loadResult, expectedFailure) ->
            val sensorBindings = RecordingSensorBindingStore(
                activeFailure = AssertionError("sensor store must not be read"),
            )
            val result = reconciler(
                credentials = UploadCredentialProvider { loadResult },
                sensorBindings = sensorBindings,
                clock = RecordingClock(),
            ).reconcile()

            assertEquals(RemoteSensorBindingReconcileResult.Failed(expectedFailure), result)
            assertEquals(0, sensorBindings.activeCalls)
        }

        val sensorBindings = RecordingSensorBindingStore(
            activeFailure = AssertionError("sensor store must not be read"),
        )
        val storageResult = reconciler(
            credentials = UploadCredentialProvider {
                throw IllegalStateException("credential storage unavailable")
            },
            sensorBindings = sensorBindings,
            clock = RecordingClock(),
        ).reconcile()
        assertEquals(
            RemoteSensorBindingReconcileResult.Failed(
                RemoteSensorBindingReconcileFailure.StorageUnavailable(
                    RemoteSensorBindingStorageOperation.LoadCredential,
                ),
            ),
            storageResult,
        )
        assertEquals(0, sensorBindings.activeCalls)
    }

    @Test
    fun cancellationIsNeverConvertedIntoARecoverableFailure() {
        val failure = runCatching {
            runBlocking {
                reconciler(
                    credentials = UploadCredentialProvider {
                        throw CancellationException("stopped")
                    },
                    sensorBindings = RecordingSensorBindingStore(),
                    clock = RecordingClock(),
                ).reconcile()
            }
        }.exceptionOrNull()

        assertTrue(failure is CancellationException)
    }

    private fun reconciler(
        credentials: UploadCredentialProvider,
        sensorBindings: LocalSensorBindingStore,
        clock: RecordingClock,
    ) = RemoteSensorBindingReconciler(
        credentials = credentials,
        sensorBindings = sensorBindings,
        nowEpochMs = clock::now,
    )

    private fun provider(
        metadata: RemoteCredentialMetadata,
    ): UploadCredentialProvider = UploadCredentialProvider {
        CredentialLoadResult.Available(runtimeCredential(metadata))
    }

    private fun runtimeCredential(
        metadata: RemoteCredentialMetadata = metadata(),
    ) = RuntimeUploadCredential(
        metadata = metadata,
        bearerToken = SecretBearerToken.fromUtf8(TOKEN.toByteArray()),
    )

    private fun metadata(
        credentialId: String = "credential-1",
        backendBindingId: String = "backend-1",
        credentialRevision: Long = 1L,
    ) = RemoteCredentialMetadata(
        credentialId = credentialId,
        backendBindingId = backendBindingId,
        credentialRevision = credentialRevision,
        expectedPatientId = PATIENT_ID,
        expectedDeviceId = DEVICE_ID,
        httpsOrigin = ORIGIN,
    )

    private fun activeBinding(
        remote: ProductPublicationBindingRecord? = null,
    ): ActiveLocalSensorBinding {
        val approval = approval()
        return ActiveLocalSensorBinding(
            publicationBindingId = remote?.publicationBindingId ?: LOCAL_BINDING_ID,
            approval = approval,
            remotePublicationBinding = remote,
        )
    }

    private fun publicationBinding(
        metadata: RemoteCredentialMetadata,
        createdAtEpochMs: Long,
    ): ProductPublicationBindingRecord = ProductPublicationBindingRecord(
        approvalId = approval().approvalId,
        publicationBindingId = LOCAL_BINDING_ID,
        httpsOrigin = metadata.httpsOrigin,
        backendBindingId = metadata.backendBindingId,
        credentialId = metadata.credentialId,
        credentialRevision = metadata.credentialRevision,
        expectedPatientId = metadata.expectedPatientId,
        expectedDeviceId = metadata.expectedDeviceId,
        createdAtEpochMs = createdAtEpochMs,
    )

    private fun approval() = PhysicalSensorApprovalRecord(
        sensorId = "sensor-approved",
        bluetoothAddress = "AA:BB:CC:DD:EE:22",
        sensorFamily = SensorFamily.SIBIONICS_GS1,
        transportVariant = 2,
        sensitivityToken = "Ab12Cd34",
        wireProfile = "V120",
        transportProtocol = "BLE_GATT",
        transportCodecId = "GS1_PACKET",
        algorithmProfile = "V116A",
        algorithmVersion = "1",
        binarySetId = "official-binary-set",
        sensitivityTokenSource = "PACKAGE_CODE",
        sensitivityCoefficient = 1.0,
        sensitivityEncoding = "NORMAL",
        initializationMode = "STANDARD",
        displayOffsetMmolL = 0.0,
        protocolEvidenceKind = "PHYSICAL_TRACE",
        protocolEvidenceSha256 = "11".repeat(32),
        physicalValidationEvidenceSha256 = "22".repeat(32),
        checkpointSchemaVersion = 1,
        approvedSequence = 10,
        approvedSensorTimeEpochMs = 1_700_000_100_000L,
        sensorStartTimeEpochMs = 1_700_000_000_000L,
        approvedCheckpointStateSha256 = "33".repeat(32),
        nativeBinarySetSha256 = "44".repeat(32),
        nativeDatahandleBinarySetSha256 = "55".repeat(32),
        approvedAtEpochMs = 1_700_000_200_000L,
    )

    private fun assertMetadataEquals(
        metadata: RemoteCredentialMetadata,
        binding: ProductPublicationBindingRecord,
    ) {
        assertEquals(metadata.httpsOrigin, binding.httpsOrigin)
        assertEquals(metadata.backendBindingId, binding.backendBindingId)
        assertEquals(metadata.credentialId, binding.credentialId)
        assertEquals(metadata.credentialRevision, binding.credentialRevision)
        assertEquals(metadata.expectedPatientId, binding.expectedPatientId)
        assertEquals(metadata.expectedDeviceId, binding.expectedDeviceId)
    }

    private fun assertCredentialClosed(credential: RuntimeUploadCredential) {
        val failure = runCatching { credential.bearerToken.useBytes { fail("token is open") } }
            .exceptionOrNull()
        assertTrue(failure is IllegalStateException)
    }

    private class RecordingClock(private val value: Long = NOW) {
        var calls = 0
            private set

        fun now(): Long {
            calls += 1
            return value
        }
    }

    private class RecordingSensorBindingStore(
        var activeValue: ActiveLocalSensorBinding? = null,
        private val activationResult: LocalSensorBindingActivationResult =
            LocalSensorBindingActivationResult.Activated,
        private val activeFailure: Throwable? = null,
        private val activationFailure: Throwable? = null,
        private val beforeActive: () -> Unit = {},
        private val afterActivate: () -> Unit = {},
    ) : LocalSensorBindingStore {
        var activeCalls = 0
            private set
        var activateRemoteCalls = 0
            private set
        var endCalls = 0
            private set
        var activatedBinding: ProductPublicationBindingRecord? = null
            private set
        var expectedPreviousRemotePublicationBindingId: String? = null
            private set

        override suspend fun active(): ActiveLocalSensorBinding? {
            activeCalls += 1
            beforeActive()
            activeFailure?.let { throw it }
            return activeValue
        }

        override suspend fun activate(
            approvalId: String,
            publicationBindingId: String,
            expectedPreviousPublicationBindingId: String?,
        ): LocalSensorBindingActivationResult = error("local activation is outside reconciliation")

        override suspend fun activateRemote(
            binding: ProductPublicationBindingRecord,
            expectedPreviousRemotePublicationBindingId: String?,
        ): LocalSensorBindingActivationResult {
            activateRemoteCalls += 1
            activatedBinding = binding
            this.expectedPreviousRemotePublicationBindingId =
                expectedPreviousRemotePublicationBindingId
            activationFailure?.let { throw it }
            afterActivate()
            return activationResult
        }

        override suspend fun end(
            expectedPublicationBindingId: String,
        ): LocalSensorBindingActivationResult {
            endCalls += 1
            return LocalSensorBindingActivationResult.Activated
        }
    }

    private companion object {
        const val TOKEN = "0123456789abcdef0123456789abcdef"
        const val PATIENT_ID = "00000000-0000-4000-8000-000000000301"
        const val DEVICE_ID = "00000000-0000-4000-8000-000000000201"
        const val ORIGIN = "https://family.example"
        const val CREATED_AT = 1_700_000_300_000L
        const val NOW = 1_800_000_000_000L
        val LOCAL_BINDING_ID = "66".repeat(32)
    }
}
