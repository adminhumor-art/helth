package com.sladkaya.app.sync

import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class RemoteCredentialPolicyTest {
    private val baseline = RemoteCredentialMetadata(
        credentialId = "credential-1",
        backendBindingId = "backend-1",
        credentialRevision = 7,
        expectedPatientId = "00000000-0000-4000-8000-000000000001",
        expectedDeviceId = "00000000-0000-4000-8000-000000000201",
        httpsOrigin = "https://family.example",
    )

    @Test
    fun aadBindsEverySecurityRelevantField() {
        val original = RemoteCredentialAad.encode(baseline).toList()
        val alternatives = listOf(
            baseline.copy(credentialId = "credential-2"),
            baseline.copy(backendBindingId = "backend-2"),
            baseline.copy(credentialRevision = 8),
            baseline.copy(expectedPatientId = "00000000-0000-4000-8000-000000000002"),
            baseline.copy(expectedDeviceId = "00000000-0000-4000-8000-000000000202"),
            baseline.copy(httpsOrigin = "https://other.example"),
        )

        alternatives.forEach { changed ->
            assertNotEquals(original, RemoteCredentialAad.encode(changed).toList())
        }
    }

    @Test
    fun endpointAcceptsOnlyCanonicalHttpsOrigin() {
        assertTrue(RemoteUploadEndpoint.parse("https://family.example") is RemoteUploadEndpointParseResult.Valid)
        assertTrue(RemoteUploadEndpoint.parse("https://family.example:443") is RemoteUploadEndpointParseResult.Valid)
        listOf(
            "http://family.example",
            "https://family.example:0",
            "https://family.example:8443",
            "https://family.example/path",
            "https://family.example/",
            "https://family.example//path",
            "https://user@family.example",
            "https://family.example?query=1",
            "https://family.example#fragment",
            "https://family.example\r\n.invalid",
            "https://bad_host.example",
            "https://localhost",
            "https://[fe80::1%25eth0]",
            "https://[2001:db8::1]",
            "https://2001:db8::1",
            "not a url",
        ).forEach { candidate ->
            assertFalse(RemoteUploadEndpoint.parse(candidate) is RemoteUploadEndpointParseResult.Valid)
        }
    }

    @Test
    fun metadataRejectsNonCanonicalIdentityAndNonPositiveRevision() {
        listOf(
            { baseline.copy(expectedPatientId = "patient-1") },
            { baseline.copy(expectedDeviceId = "00000000-0000-4000-8000-00000000020A") },
            { baseline.copy(backendBindingId = "bad binding") },
            { baseline.copy(credentialId = "") },
            { baseline.copy(credentialRevision = 0) },
            { baseline.copy(credentialRevision = 9_007_199_254_740_992L) },
            { baseline.copy(httpsOrigin = "https://[2001:db8::1]") },
        ).forEach { invalid ->
            assertThrows(IllegalArgumentException::class.java) { invalid() }
        }
        baseline.copy(credentialRevision = 9_007_199_254_740_991L)
    }

    @Test
    fun oldOutboxCannotBeSentWithAnotherRuntimeCredential() {
        assertTrue(
            CredentialOutboxBindingPolicy.matches(
                credential = baseline,
                expectedPatientId = baseline.expectedPatientId,
                expectedDeviceId = baseline.expectedDeviceId,
                backendBindingId = baseline.backendBindingId,
                credentialId = baseline.credentialId,
                credentialRevision = baseline.credentialRevision,
                httpsOrigin = baseline.httpsOrigin,
            ),
        )
        val mismatches = listOf(
            baseline.copy(expectedPatientId = "00000000-0000-4000-8000-000000000002"),
            baseline.copy(expectedDeviceId = "00000000-0000-4000-8000-000000000202"),
            baseline.copy(backendBindingId = "backend-2"),
            baseline.copy(credentialId = "credential-2"),
            baseline.copy(credentialRevision = 8),
            baseline.copy(httpsOrigin = "https://other.example"),
        )
        mismatches.forEach { changedCredential ->
            assertFalse(
                CredentialOutboxBindingPolicy.matches(
                    credential = changedCredential,
                    expectedPatientId = baseline.expectedPatientId,
                    expectedDeviceId = baseline.expectedDeviceId,
                    backendBindingId = baseline.backendBindingId,
                    credentialId = baseline.credentialId,
                    credentialRevision = baseline.credentialRevision,
                    httpsOrigin = baseline.httpsOrigin,
                ),
            )
        }
    }

    @Test
    fun bearerTokenNeverRendersItsSecret() {
        val source = "0123456789abcdef0123456789abcdef".toByteArray()
        val token = SecretBearerToken.fromUtf8(source)
        source.fill(0)
        assertFalse(token.toString().contains("0123456789abcdef"))
        token.useBytes { assertArrayEquals("0123456789abcdef0123456789abcdef".toByteArray(), it) }
        token.close()
        assertThrows(IllegalStateException::class.java) { token.useBytes { } }
        assertFalse(baseline.toString().contains(baseline.expectedPatientId))
        assertFalse(baseline.toString().contains(baseline.expectedDeviceId))
    }

    @Test
    fun bearerTokenMatchesBackendLengthAndHeaderSafetyContract() {
        listOf(
            ByteArray(31) { 'a'.code.toByte() },
            ByteArray(4_097) { 'a'.code.toByte() },
            ("0123456789abcdef0123456789abcde\n").toByteArray(),
            ByteArray(32) { 0x80.toByte() },
        ).forEach { invalid ->
            assertThrows(IllegalArgumentException::class.java) { SecretBearerToken.fromUtf8(invalid) }
        }
    }
}
