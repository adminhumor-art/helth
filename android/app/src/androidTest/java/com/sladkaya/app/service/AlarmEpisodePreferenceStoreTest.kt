package com.sladkaya.app.service

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.sladkaya.core.model.AlarmKind
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CountDownLatch
import kotlin.concurrent.thread

@RunWith(AndroidJUnit4::class)
class AlarmEpisodePreferenceStoreTest {
    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        context.getSharedPreferences(
            AlarmEpisodePreferenceStore.PREFERENCES,
            Context.MODE_PRIVATE,
        ).edit().clear().commit()
    }

    @After
    fun tearDown() {
        context.getSharedPreferences(
            AlarmEpisodePreferenceStore.PREFERENCES,
            Context.MODE_PRIVATE,
        ).edit().clear().commit()
    }

    @Test
    fun episodeAndAcknowledgementSurviveStoreRecreation() {
        val episode = episode()
        assertTrue(AlarmEpisodePreferenceStore(context).save(episode))

        val recreated = AlarmEpisodePreferenceStore(context)
        assertEquals(AlarmEpisodeLoadResult.Active(episode), recreated.load())

        val acknowledged = recreated.acknowledge(episode.id)
        assertTrue(acknowledged is AlarmEpisodeStoreAcknowledgement.Accepted)
        assertTrue(
            (AlarmEpisodePreferenceStore(context).load() as AlarmEpisodeLoadResult.Active)
                .episode.acknowledged,
        )
    }

    @Test
    fun staleEpisodeActionCannotMutateCurrentEpisode() {
        val episode = episode()
        val store = AlarmEpisodePreferenceStore(context)
        assertTrue(store.save(episode))

        assertTrue(
            store.acknowledge("stale-episode-0001") is AlarmEpisodeStoreAcknowledgement.Stale,
        )
        assertFalse((store.load() as AlarmEpisodeLoadResult.Active).episode.acknowledged)
    }

    @Test
    fun malformedStateFailsClosedInsteadOfLookingEmpty() {
        context.getSharedPreferences(
            AlarmEpisodePreferenceStore.PREFERENCES,
            Context.MODE_PRIVATE,
        ).edit().putString(AlarmEpisodePreferenceStore.KEY, "broken").commit()

        assertTrue(
            AlarmEpisodePreferenceStore(context).load() is AlarmEpisodeLoadResult.Corrupt,
        )
    }

    @Test
    fun atomicSnapshotTransformCannotOverwriteConcurrentAcknowledgement() {
        val original = episode()
        val store = AlarmEpisodePreferenceStore(context)
        assertTrue(store.save(original))
        val snapshotLoaded = CountDownLatch(1)
        val releaseSnapshot = CountDownLatch(1)

        val snapshotThread = thread {
            store.atomically {
                val loaded = (load() as AlarmEpisodeLoadResult.Active).episode
                snapshotLoaded.countDown()
                releaseSnapshot.await()
                save(
                    loaded.copy(
                        reading = checkNotNull(loaded.reading).copy(glucoseMgDl = 54),
                    ),
                )
            }
        }
        snapshotLoaded.await()
        val acknowledgementThread = thread {
            store.acknowledge(original.id)
        }
        releaseSnapshot.countDown()
        snapshotThread.join()
        acknowledgementThread.join()

        val final = (store.load() as AlarmEpisodeLoadResult.Active).episode
        assertTrue(final.acknowledged)
        assertEquals(54, final.reading?.glucoseMgDl)
    }

    private fun episode() = AlarmEpisode(
        id = "episode-store0001",
        activeKinds = setOf(AlarmKind.LOW),
        acknowledged = false,
        openedAtEpochMs = 1_800_000_000_000L,
        lastAlertAtEpochMs = 1_800_000_000_000L,
        demo = true,
        reading = AlarmReadingSnapshot(
            glucoseMgDl = 60,
            sensorTimeEpochMs = 1_800_000_000_000L,
            phoneTimeEpochMs = 1_800_000_000_000L,
        ),
    )
}
