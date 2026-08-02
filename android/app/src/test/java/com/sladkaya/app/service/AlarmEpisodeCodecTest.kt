package com.sladkaya.app.service

import com.sladkaya.core.model.AlarmKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AlarmEpisodeCodecTest {
    private val codec = AlarmEpisodeCodec()

    @Test
    fun episodeRoundTripsWithStableChecksumAndDemoMarker() {
        val episode = AlarmEpisode(
            id = "episode-12345678",
            activeKinds = linkedSetOf(AlarmKind.LOW, AlarmKind.RAPID_FALL),
            acknowledged = false,
            openedAtEpochMs = 1_800_000_000_000L,
            lastAlertAtEpochMs = 1_800_000_120_000L,
            demo = true,
            reading = AlarmReadingSnapshot(
                glucoseMgDl = 58,
                sensorTimeEpochMs = 1_800_000_000_000L,
                phoneTimeEpochMs = 1_800_000_001_000L,
            ),
        )

        val encoded = codec.encode(episode)

        assertEquals(AlarmEpisodeDecodeResult.Success(episode), codec.decode(encoded))
        assertEquals(encoded, codec.encode(episode))
    }

    @Test
    fun checksumMutationAndUnknownKindsFailClosed() {
        val encoded = codec.encode(
            AlarmEpisode(
                id = "episode-12345678",
                activeKinds = setOf(AlarmKind.LOW),
                acknowledged = false,
                openedAtEpochMs = 1_800_000_000_000L,
                lastAlertAtEpochMs = 1_800_000_000_000L,
                demo = false,
                reading = null,
            ),
        )

        assertTrue(codec.decode(encoded.replace("LOW", "HIGH")) is AlarmEpisodeDecodeResult.Failure)
        assertTrue(codec.decode(encoded.replace("LOW", "UNKNOWN")) is AlarmEpisodeDecodeResult.Failure)
        assertTrue(codec.decode("not-an-episode") is AlarmEpisodeDecodeResult.Failure)
    }
}
