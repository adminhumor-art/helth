package com.sladkaya.app.service

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class DiagnosticSessionPreferenceStoreTest {
    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        clear()
    }

    @After
    fun tearDown() = clear()

    @Test
    fun explicitStartAndStopAreBoundToOneProfileIdentity() {
        val profileA = "a".repeat(64)
        val profileB = "b".repeat(64)
        val first = DiagnosticSessionPreferenceStore(context)
        assertFalse(first.matches(profileA))

        assertTrue(first.markRunning(profileA))
        assertTrue(DiagnosticSessionPreferenceStore(context).matches(profileA))
        assertFalse(DiagnosticSessionPreferenceStore(context).matches(profileB))

        assertTrue(DiagnosticSessionPreferenceStore(context).clear())
        assertFalse(DiagnosticSessionPreferenceStore(context).matches(profileA))
    }

    @Test
    fun arbitraryTextCannotBecomeAResumeIdentity() {
        val store = DiagnosticSessionPreferenceStore(context)

        assertFalse(store.markRunning("profile-a"))
        assertFalse(store.matches("profile-a"))
    }

    private fun clear() {
        check(
            context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
                .edit()
                .clear()
                .commit(),
        )
    }

    private companion object {
        const val PREFERENCES = "diagnostic_session_preference"
    }
}
