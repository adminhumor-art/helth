package com.sladkaya.app.service

import android.content.Context
import com.sladkaya.core.model.GlucoseReading
import com.sladkaya.sensor.sibionics.Gs1DiagnosticActivationProfile
import com.sladkaya.sensor.sibionics.Gs1ProductGattDriver
import com.sladkaya.sensor.sibionics.Gs1ProductGattState
import com.sladkaya.sensor.sibionics.Gs1ProductLocalEffectsFailureCode
import com.sladkaya.sensor.sibionics.Gs1ProductPublicationBatch
import java.util.concurrent.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

internal data class ProductCommittedReading(
    val reading: GlucoseReading,
    val approvalId: String,
    val publicationBindingId: String,
)

internal interface ProductPublicationDelivery {
    val publications: List<ProductCommittedReading>

    fun acknowledgeDurablyApplied(): Boolean

    fun rejectDurableApplication(code: Gs1ProductLocalEffectsFailureCode): Boolean
}

internal sealed interface ProductPublicationApplyResult {
    data class Applied(
        val verifiedReadings: List<GlucoseReading>,
        val activeAlarms: Set<com.sladkaya.core.model.AlarmKind> = emptySet(),
    ) : ProductPublicationApplyResult

    data object StorageUnavailable : ProductPublicationApplyResult
    data object Conflict : ProductPublicationApplyResult
}

/** An implementation must cross a durable boundary before returning [ProductPublicationApplyResult.Applied]. */
internal fun interface ProductPublicationApplier {
    suspend fun apply(
        configuration: ProductSensorConfiguration,
        publications: List<ProductCommittedReading>,
    ): ProductPublicationApplyResult
}

internal interface ProductSensorDriver {
    val state: Flow<Gs1ProductGattState>
    val publicationDeliveries: Flow<ProductPublicationDelivery>

    suspend fun start(profile: Gs1DiagnosticActivationProfile)
    fun requestStop()
    suspend fun stop()
}

internal fun interface ProductSensorDriverFactory {
    fun create(): ProductSensorDriver
}

internal fun interface ProductSensorHistorySource {
    suspend fun recent(configuration: ProductSensorConfiguration): List<GlucoseReading>
}

internal sealed interface ProductAlarmMonitoringStartResult {
    data object Armed : ProductAlarmMonitoringStartResult
    data class Conflict(val detail: String?) : ProductAlarmMonitoringStartResult
    data class StorageUnavailable(val detail: String?) : ProductAlarmMonitoringStartResult
}

/** Arms the local, durable no-reading signal-loss deadline before Bluetooth may start. */
internal fun interface ProductAlarmMonitoringStarter {
    suspend fun arm(configuration: ProductSensorConfiguration): ProductAlarmMonitoringStartResult
}

internal interface ProductSensorSessionObserver {
    fun onSessionStarting(
        configuration: ProductSensorConfiguration,
        restoredHistory: List<GlucoseReading>,
    )
    fun onDriverState(state: Gs1ProductGattState)
    fun onVerifiedReadings(readings: List<GlucoseReading>, activeAlarms: Set<com.sladkaya.core.model.AlarmKind>)
    fun onDriverFailure(code: String, detail: String?, retryable: Boolean)
}

internal sealed interface ProductSensorSessionStartResult {
    data class Started(
        val configuration: ProductSensorConfiguration,
    ) : ProductSensorSessionStartResult

    data object ConfigurationMissing : ProductSensorSessionStartResult
    data class ConfigurationInvalid(val code: String, val detail: String?) :
        ProductSensorSessionStartResult
    data class ConfigurationStorageUnavailable(val detail: String?) :
        ProductSensorSessionStartResult
    data class LocalAlarmUnavailable(val code: String, val detail: String?) :
        ProductSensorSessionStartResult
}

/** Owns one product-only driver generation and replaces it atomically on restart. */
internal class ProductSensorSessionCoordinator(
    private val configurationSource: ProductSensorConfigurationSource,
    private val driverFactory: ProductSensorDriverFactory,
    private val publicationApplier: ProductPublicationApplier,
    private val observer: ProductSensorSessionObserver,
    private val scope: CoroutineScope,
    private val historySource: ProductSensorHistorySource = ProductSensorHistorySource { emptyList() },
    private val monitoringStarter: ProductAlarmMonitoringStarter = ProductAlarmMonitoringStarter {
        ProductAlarmMonitoringStartResult.Armed
    },
) {
    private val lifecycle = Mutex()

    @Volatile
    private var active: ActiveSession? = null

    suspend fun start(): ProductSensorSessionStartResult {
        requestStop()
        return lifecycle.withLock {
            stopLocked()
            val configuration = when (val loaded = configurationSource.active()) {
                is ProductSensorConfigurationResult.Available -> loaded.configuration
                ProductSensorConfigurationResult.Missing ->
                    return@withLock ProductSensorSessionStartResult.ConfigurationMissing
                is ProductSensorConfigurationResult.Invalid ->
                    return@withLock ProductSensorSessionStartResult.ConfigurationInvalid(
                        loaded.code,
                        loaded.detail,
                    )
                is ProductSensorConfigurationResult.StorageUnavailable ->
                    return@withLock ProductSensorSessionStartResult
                        .ConfigurationStorageUnavailable(loaded.detail)
            }
            val restoredHistory = try {
                historySource.recent(configuration)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                return@withLock ProductSensorSessionStartResult
                    .ConfigurationStorageUnavailable(failure.message)
            }
            if (!ProductHistoryContract.accepts(configuration, restoredHistory)) {
                return@withLock ProductSensorSessionStartResult.ConfigurationInvalid(
                    code = "PRODUCT_HISTORY_CONFLICT",
                    detail = null,
                )
            }
            when (val armed = monitoringStarter.arm(configuration)) {
                ProductAlarmMonitoringStartResult.Armed -> Unit
                is ProductAlarmMonitoringStartResult.Conflict ->
                    return@withLock ProductSensorSessionStartResult.LocalAlarmUnavailable(
                        code = "PRODUCT_LOCAL_MONITORING_CONFLICT",
                        detail = armed.detail,
                    )
                is ProductAlarmMonitoringStartResult.StorageUnavailable ->
                    return@withLock ProductSensorSessionStartResult.LocalAlarmUnavailable(
                        code = "PRODUCT_LOCAL_MONITORING_STORAGE_UNAVAILABLE",
                        detail = armed.detail,
                    )
            }
            val driver = driverFactory.create()
            observer.onSessionStarting(configuration, restoredHistory)
            val stateJob = scope.launch(start = CoroutineStart.UNDISPATCHED) {
                driver.state.collect(observer::onDriverState)
            }
            val publicationJob = scope.launch(start = CoroutineStart.UNDISPATCHED) {
                driver.publicationDeliveries.collect { delivery ->
                    settle(configuration, delivery)
                }
            }
            val driverJob = scope.launch(start = CoroutineStart.UNDISPATCHED) {
                try {
                    driver.start(configuration.profile)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: LinkageError) {
                    observer.onDriverFailure(
                        code = "NATIVE_RUNTIME_UNAVAILABLE",
                        detail = null,
                        retryable = false,
                    )
                } catch (failure: Exception) {
                    observer.onDriverFailure(
                        code = "PRODUCT_DRIVER_START_FAILED",
                        detail = failure.message,
                        retryable = true,
                    )
                }
            }
            active = ActiveSession(driver, stateJob, publicationJob, driverJob)
            ProductSensorSessionStartResult.Started(configuration)
        }
    }

    fun requestStop() {
        active?.driver?.requestStop()
    }

    suspend fun stop() {
        requestStop()
        lifecycle.withLock { stopLocked() }
    }

    private suspend fun settle(
        configuration: ProductSensorConfiguration,
        delivery: ProductPublicationDelivery,
    ) {
        if (!ProductPublicationContract.accepts(configuration, delivery.publications)) {
            delivery.rejectDurableApplication(Gs1ProductLocalEffectsFailureCode.STORAGE_CONFLICT)
            return
        }
        val applied = try {
            publicationApplier.apply(configuration, delivery.publications)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            ProductPublicationApplyResult.StorageUnavailable
        }
        when (applied) {
            is ProductPublicationApplyResult.Applied -> {
                val expected = delivery.publications.map(ProductCommittedReading::reading)
                if (applied.verifiedReadings != expected) {
                    delivery.rejectDurableApplication(
                        Gs1ProductLocalEffectsFailureCode.STORAGE_CONFLICT,
                    )
                } else if (delivery.acknowledgeDurablyApplied()) {
                    observer.onVerifiedReadings(
                        applied.verifiedReadings,
                        applied.activeAlarms,
                    )
                }
            }
            ProductPublicationApplyResult.StorageUnavailable ->
                delivery.rejectDurableApplication(
                    Gs1ProductLocalEffectsFailureCode.STORAGE_UNAVAILABLE,
                )
            ProductPublicationApplyResult.Conflict ->
                delivery.rejectDurableApplication(
                    Gs1ProductLocalEffectsFailureCode.STORAGE_CONFLICT,
                )
        }
    }

    private suspend fun stopLocked() {
        val previous = active ?: return
        active = null
        previous.publicationJob.cancelAndJoin()
        previous.stateJob.cancelAndJoin()
        previous.driverJob.cancelAndJoin()
        previous.driver.stop()
    }

    private data class ActiveSession(
        val driver: ProductSensorDriver,
        val stateJob: Job,
        val publicationJob: Job,
        val driverJob: Job,
    )
}

internal object ProductHistoryContract {
    fun accepts(
        configuration: ProductSensorConfiguration,
        readings: List<GlucoseReading>,
    ): Boolean = readings.size <= MAX_RESTORED_READINGS &&
        readings.map(GlucoseReading::eventId).distinct().size == readings.size &&
        readings.zipWithNext().all { (left, right) ->
            left.sensorTimeEpochMs <= right.sensorTimeEpochMs
        } && readings.all { reading ->
            reading.sensorId == configuration.profile.sensorId &&
                reading.sensorFamily == configuration.profile.family &&
                reading.isEligibleForProductPublication
        }

    private const val MAX_RESTORED_READINGS = 288
}

internal object ProductPublicationContract {
    fun accepts(
        configuration: ProductSensorConfiguration,
        publications: List<ProductCommittedReading>,
    ): Boolean = publications.isNotEmpty() &&
        publications.map { it.reading.eventId }.distinct().size == publications.size &&
        publications.all { publication ->
            publication.approvalId == configuration.approvalId &&
                publication.publicationBindingId == configuration.publicationBindingId &&
                publication.reading.sensorId == configuration.profile.sensorId &&
                publication.reading.sensorFamily == configuration.profile.family &&
                publication.reading.isEligibleForProductPublication
        }
}

/** Product-only facade adapter. It deliberately has no diagnostic-value surface. */
internal class Gs1ProductSensorDriver(
    context: Context,
) : ProductSensorDriver {
    private val driver = Gs1ProductGattDriver(context.applicationContext)

    override val state: Flow<Gs1ProductGattState> = driver.state
    override val publicationDeliveries: Flow<ProductPublicationDelivery> =
        driver.committedPublicationBatches.map(::Gs1ProductPublicationDelivery)

    override suspend fun start(profile: Gs1DiagnosticActivationProfile) = driver.start(profile)

    override fun requestStop() = driver.requestStop()

    override suspend fun stop() = driver.stop()
}

private class Gs1ProductPublicationDelivery(
    private val batch: Gs1ProductPublicationBatch,
) : ProductPublicationDelivery {
    override val publications: List<ProductCommittedReading> = batch.publications.map { value ->
        ProductCommittedReading(
            reading = value.reading,
            approvalId = value.approvalId,
            publicationBindingId = value.publicationBindingId,
        )
    }

    override fun acknowledgeDurablyApplied(): Boolean = batch.acknowledgeDurablyApplied()

    override fun rejectDurableApplication(code: Gs1ProductLocalEffectsFailureCode): Boolean =
        batch.rejectDurableApplication(code)
}
