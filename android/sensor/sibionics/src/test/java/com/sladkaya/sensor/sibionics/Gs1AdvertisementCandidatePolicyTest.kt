package com.sladkaya.sensor.sibionics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class Gs1AdvertisementCandidatePolicyTest {
    private val policy = Gs1AdvertisementCandidatePolicy("Ab1Zcd34")

    @Test
    fun suffixMatchesFirstFourPackageCodeCharactersCaseSensitively() {
        val result = policy.evaluate(
            Gs1AdvertisementCandidate(
                deviceName = "GS-Ab1Z",
                bluetoothAddress = "aa:bb:cc:dd:ee:ff",
            ),
        )

        assertTrue(result is Gs1AdvertisementCandidateResult.CandidateMatch)
        result as Gs1AdvertisementCandidateResult.CandidateMatch
        assertEquals("GS-Ab1Z", result.deviceName)
        assertEquals("AA:BB:CC:DD:EE:FF", result.canonicalBluetoothAddress)
    }

    @Test
    fun differentSuffixCaseDoesNotMatch() {
        val result = policy.evaluate(
            Gs1AdvertisementCandidate("GS-ab1z", "AA:BB:CC:DD:EE:FF"),
        )

        assertEquals(Gs1AdvertisementCandidateResult.NoMatch, result)
    }

    @Test
    fun nullAndShortNamesDoNotMatch() {
        assertEquals(
            Gs1AdvertisementCandidateResult.NoMatch,
            policy.evaluate(Gs1AdvertisementCandidate(null, "AA:BB:CC:DD:EE:01")),
        )
        assertEquals(
            Gs1AdvertisementCandidateResult.NoMatch,
            policy.evaluate(Gs1AdvertisementCandidate("Ab1", "AA:BB:CC:DD:EE:02")),
        )
    }

    @Test
    fun exactlyFourCharacterNameCanMatch() {
        val result = policy.evaluate(
            Gs1AdvertisementCandidate("Ab1Z", "AA:BB:CC:DD:EE:FF"),
        )

        assertTrue(result is Gs1AdvertisementCandidateResult.CandidateMatch)
    }

    @Test
    fun malformedMacIsInvalidEvenWhenNameDoesNotMatch() {
        val malformed = listOf(
            "AA:BB:CC:DD:EE",
            "AA-BB-CC-DD-EE-FF",
            "GG:BB:CC:DD:EE:FF",
            " AA:BB:CC:DD:EE:FF",
        )

        malformed.forEach { address ->
            val result = policy.evaluate(Gs1AdvertisementCandidate("unrelated", address))
            assertTrue("$address must be invalid", result is Gs1AdvertisementCandidateResult.Invalid)
        }
    }

    @Test
    fun packageCodeContractIsEnforcedAtTheBoundary() {
        listOf("1234567", "123456789", "ABCД1234", "ABCD-234").forEach { invalid ->
            assertThrows(IllegalArgumentException::class.java) {
                Gs1AdvertisementCandidatePolicy(invalid)
            }
        }
    }

    @Test
    fun selectionReturnsNoneWhenThereAreNoMatches() {
        val selection = policy.select(
            listOf(
                Gs1AdvertisementCandidate(null, "AA:BB:CC:DD:EE:01"),
                Gs1AdvertisementCandidate("other", "AA:BB:CC:DD:EE:02"),
                Gs1AdvertisementCandidate("GS-Ab1Z", "malformed"),
            ),
        )

        assertEquals(Gs1AdvertisementSelection.None, selection)
    }

    @Test
    fun selectionReturnsSingleCanonicalCandidate() {
        val selection = policy.select(
            listOf(
                Gs1AdvertisementCandidate("other", "AA:BB:CC:DD:EE:01"),
                Gs1AdvertisementCandidate("GS-Ab1Z", "aa:bb:cc:dd:ee:02"),
            ),
        )

        assertTrue(selection is Gs1AdvertisementSelection.Single)
        selection as Gs1AdvertisementSelection.Single
        assertEquals("AA:BB:CC:DD:EE:02", selection.candidate.canonicalBluetoothAddress)
    }

    @Test
    fun twoDifferentNamesWithTheSameSuffixAreAmbiguous() {
        val selection = policy.select(
            listOf(
                Gs1AdvertisementCandidate("GS-Ab1Z", "AA:BB:CC:DD:EE:01"),
                Gs1AdvertisementCandidate("MED-Ab1Z", "AA:BB:CC:DD:EE:02"),
            ),
        )

        assertTrue(selection is Gs1AdvertisementSelection.Ambiguous)
        selection as Gs1AdvertisementSelection.Ambiguous
        assertEquals(2, selection.candidates.size)
        assertEquals(
            listOf("AA:BB:CC:DD:EE:01", "AA:BB:CC:DD:EE:02"),
            selection.candidates.map { it.canonicalBluetoothAddress },
        )
    }

    @Test
    fun repeatedAdvertisementsFromOneCanonicalMacStaySingle() {
        val selection = policy.select(
            listOf(
                Gs1AdvertisementCandidate("GS-Ab1Z", "aa:bb:cc:dd:ee:01"),
                Gs1AdvertisementCandidate("GS-Ab1Z", "AA:BB:CC:DD:EE:01"),
            ),
        )

        assertTrue(selection is Gs1AdvertisementSelection.Single)
    }
}
