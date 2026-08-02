package com.sladkaya.app.familyaccess

import android.content.Context
import com.sladkaya.app.sync.AndroidKeystoreCredentialVault
import com.sladkaya.app.sync.AndroidKeystoreDeviceProvisioningIdentityProvider
import com.sladkaya.app.sync.BlockedUploadRecoveryPort
import com.sladkaya.app.sync.CredentialProvisionResult
import com.sladkaya.app.sync.HttpsDeviceProvisioningClient
import com.sladkaya.app.sync.RemoteAccessCoordinator
import com.sladkaya.app.sync.RemoteCredentialMetadata
import com.sladkaya.app.sync.RemoteProvisioningPayload
import com.sladkaya.app.sync.RemoteSensorBindingReconciler
import com.sladkaya.core.data.LocalSensorBindingRepository

/** Version one starts with no blocked uploads; this explicit fail-safe remains replaceable. */
internal data object V1NoBlockedUploadRecovery : BlockedUploadRecoveryPort {
    override suspend fun requeueBlocked(metadata: RemoteCredentialMetadata) = Unit
}

/** Production adapter that owns only durable credential provisioning, never sensor lifecycle. */
internal class RemoteAccessFamilyAccessProvisioningPort internal constructor(
    private val provisionCredential: suspend (RemoteProvisioningPayload) -> CredentialProvisionResult,
) : FamilyAccessProvisioningPort {
    constructor(remoteAccess: RemoteAccessCoordinator) : this(remoteAccess::provision)

    override suspend fun provision(
        payload: RemoteProvisioningPayload,
    ): CredentialProvisionResult = provisionCredential(payload)
}

/** Entry points used independently by the activity and the service-owned session transition. */
internal object ProductionFamilyAccessFactory {
    fun createCoordinator(context: Context): FamilyAccessCoordinator {
        val applicationContext = context.applicationContext
        return FamilyAccessCoordinator(
            identityProvider = AndroidKeystoreDeviceProvisioningIdentityProvider(applicationContext),
            client = HttpsDeviceProvisioningClient(),
            provisioner = RemoteAccessFamilyAccessProvisioningPort(
                RemoteAccessCoordinator(
                    context = applicationContext,
                    blockedUploadRecovery = V1NoBlockedUploadRecovery,
                ),
            ),
        )
    }

    fun createSensorBindingReconciler(context: Context): RemoteSensorBindingReconciler {
        val applicationContext = context.applicationContext
        return RemoteSensorBindingReconciler(
            credentials = AndroidKeystoreCredentialVault(applicationContext),
            sensorBindings = LocalSensorBindingRepository.create(applicationContext),
        )
    }
}
