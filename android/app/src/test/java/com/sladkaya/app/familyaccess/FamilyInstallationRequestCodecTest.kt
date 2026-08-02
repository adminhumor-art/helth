package com.sladkaya.app.familyaccess

import com.sladkaya.app.sync.DeviceProvisioningIdentity
import java.util.Base64
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FamilyInstallationRequestCodecTest {
    @Test
    fun canonicalIdentityProducesTheExactDeterministicRequestAndRoundTrips() {
        val encoded = FamilyInstallationRequestCodec.encode(IDENTITY)

        assertEquals(EXACT_REQUEST, encoded.value)
        assertEquals(
            FamilyInstallationRequestParseResult.Valid(IDENTITY),
            FamilyInstallationRequestCodec.parse(encoded.value),
        )
    }

    @Test
    fun requestAndParseResultsNeverRenderTheNonceOrOpaqueRequest() {
        val request = FamilyInstallationRequestCodec.encode(IDENTITY)
        val parsed = FamilyInstallationRequestCodec.parse(request.value)

        listOf(request.toString(), parsed.toString()).forEach { rendered ->
            assertFalse(rendered.contains(IDENTITY.deviceNonce))
            assertFalse(rendered.contains(request.value))
        }
    }

    @Test
    fun nonCanonicalPrefixAlphabetPaddingAndBoundsAreRejected() {
        val invalid = listOf(
            "SLKI2.${EXACT_REQUEST.substringAfter('.')}",
            EXACT_REQUEST.replaceFirst('.', ':'),
            EXACT_REQUEST + "=",
            EXACT_REQUEST.dropLast(1) + "+",
            EXACT_REQUEST.dropLast(1),
            EXACT_REQUEST + "A",
            "",
            "A".repeat(1_024),
        )

        invalid.forEach { candidate ->
            assertEquals(
                FamilyInstallationRequestParseResult.Invalid,
                FamilyInstallationRequestCodec.parse(candidate),
            )
        }
    }

    @Test
    fun decodableButNonCanonicalJsonAndIdentityValuesAreRejected() {
        val invalidJson = listOf(
            // Field order is part of the canonical format.
            "{\"deviceNonce\":\"${IDENTITY.deviceNonce}\",\"deviceId\":\"${IDENTITY.deviceId}\"}",
            "{ \"deviceId\":\"${IDENTITY.deviceId}\",\"deviceNonce\":\"${IDENTITY.deviceNonce}\"}",
            "{\"deviceId\":\"${IDENTITY.deviceId.dropLast(3)}ABC\",\"deviceNonce\":\"${IDENTITY.deviceNonce}\"}",
            "{\"deviceId\":\"${IDENTITY.deviceId}\",\"deviceNonce\":\"short\"}",
            "{\"deviceId\":\"${IDENTITY.deviceId}\",\"deviceNonce\":\"${IDENTITY.deviceNonce}\",\"extra\":\"x\"}",
        )

        invalidJson.forEach { json ->
            val candidate = "SLKI1." + Base64.getUrlEncoder().withoutPadding()
                .encodeToString(json.toByteArray(Charsets.UTF_8))
            assertEquals(
                FamilyInstallationRequestParseResult.Invalid,
                FamilyInstallationRequestCodec.parse(candidate),
            )
        }
    }

    @Test
    fun allAcceptedRequestsHaveOneExactBoundedShape() {
        val request = FamilyInstallationRequestCodec.encode(IDENTITY).value

        assertEquals(154, request.length)
        assertTrue(request.matches(Regex("^SLKI1\\.[A-Za-z0-9_-]{148}$")))
        assertFalse(request.contains('='))
    }

    private companion object {
        val IDENTITY = DeviceProvisioningIdentity(
            deviceId = "00000000-0000-4000-8000-000000000201",
            deviceNonce = "AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh8",
        )
        const val EXACT_REQUEST =
            "SLKI1.eyJkZXZpY2VJZCI6IjAwMDAwMDAwLTAwMDAtNDAwMC04MDAwLTAwMDAwMDAwMDIwMSIsImRldmljZU5vbmNlIjoiQUFFQ0F3UUZCZ2NJQ1FvTERBME9EeEFSRWhNVUZSWVhHQmthR3h3ZEhoOCJ9"
    }
}
