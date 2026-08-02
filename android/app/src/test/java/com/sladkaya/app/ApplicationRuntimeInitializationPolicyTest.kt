package com.sladkaya.app

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ApplicationRuntimeInitializationPolicyTest {
    @Test
    fun initializesCredentialProtectedRuntimeOnlyInUnlockedMainProcess() {
        assertTrue(
            ApplicationRuntimeInitializationPolicy.shouldInitialize(
                userUnlocked = true,
                isMainProcess = true,
            ),
        )
        assertFalse(
            ApplicationRuntimeInitializationPolicy.shouldInitialize(
                userUnlocked = false,
                isMainProcess = true,
            ),
        )
        assertFalse(
            ApplicationRuntimeInitializationPolicy.shouldInitialize(
                userUnlocked = true,
                isMainProcess = false,
            ),
        )
        assertFalse(
            ApplicationRuntimeInitializationPolicy.shouldInitialize(
                userUnlocked = false,
                isMainProcess = false,
            ),
        )
    }
}
