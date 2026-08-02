package com.sladkaya.app.service

import android.content.Context
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.sladkaya.core.model.AlarmKind
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AlarmAcknowledgeReceiverTest {
    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        AlarmEpisodePreferenceStore(context).clear()
    }

    @After
    fun tearDown() {
        AlarmEpisodePreferenceStore(context).clear()
        AlarmRepeatScheduler(context).cancel()
        AlarmNotifier(context).cancelAllAlarms()
    }

    @Test
    fun receiverRejectsStaleActionAndAcceptsOnlyCurrentEpisode() {
        val episode = episode()
        val store = AlarmEpisodePreferenceStore(context)
        assertTrue(store.save(episode))
        val receiver = AlarmAcknowledgeReceiver()

        receiver.onReceive(context, AlarmAcknowledgeReceiver.intent(context, "stale-episode-0001"))
        assertFalse((store.load() as AlarmEpisodeLoadResult.Active).episode.acknowledged)

        receiver.onReceive(context, AlarmAcknowledgeReceiver.intent(context, episode.id))
        assertTrue((store.load() as AlarmEpisodeLoadResult.Active).episode.acknowledged)
    }

    @Test
    fun malformedOrMissingIntentCannotAcknowledge() {
        val episode = episode()
        val store = AlarmEpisodePreferenceStore(context)
        assertTrue(store.save(episode))
        val receiver = AlarmAcknowledgeReceiver()

        receiver.onReceive(context, Intent(context, AlarmAcknowledgeReceiver::class.java))

        assertFalse((store.load() as AlarmEpisodeLoadResult.Active).episode.acknowledged)
    }

    private fun episode() = AlarmEpisode(
        id = "episode-acknow001",
        activeKinds = setOf(AlarmKind.LOW),
        acknowledged = false,
        openedAtEpochMs = 1_800_000_000_000L,
        lastAlertAtEpochMs = 1_800_000_000_000L,
        demo = true,
        reading = null,
    )
}
