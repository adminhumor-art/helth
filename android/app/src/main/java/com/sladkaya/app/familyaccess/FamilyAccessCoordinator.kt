package com.sladkaya.app.familyaccess

import com.sladkaya.app.sync.CredentialProvisionResult
import com.sladkaya.app.sync.DeviceActivationCode
import com.sladkaya.app.sync.DeviceProvisioningClient
import com.sladkaya.app.sync.DeviceProvisioningExchangeResult
import com.sladkaya.app.sync.DeviceProvisioningIdentity
import com.sladkaya.app.sync.DeviceProvisioningIdentityLoadResult
import com.sladkaya.app.sync.DeviceProvisioningIdentityProvider
import com.sladkaya.app.sync.RemoteProvisioningPayload
import com.sladkaya.app.sync.RemoteUploadEndpoint
import com.sladkaya.app.sync.RemoteUploadEndpointParseResult
import java.util.Locale
import java.util.concurrent.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex

internal sealed interface FamilyAccessUiState {
    data object LoadingIdentity : FamilyAccessUiState
    data class Ready(val deviceId: String) : FamilyAccessUiState
    data class Connecting(val deviceId: String) : FamilyAccessUiState
    data class Connected(val deviceId: String) : FamilyAccessUiState
    data class Failed(
        val deviceId: String?,
        val reason: FamilyAccessFailure,
    ) : FamilyAccessUiState
}

internal enum class FamilyAccessFailure {
    InvalidHttpsOrigin,
    InvalidActivationCode,
    DeviceSecurityUnavailable,
    DeviceStorageUnavailable,
    DeviceIdentityCorrupted,
    ProvisioningRequestRejected,
    ActivationRejected,
    NetworkUnavailable,
    ServerTemporarilyUnavailable,
    SecureConnectionBlocked,
    ServerResponseInvalid,
    CredentialSecurityUnavailable,
    CredentialStorageUnavailable,
    UnexpectedFailure,
}

/**
 * Integration boundary for saving the one-use server payload. The production adapter delegates
 * directly to RemoteAccessCoordinator.provision(payload).
 */
internal fun interface FamilyAccessProvisioningPort {
    suspend fun provision(payload: RemoteProvisioningPayload): CredentialProvisionResult
}

/**
 * Owns only optional family-server activation. It has no reference to sensor collection, local
 * measurements, alarms or widgets, so those paths cannot become network-dependent.
 */
internal class FamilyAccessCoordinator(
    private val identityProvider: DeviceProvisioningIdentityProvider,
    private val client: DeviceProvisioningClient,
    private val provisioner: FamilyAccessProvisioningPort,
) {
    private val operation = Mutex()
    private var identity: DeviceProvisioningIdentity? = null
    private val mutableState = MutableStateFlow<FamilyAccessUiState>(
        FamilyAccessUiState.LoadingIdentity,
    )
    val state = mutableState.asStateFlow()
    private val mutableInstallationRequest = MutableStateFlow<FamilyInstallationRequest?>(null)
    val installationRequest = mutableInstallationRequest.asStateFlow()

    suspend fun load(): Boolean {
        identity?.let { return true }
        if (!operation.tryLock()) return false
        return try {
            loadIdentity() != null
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: RuntimeException) {
            fail(deviceId = null, FamilyAccessFailure.UnexpectedFailure)
        } finally {
            operation.unlock()
        }
    }

    suspend fun connect(
        originInput: String,
        activationCodeInput: String,
    ): Boolean {
        if (!operation.tryLock()) return false
        var activeIdentity: DeviceProvisioningIdentity? = identity
        return try {
            if (mutableState.value is FamilyAccessUiState.Connected) return false
            if (activeIdentity == null) {
                activeIdentity = loadIdentity() ?: return false
            }
            val verifiedIdentity = checkNotNull(activeIdentity)
            val endpoint = parseEndpoint(originInput)
                ?: return fail(
                    verifiedIdentity.deviceId,
                    FamilyAccessFailure.InvalidHttpsOrigin,
                )
            val activationCode = parseActivationCode(activationCodeInput)
                ?: return fail(
                    verifiedIdentity.deviceId,
                    FamilyAccessFailure.InvalidActivationCode,
                )

            mutableState.value = FamilyAccessUiState.Connecting(verifiedIdentity.deviceId)
            when (val exchange = client.provision(endpoint, activationCode, verifiedIdentity)) {
                is DeviceProvisioningExchangeResult.Provisioned ->
                    persist(exchange.payload, verifiedIdentity.deviceId)
                DeviceProvisioningExchangeResult.MalformedRequest -> fail(
                    verifiedIdentity.deviceId,
                    FamilyAccessFailure.ProvisioningRequestRejected,
                )
                DeviceProvisioningExchangeResult.ActivationRejected -> fail(
                    verifiedIdentity.deviceId,
                    FamilyAccessFailure.ActivationRejected,
                )
                DeviceProvisioningExchangeResult.RetryableNetwork -> fail(
                    verifiedIdentity.deviceId,
                    FamilyAccessFailure.NetworkUnavailable,
                )
                DeviceProvisioningExchangeResult.RetryableServer -> fail(
                    verifiedIdentity.deviceId,
                    FamilyAccessFailure.ServerTemporarilyUnavailable,
                )
                DeviceProvisioningExchangeResult.EndpointBlocked -> fail(
                    verifiedIdentity.deviceId,
                    FamilyAccessFailure.SecureConnectionBlocked,
                )
                DeviceProvisioningExchangeResult.ContractBlocked -> fail(
                    verifiedIdentity.deviceId,
                    FamilyAccessFailure.ServerResponseInvalid,
                )
            }
        } catch (cancelled: CancellationException) {
            activeIdentity?.let { mutableState.value = FamilyAccessUiState.Ready(it.deviceId) }
            throw cancelled
        } catch (_: RuntimeException) {
            fail(activeIdentity?.deviceId, FamilyAccessFailure.UnexpectedFailure)
        } finally {
            operation.unlock()
        }
    }

    private suspend fun loadIdentity(): DeviceProvisioningIdentity? {
        mutableState.value = FamilyAccessUiState.LoadingIdentity
        return when (val result = identityProvider.loadOrCreate()) {
            is DeviceProvisioningIdentityLoadResult.Available -> result.identity.also {
                identity = it
                mutableInstallationRequest.value = FamilyInstallationRequestCodec.encode(it)
                mutableState.value = FamilyAccessUiState.Ready(it.deviceId)
            }
            DeviceProvisioningIdentityLoadResult.KeyUnavailable -> {
                fail(null, FamilyAccessFailure.DeviceSecurityUnavailable)
                null
            }
            DeviceProvisioningIdentityLoadResult.StorageUnavailable -> {
                fail(null, FamilyAccessFailure.DeviceStorageUnavailable)
                null
            }
            DeviceProvisioningIdentityLoadResult.Corrupted -> {
                fail(null, FamilyAccessFailure.DeviceIdentityCorrupted)
                null
            }
        }
    }

    private suspend fun persist(
        payload: RemoteProvisioningPayload,
        deviceId: String,
    ): Boolean = try {
        when (provisioner.provision(payload)) {
            CredentialProvisionResult.Provisioned -> {
                mutableState.value = FamilyAccessUiState.Connected(deviceId)
                true
            }
            CredentialProvisionResult.KeyUnavailable -> fail(
                deviceId,
                FamilyAccessFailure.CredentialSecurityUnavailable,
            )
            CredentialProvisionResult.StorageUnavailable -> fail(
                deviceId,
                FamilyAccessFailure.CredentialStorageUnavailable,
            )
        }
    } finally {
        payload.close()
    }

    private fun parseEndpoint(candidate: String): RemoteUploadEndpoint? =
        when (val parsed = RemoteUploadEndpoint.parse(candidate.trim())) {
            is RemoteUploadEndpointParseResult.Valid -> parsed.endpoint
            RemoteUploadEndpointParseResult.Invalid -> null
        }

    private fun parseActivationCode(candidate: String): DeviceActivationCode? =
        try {
            DeviceActivationCode.require(candidate.trim().uppercase(Locale.ROOT))
        } catch (_: IllegalArgumentException) {
            null
        }

    private fun fail(deviceId: String?, reason: FamilyAccessFailure): Boolean {
        mutableState.value = FamilyAccessUiState.Failed(deviceId, reason)
        return false
    }
}
