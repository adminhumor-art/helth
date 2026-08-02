package com.sladkaya.app.service

import android.content.Context
import com.sladkaya.app.settings.AlarmSettingsStore
import com.sladkaya.core.data.AlarmThresholdSnapshot
import com.sladkaya.core.data.LocalAlarmMonitoringStartRequest
import com.sladkaya.core.data.LocalAlarmMonitoringStartResult
import com.sladkaya.core.data.LocalAlarmRepository
import java.util.concurrent.CancellationException

/** Product-only startup adapter. It does no network work and never creates a glucose reading. */
internal class RoomProductAlarmMonitoringStarter(
    private val store: com.sladkaya.core.data.LocalAlarmStore,
    private val thresholds: () -> com.sladkaya.core.model.AlarmThresholds,
    private val nowEpochMs: () -> Long,
    private val drain: ProductLocalDeliveryDrain,
) : ProductAlarmMonitoringStarter {
    constructor(context: Context) : this(
        store = LocalAlarmRepository.create(context.applicationContext),
        thresholds = { AlarmSettingsStore(context.applicationContext).load().thresholds },
        nowEpochMs = System::currentTimeMillis,
        drain = ProductLocalDeliveryProductionRuntime.createDrain(context.applicationContext),
    )

    override suspend fun arm(
        configuration: ProductSensorConfiguration,
    ): ProductAlarmMonitoringStartResult {
        val thresholdSnapshot = try {
            AlarmThresholdSnapshot.from(thresholds())
        } catch (_: IllegalArgumentException) {
            return ProductAlarmMonitoringStartResult.Conflict("Alarm thresholds are malformed")
        } catch (_: RuntimeException) {
            return ProductAlarmMonitoringStartResult.StorageUnavailable(null)
        }
        val startedAt = nowEpochMs()
        if (startedAt <= 0L) {
            return ProductAlarmMonitoringStartResult.Conflict("Monitoring clock is invalid")
        }
        val initialized = try {
            store.initializeMonitoring(
                LocalAlarmMonitoringStartRequest(
                    publicationBindingId = configuration.publicationBindingId,
                    approvalId = configuration.approvalId,
                    monitoringStartedAtEpochMs = startedAt,
                    approvedSequence = configuration.approvedSequence,
                    thresholds = thresholdSnapshot,
                ),
            )
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            return ProductAlarmMonitoringStartResult.StorageUnavailable(failure.message)
        }
        when (initialized) {
            is LocalAlarmMonitoringStartResult.Conflict ->
                return ProductAlarmMonitoringStartResult.Conflict(initialized.reason)
            is LocalAlarmMonitoringStartResult.Initialized,
            is LocalAlarmMonitoringStartResult.AlreadyInitialized,
            -> Unit
        }
        when (
            val reconciled = ProductAlarmSettingsReconciler(store, nowEpochMs).reconcile(
                configuration.publicationBindingId,
                thresholdSnapshot,
            )
        ) {
            is ProductAlarmSettingsReconcileResult.Applied,
            is ProductAlarmSettingsReconcileResult.Current,
            -> Unit
            is ProductAlarmSettingsReconcileResult.Conflict ->
                return ProductAlarmMonitoringStartResult.Conflict(reconciled.detail)
            is ProductAlarmSettingsReconcileResult.StorageUnavailable ->
                return ProductAlarmMonitoringStartResult.StorageUnavailable(reconciled.detail)
        }
        val drained = try {
            drain.runBounded()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            return ProductAlarmMonitoringStartResult.StorageUnavailable(failure.message)
        }
        return when (drained) {
            is ProductLocalDeliveryRunResult.Drained,
            is ProductLocalDeliveryRunResult.Waiting,
            is ProductLocalDeliveryRunResult.Yielded,
            -> ProductAlarmMonitoringStartResult.Armed
            is ProductLocalDeliveryRunResult.TransientFailure ->
                ProductAlarmMonitoringStartResult.StorageUnavailable(
                    "Local alarm wake could not be scheduled at ${drained.retryAtEpochMs}",
                )
            is ProductLocalDeliveryRunResult.Degraded ->
                ProductAlarmMonitoringStartResult.Conflict(
                    "Local alarm delivery queue contains irrecoverable work",
                )
            is ProductLocalDeliveryRunResult.Conflict ->
                ProductAlarmMonitoringStartResult.Conflict("Local alarm wake conflicts with storage")
        }
    }
}
