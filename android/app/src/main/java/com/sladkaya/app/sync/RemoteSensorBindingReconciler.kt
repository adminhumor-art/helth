package com.sladkaya.app.sync

import com.sladkaya.core.data.LocalSensorBindingActivationResult
import com.sladkaya.core.data.LocalSensorBindingStore
import com.sladkaya.core.data.ProductPublicationBindingRecord
import kotlinx.coroutines.CancellationException

internal enum class RemoteSensorBindingStorageOperation {
    LoadCredential,
    ReadSensor,
    Activate,
}

internal sealed interface RemoteSensorBindingReconcileFailure {
    data object CredentialMissingKey : RemoteSensorBindingReconcileFailure
    data object CredentialKeyUnavailable : RemoteSensorBindingReconcileFailure
    data object CredentialCorrupted : RemoteSensorBindingReconcileFailure
    data class StorageUnavailable(
        val operation: RemoteSensorBindingStorageOperation,
    ) : RemoteSensorBindingReconcileFailure

    data class BindingConflict(val reason: String) : RemoteSensorBindingReconcileFailure
    data object InvalidBinding : RemoteSensorBindingReconcileFailure
}

internal sealed interface RemoteSensorBindingReconcileResult {
    /** No credential is stored; reconciliation leaves any current local state unchanged. */
    data object NoCredential : RemoteSensorBindingReconcileResult

    /** The credential is durable and will be retried after a local sensor is approved. */
    data object PendingSensor : RemoteSensorBindingReconcileResult

    data object AlreadyBound : RemoteSensorBindingReconcileResult
    data class Bound(
        val remotePublicationBindingId: String,
    ) : RemoteSensorBindingReconcileResult
    data class Rotated(
        val remotePublicationBindingId: String,
    ) : RemoteSensorBindingReconcileResult
    data class Failed(
        val failure: RemoteSensorBindingReconcileFailure,
    ) : RemoteSensorBindingReconcileResult
}

/**
 * Reconciles durable remote credential metadata with an already-approved local sensor.
 *
 * This performs no network I/O and changes only the independent remote route. The stable local
 * publication binding used by collection, history, alarms and widgets is never replaced.
 */
internal class RemoteSensorBindingReconciler(
    private val credentials: UploadCredentialProvider,
    private val sensorBindings: LocalSensorBindingStore,
    private val nowEpochMs: () -> Long = System::currentTimeMillis,
) {
    suspend fun reconcile(): RemoteSensorBindingReconcileResult {
        val loaded = try {
            credentials.load()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: RuntimeException) {
            return failedStorage(RemoteSensorBindingStorageOperation.LoadCredential)
        }
        val metadata = when (loaded) {
            is CredentialLoadResult.Available -> loaded.credential.use { it.metadata }
            CredentialLoadResult.NotProvisioned ->
                return RemoteSensorBindingReconcileResult.NoCredential
            CredentialLoadResult.MissingKey ->
                return failed(RemoteSensorBindingReconcileFailure.CredentialMissingKey)
            CredentialLoadResult.KeyUnavailable ->
                return failed(RemoteSensorBindingReconcileFailure.CredentialKeyUnavailable)
            CredentialLoadResult.Corrupted ->
                return failed(RemoteSensorBindingReconcileFailure.CredentialCorrupted)
        }

        val active = try {
            sensorBindings.active()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: RuntimeException) {
            return failedStorage(RemoteSensorBindingStorageOperation.ReadSensor)
        } ?: return RemoteSensorBindingReconcileResult.PendingSensor

        val previousRemote = active.remotePublicationBinding
        if (previousRemote?.matches(metadata) == true) {
            return RemoteSensorBindingReconcileResult.AlreadyBound
        }

        val binding = try {
            ProductPublicationBindingRecord(
                approvalId = active.approval.approvalId,
                publicationBindingId = active.publicationBindingId,
                httpsOrigin = metadata.httpsOrigin,
                backendBindingId = metadata.backendBindingId,
                credentialId = metadata.credentialId,
                credentialRevision = metadata.credentialRevision,
                expectedPatientId = metadata.expectedPatientId,
                expectedDeviceId = metadata.expectedDeviceId,
                createdAtEpochMs = nowEpochMs(),
            )
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: RuntimeException) {
            return failed(RemoteSensorBindingReconcileFailure.InvalidBinding)
        }

        val activation = try {
            sensorBindings.activateRemote(
                binding = binding,
                expectedPreviousRemotePublicationBindingId =
                    previousRemote?.remotePublicationBindingId,
            )
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: RuntimeException) {
            return failedStorage(RemoteSensorBindingStorageOperation.Activate)
        }
        return when (activation) {
            LocalSensorBindingActivationResult.Activated,
            LocalSensorBindingActivationResult.AlreadyActive,
            -> if (previousRemote == null) {
                RemoteSensorBindingReconcileResult.Bound(
                    binding.remotePublicationBindingId,
                )
            } else {
                RemoteSensorBindingReconcileResult.Rotated(
                    binding.remotePublicationBindingId,
                )
            }
            is LocalSensorBindingActivationResult.Conflict -> {
                val refreshed = try {
                    sensorBindings.active()
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: RuntimeException) {
                    return failedStorage(RemoteSensorBindingStorageOperation.ReadSensor)
                }
                if (refreshed?.remotePublicationBinding?.matches(metadata) == true) {
                    RemoteSensorBindingReconcileResult.AlreadyBound
                } else {
                    failed(RemoteSensorBindingReconcileFailure.BindingConflict(activation.reason))
                }
            }
        }
    }

    private fun ProductPublicationBindingRecord.matches(
        metadata: RemoteCredentialMetadata,
    ): Boolean = httpsOrigin == metadata.httpsOrigin &&
        backendBindingId == metadata.backendBindingId &&
        credentialId == metadata.credentialId &&
        credentialRevision == metadata.credentialRevision &&
        expectedPatientId == metadata.expectedPatientId &&
        expectedDeviceId == metadata.expectedDeviceId

    private fun failed(
        failure: RemoteSensorBindingReconcileFailure,
    ) = RemoteSensorBindingReconcileResult.Failed(failure)

    private fun failedStorage(
        operation: RemoteSensorBindingStorageOperation,
    ) = failed(RemoteSensorBindingReconcileFailure.StorageUnavailable(operation))
}
