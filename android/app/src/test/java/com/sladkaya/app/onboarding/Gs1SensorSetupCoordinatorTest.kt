package com.sladkaya.app.onboarding

import com.sladkaya.core.model.SensorFamily
import com.sladkaya.sensor.sibionics.Gs1AdvertisementScanOutcome
import com.sladkaya.sensor.sibionics.Gs1AdvertisementScanner
import com.sladkaya.sensor.sibionics.Gs1DiscoveredAdvertisement
import com.sladkaya.sensor.sibionics.Gs1MarketProfile
import com.sladkaya.sensor.sibionics.Gs1OnboardingSnapshot
import com.sladkaya.sensor.sibionics.Gs1OnboardingState
import com.sladkaya.sensor.sibionics.Gs1OnboardingStateStore
import com.sladkaya.sensor.sibionics.Gs1PackageCodeInput
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class Gs1SensorSetupCoordinatorTest {
    @Test
    fun missingOrUnsupportedMarketNeverCallsTheBleScanner() = runBlocking {
        val scanner = QueueScanner(ArrayDeque())
        val coordinator = Gs1SensorSetupCoordinator(InMemoryStore(), scanner)

        assertFalse(
            coordinator.submitPackageCode(
                SensorFamily.SIBIONICS_GS1,
                Gs1PackageCodeInput.Manual("Ab1Zcd34"),
                marketProfile = null,
            ),
        )
        assertFalse(coordinator.search())
        assertEquals(0, scanner.calls)

        assertFalse(
            coordinator.submitPackageCode(
                SensorFamily.SIBIONICS_GS1,
                Gs1PackageCodeInput.Manual("Ab1Zcd34"),
                marketProfile = Gs1MarketProfile.RUSSIAN,
            ),
        )
        assertFalse(coordinator.search())
        assertEquals(0, scanner.calls)
        assertEquals(
            "PROFILE_NOT_PHYSICALLY_VERIFIED",
            coordinator.state.value.technicalCode,
        )
    }

    @Test
    fun chineseMarketUsesTheSameBoundedDiagnosticSearch() = runBlocking {
        val scanner = QueueScanner(
            ArrayDeque(
                listOf(
                    Gs1AdvertisementScanOutcome.Success(
                        listOf(Gs1DiscoveredAdvertisement("GS-Ab1Z", "AA:BB:CC:DD:EE:01")),
                    ),
                ),
            ),
        )
        val coordinator = Gs1SensorSetupCoordinator(InMemoryStore(), scanner)

        assertTrue(
            coordinator.submitPackageCode(
                SensorFamily.SIBIONICS_GS1,
                Gs1PackageCodeInput.Manual("Ab1Zcd34"),
                marketProfile = Gs1MarketProfile.CHINESE,
            ),
        )
        assertTrue(coordinator.search())

        val pending = coordinator.state.value.onboarding as Gs1OnboardingState.PendingDiagnostic
        assertEquals(Gs1MarketProfile.CHINESE, pending.profile.marketProfile)
        assertEquals(2, pending.profile.transportVariant)
        assertEquals(1, scanner.calls)
    }

    @Test
    fun ecoSplitBlockExplainsThatASeparateTwoDataMatrixFlowIsRequired() {
        val coordinator = Gs1SensorSetupCoordinator(
            InMemoryStore(),
            QueueScanner(ArrayDeque()),
        )

        assertFalse(
            coordinator.submitPackageCode(
                SensorFamily.SIBIONICS_GS1,
                Gs1PackageCodeInput.DataMatrix("0316015A"),
                marketProfile = Gs1MarketProfile.ECO_SPLIT,
            ),
        )

        assertTrue(coordinator.state.value.message.orEmpty().contains("два DataMatrix"))
        assertEquals(
            "PROFILE_NOT_PHYSICALLY_VERIFIED",
            coordinator.state.value.technicalCode,
        )
    }

    @Test
    fun exactCodeAndOneAdvertisementProduceOnlyPendingDiagnostic() = runBlocking {
        val coordinator = coordinator(
            Gs1AdvertisementScanOutcome.Success(
                listOf(Gs1DiscoveredAdvertisement("GS-Ab1Z", "aa:bb:cc:dd:ee:01")),
            ),
        )

        assertTrue(
            coordinator.submitPackageCode(
                SensorFamily.SIBIONICS_GS1,
                Gs1PackageCodeInput.Manual("Ab1Zcd34"),
            ),
        )
        assertTrue(coordinator.search())

        val pending = coordinator.state.value.onboarding as Gs1OnboardingState.PendingDiagnostic
        assertEquals("Ab1Zcd34", pending.profile.packageCode)
        assertEquals("AA:BB:CC:DD:EE:01", pending.profile.canonicalBluetoothAddress)
        assertFalse(pending.profile.eligibleForProductPublication)
    }

    @Test
    fun platformFailureKeepsTheSavedRequestRetryable() = runBlocking {
        val coordinator = coordinator(Gs1AdvertisementScanOutcome.BluetoothDisabled)
        coordinator.submitPackageCode(
            SensorFamily.SIBIONICS_GS1SB,
            Gs1PackageCodeInput.DataMatrix("Ab1Zcd34"),
        )

        assertFalse(coordinator.search())

        val state = coordinator.state.value
        assertTrue(state.onboarding is Gs1OnboardingState.Discovering)
        assertEquals("BLUETOOTH_DISABLED", state.technicalCode)
        assertTrue(state.canRetrySearch)
    }

    @Test
    fun legacyLocationSwitchFailureHasAnExplicitRecoveryMessage() = runBlocking {
        val coordinator = coordinator(Gs1AdvertisementScanOutcome.LocationServicesDisabled)
        coordinator.submitPackageCode(
            SensorFamily.SIBIONICS_GS1,
            Gs1PackageCodeInput.Manual("Ab1Zcd34"),
        )

        assertFalse(coordinator.search())

        assertEquals("LOCATION_SERVICES_DISABLED", coordinator.state.value.technicalCode)
        assertTrue(coordinator.state.value.message.orEmpty().contains("геолокацию"))
        assertTrue(coordinator.state.value.canRetrySearch)
    }

    @Test
    fun noMatchCanBeRetriedWithoutReenteringTheCode() = runBlocking {
        val scanner = QueueScanner(
            ArrayDeque(
                listOf(
                    Gs1AdvertisementScanOutcome.Success(emptyList()),
                    Gs1AdvertisementScanOutcome.Success(
                        listOf(Gs1DiscoveredAdvertisement("GS-Ab1Z", "AA:BB:CC:DD:EE:02")),
                    ),
                ),
            ),
        )
        val coordinator = Gs1SensorSetupCoordinator(InMemoryStore(), scanner)
        coordinator.submitPackageCode(
            SensorFamily.SIBIONICS_GS1,
            Gs1PackageCodeInput.Manual("Ab1Zcd34"),
        )

        assertFalse(coordinator.search())
        assertTrue(coordinator.state.value.canRetrySearch)
        assertTrue(coordinator.search())

        assertTrue(coordinator.state.value.onboarding is Gs1OnboardingState.PendingDiagnostic)
    }

    @Test
    fun ambiguityRequiresResetAndNeverOffersAutomaticRetry() = runBlocking {
        val coordinator = coordinator(
            Gs1AdvertisementScanOutcome.Success(
                listOf(
                    Gs1DiscoveredAdvertisement("GS-Ab1Z", "AA:BB:CC:DD:EE:01"),
                    Gs1DiscoveredAdvertisement("MED-Ab1Z", "AA:BB:CC:DD:EE:02"),
                ),
            ),
        )
        coordinator.submitPackageCode(
            SensorFamily.SIBIONICS_GS1,
            Gs1PackageCodeInput.Manual("Ab1Zcd34"),
        )

        assertFalse(coordinator.search())

        assertTrue(coordinator.state.value.onboarding is Gs1OnboardingState.ResolutionBlocked)
        assertFalse(coordinator.state.value.canRetrySearch)
        assertFalse(coordinator.search())
        assertTrue(coordinator.reset())
        assertEquals(Gs1OnboardingState.AwaitingPackageCode, coordinator.state.value.onboarding)
    }

    @Test
    fun malformedManualCodeNeverStartsBluetoothDiscovery() = runBlocking {
        val scanner = QueueScanner(ArrayDeque())
        val coordinator = Gs1SensorSetupCoordinator(InMemoryStore(), scanner)

        assertFalse(
            coordinator.submitPackageCode(
                SensorFamily.SIBIONICS_GS1,
                Gs1PackageCodeInput.Manual("too-long-code"),
            ),
        )
        assertFalse(coordinator.search())

        assertEquals(0, scanner.calls)
        assertEquals(Gs1OnboardingState.AwaitingPackageCode, coordinator.state.value.onboarding)
        assertEquals("INVALID_PACKAGE_CODE_LENGTH", coordinator.state.value.technicalCode)
    }

    @Test
    fun aSecondConcurrentSearchIsRejectedWithoutStartingAnotherBleScan() = runBlocking {
        val scanner = BlockingScanner()
        val coordinator = Gs1SensorSetupCoordinator(InMemoryStore(), scanner)
        coordinator.submitPackageCode(
            SensorFamily.SIBIONICS_GS1,
            Gs1PackageCodeInput.Manual("Ab1Zcd34"),
        )

        val first = async { coordinator.search() }
        scanner.started.await()

        assertFalse(coordinator.search())
        assertEquals(1, scanner.calls)

        scanner.complete(
            Gs1AdvertisementScanOutcome.Success(
                listOf(Gs1DiscoveredAdvertisement("GS-Ab1Z", "AA:BB:CC:DD:EE:03")),
            ),
        )
        assertTrue(first.await())
        assertTrue(coordinator.state.value.onboarding is Gs1OnboardingState.PendingDiagnostic)
    }

    @Test
    fun cancelSearchStopsItsJobAndCannotPublishALatePendingProfile() = runBlocking {
        val scanner = BlockingScanner()
        val coordinator = Gs1SensorSetupCoordinator(InMemoryStore(), scanner)
        coordinator.submitPackageCode(
            SensorFamily.SIBIONICS_GS1,
            Gs1PackageCodeInput.Manual("Ab1Zcd34"),
        )
        val search = async { coordinator.search() }
        scanner.started.await()

        coordinator.cancelSearch()
        search.cancelAndJoin()
        scanner.complete(
            Gs1AdvertisementScanOutcome.Success(
                listOf(Gs1DiscoveredAdvertisement("GS-Ab1Z", "AA:BB:CC:DD:EE:04")),
            ),
        )

        assertTrue(coordinator.state.value.onboarding is Gs1OnboardingState.Discovering)
        assertFalse(coordinator.state.value.scanning)
    }

    @Test
    fun resetCancelsAnActiveSearchBeforeClearingTheDraft() = runBlocking {
        val scanner = BlockingScanner()
        val coordinator = Gs1SensorSetupCoordinator(InMemoryStore(), scanner)
        coordinator.submitPackageCode(
            SensorFamily.SIBIONICS_GS1,
            Gs1PackageCodeInput.Manual("Ab1Zcd34"),
        )
        val search = async { coordinator.search() }
        scanner.started.await()

        assertTrue(coordinator.reset())
        search.cancelAndJoin()

        assertEquals(Gs1OnboardingState.AwaitingPackageCode, coordinator.state.value.onboarding)
        assertFalse(coordinator.state.value.scanning)
    }

    private fun coordinator(outcome: Gs1AdvertisementScanOutcome) =
        Gs1SensorSetupCoordinator(
            stateStore = InMemoryStore(),
            scanner = QueueScanner(ArrayDeque(listOf(outcome))),
        )

    private class QueueScanner(
        private val outcomes: ArrayDeque<Gs1AdvertisementScanOutcome>,
    ) : Gs1AdvertisementScanner {
        var calls = 0
            private set

        override suspend fun scan(): Gs1AdvertisementScanOutcome {
            calls += 1
            return outcomes.removeFirst()
        }
    }

    private class BlockingScanner : Gs1AdvertisementScanner {
        val started = CompletableDeferred<Unit>()
        private val outcome = CompletableDeferred<Gs1AdvertisementScanOutcome>()
        var calls = 0
            private set

        override suspend fun scan(): Gs1AdvertisementScanOutcome {
            calls += 1
            started.complete(Unit)
            return outcome.await()
        }

        fun complete(value: Gs1AdvertisementScanOutcome) {
            outcome.complete(value)
        }
    }

    private class InMemoryStore : Gs1OnboardingStateStore {
        private var snapshot: Gs1OnboardingSnapshot? = null

        override fun load(): Gs1OnboardingSnapshot? = snapshot

        override fun compareAndSet(
            expectedRevision: Long?,
            snapshot: Gs1OnboardingSnapshot,
        ): Boolean {
            if (this.snapshot?.revision != expectedRevision) return false
            this.snapshot = snapshot
            return true
        }
    }
}

private fun Gs1SensorSetupCoordinator.submitPackageCode(
    family: SensorFamily,
    input: Gs1PackageCodeInput,
): Boolean = submitPackageCode(
    family = family,
    input = input,
    marketProfile = Gs1MarketProfile.GLOBAL,
)
