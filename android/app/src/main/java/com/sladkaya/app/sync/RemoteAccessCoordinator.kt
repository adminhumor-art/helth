package com.sladkaya.app.sync

import android.content.Context
import androidx.work.WorkManager
import kotlinx.coroutines.CancellationException

internal interface RemoteCredentialMutationVault {
    suspend fun provision(
        metadata: RemoteCredentialMetadata,
        token: SecretBearerToken,
    ): CredentialProvisionResult

    suspend fun revoke(): CredentialRevokeResult
}

/** Requeues durable uploads blocked against the exact credential tuple that was provisioned. */
fun interface BlockedUploadRecoveryPort {
    suspend fun requeueBlocked(metadata: RemoteCredentialMetadata)
}

sealed interface RemoteSyncScheduleResult {
    data object Requested : RemoteSyncScheduleResult
    data object Deferred : RemoteSyncScheduleResult
}

/**
 * Internal one-use transport for provisioning material. [DeviceProvisioningResponsePolicy]
 * verifies the exact HTTPS origin and installation identity before calling [capture].
 * Source bytes and the owned token are erased even when persistence is cancelled or fails.
 */
internal class RemoteProvisioningPayload private constructor(
    val metadata: RemoteCredentialMetadata,
    private var token: SecretBearerToken?,
) : AutoCloseable {
    internal suspend fun <T> consumeToken(block: suspend (SecretBearerToken) -> T): T {
        val ownedToken = synchronized(this) {
            checkNotNull(token) { "Provisioning payload is already consumed" }.also { token = null }
        }
        return ownedToken.use { block(it) }
    }

    override fun close() {
        val ownedToken = synchronized(this) {
            token.also { token = null }
        }
        ownedToken?.close()
    }

    override fun toString(): String = "RemoteProvisioningPayload([REDACTED])"

    companion object {
        internal fun capture(
            metadata: RemoteCredentialMetadata,
            bearerTokenUtf8: ByteArray,
        ): RemoteProvisioningPayload = try {
            RemoteProvisioningPayload(
                metadata = metadata,
                token = SecretBearerToken.fromUtf8(bearerTokenUtf8),
            )
        } finally {
            bearerTokenUtf8.fill(0)
        }
    }
}

/** Saves or revokes remote access; it never parses product codes or performs network I/O. */
internal class RemoteAccessCoordinator internal constructor(
    private val vault: RemoteCredentialMutationVault,
    private val blockedUploadRecovery: BlockedUploadRecoveryPort,
    private val requestDrain: () -> Unit,
) {
    internal constructor(
        vault: RemoteCredentialMutationVault,
        blockedUploadRecovery: BlockedUploadRecoveryPort,
        scheduler: RemoteUploadWorkScheduler,
    ) : this(vault, blockedUploadRecovery, scheduler::requestDrain)

    constructor(
        context: Context,
        blockedUploadRecovery: BlockedUploadRecoveryPort,
    ) : this(
        vault = AndroidKeystoreCredentialVault(context.applicationContext),
        blockedUploadRecovery = blockedUploadRecovery,
        requestDrain = {
            WorkManagerRemoteUploadScheduler(WorkManager.getInstance(context.applicationContext))
                .requestDrain()
        },
    )

    suspend fun provision(payload: RemoteProvisioningPayload): CredentialProvisionResult {
        val result = payload.consumeToken { token ->
            vault.provision(payload.metadata, token)
        }
        if (result == CredentialProvisionResult.Provisioned) {
            recoverBlockedAfterSuccessfulProvision(blockedUploadRecovery, payload.metadata)
            requestAfterSuccessfulProvision(requestDrain)
        }
        return result
    }

    suspend fun revoke(): CredentialRevokeResult = vault.revoke()
}

/** Called only after an atomic product measurement/outbox commit has succeeded. */
fun interface ProductPublicationCommittedPort {
    fun onProductPublicationCommitted(): RemoteSyncScheduleResult
}

/** The WorkManager implementation of the post-commit integration port. */
class RemoteUploadAfterPublicationHook internal constructor(
    private val requestDrain: () -> Unit,
) : ProductPublicationCommittedPort {
    internal constructor(scheduler: RemoteUploadWorkScheduler) : this(scheduler::requestDrain)

    constructor(context: Context) : this(
        requestDrain = {
            WorkManagerRemoteUploadScheduler(WorkManager.getInstance(context.applicationContext))
                .requestDrain()
        },
    )

    override fun onProductPublicationCommitted(): RemoteSyncScheduleResult =
        requestWithoutBreakingLocalOperation(requestDrain)
}

internal class RemoteSyncProcessLifecycle(
    private val ensurePeriodicReconciliation: () -> Unit,
) {
    constructor(scheduler: RemoteUploadWorkScheduler) : this(scheduler::ensurePeriodicReconciliation)

    fun onProcessStarted(): RemoteSyncScheduleResult =
        requestWithoutBreakingLocalOperation(ensurePeriodicReconciliation)

    companion object {
        fun create(context: Context): RemoteSyncProcessLifecycle = RemoteSyncProcessLifecycle {
            WorkManagerRemoteUploadScheduler(WorkManager.getInstance(context.applicationContext))
                .ensurePeriodicReconciliation()
        }
    }
}

private inline fun requestWithoutBreakingLocalOperation(
    request: () -> Unit,
): RemoteSyncScheduleResult = try {
    request()
    RemoteSyncScheduleResult.Requested
} catch (_: RuntimeException) {
    RemoteSyncScheduleResult.Deferred
}

private inline fun requestAfterSuccessfulProvision(request: () -> Unit) {
    try {
        request()
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: RuntimeException) {
        // Periodic reconciliation on the next healthy process start will retry scheduling.
    }
}

private suspend fun recoverBlockedAfterSuccessfulProvision(
    recovery: BlockedUploadRecoveryPort,
    metadata: RemoteCredentialMetadata,
) {
    try {
        recovery.requeueBlocked(metadata)
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: RuntimeException) {
        // Credential persistence is already committed. A recovery failure must not break local use;
        // the drain is still requested so unrelated pending uploads can make progress.
    }
}
