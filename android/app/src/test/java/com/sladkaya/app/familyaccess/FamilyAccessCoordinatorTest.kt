package com.sladkaya.app.familyaccess

import com.sladkaya.app.sync.CredentialProvisionResult
import com.sladkaya.app.sync.DeviceActivationCode
import com.sladkaya.app.sync.DeviceProvisioningClient
import com.sladkaya.app.sync.DeviceProvisioningExchangeResult
import com.sladkaya.app.sync.DeviceProvisioningIdentity
import com.sladkaya.app.sync.DeviceProvisioningIdentityLoadResult
import com.sladkaya.app.sync.DeviceProvisioningIdentityProvider
import com.sladkaya.app.sync.RemoteCredentialMetadata
import com.sladkaya.app.sync.RemoteProvisioningPayload
import com.sladkaya.app.sync.RemoteUploadEndpoint
import java.util.concurrent.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class FamilyAccessCoordinatorTest {
    @Test
    fun loadShowsOnlyTheStableDeviceIdAndNeverPublishesTheNonce() = runBlocking {
        val provider = FakeIdentityProvider(DeviceProvisioningIdentityLoadResult.Available(IDENTITY))
        val coordinator = coordinator(identityProvider = provider)

        assertTrue(coordinator.load())

        assertEquals(FamilyAccessUiState.Ready(IDENTITY.deviceId), coordinator.state.value)
        val installationRequest = FamilyInstallationRequestCodec.encode(IDENTITY)
        assertEquals(installationRequest, coordinator.installationRequest.value)
        assertFalse(coordinator.state.value.toString().contains(IDENTITY.deviceNonce))
        assertFalse(coordinator.state.value.toString().contains(installationRequest.value))
        assertEquals(1, provider.calls)

        assertTrue(coordinator.load())
        assertEquals(1, provider.calls)
    }

    @Test
    fun everyIdentityFailureIsFailClosedAndNeverCallsTheNetwork() = runBlocking {
        val cases = listOf(
            DeviceProvisioningIdentityLoadResult.KeyUnavailable to
                FamilyAccessFailure.DeviceSecurityUnavailable,
            DeviceProvisioningIdentityLoadResult.StorageUnavailable to
                FamilyAccessFailure.DeviceStorageUnavailable,
            DeviceProvisioningIdentityLoadResult.Corrupted to
                FamilyAccessFailure.DeviceIdentityCorrupted,
        )

        cases.forEach { (loadResult, expectedFailure) ->
            val client = FakeClient()
            val coordinator = coordinator(
                identityProvider = FakeIdentityProvider(loadResult),
                client = client,
            )

            assertFalse(coordinator.load())
            assertEquals(
                FamilyAccessUiState.Failed(deviceId = null, reason = expectedFailure),
                coordinator.state.value,
            )
            assertFalse(coordinator.connect(ORIGIN, CODE))
            assertEquals(0, client.calls)
        }
    }

    @Test
    fun invalidOriginAndCodeNeverReachTheProvisioningClient() = runBlocking {
        val client = FakeClient()
        val coordinator = coordinator(client = client)
        coordinator.load()

        assertFalse(coordinator.connect("http://family.example.test", CODE))
        assertEquals(
            FamilyAccessUiState.Failed(
                IDENTITY.deviceId,
                FamilyAccessFailure.InvalidHttpsOrigin,
            ),
            coordinator.state.value,
        )

        assertFalse(coordinator.connect(ORIGIN, "SLK1-TOO-SHORT"))
        assertEquals(
            FamilyAccessUiState.Failed(
                IDENTITY.deviceId,
                FamilyAccessFailure.InvalidActivationCode,
            ),
            coordinator.state.value,
        )
        assertEquals(0, client.calls)
    }

    @Test
    fun validInputIsNormalizedAndSuccessfulPayloadIsForwardedThenClosed() = runBlocking {
        val payload = payload()
        val client = FakeClient(DeviceProvisioningExchangeResult.Provisioned(payload))
        var received: RemoteProvisioningPayload? = null
        val coordinator = coordinator(
            client = client,
            provisioner = FamilyAccessProvisioningPort {
                received = it
                CredentialProvisionResult.Provisioned
            },
        )

        assertTrue(coordinator.connect("  $ORIGIN  ", "  ${CODE.lowercase()}  "))

        assertSame(payload, received)
        assertEquals(ORIGIN, client.endpoint?.origin)
        assertEquals(CODE, client.activationCode?.value)
        assertEquals(IDENTITY, client.identity)
        assertEquals(FamilyAccessUiState.Connected(IDENTITY.deviceId), coordinator.state.value)
        assertPayloadClosed(payload)
    }

    @Test
    fun anEstablishedConnectionCannotBeSubmittedASecondTime() = runBlocking {
        val payload = payload()
        val client = FakeClient(DeviceProvisioningExchangeResult.Provisioned(payload))
        val coordinator = coordinator(client = client)

        assertTrue(coordinator.connect(ORIGIN, CODE))
        assertFalse(coordinator.connect(ORIGIN, CODE))

        assertEquals(1, client.calls)
        assertEquals(FamilyAccessUiState.Connected(IDENTITY.deviceId), coordinator.state.value)
    }

    @Test
    fun everyServerAndTransportOutcomeHasAnExplicitUserFacingFailure() = runBlocking {
        val cases = listOf(
            DeviceProvisioningExchangeResult.MalformedRequest to
                FamilyAccessFailure.ProvisioningRequestRejected,
            DeviceProvisioningExchangeResult.ActivationRejected to
                FamilyAccessFailure.ActivationRejected,
            DeviceProvisioningExchangeResult.RetryableNetwork to
                FamilyAccessFailure.NetworkUnavailable,
            DeviceProvisioningExchangeResult.RetryableServer to
                FamilyAccessFailure.ServerTemporarilyUnavailable,
            DeviceProvisioningExchangeResult.EndpointBlocked to
                FamilyAccessFailure.SecureConnectionBlocked,
            DeviceProvisioningExchangeResult.ContractBlocked to
                FamilyAccessFailure.ServerResponseInvalid,
        )

        cases.forEach { (exchange, expectedFailure) ->
            val provisioner = CountingProvisioner()
            val coordinator = coordinator(
                client = FakeClient(exchange),
                provisioner = provisioner,
            )

            assertFalse(coordinator.connect(ORIGIN, CODE))
            assertEquals(
                FamilyAccessUiState.Failed(IDENTITY.deviceId, expectedFailure),
                coordinator.state.value,
            )
            assertEquals(0, provisioner.calls)
        }
    }

    @Test
    fun everyCredentialPersistenceOutcomeIsMappedAndPayloadIsAlwaysClosed() = runBlocking {
        val cases = listOf(
            CredentialProvisionResult.KeyUnavailable to
                FamilyAccessFailure.CredentialSecurityUnavailable,
            CredentialProvisionResult.StorageUnavailable to
                FamilyAccessFailure.CredentialStorageUnavailable,
        )

        cases.forEach { (provisionResult, expectedFailure) ->
            val payload = payload()
            val coordinator = coordinator(
                client = FakeClient(DeviceProvisioningExchangeResult.Provisioned(payload)),
                provisioner = FamilyAccessProvisioningPort { provisionResult },
            )

            assertFalse(coordinator.connect(ORIGIN, CODE))
            assertEquals(
                FamilyAccessUiState.Failed(IDENTITY.deviceId, expectedFailure),
                coordinator.state.value,
            )
            assertPayloadClosed(payload)
        }
    }

    @Test
    fun unexpectedClientAndPersistenceFailuresDoNotExposeSecretsOrLeavePayloadOpen() = runBlocking {
        val clientFailure = coordinator(
            client = DeviceProvisioningClient { _, _, _ -> error("transport internal failure") },
        )
        assertFalse(clientFailure.connect(ORIGIN, CODE))
        assertEquals(
            FamilyAccessUiState.Failed(IDENTITY.deviceId, FamilyAccessFailure.UnexpectedFailure),
            clientFailure.state.value,
        )

        val payload = payload()
        val persistenceFailure = coordinator(
            client = FakeClient(DeviceProvisioningExchangeResult.Provisioned(payload)),
            provisioner = FamilyAccessProvisioningPort { error("vault internal failure") },
        )
        assertFalse(persistenceFailure.connect(ORIGIN, CODE))
        assertEquals(
            FamilyAccessUiState.Failed(IDENTITY.deviceId, FamilyAccessFailure.UnexpectedFailure),
            persistenceFailure.state.value,
        )
        assertPayloadClosed(payload)

        val rendered = listOf(clientFailure.state.value, persistenceFailure.state.value)
            .joinToString()
        assertFalse(rendered.contains(CODE))
        assertFalse(rendered.contains(IDENTITY.deviceNonce))
    }

    @Test
    fun cancellationIsPropagatedAndReturnsTheScreenToReady() = runBlocking {
        val coordinator = coordinator(
            client = DeviceProvisioningClient { _, _, _ ->
                throw CancellationException("screen closed")
            },
        )

        val failure = runCatching { coordinator.connect(ORIGIN, CODE) }.exceptionOrNull()

        assertTrue(failure is CancellationException)
        assertEquals(FamilyAccessUiState.Ready(IDENTITY.deviceId), coordinator.state.value)
    }

    @Test
    fun aSecondConcurrentConnectIsRejectedWithoutASecondRequest() = runBlocking {
        val client = BlockingClient()
        val coordinator = coordinator(client = client)

        val first = async { coordinator.connect(ORIGIN, CODE) }
        client.started.await()

        assertFalse(coordinator.connect(ORIGIN, CODE))
        assertEquals(1, client.calls)

        client.complete(DeviceProvisioningExchangeResult.ActivationRejected)
        assertFalse(first.await())
        assertEquals(1, client.calls)
    }

    @Test
    fun unexpectedIdentityProviderFailureHasNoNetworkSideEffect() = runBlocking {
        val client = FakeClient()
        val coordinator = coordinator(
            identityProvider = DeviceProvisioningIdentityProvider {
                error("keystore implementation failure")
            },
            client = client,
        )

        assertFalse(coordinator.connect(ORIGIN, CODE))
        assertEquals(
            FamilyAccessUiState.Failed(null, FamilyAccessFailure.UnexpectedFailure),
            coordinator.state.value,
        )
        assertEquals(0, client.calls)
    }

    @Test
    fun everyFailureHasSafeNonEmptyUserText() {
        FamilyAccessFailure.entries.forEach { failure ->
            val message = failure.userMessage()
            assertTrue(message.isNotBlank())
            assertFalse(message.contains(CODE))
            assertFalse(message.contains(IDENTITY.deviceNonce))
        }
    }

    private fun coordinator(
        identityProvider: DeviceProvisioningIdentityProvider =
            FakeIdentityProvider(DeviceProvisioningIdentityLoadResult.Available(IDENTITY)),
        client: DeviceProvisioningClient = FakeClient(),
        provisioner: FamilyAccessProvisioningPort = CountingProvisioner(),
    ) = FamilyAccessCoordinator(identityProvider, client, provisioner)

    private fun payload(): RemoteProvisioningPayload = RemoteProvisioningPayload.capture(
        metadata = RemoteCredentialMetadata(
            credentialId = "credential-1",
            backendBindingId = "binding-1",
            credentialRevision = 1,
            expectedPatientId = "00000000-0000-4000-8000-000000000301",
            expectedDeviceId = IDENTITY.deviceId,
            httpsOrigin = ORIGIN,
        ),
        bearerTokenUtf8 = "0123456789abcdefghijklmnopqrstuv".toByteArray(),
    )

    private suspend fun assertPayloadClosed(payload: RemoteProvisioningPayload) {
        val failure = runCatching {
            payload.consumeToken { error("closed payload unexpectedly exposed a token") }
        }.exceptionOrNull()
        assertTrue(failure is IllegalStateException)
    }

    private class FakeIdentityProvider(
        private val result: DeviceProvisioningIdentityLoadResult,
    ) : DeviceProvisioningIdentityProvider {
        var calls = 0
            private set

        override suspend fun loadOrCreate(): DeviceProvisioningIdentityLoadResult {
            calls += 1
            return result
        }
    }

    private class FakeClient(
        private val result: DeviceProvisioningExchangeResult =
            DeviceProvisioningExchangeResult.ActivationRejected,
    ) : DeviceProvisioningClient {
        var calls = 0
            private set
        var endpoint: RemoteUploadEndpoint? = null
            private set
        var activationCode: DeviceActivationCode? = null
            private set
        var identity: DeviceProvisioningIdentity? = null
            private set

        override suspend fun provision(
            endpoint: RemoteUploadEndpoint,
            activationCode: DeviceActivationCode,
            identity: DeviceProvisioningIdentity,
        ): DeviceProvisioningExchangeResult {
            calls += 1
            this.endpoint = endpoint
            this.activationCode = activationCode
            this.identity = identity
            return result
        }
    }

    private class BlockingClient : DeviceProvisioningClient {
        val started = CompletableDeferred<Unit>()
        private val result = CompletableDeferred<DeviceProvisioningExchangeResult>()
        var calls = 0
            private set

        override suspend fun provision(
            endpoint: RemoteUploadEndpoint,
            activationCode: DeviceActivationCode,
            identity: DeviceProvisioningIdentity,
        ): DeviceProvisioningExchangeResult {
            calls += 1
            started.complete(Unit)
            return result.await()
        }

        fun complete(value: DeviceProvisioningExchangeResult) {
            result.complete(value)
        }
    }

    private class CountingProvisioner : FamilyAccessProvisioningPort {
        var calls = 0
            private set

        override suspend fun provision(payload: RemoteProvisioningPayload): CredentialProvisionResult {
            calls += 1
            return CredentialProvisionResult.Provisioned
        }
    }

    private companion object {
        const val ORIGIN = "https://family.example.test"
        const val CODE =
            "SLK1-0123-4567-89AB-CDEF-GHJK-MNPQ-RSTV-WXYZ"
        val IDENTITY = DeviceProvisioningIdentity(
            deviceId = "00000000-0000-4000-8000-000000000201",
            deviceNonce = "AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh8",
        )
    }
}
