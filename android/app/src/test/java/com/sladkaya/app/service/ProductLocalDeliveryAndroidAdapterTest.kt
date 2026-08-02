package com.sladkaya.app.service

import com.sladkaya.core.model.AlarmKind
import com.sladkaya.core.model.GlucoseReading
import com.sladkaya.core.model.ReadingQuality
import com.sladkaya.core.model.SensorFamily
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ProductLocalDeliveryAndroidAdapterTest {
    @Test
    fun productAlarmIsNotMarkedAudiblyDeliveredWhileAndroidBlocksItsSound() {
        val audibleBlockers = setOf(
            AlarmReadinessBlocker.NOTIFICATION_PERMISSION,
            AlarmReadinessBlocker.APPLICATION_NOTIFICATIONS,
            AlarmReadinessBlocker.ALARM_CHANNEL_MISSING,
            AlarmReadinessBlocker.ALARM_CHANNEL_IMPORTANCE,
            AlarmReadinessBlocker.ALARM_CHANNEL_SOUND,
            AlarmReadinessBlocker.ALARM_AUDIO_USAGE,
            AlarmReadinessBlocker.ALARM_CHANNEL_VIBRATION,
            AlarmReadinessBlocker.ALARM_VOLUME,
            AlarmReadinessBlocker.DO_NOT_DISTURB,
        )

        audibleBlockers.forEach { blocker ->
            assertFalse(
                blocker.name,
                ProductAlarmDeliveryReadinessPolicy.canDeliverAudibly(setOf(blocker)),
            )
        }
        assertTrue(
            ProductAlarmDeliveryReadinessPolicy.canDeliverAudibly(
                setOf(
                    AlarmReadinessBlocker.EXACT_ALARM_ACCESS,
                    AlarmReadinessBlocker.BATTERY_OPTIMIZATION,
                ),
            ),
        )
    }

    @Test
    fun effectsRouteShowUpdateRepeatCloseAndWidgetWithoutAlarmPreferences() = runBlocking {
        val notifications = RecordingProductNotificationPort()
        val widgets = RecordingProductWidgetPort()
        val effects = AndroidProductLocalDeliveryEffects(notifications, widgets)
        val presentation = presentation()

        assertEquals(ProductLocalDeliveryEffectResult.Applied, effects.show(presentation))
        assertEquals(ProductLocalDeliveryEffectResult.Applied, effects.update(presentation))
        assertEquals(ProductLocalDeliveryEffectResult.Applied, effects.repeat(presentation))
        assertEquals(ProductLocalDeliveryEffectResult.Applied, effects.close(presentation.episodeId))
        assertEquals(ProductLocalDeliveryEffectResult.Applied, effects.updateWidget(reading()))

        assertEquals(
            listOf(
                "SHOW:${presentation.episodeId}:true",
                "CLOSE:${ProductAlarmEpisodeIdentity.derive(BINDING, 2)}",
                "SHOW:${presentation.episodeId}:false",
                "SHOW:${presentation.episodeId}:true",
                "CLOSE:${presentation.episodeId}",
            ),
            notifications.actions,
        )
        assertEquals(listOf("event-1"), widgets.events)
    }

    @Test
    fun durableShowPresentsTheNewGenerationThenClosesThePreviousEpisode() = runBlocking {
        val notifications = RecordingProductNotificationPort()
        val effects = AndroidProductLocalDeliveryEffects(
            notifications = notifications,
            widgets = RecordingProductWidgetPort(),
        )
        val next = presentation()

        assertEquals(ProductLocalDeliveryEffectResult.Applied, effects.show(next))
        assertEquals(
            listOf(
                "SHOW:${next.episodeId}:true",
                "CLOSE:${ProductAlarmEpisodeIdentity.derive(BINDING, 2)}",
            ),
            notifications.actions,
        )
    }

    @Test
    fun failedPreviousEpisodeCloseKeepsTheVisibleReplacementAndDurableShowRetryable() =
        runBlocking {
            val notifications = object : ProductAlarmNotificationPort {
                val actions = mutableListOf<String>()

                override fun show(
                    presentation: ProductAlarmPresentation,
                    alert: Boolean,
                ): Boolean {
                    actions += "SHOW:${presentation.episodeId}:$alert"
                    return true
                }

                override fun close(episodeId: String): Boolean {
                    actions += "CLOSE:$episodeId"
                    return false
                }
            }
            val effects = AndroidProductLocalDeliveryEffects(
                notifications = notifications,
                widgets = RecordingProductWidgetPort(),
            )

            assertEquals(
                ProductLocalDeliveryEffectResult.TransientFailure,
                effects.show(presentation()),
            )
            assertEquals(
                listOf(
                    "SHOW:${presentation().episodeId}:true",
                    "CLOSE:${ProductAlarmEpisodeIdentity.derive(BINDING, 2)}",
                ),
                notifications.actions,
            )
        }

    @Test
    fun androidFailureIsTypedAsTransientSoRunnerCanDurablyRetry() = runBlocking {
        val notifications = RecordingProductNotificationPort().apply { succeeds = false }
        val widgets = RecordingProductWidgetPort().apply { succeeds = false }
        val effects = AndroidProductLocalDeliveryEffects(notifications, widgets)

        assertEquals(
            ProductLocalDeliveryEffectResult.TransientFailure,
            effects.show(presentation()),
        )
        assertEquals(
            ProductLocalDeliveryEffectResult.TransientFailure,
            effects.updateWidget(reading()),
        )
    }

    @Test
    fun wakePlanKeepsTheExactDurableDeadlineAndAddsRevocationWatchdog() {
        val exact = ProductLocalDeliveryWakePlanPolicy.plan(
            deadlineEpochMs = DEADLINE,
            exactAlarmAccess = true,
        )
        assertEquals(ProductLocalDeliveryWakeKind.EXACT, exact.primaryKind)
        assertEquals(DEADLINE, exact.deadlineEpochMs)
        assertEquals(DEADLINE + WATCHDOG_GRACE_MS, exact.watchdogEpochMs)

        val fallback = ProductLocalDeliveryWakePlanPolicy.plan(
            deadlineEpochMs = DEADLINE,
            exactAlarmAccess = false,
        )
        assertEquals(ProductLocalDeliveryWakeKind.INEXACT_FALLBACK, fallback.primaryKind)
        assertEquals(DEADLINE, fallback.deadlineEpochMs)
        assertNull(fallback.watchdogEpochMs)
    }

    @Test
    fun wakePlanBoundsWatchdogOverflowWithoutChangingPrimaryDeadline() {
        val plan = ProductLocalDeliveryWakePlanPolicy.plan(
            deadlineEpochMs = Long.MAX_VALUE - 10,
            exactAlarmAccess = true,
        )

        assertEquals(Long.MAX_VALUE - 10, plan.deadlineEpochMs)
        assertEquals(Long.MAX_VALUE, plan.watchdogEpochMs)
    }

    @Test
    fun presentationUsesStableEpisodeIdentityAndPhysicalReading() {
        val presentation = presentation()

        assertEquals(
            ProductAlarmEpisodeIdentity.derive(BINDING, 3),
            presentation.episodeId,
        )
        assertFalse(presentation.acknowledged)
        assertTrue(presentation.activeKinds.contains(AlarmKind.LOW))
        assertEquals(SensorFamily.SIBIONICS_GS1, requireNotNull(presentation.reading).sensorFamily)
    }

    @Test
    fun signalLossPresentationNeverLabelsTheSourceReadingAsCurrent() {
        val signalLoss = presentation().copy(activeKinds = setOf(AlarmKind.SIGNAL_LOSS))

        assertFalse(ProductAlarmNotificationPresentationPolicy.showCurrentValue(signalLoss))
        assertEquals(
            ProductAlarmCurrentValuePresentation.UNAVAILABLE,
            ProductAlarmNotificationPresentationPolicy.currentValue(signalLoss),
        )
    }

    @Test
    fun startupSignalLossUsesNoGlucoseValueAndShowsNoFreshDataWidget() = runBlocking {
        val widgets = RecordingProductWidgetPort()
        val effects = AndroidProductLocalDeliveryEffects(
            notifications = RecordingProductNotificationPort(),
            widgets = widgets,
        )
        val startupSignalLoss = presentation().copy(
            activeKinds = setOf(AlarmKind.SIGNAL_LOSS),
            reading = null,
        )

        assertEquals(
            ProductAlarmCurrentValuePresentation.UNAVAILABLE,
            ProductAlarmNotificationPresentationPolicy.currentValue(startupSignalLoss),
        )
        assertEquals(ProductLocalDeliveryEffectResult.Applied, effects.show(startupSignalLoss))
        assertEquals(ProductLocalDeliveryEffectResult.Applied, effects.updateWidget(null))
        assertEquals(listOf("NO_READING"), widgets.events)
    }

    @Test
    fun glucoseAlarmPresentationShowsItsExactSourceReading() {
        val low = presentation()

        assertTrue(ProductAlarmNotificationPresentationPolicy.showCurrentValue(low))
        assertEquals(
            ProductAlarmCurrentValuePresentation.Available(requireNotNull(low.reading).glucoseMgDl),
            ProductAlarmNotificationPresentationPolicy.currentValue(low),
        )
    }

    @Test
    fun acknowledgementIdentityMustBindEpisodeToExactBindingAndGeneration() {
        val presentation = presentation()

        assertTrue(
            ProductAlarmAcknowledgementIdentityPolicy.accepts(
                presentation.episodeId,
                presentation.publicationBindingId,
                presentation.generation,
            ),
        )
        assertFalse(
            ProductAlarmAcknowledgementIdentityPolicy.accepts(
                presentation.episodeId,
                presentation.publicationBindingId,
                presentation.generation + 1,
            ),
        )
        assertFalse(
            ProductAlarmAcknowledgementIdentityPolicy.accepts(
                "ff".repeat(32),
                presentation.publicationBindingId,
                presentation.generation,
            ),
        )
    }

    private fun presentation() = ProductAlarmPresentation(
        episodeId = ProductAlarmEpisodeIdentity.derive(BINDING, 3),
        publicationBindingId = BINDING,
        generation = 3,
        activeKinds = setOf(AlarmKind.LOW),
        acknowledged = false,
        openedAtEpochMs = DEADLINE - 60_000,
        reading = reading(),
    )

    private fun reading() = GlucoseReading(
        eventId = "event-1",
        sensorId = "sensor-a",
        sensorFamily = SensorFamily.SIBIONICS_GS1,
        sensorTimeEpochMs = DEADLINE - 2_000,
        phoneTimeEpochMs = DEADLINE - 1_000,
        glucoseMgDl = 60,
        trendMgDlPerMinute = -1.0,
        quality = ReadingQuality.VALID,
        sequence = 1,
    )

    private companion object {
        const val DEADLINE = 1_700_000_000_000L
        const val WATCHDOG_GRACE_MS = 60_000L
        val BINDING = "22".repeat(32)
    }
}

private class RecordingProductNotificationPort : ProductAlarmNotificationPort {
    val actions = mutableListOf<String>()
    var succeeds = true

    override fun show(presentation: ProductAlarmPresentation, alert: Boolean): Boolean {
        actions += "SHOW:${presentation.episodeId}:$alert"
        return succeeds
    }

    override fun close(episodeId: String): Boolean {
        actions += "CLOSE:$episodeId"
        return succeeds
    }
}

private class RecordingProductWidgetPort : ProductWidgetUpdatePort {
    val events = mutableListOf<String>()
    var succeeds = true

    override fun update(reading: GlucoseReading): Boolean {
        events += reading.eventId
        return succeeds
    }

    override fun showNoFreshData(): Boolean {
        events += "NO_READING"
        return succeeds
    }
}
