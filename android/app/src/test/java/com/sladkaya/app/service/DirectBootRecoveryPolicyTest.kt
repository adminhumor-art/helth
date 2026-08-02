package com.sladkaya.app.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DirectBootRecoveryPolicyTest {
    @Test
    fun lockedBootShowsOnlyUnlockRequiredWarning() {
        assertEquals(
            DirectBootRecoveryDecision.ShowUnlockRequiredWarning,
            DirectBootRecoveryPolicy.decide("android.intent.action.LOCKED_BOOT_COMPLETED"),
        )

        val presentation = DirectBootRecoveryPolicy.warningPresentation()
        assertEquals("Контроль не восстановлен", presentation.title)
        assertEquals(
            "Разблокируйте телефон — контроль не восстановлен",
            presentation.message,
        )
        assertTrue(presentation.ongoing)
        assertFalse(presentation.opensCredentialProtectedUi)
        var initializationAttempts = 0
        assertFalse(
            DirectBootRecoveryPolicy.initializeCredentialProtectedRuntimeIfAllowed(
                userUnlocked = false,
            ) {
                initializationAttempts += 1
            },
        )
        assertEquals(0, initializationAttempts)
    }

    @Test
    fun unlockedBootContinuesExistingRecoveryAndClearsWarning() {
        assertEquals(
            DirectBootRecoveryDecision.ContinueNormalRecovery,
            DirectBootRecoveryPolicy.decide("android.intent.action.BOOT_COMPLETED"),
        )
        var initializationAttempts = 0
        assertTrue(
            DirectBootRecoveryPolicy.initializeCredentialProtectedRuntimeIfAllowed(
                userUnlocked = true,
            ) {
                initializationAttempts += 1
            },
        )
        assertEquals(1, initializationAttempts)
    }

    @Test
    fun unrelatedBroadcastIsIgnored() {
        assertEquals(
            DirectBootRecoveryDecision.Ignore,
            DirectBootRecoveryPolicy.decide("example.UNRELATED"),
        )
    }
}
