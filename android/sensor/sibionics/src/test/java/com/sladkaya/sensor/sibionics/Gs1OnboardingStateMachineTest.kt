package com.sladkaya.sensor.sibionics

import com.sladkaya.core.model.SensorFamily
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class Gs1OnboardingStateMachineTest {
    @Test
    fun validCodeWithoutAnExplicitMarketProfileCannotStartDiscovery() {
        val machine = openMachine(RecordingOnboardingStateStore())

        val result = machine.submitPackageCode(
            SensorFamily.SIBIONICS_GS1,
            Gs1PackageCodeInput.Manual("Ab1Zcd34"),
            marketProfile = null,
        )

        assertEquals(
            Gs1OnboardingRejectionReason.MARKET_PROFILE_REQUIRED,
            (result as Gs1OnboardingActionResult.Rejected).reason,
        )
        assertEquals(Gs1OnboardingState.AwaitingPackageCode, machine.state)
    }

    @Test
    fun unsupportedMarketProfilesArePersistedBlockedBeforeDiscovery() {
        listOf(
            Gs1MarketProfile.RUSSIAN,
            Gs1MarketProfile.ECO_SPLIT,
        ).forEach { marketProfile ->
            val store = RecordingOnboardingStateStore()
            val machine = openMachine(store)

            val result = machine.submitPackageCode(
                SensorFamily.SIBIONICS_GS1,
                Gs1PackageCodeInput.Manual("Ab1Zcd34"),
                marketProfile = marketProfile,
            )

            assertEquals(
                Gs1OnboardingRejectionReason.PROFILE_NOT_PHYSICALLY_VERIFIED,
                (result as Gs1OnboardingActionResult.Rejected).reason,
            )
            val blocked = machine.state as Gs1OnboardingState.ProfileBlocked
            assertEquals(marketProfile, blocked.request.marketProfile)
            assertEquals(
                marketProfile,
                (openMachine(store).state as Gs1OnboardingState.ProfileBlocked)
                    .request.marketProfile,
            )
        }
    }

    @Test
    fun globalAndChineseBoxesUseOneDiagnosticFlowWithInternalProtocolResolution() {
        listOf(Gs1MarketProfile.GLOBAL, Gs1MarketProfile.CHINESE).forEach { marketProfile ->
            val store = RecordingOnboardingStateStore()
            val machine = openMachine(store)

            val submitted = machine.submitPackageCode(
                SensorFamily.SIBIONICS_GS1,
                Gs1PackageCodeInput.Manual("Ab1Zcd34"),
                marketProfile = marketProfile,
            )
            assertTrue(submitted is Gs1OnboardingActionResult.Advanced)
            assertEquals(
                marketProfile,
                (machine.state as Gs1OnboardingState.Discovering).request.marketProfile,
            )

            val resolved = machine.resolveAdvertisements(
                listOf(Gs1DiscoveredAdvertisement("GS-Ab1Z", "AA:BB:CC:DD:EE:02")),
            )
            assertTrue(resolved is Gs1OnboardingActionResult.Advanced)
            val pending = machine.state as Gs1OnboardingState.PendingDiagnostic
            assertEquals(marketProfile, pending.profile.marketProfile)
            assertEquals(marketProfile.transportVariant, pending.profile.transportVariant)
            assertFalse(pending.profile.eligibleForProductPublication)
            assertEquals(
                marketProfile,
                (openMachine(store).state as Gs1OnboardingState.PendingDiagnostic)
                    .profile.marketProfile,
            )
        }
    }

    @Test
    fun manualAndDataMatrixInputsPreserveTheExactEightCharacterCode() {
        listOf(
            Gs1PackageCodeInput.Manual("Ab1Zcd34") to Gs1PackageCodeSource.MANUAL,
            Gs1PackageCodeInput.DataMatrix("Ab1Zcd34") to Gs1PackageCodeSource.DATA_MATRIX,
        ).forEach { (input, expectedSource) ->
            val machine = openMachine(RecordingOnboardingStateStore())

            val result = machine.submitPackageCode(SensorFamily.SIBIONICS_GS1, input)

            assertTrue(result is Gs1OnboardingActionResult.Advanced)
            val state = machine.state as Gs1OnboardingState.Discovering
            assertEquals("Ab1Zcd34", state.request.packageCode)
            assertEquals(expectedSource, state.request.source)
            assertEquals(Gs1MarketProfile.GLOBAL, state.request.marketProfile)
        }
    }

    @Test
    fun packageCodeIsNotTrimmedCaseFoldedOrUnicodeNormalized() {
        val invalidCodes = listOf(
            "1234567",
            "123456789",
            " ABC1234",
            "ABC1234 ",
            "ABC-1234",
            "ABCД1234",
        )

        invalidCodes.forEach { code ->
            val machine = openMachine(RecordingOnboardingStateStore())

            val result = machine.submitPackageCode(
                SensorFamily.SIBIONICS_GS1,
                Gs1PackageCodeInput.Manual(code),
            )

            assertTrue("$code must be rejected", result is Gs1OnboardingActionResult.Rejected)
            result as Gs1OnboardingActionResult.Rejected
            assertTrue(
                result.reason == Gs1OnboardingRejectionReason.INVALID_PACKAGE_CODE_LENGTH ||
                    result.reason == Gs1OnboardingRejectionReason.INVALID_PACKAGE_CODE_CHARACTER,
            )
            assertEquals(Gs1OnboardingState.AwaitingPackageCode, machine.state)
        }
    }

    @Test
    fun gs3CannotEnterTheGs1OnboardingMachine() {
        val machine = openMachine(RecordingOnboardingStateStore())

        val result = machine.submitPackageCode(
            SensorFamily.SIBIONICS_GS3,
            Gs1PackageCodeInput.Manual("Ab1Zcd34"),
        )

        assertEquals(
            Gs1OnboardingRejectionReason.UNSUPPORTED_FAMILY,
            (result as Gs1OnboardingActionResult.Rejected).reason,
        )
        assertEquals(Gs1OnboardingState.AwaitingPackageCode, machine.state)
    }

    @Test
    fun onePolicyCandidateCreatesOnlyAPendingDiagnosticProfile() {
        val machine = machineAfterCode(source = Gs1PackageCodeSource.MANUAL)

        val result = machine.resolveAdvertisements(
            listOf(
                Gs1DiscoveredAdvertisement("unrelated", "AA:BB:CC:DD:EE:01"),
                Gs1DiscoveredAdvertisement("GS-Ab1Z", "aa:bb:cc:dd:ee:02"),
            ),
        )

        assertTrue(result is Gs1OnboardingActionResult.Advanced)
        val state = machine.state as Gs1OnboardingState.PendingDiagnostic
        val profile = state.profile
        assertEquals(Gs1OnboardingProfileStatus.PENDING_DIAGNOSTIC, profile.status)
        assertEquals("Ab1Zcd34", profile.packageCode)
        assertEquals(Gs1PackageCodeSource.MANUAL, profile.codeSource)
        assertEquals(SensorFamily.SIBIONICS_GS1, profile.family)
        assertEquals(Gs1MarketProfile.GLOBAL, profile.marketProfile)
        assertEquals("AA:BB:CC:DD:EE:02", profile.canonicalBluetoothAddress)
        assertFalse(profile.physicalEvidenceVerified)
        assertFalse(profile.eligibleForConfirmedConfiguration)
        assertFalse(profile.eligibleForProductPublication)

        val activation = profile.diagnosticActivationProfile()
        assertEquals("Ab1Zcd34", activation.packageCode)
        assertEquals("AA:BB:CC:DD:EE:02", activation.bluetoothAddress)
    }

    @Test
    fun dataMatrixAndManualInputResolveToTheSamePhysicalIdentity() {
        val advertisement = listOf(
            Gs1DiscoveredAdvertisement("GS-Ab1Z", "aa:bb:cc:dd:ee:02"),
        )
        val manual = machineAfterCode(Gs1PackageCodeSource.MANUAL).also {
            it.resolveAdvertisements(advertisement)
        }.state as Gs1OnboardingState.PendingDiagnostic
        val dataMatrix = machineAfterCode(Gs1PackageCodeSource.DATA_MATRIX).also {
            it.resolveAdvertisements(advertisement)
        }.state as Gs1OnboardingState.PendingDiagnostic

        assertEquals(manual.profile.sensorId, dataMatrix.profile.sensorId)
        assertEquals(manual.profile.packageCode, dataMatrix.profile.packageCode)
        assertEquals(
            manual.profile.canonicalBluetoothAddress,
            dataMatrix.profile.canonicalBluetoothAddress,
        )
    }

    @Test
    fun suffixMatchingRemainsCaseSensitiveThroughOnboarding() {
        val machine = machineAfterCode()

        val result = machine.resolveAdvertisements(
            listOf(Gs1DiscoveredAdvertisement("GS-ab1z", "AA:BB:CC:DD:EE:01")),
        )

        assertEquals(
            Gs1OnboardingRejectionReason.NO_MATCHING_CANDIDATE,
            (result as Gs1OnboardingActionResult.Rejected).reason,
        )
        val blocked = machine.state as Gs1OnboardingState.ResolutionBlocked
        assertEquals(Gs1OnboardingRejectionReason.NO_MATCHING_CANDIDATE, blocked.reason)
    }

    @Test
    fun malformedBluetoothAddressHasAnExplicitFailureReason() {
        val machine = machineAfterCode()

        val result = machine.resolveAdvertisements(
            listOf(Gs1DiscoveredAdvertisement("GS-Ab1Z", "not-a-mac")),
        )

        assertEquals(
            Gs1OnboardingRejectionReason.MALFORMED_BLUETOOTH_ADDRESS,
            (result as Gs1OnboardingActionResult.Rejected).reason,
        )
        assertEquals(
            Gs1OnboardingRejectionReason.MALFORMED_BLUETOOTH_ADDRESS,
            (machine.state as Gs1OnboardingState.ResolutionBlocked).reason,
        )
    }

    @Test
    fun twoDistinctCandidatesAreExplicitlyAmbiguousAndNeverAutoSelected() {
        val machine = machineAfterCode()

        val result = machine.resolveAdvertisements(
            listOf(
                Gs1DiscoveredAdvertisement("GS-Ab1Z", "aa:bb:cc:dd:ee:01"),
                Gs1DiscoveredAdvertisement("MED-Ab1Z", "AA:BB:CC:DD:EE:02"),
            ),
        )

        assertEquals(
            Gs1OnboardingRejectionReason.AMBIGUOUS_CANDIDATES,
            (result as Gs1OnboardingActionResult.Rejected).reason,
        )
        val blocked = machine.state as Gs1OnboardingState.ResolutionBlocked
        assertEquals(
            listOf("AA:BB:CC:DD:EE:01", "AA:BB:CC:DD:EE:02"),
            blocked.candidates.map { it.canonicalBluetoothAddress },
        )
        assertFalse(machine.state is Gs1OnboardingState.PendingDiagnostic)
    }

    @Test
    fun ambiguityRemainsStickyUntilTheUserResetsOnboarding() {
        val machine = machineAfterCode()
        machine.resolveAdvertisements(
            listOf(
                Gs1DiscoveredAdvertisement("GS-Ab1Z", "AA:BB:CC:DD:EE:01"),
                Gs1DiscoveredAdvertisement("MED-Ab1Z", "AA:BB:CC:DD:EE:02"),
            ),
        )

        val result = machine.resolveAdvertisements(
            listOf(Gs1DiscoveredAdvertisement("GS-Ab1Z", "AA:BB:CC:DD:EE:01")),
        )

        assertEquals(
            Gs1OnboardingRejectionReason.AMBIGUOUS_CANDIDATES,
            (result as Gs1OnboardingActionResult.Rejected).reason,
        )
        assertEquals(
            listOf("AA:BB:CC:DD:EE:01", "AA:BB:CC:DD:EE:02"),
            (machine.state as Gs1OnboardingState.ResolutionBlocked)
                .candidates.map { it.canonicalBluetoothAddress },
        )
    }

    @Test
    fun malformedAddressOnAnUnrelatedAdvertisementDoesNotBlockTheExactCandidate() {
        val machine = machineAfterCode()

        val result = machine.resolveAdvertisements(
            listOf(
                Gs1DiscoveredAdvertisement("unrelated", "not-a-mac"),
                Gs1DiscoveredAdvertisement("GS-Ab1Z", "AA:BB:CC:DD:EE:01"),
            ),
        )

        assertTrue(result is Gs1OnboardingActionResult.Advanced)
        assertEquals(
            "AA:BB:CC:DD:EE:01",
            (machine.state as Gs1OnboardingState.PendingDiagnostic)
                .profile.canonicalBluetoothAddress,
        )
    }

    @Test
    fun oversizedAdvertisementBatchIsRejectedBeforeCandidateResolution() {
        val machine = machineAfterCode()

        val result = machine.resolveAdvertisements(
            List(257) {
                Gs1DiscoveredAdvertisement("GS-Ab1Z", "AA:BB:CC:DD:EE:01")
            },
        )

        assertEquals(
            Gs1OnboardingRejectionReason.TOO_MANY_CANDIDATES,
            (result as Gs1OnboardingActionResult.Rejected).reason,
        )
        assertTrue(machine.state is Gs1OnboardingState.ResolutionBlocked)
    }

    @Test
    fun moreThanSixtyFourDistinctMatchingCandidatesIsRejectedExplicitly() {
        val machine = machineAfterCode()
        val advertisements = List(65) { index ->
            val fourth = index / 256
            val fifth = index % 256
            Gs1DiscoveredAdvertisement(
                "GS-Ab1Z",
                "AA:BB:CC:DD:%02X:%02X".format(fourth, fifth),
            )
        }

        val result = machine.resolveAdvertisements(advertisements)

        assertEquals(
            Gs1OnboardingRejectionReason.TOO_MANY_CANDIDATES,
            (result as Gs1OnboardingActionResult.Rejected).reason,
        )
    }

    @Test
    fun staleSecondMachineCannotOverwriteTheFirstMachinesPersistedRequest() {
        val store = RecordingOnboardingStateStore()
        val first = openMachine(store)
        val staleSecond = openMachine(store)

        assertTrue(
            first.submitPackageCode(
                SensorFamily.SIBIONICS_GS1,
                Gs1PackageCodeInput.Manual("Ab1Zcd34"),
            ) is Gs1OnboardingActionResult.Advanced,
        )
        val staleWrite = staleSecond.submitPackageCode(
            SensorFamily.SIBIONICS_GS1SB,
            Gs1PackageCodeInput.Manual("ZZZZ9999"),
        )

        assertEquals(
            Gs1OnboardingRejectionReason.STORAGE_CONFLICT,
            (staleWrite as Gs1OnboardingActionResult.Rejected).reason,
        )
        val restored = openMachine(store).state as Gs1OnboardingState.Discovering
        assertEquals("Ab1Zcd34", restored.request.packageCode)
        assertEquals(SensorFamily.SIBIONICS_GS1, restored.request.family)
    }

    @Test
    fun invalidTransitionsCannotSkipCodeOrReplaceAPendingProfile() {
        val machine = openMachine(RecordingOnboardingStateStore())
        val beforeCode = machine.resolveAdvertisements(
            listOf(Gs1DiscoveredAdvertisement("GS-Ab1Z", "AA:BB:CC:DD:EE:01")),
        )
        assertEquals(
            Gs1OnboardingRejectionReason.INVALID_TRANSITION,
            (beforeCode as Gs1OnboardingActionResult.Rejected).reason,
        )

        machine.submitPackageCode(
            SensorFamily.SIBIONICS_GS1,
            Gs1PackageCodeInput.Manual("Ab1Zcd34"),
        )
        machine.resolveAdvertisements(
            listOf(Gs1DiscoveredAdvertisement("GS-Ab1Z", "AA:BB:CC:DD:EE:01")),
        )
        val pending = machine.state

        val replacement = machine.submitPackageCode(
            SensorFamily.SIBIONICS_GS1SB,
            Gs1PackageCodeInput.Manual("ZZZZ9999"),
        )

        assertEquals(
            Gs1OnboardingRejectionReason.INVALID_TRANSITION,
            (replacement as Gs1OnboardingActionResult.Rejected).reason,
        )
        assertEquals(pending, machine.state)
    }

    @Test
    fun restartRestoresDiscoveryAndCanContinueToPendingDiagnostic() {
        val store = RecordingOnboardingStateStore()
        val first = openMachine(store)
        first.submitPackageCode(
            SensorFamily.SIBIONICS_GS1SB,
            Gs1PackageCodeInput.DataMatrix("Ab1Zcd34"),
        )

        val restored = openMachine(store)
        val discovery = restored.state as Gs1OnboardingState.Discovering
        assertEquals(SensorFamily.SIBIONICS_GS1SB, discovery.request.family)
        assertEquals(Gs1PackageCodeSource.DATA_MATRIX, discovery.request.source)

        restored.resolveAdvertisements(
            listOf(Gs1DiscoveredAdvertisement("GS-Ab1Z", "aa:bb:cc:dd:ee:09")),
        )
        val pending = openMachine(store).state as Gs1OnboardingState.PendingDiagnostic
        assertEquals("Ab1Zcd34", pending.profile.packageCode)
        assertEquals("AA:BB:CC:DD:EE:09", pending.profile.canonicalBluetoothAddress)
        assertFalse(pending.profile.eligibleForConfirmedConfiguration)
        assertFalse(pending.profile.eligibleForProductPublication)
    }

    @Test
    fun restartPreservesAmbiguityInsteadOfPickingADevice() {
        val store = RecordingOnboardingStateStore()
        val first = openMachine(store)
        first.submitPackageCode(
            SensorFamily.SIBIONICS_GS1,
            Gs1PackageCodeInput.Manual("Ab1Zcd34"),
        )
        first.resolveAdvertisements(
            listOf(
                Gs1DiscoveredAdvertisement("GS-Ab1Z", "AA:BB:CC:DD:EE:01"),
                Gs1DiscoveredAdvertisement("MED-Ab1Z", "AA:BB:CC:DD:EE:02"),
            ),
        )

        val restored = openMachine(store).state as Gs1OnboardingState.ResolutionBlocked

        assertEquals(Gs1OnboardingRejectionReason.AMBIGUOUS_CANDIDATES, restored.reason)
        assertEquals(2, restored.candidates.size)
    }

    @Test
    fun ambiguityRemainsStickyAcrossRestartWhenOnlyOneDeviceIsCurrentlyVisible() {
        val store = RecordingOnboardingStateStore()
        val first = openMachine(store)
        first.submitPackageCode(
            SensorFamily.SIBIONICS_GS1,
            Gs1PackageCodeInput.Manual("Ab1Zcd34"),
        )
        first.resolveAdvertisements(
            listOf(
                Gs1DiscoveredAdvertisement("GS-Ab1Z", "AA:BB:CC:DD:EE:01"),
                Gs1DiscoveredAdvertisement("MED-Ab1Z", "AA:BB:CC:DD:EE:02"),
            ),
        )

        val restored = openMachine(store)
        val result = restored.resolveAdvertisements(
            listOf(Gs1DiscoveredAdvertisement("GS-Ab1Z", "AA:BB:CC:DD:EE:01")),
        )

        assertEquals(
            Gs1OnboardingRejectionReason.AMBIGUOUS_CANDIDATES,
            (result as Gs1OnboardingActionResult.Rejected).reason,
        )
        assertEquals(
            2,
            (restored.state as Gs1OnboardingState.ResolutionBlocked).candidates.size,
        )
    }

    @Test
    fun resetIsPersistedAndIsTheOnlyWayToReplaceARequest() {
        val store = RecordingOnboardingStateStore()
        val first = openMachine(store)
        first.submitPackageCode(
            SensorFamily.SIBIONICS_GS1,
            Gs1PackageCodeInput.Manual("Ab1Zcd34"),
        )
        first.resolveAdvertisements(
            listOf(Gs1DiscoveredAdvertisement("GS-Ab1Z", "AA:BB:CC:DD:EE:01")),
        )

        val reset = first.reset()

        assertTrue(reset is Gs1OnboardingActionResult.Advanced)
        assertEquals(Gs1OnboardingState.AwaitingPackageCode, openMachine(store).state)
    }

    @Test
    fun failedPersistenceDoesNotAdvanceInMemoryState() {
        val store = RecordingOnboardingStateStore().apply { failSaves = true }
        val machine = openMachine(store)

        val result = machine.submitPackageCode(
            SensorFamily.SIBIONICS_GS1,
            Gs1PackageCodeInput.Manual("Ab1Zcd34"),
        )

        assertEquals(
            Gs1OnboardingRejectionReason.STORAGE_UNAVAILABLE,
            (result as Gs1OnboardingActionResult.Rejected).reason,
        )
        assertEquals(Gs1OnboardingState.AwaitingPackageCode, machine.state)
    }

    @Test
    fun corruptSavedStateFailsClosedWithAnExplicitOpenError() {
        val store = RecordingOnboardingStateStore(
            restored = Gs1OnboardingSnapshot(
                schemaVersion = 99,
                stage = Gs1OnboardingStage.PENDING_DIAGNOSTIC,
            ),
        )

        val result = Gs1OnboardingStateMachine.open(store)

        assertEquals(
            Gs1OnboardingOpenError.UNSUPPORTED_SAVED_SCHEMA,
            (result as Gs1OnboardingOpenResult.Failure).error,
        )
    }

    @Test
    fun pendingRestoreRejectsANonCanonicalCandidateIdentity() {
        val result = Gs1OnboardingStateMachine.open(
            RecordingOnboardingStateStore(
                restored = Gs1OnboardingSnapshot(
                    stage = Gs1OnboardingStage.PENDING_DIAGNOSTIC,
                    family = SensorFamily.SIBIONICS_GS1,
                    codeSource = Gs1PackageCodeSource.MANUAL,
                    packageCode = "Ab1Zcd34",
                    selectedDeviceName = "GS-Ab1Z",
                    selectedBluetoothAddress = "aa:bb:cc:dd:ee:01",
                ),
            ),
        )

        assertEquals(
            Gs1OnboardingOpenError.INVALID_SAVED_STATE,
            (result as Gs1OnboardingOpenResult.Failure).error,
        )
    }

    @Test
    fun restoreRejectsAnOversizedAmbiguousCandidateSet() {
        val result = Gs1OnboardingStateMachine.open(
            RecordingOnboardingStateStore(
                restored = Gs1OnboardingSnapshot(
                    stage = Gs1OnboardingStage.RESOLUTION_BLOCKED,
                    family = SensorFamily.SIBIONICS_GS1,
                    codeSource = Gs1PackageCodeSource.MANUAL,
                    packageCode = "Ab1Zcd34",
                    rejectionReason = Gs1OnboardingRejectionReason.AMBIGUOUS_CANDIDATES,
                    candidates = List(65) { index ->
                        Gs1ResolvedAdvertisement(
                            "GS-Ab1Z",
                            "AA:BB:CC:DD:%02X:%02X".format(index / 256, index % 256),
                        )
                    },
                ),
            ),
        )

        assertEquals(
            Gs1OnboardingOpenError.INVALID_SAVED_STATE,
            (result as Gs1OnboardingOpenResult.Failure).error,
        )
    }

    @Test
    fun storageReadFailureIsAnExplicitFailClosedOpenResult() {
        val store = RecordingOnboardingStateStore().apply { failLoads = true }

        val result = Gs1OnboardingStateMachine.open(store)

        assertEquals(
            Gs1OnboardingOpenError.STORAGE_UNAVAILABLE,
            (result as Gs1OnboardingOpenResult.Failure).error,
        )
    }

    private fun machineAfterCode(
        source: Gs1PackageCodeSource = Gs1PackageCodeSource.MANUAL,
    ): Gs1OnboardingStateMachine = openMachine(RecordingOnboardingStateStore()).also { machine ->
        val input = when (source) {
            Gs1PackageCodeSource.MANUAL -> Gs1PackageCodeInput.Manual("Ab1Zcd34")
            Gs1PackageCodeSource.DATA_MATRIX -> Gs1PackageCodeInput.DataMatrix("Ab1Zcd34")
        }
        machine.submitPackageCode(SensorFamily.SIBIONICS_GS1, input)
    }

    private fun openMachine(store: Gs1OnboardingStateStore): Gs1OnboardingStateMachine {
        val opened = Gs1OnboardingStateMachine.open(store)
        assertTrue(opened is Gs1OnboardingOpenResult.Ready)
        return (opened as Gs1OnboardingOpenResult.Ready).machine
    }
}

private fun Gs1OnboardingStateMachine.submitPackageCode(
    family: SensorFamily,
    input: Gs1PackageCodeInput,
): Gs1OnboardingActionResult = submitPackageCode(
    family = family,
    input = input,
    marketProfile = Gs1MarketProfile.GLOBAL,
)

private class RecordingOnboardingStateStore(
    restored: Gs1OnboardingSnapshot? = null,
) : Gs1OnboardingStateStore {
    private var saved = restored
    var failLoads = false
    var failSaves = false

    override fun load(): Gs1OnboardingSnapshot? {
        if (failLoads) error("storage unavailable")
        return saved
    }

    override fun compareAndSet(
        expectedRevision: Long?,
        snapshot: Gs1OnboardingSnapshot,
    ): Boolean {
        if (failSaves) error("storage unavailable")
        if (saved?.revision != expectedRevision) return false
        saved = snapshot
        return true
    }
}
