package com.sladkaya.app.service

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SignalLossWatchdogPreferenceStoreTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    @Before
    @After
    fun clearPreferences() {
        context.getSharedPreferences(
            SignalLossWatchdogPreferenceStore.PREFERENCES,
            Context.MODE_PRIVATE,
        ).edit().clear().commit()
    }

    @Test
    fun stateSurvivesStoreRecreationAndCanBeCleared() {
        val state = state()

        assertTrue(SignalLossWatchdogPreferenceStore(context).save(state))
        assertEquals(
            SignalLossWatchdogLoadResult.Active(state),
            SignalLossWatchdogPreferenceStore(context).load(),
        )
        assertTrue(SignalLossWatchdogPreferenceStore(context).clear())
        assertTrue(
            SignalLossWatchdogPreferenceStore(context).load() ===
                SignalLossWatchdogLoadResult.Empty,
        )
    }

    @Test
    fun malformedDurableStateFailsClosed() {
        context.getSharedPreferences(
            SignalLossWatchdogPreferenceStore.PREFERENCES,
            Context.MODE_PRIVATE,
        ).edit().putString(SignalLossWatchdogPreferenceStore.KEY, "broken").commit()

        assertTrue(
            SignalLossWatchdogPreferenceStore(context).load() ===
                SignalLossWatchdogLoadResult.Corrupt,
        )
    }

    private fun state() = SignalLossWatchdogState(
        generation = 3L,
        readingIdentity = "d".repeat(64),
        sensorTimeEpochMs = 1_800_000_000_000L,
        phoneTimeEpochMs = 1_800_000_001_000L,
        staleAfterMs = 600_000L,
        demo = false,
    )
}
