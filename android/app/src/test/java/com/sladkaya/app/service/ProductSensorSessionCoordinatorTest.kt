package com.sladkaya.app.service

import com.sladkaya.core.model.GlucoseReading
import com.sladkaya.core.model.ReadingQuality
import com.sladkaya.core.model.SensorFamily
import com.sladkaya.sensor.sibionics.Gs1DiagnosticActivationProfile
import com.sladkaya.sensor.sibionics.Gs1DiagnosticActivationProfileValidation
import com.sladkaya.sensor.sibionics.Gs1ProductGattState
import com.sladkaya.sensor.sibionics.Gs1ProductLocalEffectsFailureCode
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ProductSensorSessionCoordinatorTest {
    @Test
    fun productDriverBoundaryExposesNoDiagnosticReadingStream() {
        val methods = Gs1ProductSensorDriver::class.java.declaredMethods
            .map(java.lang.reflect.Method::getName)

        assertFalse(methods.any { it.contains("diagnostic", ignoreCase = true) })
    }

    @Test
    fun productDriverCannotStartBeforeDurableSignalLossMonitoringIsArmed() = runBlocking {
        var driverCreates = 0
        val configuration = configuration(transportVariant = 2)
        val scope = CoroutineScope(coroutineContext + SupervisorJob())
        val coordinator = ProductSensorSessionCoordinator(
            configurationSource = ProductSensorConfigurationSource {
                ProductSensorConfigurationResult.Available(configuration)
            },
            driverFactory = ProductSensorDriverFactory {
                driverCreates += 1
                FakeProductSensorDriver(mutableListOf(), driverCreates)
            },
            publicationApplier = ProductPublicationApplier { _, publications ->
                ProductPublicationApplyResult.Applied(publications.map { it.reading })
            },
            monitoringStarter = ProductAlarmMonitoringStarter {
                ProductAlarmMonitoringStartResult.Conflict("durable start rejected")
            },
            observer = RecordingProductSensorObserver(),
            scope = scope,
        )

        assertEquals(
            ProductSensorSessionStartResult.LocalAlarmUnavailable(
                code = "PRODUCT_LOCAL_MONITORING_CONFLICT",
                detail = "durable start rejected",
            ),
            coordinator.start(),
        )
        assertEquals(0, driverCreates)
        scope.cancel()
    }

    @Test
    fun startStopAndRestartReplaceTheDriverAndPreserveTheApprovedChineseVariant() = runBlocking {
        val events = mutableListOf<String>()
        val drivers = mutableListOf<FakeProductSensorDriver>()
        val scope = CoroutineScope(coroutineContext + SupervisorJob())
        val coordinator = ProductSensorSessionCoordinator(
            configurationSource = ProductSensorConfigurationSource {
                ProductSensorConfigurationResult.Available(configuration(transportVariant = 2))
            },
            driverFactory = ProductSensorDriverFactory {
                FakeProductSensorDriver(events, drivers.size + 1).also(drivers::add)
            },
            publicationApplier = ProductPublicationApplier {
                    _, publications ->
                ProductPublicationApplyResult.Applied(publications.map { it.reading })
            },
            observer = RecordingProductSensorObserver(),
            scope = scope,
        )

        assertTrue(coordinator.start() is ProductSensorSessionStartResult.Started)
        val firstProfile = withTimeout(500) { drivers[0].started.await() }
        assertEquals(2, firstProfile.transportVariant)

        assertTrue(coordinator.start() is ProductSensorSessionStartResult.Started)
        withTimeout(500) { drivers[1].started.await() }
        assertEquals(
            listOf("start-1-v2", "request-stop-1", "stop-1", "start-2-v2"),
            events,
        )

        coordinator.stop()
        assertEquals(
            listOf(
                "start-1-v2",
                "request-stop-1",
                "stop-1",
                "start-2-v2",
                "request-stop-2",
                "stop-2",
            ),
            events,
        )
        scope.cancel()
    }

    @Test
    fun missingPhysicalApprovalNeverCreatesOrStartsAProductDriver() = runBlocking {
        var factoryCalls = 0
        val scope = CoroutineScope(coroutineContext + SupervisorJob())
        val coordinator = ProductSensorSessionCoordinator(
            configurationSource = ProductSensorConfigurationSource {
                ProductSensorConfigurationResult.Missing
            },
            driverFactory = ProductSensorDriverFactory {
                factoryCalls += 1
                FakeProductSensorDriver(mutableListOf(), factoryCalls)
            },
            publicationApplier = ProductPublicationApplier { _, _ ->
                error("publication is impossible without an approved configuration")
            },
            observer = RecordingProductSensorObserver(),
            scope = scope,
        )

        assertEquals(ProductSensorSessionStartResult.ConfigurationMissing, coordinator.start())
        assertEquals(0, factoryCalls)
        scope.cancel()
    }

    @Test
    fun durableHistoryIsRestoredBeforeTheProductDriverStarts() = runBlocking {
        val events = mutableListOf<String>()
        val driver = FakeProductSensorDriver(events, 1)
        val observer = RecordingProductSensorObserver()
        val history = listOf(
            reading(SensorFamily.SIBIONICS_GS1).copy(eventId = "history-1", sequence = 1),
            reading(SensorFamily.SIBIONICS_GS1).copy(
                eventId = "history-2",
                sequence = 2,
                sensorTimeEpochMs = 1_700_000_060_000L,
                phoneTimeEpochMs = 1_700_000_061_000L,
            ),
        )
        val scope = CoroutineScope(coroutineContext + SupervisorJob())
        val coordinator = ProductSensorSessionCoordinator(
            configurationSource = ProductSensorConfigurationSource {
                ProductSensorConfigurationResult.Available(configuration(transportVariant = 2))
            },
            driverFactory = ProductSensorDriverFactory { driver },
            publicationApplier = ProductPublicationApplier { _, publications ->
                ProductPublicationApplyResult.Applied(publications.map { it.reading })
            },
            historySource = ProductSensorHistorySource { history },
            observer = observer,
            scope = scope,
        )

        assertTrue(coordinator.start() is ProductSensorSessionStartResult.Started)
        withTimeout(500) { driver.started.await() }
        assertEquals(history, observer.restoredHistory)

        coordinator.stop()
        scope.cancel()
    }

    @Test
    fun mismatchedDurableHistoryBlocksTheDriver() = runBlocking {
        var driverCreates = 0
        val scope = CoroutineScope(coroutineContext + SupervisorJob())
        val coordinator = ProductSensorSessionCoordinator(
            configurationSource = ProductSensorConfigurationSource {
                ProductSensorConfigurationResult.Available(configuration(transportVariant = 2))
            },
            driverFactory = ProductSensorDriverFactory {
                driverCreates += 1
                FakeProductSensorDriver(mutableListOf(), driverCreates)
            },
            publicationApplier = ProductPublicationApplier { _, publications ->
                ProductPublicationApplyResult.Applied(publications.map { it.reading })
            },
            historySource = ProductSensorHistorySource {
                listOf(reading(SensorFamily.SIBIONICS_GS1).copy(sensorId = "other-sensor"))
            },
            observer = RecordingProductSensorObserver(),
            scope = scope,
        )

        assertEquals(
            ProductSensorSessionStartResult.ConfigurationInvalid(
                code = "PRODUCT_HISTORY_CONFLICT",
                detail = null,
            ),
            coordinator.start(),
        )
        assertEquals(0, driverCreates)
        scope.cancel()
    }

    @Test
    fun unapprovedPhysicalAndDemoReadingsAreRejectedBeforeAnyApplicationOrUiCallback() =
        runBlocking {
            val configuration = configuration(transportVariant = 2)
            val driver = FakeProductSensorDriver(mutableListOf(), 1)
            val observer = RecordingProductSensorObserver()
            var applyCalls = 0
            val scope = CoroutineScope(coroutineContext + SupervisorJob())
            val coordinator = ProductSensorSessionCoordinator(
                configurationSource = ProductSensorConfigurationSource {
                    ProductSensorConfigurationResult.Available(configuration)
                },
                driverFactory = ProductSensorDriverFactory { driver },
                publicationApplier = ProductPublicationApplier { _, publications ->
                    applyCalls += 1
                    ProductPublicationApplyResult.Applied(publications.map { it.reading })
                },
                observer = observer,
                scope = scope,
            )
            coordinator.start()
            withTimeout(500) { driver.started.await() }

            val unapproved = FakeProductPublicationDelivery(
                listOf(
                    ProductCommittedReading(
                        reading = reading(SensorFamily.SIBIONICS_GS1),
                        approvalId = "77".repeat(32),
                        publicationBindingId = configuration.publicationBindingId,
                    ),
                ),
            )
            val demo = FakeProductPublicationDelivery(
                listOf(
                    ProductCommittedReading(
                        reading = reading(SensorFamily.SIMULATOR),
                        approvalId = configuration.approvalId,
                        publicationBindingId = configuration.publicationBindingId,
                    ),
                ),
            )

            driver.deliveries.send(unapproved)
            driver.deliveries.send(demo)
            withTimeout(500) {
                unapproved.rejected.receive()
                demo.rejected.receive()
            }

            assertEquals(0, applyCalls)
            assertTrue(observer.verifiedReadings.isEmpty())
            assertEquals(Gs1ProductLocalEffectsFailureCode.STORAGE_CONFLICT, unapproved.lastRejection)
            assertEquals(Gs1ProductLocalEffectsFailureCode.STORAGE_CONFLICT, demo.lastRejection)
            assertFalse(unapproved.acknowledged)
            assertFalse(demo.acknowledged)
            coordinator.stop()
            scope.cancel()
        }

    @Test
    fun uiCallbackReceivesOnlyReadingsReturnedByTheDurableApplicationPort() = runBlocking {
        val configuration = configuration(transportVariant = 2)
        val driver = FakeProductSensorDriver(mutableListOf(), 1)
        val observer = RecordingProductSensorObserver()
        val candidate = reading(SensorFamily.SIBIONICS_GS1)
        val verified = candidate.copy()
        val scope = CoroutineScope(coroutineContext + SupervisorJob())
        val coordinator = ProductSensorSessionCoordinator(
            configurationSource = ProductSensorConfigurationSource {
                ProductSensorConfigurationResult.Available(configuration)
            },
            driverFactory = ProductSensorDriverFactory { driver },
            publicationApplier = ProductPublicationApplier { _, _ ->
                ProductPublicationApplyResult.Applied(listOf(verified))
            },
            observer = observer,
            scope = scope,
        )
        coordinator.start()
        withTimeout(500) { driver.started.await() }
        val delivery = FakeProductPublicationDelivery(
            listOf(
                ProductCommittedReading(
                    reading = candidate,
                    approvalId = configuration.approvalId,
                    publicationBindingId = configuration.publicationBindingId,
                ),
            ),
        )

        driver.deliveries.send(delivery)
        withTimeout(500) { delivery.acked.receive() }

        assertTrue(delivery.acknowledged)
        assertEquals(listOf(verified), observer.verifiedReadings)
        coordinator.stop()
        scope.cancel()
    }

    @Test
    fun durableApplicationMismatchIsRejectedWithoutPublishingEitherValue() = runBlocking {
        val configuration = configuration(transportVariant = 2)
        val driver = FakeProductSensorDriver(mutableListOf(), 1)
        val observer = RecordingProductSensorObserver()
        val scope = CoroutineScope(coroutineContext + SupervisorJob())
        val coordinator = ProductSensorSessionCoordinator(
            configurationSource = ProductSensorConfigurationSource {
                ProductSensorConfigurationResult.Available(configuration)
            },
            driverFactory = ProductSensorDriverFactory { driver },
            publicationApplier = ProductPublicationApplier { _, _ ->
                ProductPublicationApplyResult.Applied(
                    listOf(reading(SensorFamily.SIBIONICS_GS1).copy(eventId = "other-event")),
                )
            },
            observer = observer,
            scope = scope,
        )
        coordinator.start()
        withTimeout(500) { driver.started.await() }
        val delivery = FakeProductPublicationDelivery(
            listOf(
                ProductCommittedReading(
                    reading = reading(SensorFamily.SIBIONICS_GS1),
                    approvalId = configuration.approvalId,
                    publicationBindingId = configuration.publicationBindingId,
                ),
            ),
        )

        driver.deliveries.send(delivery)
        withTimeout(500) { delivery.rejected.receive() }

        assertEquals(Gs1ProductLocalEffectsFailureCode.STORAGE_CONFLICT, delivery.lastRejection)
        assertTrue(observer.verifiedReadings.isEmpty())
        coordinator.stop()
        scope.cancel()
    }

    private fun configuration(transportVariant: Int): ProductSensorConfiguration {
        val validation = Gs1DiagnosticActivationProfile.validate(
            sensorId = "sensor-approved",
            family = SensorFamily.SIBIONICS_GS1,
            bluetoothAddress = "AA:BB:CC:DD:EE:22",
            transportVariant = transportVariant,
            packageCode = "Ab12Cd34",
        ) as Gs1DiagnosticActivationProfileValidation.Valid
        return ProductSensorConfiguration(
            profile = validation.profile,
            approvalId = "11".repeat(32),
            publicationBindingId = "22".repeat(32),
        )
    }

    private fun reading(family: SensorFamily) = GlucoseReading(
        eventId = "event-${family.wireName}",
        sensorId = "sensor-approved",
        sensorFamily = family,
        sensorTimeEpochMs = 1_700_000_000_000L,
        phoneTimeEpochMs = 1_700_000_001_000L,
        glucoseMgDl = 104,
        trendMgDlPerMinute = -1.2,
        quality = ReadingQuality.VALID,
        sequence = 10,
    )
}

private class FakeProductSensorDriver(
    private val events: MutableList<String>,
    private val id: Int,
) : ProductSensorDriver {
    override val state = MutableStateFlow<Gs1ProductGattState>(Gs1ProductGattState.Idle)
    val deliveries = Channel<ProductPublicationDelivery>(Channel.UNLIMITED)
    override val publicationDeliveries = deliveries.receiveAsFlow()
    val started = kotlinx.coroutines.CompletableDeferred<Gs1DiagnosticActivationProfile>()

    override suspend fun start(profile: Gs1DiagnosticActivationProfile) {
        events += "start-$id-v${profile.transportVariant}"
        started.complete(profile)
        awaitCancellation()
    }

    override fun requestStop() {
        events += "request-stop-$id"
    }

    override suspend fun stop() {
        events += "stop-$id"
    }
}

private class FakeProductPublicationDelivery(
    override val publications: List<ProductCommittedReading>,
) : ProductPublicationDelivery {
    val acked = Channel<Unit>(capacity = 1)
    val rejected = Channel<Unit>(capacity = 1)
    var acknowledged = false
    var lastRejection: Gs1ProductLocalEffectsFailureCode? = null

    override fun acknowledgeDurablyApplied(): Boolean {
        acknowledged = true
        acked.trySend(Unit)
        return true
    }

    override fun rejectDurableApplication(code: Gs1ProductLocalEffectsFailureCode): Boolean {
        lastRejection = code
        rejected.trySend(Unit)
        return true
    }
}

private class RecordingProductSensorObserver : ProductSensorSessionObserver {
    val verifiedReadings = mutableListOf<GlucoseReading>()
    var restoredHistory = emptyList<GlucoseReading>()

    override fun onSessionStarting(
        configuration: ProductSensorConfiguration,
        restoredHistory: List<GlucoseReading>,
    ) {
        this.restoredHistory = restoredHistory
    }

    override fun onDriverState(state: Gs1ProductGattState) = Unit

    override fun onVerifiedReadings(
        readings: List<GlucoseReading>,
        activeAlarms: Set<com.sladkaya.core.model.AlarmKind>,
    ) {
        verifiedReadings += readings
    }

    override fun onDriverFailure(code: String, detail: String?, retryable: Boolean) = Unit
}
