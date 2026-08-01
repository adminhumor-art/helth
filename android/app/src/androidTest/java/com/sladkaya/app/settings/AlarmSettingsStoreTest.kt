package com.sladkaya.app.settings

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.sladkaya.core.model.AlarmThresholds
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AlarmSettingsStoreTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    @Before
    @After
    fun clearSettings() {
        context.getSharedPreferences(AlarmSettingsStore.PREFERENCES, Context.MODE_PRIVATE)
            .edit()
            .clear()
            .commit()
    }

    @Test
    fun emptyStoreLoadsDefaultsWithoutClaimingRecovery() {
        val loaded = AlarmSettingsStore(context).load()

        assertEquals(AlarmThresholds(), loaded.thresholds)
        assertFalse(loaded.recoveredFromCorruption)
    }

    @Test
    fun savedThresholdsSurviveAStoreReopen() {
        val expected = AlarmThresholds(lowMgDl = 65, highMgDl = 230, staleAfterMs = 15 * 60_000L)
        assertTrue(AlarmSettingsStore(context).save(expected))

        val loaded = AlarmSettingsStore(context).load()

        assertEquals(expected, loaded.thresholds)
        assertFalse(loaded.recoveredFromCorruption)
    }

    @Test
    fun corruptedValueRecoversToExplicitDefaultsAndReportsIt() {
        context.getSharedPreferences(AlarmSettingsStore.PREFERENCES, Context.MODE_PRIVATE)
            .edit()
            .putString(AlarmSettingsStore.KEY, "corrupt")
            .commit()

        val loaded = AlarmSettingsStore(context).load()

        assertEquals(AlarmThresholds(), loaded.thresholds)
        assertTrue(loaded.recoveredFromCorruption)
    }
}
