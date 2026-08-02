package com.sladkaya.app.familyaccess

import com.sladkaya.app.sync.CredentialProvisionResult
import com.sladkaya.app.sync.RemoteCredentialMetadata
import com.sladkaya.app.sync.RemoteProvisioningPayload
import java.util.concurrent.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class ProductionFamilyAccessFactoryTest {
    @Test
    fun productionPortPersistsTheExactPayloadWithoutChangingSensorState() = runBlocking {
        val payload = payload()
        val calls = mutableListOf<String>()
        val port = RemoteAccessFamilyAccessProvisioningPort(
            provisionCredential = {
                assertSame(payload, it)
                calls += "credential"
                it.consumeToken { }
                CredentialProvisionResult.Provisioned
            },
        )

        assertEquals(CredentialProvisionResult.Provisioned, port.provision(payload))
        assertEquals(listOf("credential"), calls)
        assertPayloadClosed(payload)
    }

    @Test
    fun everyCredentialPersistenceResultIsPreservedExactly() = runBlocking {
        listOf(
            CredentialProvisionResult.Provisioned,
            CredentialProvisionResult.KeyUnavailable,
            CredentialProvisionResult.StorageUnavailable,
        ).forEach { persistenceResult ->
            val port = RemoteAccessFamilyAccessProvisioningPort(
                provisionCredential = { persistenceResult },
            )

            assertEquals(persistenceResult, port.provision(payload()))
        }
    }

    @Test
    fun credentialPersistenceCancellationRemainsCancellation() {
        val failure = runCatching {
            runBlocking {
                RemoteAccessFamilyAccessProvisioningPort(
                    provisionCredential = { throw CancellationException("stopped") },
                ).provision(payload())
            }
        }.exceptionOrNull()

        assertTrue(failure is CancellationException)
    }

    @Test
    fun v1NoBlockedUploadRecoveryIsAnExplicitSafeNoOp() = runBlocking {
        V1NoBlockedUploadRecovery.requeueBlocked(metadata())
    }

    private fun payload() = RemoteProvisioningPayload.capture(
        metadata = metadata(),
        bearerTokenUtf8 = TOKEN.toByteArray(),
    )

    private fun metadata() = RemoteCredentialMetadata(
        credentialId = "credential-1",
        backendBindingId = "backend-1",
        credentialRevision = 1L,
        expectedPatientId = "00000000-0000-4000-8000-000000000301",
        expectedDeviceId = "00000000-0000-4000-8000-000000000201",
        httpsOrigin = "https://family.example",
    )

    private suspend fun assertPayloadClosed(payload: RemoteProvisioningPayload) {
        val failure = runCatching { payload.consumeToken { } }.exceptionOrNull()
        assertTrue(failure is IllegalStateException)
    }

    private companion object {
        const val TOKEN = "0123456789abcdef0123456789abcdef"
    }
}
