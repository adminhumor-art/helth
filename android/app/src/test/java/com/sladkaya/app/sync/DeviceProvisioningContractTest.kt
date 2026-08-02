package com.sladkaya.app.sync

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class DeviceProvisioningContractTest {
    private val identity = DeviceProvisioningIdentity(
        deviceId = "00000000-0000-4000-8000-00000000020a",
        deviceNonce = "AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh8",
    )

    @Test
    fun activationCodeMatchesTheExactBackendFormat() {
        val valid = "SLK1-0123-4567-89AB-CDEF-GHJK-MNPQ-RSTV-WXYZ"

        assertEquals(valid, DeviceActivationCode.require(valid).value)
        listOf(
            valid.lowercase(),
            " $valid",
            "$valid ",
            valid.replaceFirst("0", "O"),
            valid.replaceFirst("-", ""),
            "SLK2-0123-4567-89AB-CDEF-GHJK-MNPQ-RSTV-WXYZ",
        ).forEach { invalid ->
            assertThrows(IllegalArgumentException::class.java) {
                DeviceActivationCode.require(invalid)
            }
        }
    }

    @Test
    fun identityRequiresCanonicalInstallationUuidAndNonce() {
        assertEquals(43, identity.deviceNonce.length)
        listOf(
            { identity.copy(deviceId = identity.deviceId.uppercase()) },
            { identity.copy(deviceId = "device-1") },
            { identity.copy(deviceNonce = identity.deviceNonce + "=") },
            { identity.copy(deviceNonce = identity.deviceNonce.dropLast(1)) },
            { identity.copy(deviceNonce = "!" + identity.deviceNonce.drop(1)) },
        ).forEach { invalid ->
            assertThrows(IllegalArgumentException::class.java) { invalid() }
        }
    }

    @Test
    fun successIsBoundToTheRequestedServerAndExactInstallation() = runBlocking {
        val response = DeviceProvisioningResponsePolicy.classify(
            statusCode = 201,
            contentType = "application/json; charset=utf-8",
            responseBody = successJson(),
            expectedOrigin = "https://family.example",
            expectedIdentity = identity,
        )

        assertTrue(response is DeviceProvisioningExchangeResult.Provisioned)
        val payload = (response as DeviceProvisioningExchangeResult.Provisioned).payload
        assertEquals("credential-1", payload.metadata.credentialId)
        assertEquals("backend-1", payload.metadata.backendBindingId)
        assertEquals(identity.deviceId, payload.metadata.expectedDeviceId)
        assertEquals("00000000-0000-4000-8000-000000000001", payload.metadata.expectedPatientId)
        assertEquals("https://family.example", payload.metadata.httpsOrigin)
        payload.consumeToken { token ->
            token.useHeaderValue { value ->
                assertEquals("0123456789abcdef0123456789abcdef01234567890", value)
            }
        }
        assertFalse(response.toString().contains("0123456789abcdef"))
    }

    @Test
    fun aSuccessfulResponseCannotRebindOriginOrDevice() {
        val variants = listOf(
            successJson().replace("https://family.example", "https://other.example"),
            successJson().replace(identity.deviceId, "00000000-0000-4000-8000-000000000202"),
        )

        variants.forEachIndexed { index, body ->
            assertEquals(
                "variant $index must be blocked",
                DeviceProvisioningExchangeResult.ContractBlocked,
                DeviceProvisioningResponsePolicy.classify(
                    201,
                    "application/json",
                    body,
                    "https://family.example",
                    identity,
                ),
            )
        }
    }

    @Test
    fun responseRejectsUnknownMissingDuplicateAndMalformedFields() {
        val variants = listOf(
            successJson().dropLast(1) + ",\"extra\":true}",
            successJson().replace("\"credentialRevision\":1", ""),
            successJson().replace(
                "\"credentialId\":\"credential-1\"",
                "\"credentialId\":\"credential-1\",\"credentialId\":\"credential-2\"",
            ),
            successJson().replace("\"credentialRevision\":1", "\"credentialRevision\":0"),
            successJson().replace("0123456789abcdef0123456789abcdef01234567890", "short"),
        )

        variants.forEachIndexed { index, body ->
            assertEquals(
                "variant $index must be blocked",
                DeviceProvisioningExchangeResult.ContractBlocked,
                DeviceProvisioningResponsePolicy.classify(
                    201,
                    "application/json",
                    body,
                    "https://family.example",
                    identity,
                ),
            )
        }
        assertEquals(
            DeviceProvisioningExchangeResult.ContractBlocked,
            DeviceProvisioningResponsePolicy.classify(
                201,
                "text/plain",
                successJson(),
                "https://family.example",
                identity,
            ),
        )
    }

    @Test
    fun failureStatusesAreStableAndNeverExposeServerBodies() {
        assertEquals(
            DeviceProvisioningExchangeResult.MalformedRequest,
            DeviceProvisioningResponsePolicy.classify(
                400,
                "application/json",
                "private server detail",
                "https://family.example",
                identity,
            ),
        )
        assertEquals(
            DeviceProvisioningExchangeResult.ActivationRejected,
            DeviceProvisioningResponsePolicy.classify(
                401,
                "application/json",
                "private server detail",
                "https://family.example",
                identity,
            ),
        )
        listOf(408, 429, 500, 503).forEach { status ->
            assertEquals(
                DeviceProvisioningExchangeResult.RetryableServer,
                DeviceProvisioningResponsePolicy.classify(
                    status,
                    "application/json",
                    "private server detail",
                    "https://family.example",
                    identity,
                ),
            )
        }
        listOf(200, 202, 204, 301, 403, 404, 409, 422).forEach { status ->
            assertEquals(
                DeviceProvisioningExchangeResult.ContractBlocked,
                DeviceProvisioningResponsePolicy.classify(
                    status,
                    "application/json",
                    "private server detail",
                    "https://family.example",
                    identity,
                ),
            )
        }
    }

    private fun successJson(): String =
        "{" +
            "\"deviceToken\":\"0123456789abcdef0123456789abcdef01234567890\"," +
            "\"apiOrigin\":\"https://family.example\"," +
            "\"deviceId\":\"${identity.deviceId}\"," +
            "\"patientId\":\"00000000-0000-4000-8000-000000000001\"," +
            "\"backendBindingId\":\"backend-1\"," +
            "\"credentialId\":\"credential-1\"," +
            "\"credentialRevision\":1" +
            "}"
}
