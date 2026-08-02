package com.sladkaya.app.service

import android.app.Notification
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.core.app.NotificationCompat
import com.sladkaya.core.model.AlarmKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AlarmNotificationContractTest {
    @Test
    fun activeEpisodeHasStableChannelAndDirectAcknowledgementAction() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val episode = episode()

        val notification = AlarmNotifier(context).episodeNotification(
            episode = episode,
            alert = true,
        )

        assertEquals(AlarmNotifier.ALARM_CHANNEL, notification.channelId)
        assertEquals(Notification.CATEGORY_ALARM, notification.category)
        assertEquals(Notification.VISIBILITY_PUBLIC, notification.visibility)
        assertTrue(notification.flags and Notification.FLAG_ONGOING_EVENT != 0)
        assertNotNull(notification.contentIntent)
        assertEquals(1, notification.actions.size)
        assertEquals("Подтвердить", notification.actions.single().title.toString())
        assertNotNull(notification.actions.single().actionIntent)
        assertEquals(null, notification.fullScreenIntent)
    }

    @Test
    fun acknowledgedEpisodeRemainsVisibleButCannotAlertOrAcknowledgeAgain() {
        val context = ApplicationProvider.getApplicationContext<Context>()

        val notification = AlarmNotifier(context).episodeNotification(
            episode = episode().copy(acknowledged = true),
            alert = false,
        )

        assertTrue(notification.flags and Notification.FLAG_ONLY_ALERT_ONCE != 0)
        assertEquals(NotificationCompat.GROUP_KEY_SILENT, notification.group)
        assertTrue(notification.flags and Notification.FLAG_ONGOING_EVENT != 0)
        assertTrue(notification.actions.isEmpty())
    }

    private fun episode() = AlarmEpisode(
        id = "episode-notify001",
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
