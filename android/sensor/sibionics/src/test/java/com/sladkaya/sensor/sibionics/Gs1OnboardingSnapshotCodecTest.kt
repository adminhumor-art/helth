package com.sladkaya.sensor.sibionics

import com.sladkaya.core.model.SensorFamily
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class Gs1OnboardingSnapshotCodecTest {
    @Test
    fun everyStateShapeRoundTripsWithoutChangingCaseOrCandidateOrder() {
        val snapshots = listOf(
            Gs1OnboardingSnapshot(),
            Gs1OnboardingSnapshot(
                stage = Gs1OnboardingStage.DISCOVERING,
                family = SensorFamily.SIBIONICS_GS1,
                codeSource = Gs1PackageCodeSource.MANUAL,
                packageCode = "Ab1Zcd34",
            ),
            Gs1OnboardingSnapshot(
                stage = Gs1OnboardingStage.RESOLUTION_BLOCKED,
                family = SensorFamily.SIBIONICS_GS1SB,
                codeSource = Gs1PackageCodeSource.DATA_MATRIX,
                packageCode = "Ab1Zcd34",
                rejectionReason = Gs1OnboardingRejectionReason.AMBIGUOUS_CANDIDATES,
                candidates = listOf(
                    Gs1ResolvedAdvertisement("GS-Ab1Z", "AA:BB:CC:DD:EE:02"),
                    Gs1ResolvedAdvertisement("MED-Ab1Z", "AA:BB:CC:DD:EE:01"),
                ),
            ),
            pendingSnapshot(),
        )

        snapshots.forEach { snapshot ->
            val decoded = Gs1OnboardingSnapshotCodec.decode(
                Gs1OnboardingSnapshotCodec.encode(snapshot),
            )

            assertEquals(
                snapshot,
                (decoded as Gs1OnboardingSnapshotDecodeResult.Success).snapshot,
            )
        }
    }

    @Test
    fun aChangedEncodedByteIsRejectedByTheChecksum() {
        val encoded = Gs1OnboardingSnapshotCodec.encode(pendingSnapshot())
        val index = encoded.length / 2
        val replacement = if (encoded[index] == 'A') 'B' else 'A'
        val corrupted = encoded.replaceRange(index, index + 1, replacement.toString())

        val decoded = Gs1OnboardingSnapshotCodec.decode(corrupted)

        assertEquals(
            Gs1OnboardingSnapshotDecodeError.CHECKSUM_MISMATCH,
            (decoded as Gs1OnboardingSnapshotDecodeResult.Failure).error,
        )
    }

    @Test
    fun malformedBase64AndOversizedValuesAreRejectedBeforeParsing() {
        val malformed = Gs1OnboardingSnapshotCodec.decode("not valid base64 **")
        val oversized = Gs1OnboardingSnapshotCodec.decode("A".repeat(200_000))

        assertEquals(
            Gs1OnboardingSnapshotDecodeError.MALFORMED_BASE64,
            (malformed as Gs1OnboardingSnapshotDecodeResult.Failure).error,
        )
        assertEquals(
            Gs1OnboardingSnapshotDecodeError.ENCODED_VALUE_TOO_LARGE,
            (oversized as Gs1OnboardingSnapshotDecodeResult.Failure).error,
        )
    }

    @Test
    fun checksumValidButSemanticallyInvalidSnapshotStillFailsStateRestore() {
        val store = EncodedOnboardingStore(
            Gs1OnboardingSnapshotCodec.encode(
                Gs1OnboardingSnapshot(
                    stage = Gs1OnboardingStage.PENDING_DIAGNOSTIC,
                    family = SensorFamily.SIBIONICS_GS1,
                    codeSource = Gs1PackageCodeSource.MANUAL,
                    packageCode = "Ab1Zcd34",
                ),
            ),
        )

        val opened = Gs1OnboardingStateMachine.open(store)

        assertEquals(
            Gs1OnboardingOpenError.INVALID_SAVED_STATE,
            (opened as Gs1OnboardingOpenResult.Failure).error,
        )
    }

    @Test
    fun corruptPersistedValueFailsClosedInsteadOfStartingANewOnboarding() {
        val store = EncodedOnboardingStore("corrupt")

        val opened = Gs1OnboardingStateMachine.open(store)

        assertEquals(
            Gs1OnboardingOpenError.STORAGE_UNAVAILABLE,
            (opened as Gs1OnboardingOpenResult.Failure).error,
        )
    }

    @Test
    fun codecBackedRestartKeepsTheProfilePendingAndNonPublishable() {
        val store = EncodedOnboardingStore()
        val first = (Gs1OnboardingStateMachine.open(store) as Gs1OnboardingOpenResult.Ready).machine
        first.submitPackageCode(
            SensorFamily.SIBIONICS_GS1,
            Gs1PackageCodeInput.DataMatrix("Ab1Zcd34"),
        )
        first.resolveAdvertisements(
            listOf(Gs1DiscoveredAdvertisement("GS-Ab1Z", "aa:bb:cc:dd:ee:01")),
        )

        val restarted =
            (Gs1OnboardingStateMachine.open(store) as Gs1OnboardingOpenResult.Ready).machine
        val profile = (restarted.state as Gs1OnboardingState.PendingDiagnostic).profile

        assertEquals(Gs1OnboardingProfileStatus.PENDING_DIAGNOSTIC, profile.status)
        assertEquals("Ab1Zcd34", profile.packageCode)
        assertFalse(profile.physicalEvidenceVerified)
        assertFalse(profile.eligibleForConfirmedConfiguration)
        assertFalse(profile.eligibleForProductPublication)
        assertTrue(store.encodedValue?.isNotBlank() == true)
    }

    private fun pendingSnapshot() = Gs1OnboardingSnapshot(
        stage = Gs1OnboardingStage.PENDING_DIAGNOSTIC,
        family = SensorFamily.SIBIONICS_GS1,
        codeSource = Gs1PackageCodeSource.MANUAL,
        packageCode = "Ab1Zcd34",
        selectedDeviceName = "GS-Ab1Z",
        selectedBluetoothAddress = "AA:BB:CC:DD:EE:01",
    )
}

private class EncodedOnboardingStore(
    var encodedValue: String? = null,
) : Gs1OnboardingStateStore {
    override fun load(): Gs1OnboardingSnapshot? = encodedValue?.let { encoded ->
        when (val decoded = Gs1OnboardingSnapshotCodec.decode(encoded)) {
            is Gs1OnboardingSnapshotDecodeResult.Success -> decoded.snapshot
            is Gs1OnboardingSnapshotDecodeResult.Failure -> {
                error("Invalid pending onboarding snapshot: ${decoded.error}")
            }
        }
    }

    override fun compareAndSet(
        expectedRevision: Long?,
        snapshot: Gs1OnboardingSnapshot,
    ): Boolean {
        val currentRevision = encodedValue?.let { encoded ->
            (Gs1OnboardingSnapshotCodec.decode(encoded) as Gs1OnboardingSnapshotDecodeResult.Success)
                .snapshot.revision
        }
        if (currentRevision != expectedRevision) return false
        encodedValue = Gs1OnboardingSnapshotCodec.encode(snapshot)
        return true
    }
}
