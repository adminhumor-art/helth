package com.sladkaya.app.settings

import com.sladkaya.core.model.AlarmThresholds
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AlarmSettingsCodecTest {
    private val codec = AlarmSettingsCodec()

    @Test
    fun roundTripPreservesEveryAlarmThresholdExactly() {
        val settings = AlarmThresholds(
            lowMgDl = 65,
            highMgDl = 230,
            rapidFallMgDlPerMinute = -2.5,
            rapidRiseMgDlPerMinute = 2.75,
            recoveryHysteresisMgDl = 7,
            staleAfterMs = 15 * 60_000L,
        )

        val decoded = codec.decode(codec.encode(settings))

        assertEquals(settings, (decoded as AlarmSettingsDecodeResult.Success).thresholds)
    }

    @Test
    fun corruptionNeverSilentlyChangesAlarmThresholds() {
        val encoded = codec.encode(AlarmThresholds())
        val replacement = if (encoded.last() == 'A') 'B' else 'A'

        val decoded = codec.decode(encoded.dropLast(1) + replacement)

        assertTrue(decoded is AlarmSettingsDecodeResult.Failure)
    }

    @Test
    fun malformedAndOversizedValuesFailClosed() {
        assertEquals(
            AlarmSettingsDecodeError.MALFORMED_ENCODING,
            (codec.decode("not base64 !") as AlarmSettingsDecodeResult.Failure).error,
        )
        assertEquals(
            AlarmSettingsDecodeError.VALUE_TOO_LARGE,
            (codec.decode("A".repeat(10_000)) as AlarmSettingsDecodeResult.Failure).error,
        )
    }
}
