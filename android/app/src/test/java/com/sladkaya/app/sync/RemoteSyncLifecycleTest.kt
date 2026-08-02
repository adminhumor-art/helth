package com.sladkaya.app.sync

import java.util.concurrent.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class RemoteSyncLifecycleTest {
    @Test
    fun everyProcessStartReconcilesTheUniquePeriodicWorkWithoutStartingADrain() {
        val scheduler = RecordingScheduler()

        assertEquals(
            RemoteSyncScheduleResult.Requested,
            RemoteSyncProcessLifecycle(scheduler).onProcessStarted(),
        )
        assertEquals(
            RemoteSyncScheduleResult.Requested,
            RemoteSyncProcessLifecycle(scheduler).onProcessStarted(),
        )

        assertEquals(2, scheduler.periodicRequests)
        assertEquals(0, scheduler.drainRequests)
    }

    @Test
    fun unavailableWorkManagerCannotCrashProcessStartup() {
        val scheduler = RecordingScheduler(failScheduling = true)

        assertEquals(
            RemoteSyncScheduleResult.Deferred,
            RemoteSyncProcessLifecycle(scheduler).onProcessStarted(),
        )
    }

    @Test
    fun unavailableSchedulerFactoryCannotCrashProcessStartupOrPostCommit() {
        val unavailableFactory = { throw IllegalStateException("test factory failure") }

        assertEquals(
            RemoteSyncScheduleResult.Deferred,
            RemoteSyncProcessLifecycle(unavailableFactory).onProcessStarted(),
        )
        assertEquals(
            RemoteSyncScheduleResult.Deferred,
            RemoteUploadAfterPublicationHook(unavailableFactory).onProductPublicationCommitted(),
        )
    }

    @Test
    fun committedProductPublicationUsesTheExplicitDrainHook() {
        val scheduler = RecordingScheduler()

        assertEquals(
            RemoteSyncScheduleResult.Requested,
            RemoteUploadAfterPublicationHook(scheduler).onProductPublicationCommitted(),
        )

        assertEquals(1, scheduler.drainRequests)
        assertEquals(0, scheduler.periodicRequests)
    }

    @Test
    fun unavailableWorkManagerCannotBreakAnAtomicProductPublication() {
        val scheduler = RecordingScheduler(failScheduling = true)

        assertEquals(
            RemoteSyncScheduleResult.Deferred,
            RemoteUploadAfterPublicationHook(scheduler).onProductPublicationCommitted(),
        )
    }

    @Test
    fun successfulProvisionRequeuesBlockedUploadsBeforeRequestingDrain() {
        runBlocking {
            val calls = mutableListOf<String>()
            val scheduler = RecordingScheduler(calls = calls)
            val vault = RecordingMutationVault(calls = calls)
            val recovery = RecordingBlockedUploadRecovery(eventLog = calls)
            val coordinator = RemoteAccessCoordinator(vault, recovery, scheduler)
            val source = bearerBytes()
            val payload = RemoteProvisioningPayload.capture(metadata(), source)

            assertTrue(source.all { it == 0.toByte() })
            assertEquals(CredentialProvisionResult.Provisioned, coordinator.provision(payload))
            assertTrue(vault.persistedBeforeDrain)
            assertEquals(listOf(metadata()), recovery.metadata)
            assertEquals(listOf("persist", "requeue", "drain"), calls)
            assertEquals(1, scheduler.drainRequests)
            assertEquals(0, scheduler.periodicRequests)
            assertThrows(IllegalStateException::class.java) {
                checkNotNull(vault.receivedToken).useBytes { }
            }
        }
    }

    @Test
    fun failedProvisionStillConsumesSecretAndDoesNotRequestDrain() {
        runBlocking {
            val scheduler = RecordingScheduler()
            val vault = RecordingMutationVault(provisionResult = CredentialProvisionResult.StorageUnavailable)
            val recovery = RecordingBlockedUploadRecovery()
            val coordinator = RemoteAccessCoordinator(vault, recovery, scheduler)

            assertEquals(
                CredentialProvisionResult.StorageUnavailable,
                coordinator.provision(RemoteProvisioningPayload.capture(metadata(), bearerBytes())),
            )
            assertEquals(0, recovery.calls)
            assertEquals(0, scheduler.drainRequests)
            assertThrows(IllegalStateException::class.java) {
                checkNotNull(vault.receivedToken).useBytes { }
            }
        }
    }

    @Test
    fun successfulPersistenceRemainsSuccessfulWhenImmediateDrainCannotBeScheduled() {
        runBlocking {
            val scheduler = RecordingScheduler(failScheduling = true)
            val vault = RecordingMutationVault()
            val recovery = RecordingBlockedUploadRecovery()

            assertEquals(
                CredentialProvisionResult.Provisioned,
                RemoteAccessCoordinator(vault, recovery, scheduler).provision(
                    RemoteProvisioningPayload.capture(metadata(), bearerBytes()),
                ),
            )
            assertTrue(vault.persistedBeforeDrain)
            assertEquals(1, recovery.calls)
            assertThrows(IllegalStateException::class.java) {
                checkNotNull(vault.receivedToken).useBytes { }
            }
        }
    }

    @Test
    fun recoveryFailureCannotUndoProvisioningAndDrainIsStillRequestedAfterTheAttempt() {
        runBlocking {
            val calls = mutableListOf<String>()
            val scheduler = RecordingScheduler(calls = calls)
            val vault = RecordingMutationVault(calls = calls)
            val recovery = RecordingBlockedUploadRecovery(failRecovery = true, eventLog = calls)

            assertEquals(
                CredentialProvisionResult.Provisioned,
                RemoteAccessCoordinator(vault, recovery, scheduler).provision(
                    RemoteProvisioningPayload.capture(metadata(), bearerBytes()),
                ),
            )

            assertEquals(listOf("persist", "requeue", "drain"), calls)
            assertEquals(1, scheduler.drainRequests)
        }
    }

    @Test
    fun successfulPersistenceSurvivesUnavailableSchedulerFactory() {
        runBlocking {
            val vault = RecordingMutationVault()
            val recovery = RecordingBlockedUploadRecovery()
            val coordinator = RemoteAccessCoordinator(vault, recovery) {
                throw IllegalStateException("test factory failure")
            }

            assertEquals(
                CredentialProvisionResult.Provisioned,
                coordinator.provision(
                    RemoteProvisioningPayload.capture(metadata(), bearerBytes()),
                ),
            )
            assertTrue(vault.persistedBeforeDrain)
            assertEquals(1, recovery.calls)
            assertThrows(IllegalStateException::class.java) {
                checkNotNull(vault.receivedToken).useBytes { }
            }
        }
    }

    @Test
    fun cancellationFromPostProvisionSchedulingIsNeverSwallowed() {
        val vault = RecordingMutationVault()
        val recovery = RecordingBlockedUploadRecovery()
        val coordinator = RemoteAccessCoordinator(vault, recovery) {
            throw CancellationException("test cancellation")
        }

        assertThrows(CancellationException::class.java) {
            runBlocking {
                coordinator.provision(
                    RemoteProvisioningPayload.capture(metadata(), bearerBytes()),
                )
            }
        }
        assertTrue(vault.persistedBeforeDrain)
        assertEquals(1, recovery.calls)
        assertThrows(IllegalStateException::class.java) {
            checkNotNull(vault.receivedToken).useBytes { }
        }
    }

    @Test
    fun cancellationFromBlockedRecoveryIsRethrownAndDrainIsNotRequested() {
        val scheduler = RecordingScheduler()
        val vault = RecordingMutationVault()
        val recovery = RecordingBlockedUploadRecovery(cancelRecovery = true)
        val coordinator = RemoteAccessCoordinator(vault, recovery, scheduler)

        assertThrows(CancellationException::class.java) {
            runBlocking {
                coordinator.provision(
                    RemoteProvisioningPayload.capture(metadata(), bearerBytes()),
                )
            }
        }

        assertTrue(vault.persistedBeforeDrain)
        assertEquals(1, recovery.calls)
        assertEquals(0, scheduler.drainRequests)
    }

    @Test
    fun cancellationIsRethrownAfterSecretIsConsumedAndNeverSchedulesWork() {
        val scheduler = RecordingScheduler()
        val vault = RecordingMutationVault(cancelProvision = true)
        val recovery = RecordingBlockedUploadRecovery()
        val coordinator = RemoteAccessCoordinator(vault, recovery, scheduler)

        assertThrows(CancellationException::class.java) {
            runBlocking {
                coordinator.provision(
                    RemoteProvisioningPayload.capture(metadata(), bearerBytes()),
                )
            }
        }
        assertEquals(0, recovery.calls)
        assertEquals(0, scheduler.drainRequests)
        assertThrows(IllegalStateException::class.java) {
            checkNotNull(vault.receivedToken).useBytes { }
        }
    }

    @Test
    fun revokeDoesNotStartNetworkWorkAndPropagatesCancellation() {
        runBlocking {
            val scheduler = RecordingScheduler()
            val recovery = RecordingBlockedUploadRecovery()
            val successfulVault = RecordingMutationVault()

            assertEquals(
                CredentialRevokeResult.Revoked,
                RemoteAccessCoordinator(successfulVault, recovery, scheduler).revoke(),
            )
            assertEquals(1, successfulVault.revokeCalls)
            assertEquals(0, recovery.calls)
            assertEquals(0, scheduler.drainRequests)

            val cancelledVault = RecordingMutationVault(cancelRevoke = true)
            assertThrows(CancellationException::class.java) {
                runBlocking {
                    RemoteAccessCoordinator(cancelledVault, recovery, scheduler).revoke()
                }
            }
        }
    }

    @Test
    fun payloadNeverRendersIdentityOrSecretAndCannotBeReused() {
        runBlocking {
            val payload = RemoteProvisioningPayload.capture(metadata(), bearerBytes())
            val rendered = payload.toString()

            assertFalse(rendered.contains("family.example"))
            assertFalse(rendered.contains("credential-1"))
            assertFalse(rendered.contains("0123456789abcdef"))

            val coordinator = RemoteAccessCoordinator(
                RecordingMutationVault(),
                RecordingBlockedUploadRecovery(),
                RecordingScheduler(),
            )
            coordinator.provision(payload)
            assertThrows(IllegalStateException::class.java) {
                runBlocking { coordinator.provision(payload) }
            }
        }
    }

    @Test
    fun rejectedBearerInputIsAlsoErasedAtTheApiBoundary() {
        val invalidSource = ByteArray(31) { 'x'.code.toByte() }

        assertThrows(IllegalArgumentException::class.java) {
            RemoteProvisioningPayload.capture(metadata(), invalidSource)
        }

        assertTrue(invalidSource.all { it == 0.toByte() })
    }

    private fun metadata() = RemoteCredentialMetadata(
        credentialId = "credential-1",
        backendBindingId = "backend-1",
        credentialRevision = 2,
        expectedPatientId = "00000000-0000-4000-8000-000000000001",
        expectedDeviceId = "00000000-0000-4000-8000-000000000201",
        httpsOrigin = "https://family.example",
    )

    private fun bearerBytes() = "0123456789abcdef0123456789abcdef".toByteArray()

    private class RecordingScheduler(
        private val failScheduling: Boolean = false,
        private val calls: MutableList<String>? = null,
    ) : RemoteUploadWorkScheduler {
        var drainRequests = 0
        var periodicRequests = 0

        override fun requestDrain() {
            calls?.add("drain")
            if (failScheduling) throw IllegalStateException("test scheduler failure")
            drainRequests += 1
        }

        override fun ensurePeriodicReconciliation() {
            if (failScheduling) throw IllegalStateException("test scheduler failure")
            periodicRequests += 1
        }
    }

    private class RecordingMutationVault(
        private val provisionResult: CredentialProvisionResult = CredentialProvisionResult.Provisioned,
        private val cancelProvision: Boolean = false,
        private val cancelRevoke: Boolean = false,
        private val calls: MutableList<String>? = null,
    ) : RemoteCredentialMutationVault {
        var receivedToken: SecretBearerToken? = null
        var persistedBeforeDrain = false
        var revokeCalls = 0

        override suspend fun provision(
            metadata: RemoteCredentialMetadata,
            token: SecretBearerToken,
        ): CredentialProvisionResult {
            receivedToken = token
            if (cancelProvision) throw CancellationException("test cancellation")
            persistedBeforeDrain = provisionResult == CredentialProvisionResult.Provisioned
            if (persistedBeforeDrain) calls?.add("persist")
            return provisionResult
        }

        override suspend fun revoke(): CredentialRevokeResult {
            revokeCalls += 1
            if (cancelRevoke) throw CancellationException("test cancellation")
            return CredentialRevokeResult.Revoked
        }
    }

    private class RecordingBlockedUploadRecovery(
        private val failRecovery: Boolean = false,
        private val cancelRecovery: Boolean = false,
        private val eventLog: MutableList<String>? = null,
    ) : BlockedUploadRecoveryPort {
        var calls = 0
        val metadata = mutableListOf<RemoteCredentialMetadata>()

        override suspend fun requeueBlocked(metadata: RemoteCredentialMetadata) {
            calls += 1
            this.metadata += metadata
            eventLog?.add("requeue")
            if (cancelRecovery) throw CancellationException("test cancellation")
            if (failRecovery) throw IllegalStateException("test recovery failure")
        }
    }
}
