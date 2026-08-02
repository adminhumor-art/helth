package com.sladkaya.app.service

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.sladkaya.core.model.SensorFamily
import com.sladkaya.sensor.sibionics.Gs1OnboardingActionResult
import com.sladkaya.sensor.sibionics.Gs1OnboardingOpenError
import com.sladkaya.sensor.sibionics.Gs1OnboardingOpenResult
import com.sladkaya.sensor.sibionics.Gs1OnboardingRejectionReason
import com.sladkaya.sensor.sibionics.Gs1OnboardingState
import com.sladkaya.sensor.sibionics.Gs1OnboardingStateMachine
import com.sladkaya.sensor.sibionics.Gs1DiscoveredAdvertisement
import com.sladkaya.sensor.sibionics.Gs1MarketProfile
import com.sladkaya.sensor.sibionics.Gs1PackageCodeInput
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PendingDiagnosticGs1OnboardingStateStoreTest {
    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        clearTestPreferences()
    }

    @After
    fun tearDown() {
        clearTestPreferences()
    }

    @Test
    fun aNewStoreInstanceRestoresTheExactPendingDraft() {
        val first = openMachine(PendingDiagnosticGs1OnboardingStateStore(context))
        first.submitPackageCode(
            SensorFamily.SIBIONICS_GS1SB,
            Gs1PackageCodeInput.DataMatrix("Ab1Zcd34"),
        )

        val restarted = openMachine(PendingDiagnosticGs1OnboardingStateStore(context))

        val discovering = restarted.state as Gs1OnboardingState.Discovering
        assertEquals(SensorFamily.SIBIONICS_GS1SB, discovering.request.family)
        assertEquals(Gs1MarketProfile.GLOBAL, discovering.request.marketProfile)
        assertEquals("Ab1Zcd34", discovering.request.packageCode)
    }

    @Test
    fun corruptStoredBytesFailClosedInsteadOfStartingNewOnboarding() {
        assertTrue(
            context.getSharedPreferences(PENDING_PREFERENCES, Context.MODE_PRIVATE)
                .edit()
                .putString(PENDING_KEY, "corrupt")
                .commit(),
        )

        val opened = Gs1OnboardingStateMachine.open(
            PendingDiagnosticGs1OnboardingStateStore(context),
        )

        assertEquals(
            Gs1OnboardingOpenError.STORAGE_UNAVAILABLE,
            (opened as Gs1OnboardingOpenResult.Failure).error,
        )
    }

    @Test
    fun twoMachinesCannotOverwriteEachOthersRevision() {
        val first = openMachine(PendingDiagnosticGs1OnboardingStateStore(context))
        val staleSecond = openMachine(PendingDiagnosticGs1OnboardingStateStore(context))

        assertTrue(
            first.submitPackageCode(
                SensorFamily.SIBIONICS_GS1,
                Gs1PackageCodeInput.Manual("Ab1Zcd34"),
            ) is Gs1OnboardingActionResult.Advanced,
        )
        val stale = staleSecond.submitPackageCode(
            SensorFamily.SIBIONICS_GS1SB,
            Gs1PackageCodeInput.Manual("ZZZZ9999"),
        )

        assertEquals(
            Gs1OnboardingRejectionReason.STORAGE_CONFLICT,
            (stale as Gs1OnboardingActionResult.Rejected).reason,
        )
        val restored = openMachine(PendingDiagnosticGs1OnboardingStateStore(context))
            .state as Gs1OnboardingState.Discovering
        assertEquals(SensorFamily.SIBIONICS_GS1, restored.request.family)
        assertEquals("Ab1Zcd34", restored.request.packageCode)
    }

    @Test
    fun serviceLoaderReturnsOnlyACompletePendingDiagnosticProfile() {
        val store = PendingDiagnosticGs1OnboardingStateStore(context)
        val machine = openMachine(store)
        machine.submitPackageCode(
            SensorFamily.SIBIONICS_GS1,
            Gs1PackageCodeInput.Manual("Ab1Zcd34"),
        )
        assertEquals(null, store.loadPendingDiagnosticProfile())

        machine.resolveAdvertisements(
            listOf(Gs1DiscoveredAdvertisement("GS-Ab1Z", "AA:BB:CC:DD:EE:01")),
        )

        val profile = store.loadPendingDiagnosticProfile()
        assertEquals("Ab1Zcd34", profile?.packageCode)
        assertEquals(Gs1MarketProfile.GLOBAL, profile?.marketProfile)
        assertEquals("AA:BB:CC:DD:EE:01", profile?.canonicalBluetoothAddress)
    }

    @Test
    fun clearingDraftRemovesOnlyPendingDiagnosticState() {
        val store = PendingDiagnosticGs1OnboardingStateStore(context)
        val machine = openMachine(store)
        machine.submitPackageCode(
            SensorFamily.SIBIONICS_GS1,
            Gs1PackageCodeInput.Manual("Ab1Zcd34"),
        )

        assertTrue(store.clearDraft())

        assertEquals(null, store.load())
    }

    private fun openMachine(
        store: PendingDiagnosticGs1OnboardingStateStore,
    ): Gs1OnboardingStateMachine {
        val opened = Gs1OnboardingStateMachine.open(store)
        assertTrue(opened is Gs1OnboardingOpenResult.Ready)
        return (opened as Gs1OnboardingOpenResult.Ready).machine
    }

    private fun clearTestPreferences() {
        check(
            context.getSharedPreferences(PENDING_PREFERENCES, Context.MODE_PRIVATE)
                .edit()
                .clear()
                .commit(),
        )
    }

    private companion object {
        const val PENDING_PREFERENCES = "pending_diagnostic_gs1_onboarding"
        const val PENDING_KEY = "snapshot_current"
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
