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
import com.sladkaya.sensor.sibionics.Gs1PackageCodeInput
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
    fun pendingDraftNeverCreatesAConfirmedConfigurationMarker() {
        val machine = openMachine(PendingDiagnosticGs1OnboardingStateStore(context))
        machine.submitPackageCode(
            SensorFamily.SIBIONICS_GS1,
            Gs1PackageCodeInput.Manual("Ab1Zcd34"),
        )

        assertFalse(ConfirmedSensorConfigurationStore(context).hasConfirmedConfiguration())
        assertFalse(
            context.getSharedPreferences(CONFIRMED_PREFERENCES, Context.MODE_PRIVATE)
                .contains("confirmed"),
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

    private fun openMachine(
        store: PendingDiagnosticGs1OnboardingStateStore,
    ): Gs1OnboardingStateMachine {
        val opened = Gs1OnboardingStateMachine.open(store)
        assertTrue(opened is Gs1OnboardingOpenResult.Ready)
        return (opened as Gs1OnboardingOpenResult.Ready).machine
    }

    private fun clearTestPreferences() {
        listOf(PENDING_PREFERENCES, CONFIRMED_PREFERENCES).forEach { name ->
            check(context.getSharedPreferences(name, Context.MODE_PRIVATE).edit().clear().commit())
        }
    }

    private companion object {
        const val PENDING_PREFERENCES = "pending_diagnostic_gs1_onboarding"
        const val PENDING_KEY = "snapshot_v1"
        const val CONFIRMED_PREFERENCES = "confirmed_sensor_configuration"
    }
}
