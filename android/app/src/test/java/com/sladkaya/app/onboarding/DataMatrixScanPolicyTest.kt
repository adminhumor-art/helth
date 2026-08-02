package com.sladkaya.app.onboarding

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DataMatrixScanPolicyTest {
    @Test
    fun strictPackageCodeAcceptsOnlyEightAsciiLettersOrDigitsAndPreservesCase() {
        listOf("Ab1Zcd34", "12345678", "ABCDEFGH", "abcdefgh").forEach { raw ->
            assertEquals(raw, DataMatrixPackageCodePolicy.accept(raw))
        }
    }

    @Test
    fun strictPackageCodeRejectsMissingMalformedAndEmbeddedValuesWithoutGuessing() {
        listOf(
            null,
            "",
            "1234567",
            "123456789",
            " Ab1Zcd34",
            "Ab1Zcd34 ",
            "Ab1Z-c34",
            "ABCД1234",
            "prefix-Ab1Zcd34-suffix",
            "https://example.invalid/Ab1Zcd34",
        ).forEach { raw ->
            assertNull("must reject raw value: $raw", DataMatrixPackageCodePolicy.accept(raw))
        }
    }

    @Test
    fun twoIdenticalConsecutiveFramesConfirmTheExactRawPayload() {
        val consensus = DataMatrixScanConsensus()

        assertEquals(
            DataMatrixScanDecision.AwaitingConfirmation,
            consensus.observe(listOf("Ab1Zcd34")),
        )
        assertEquals(
            DataMatrixScanDecision.Confirmed("Ab1Zcd34"),
            consensus.observe(listOf("Ab1Zcd34")),
        )
    }

    @Test
    fun aDifferentValidCodeStartsANewConsensusInsteadOfConfirmingEitherCode() {
        val consensus = DataMatrixScanConsensus()

        consensus.observe(listOf("Ab1Zcd34"))
        assertEquals(
            DataMatrixScanDecision.AwaitingConfirmation,
            consensus.observe(listOf("ZZZZ9999")),
        )
        assertEquals(
            DataMatrixScanDecision.Confirmed("ZZZZ9999"),
            consensus.observe(listOf("ZZZZ9999")),
        )
    }

    @Test
    fun missingOversizedNonAsciiOrMultipleResultsBreakConsecutiveness() {
        val interruptions = listOf(
            emptyList(),
            listOf(null),
            listOf("A".repeat(513)),
            listOf("ABCД1234"),
            listOf("Ab1Zcd34", "ZZZZ9999"),
            listOf("Ab1Zcd34", "Ab1Zcd34"),
        )

        interruptions.forEach { interruption ->
            val consensus = DataMatrixScanConsensus()
            consensus.observe(listOf("Ab1Zcd34"))

            assertEquals(
                DataMatrixScanDecision.AwaitingConfirmation,
                consensus.observe(interruption),
            )
            assertEquals(
                DataMatrixScanDecision.AwaitingConfirmation,
                consensus.observe(listOf("Ab1Zcd34")),
            )
        }
    }

    @Test
    fun fullGs1ElementStringCanReachConsensusWithoutBeingTruncatedOrGuessed() {
        val raw = "\u001d0106972831641803112412191725121810LT4F241247J\u001d21241247YEZ1450HAJ02"
        val consensus = DataMatrixScanConsensus()

        assertEquals(DataMatrixScanDecision.AwaitingConfirmation, consensus.observe(listOf(raw)))
        assertEquals(DataMatrixScanDecision.Confirmed(raw), consensus.observe(listOf(raw)))
    }

    @Test
    fun aConfirmedConsensusDoesNotEmitTheResultTwice() {
        val consensus = DataMatrixScanConsensus()
        consensus.observe(listOf("Ab1Zcd34"))

        assertTrue(consensus.observe(listOf("Ab1Zcd34")) is DataMatrixScanDecision.Confirmed)
        assertEquals(
            DataMatrixScanDecision.AlreadyConfirmed,
            consensus.observe(listOf("Ab1Zcd34")),
        )
    }
}
