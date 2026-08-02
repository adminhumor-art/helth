package com.sladkaya.app.service

import com.sladkaya.core.data.AlarmThresholdSnapshot
import com.sladkaya.core.data.LocalAlarmSettingsApplyRequest
import com.sladkaya.core.data.LocalAlarmSettingsApplyResult
import com.sladkaya.core.data.LocalAlarmSettingsSettlement
import com.sladkaya.core.data.LocalAlarmStateReadResult
import com.sladkaya.core.data.LocalAlarmStateRecord
import com.sladkaya.core.data.LocalAlarmStore
import java.util.concurrent.CancellationException

internal sealed interface ProductAlarmSettingsReconcileResult {
    data class Applied(val settlement: LocalAlarmSettingsSettlement) :
        ProductAlarmSettingsReconcileResult

    data class Current(val state: LocalAlarmStateRecord) : ProductAlarmSettingsReconcileResult
    data class StorageUnavailable(val detail: String?) : ProductAlarmSettingsReconcileResult
    data class Conflict(val detail: String?) : ProductAlarmSettingsReconcileResult
}

/** Reconciles preferences with the exact durable product state; it never republishes a reading. */
internal class ProductAlarmSettingsReconciler(
    private val store: LocalAlarmStore,
    private val nowEpochMs: () -> Long = System::currentTimeMillis,
) {
    suspend fun reconcile(
        publicationBindingId: String,
        thresholds: AlarmThresholdSnapshot,
    ): ProductAlarmSettingsReconcileResult {
        repeat(MAX_OBSOLETE_RETRIES) {
            val state = when (val loaded = safeReadState(publicationBindingId)) {
                is ProductAlarmSettingsReconcileResult.Current -> loaded.state
                is ProductAlarmSettingsReconcileResult.StorageUnavailable -> return loaded
                is ProductAlarmSettingsReconcileResult.Conflict -> return loaded
                is ProductAlarmSettingsReconcileResult.Applied -> error("unreachable")
            }
            if (state.thresholds.fingerprint == thresholds.fingerprint) {
                return ProductAlarmSettingsReconcileResult.Current(state)
            }
            val wallClock = nowEpochMs()
            if (wallClock <= 0L) {
                return ProductAlarmSettingsReconcileResult.Conflict(
                    "Alarm settings clock is invalid",
                )
            }
            val appliedAtEpochMs = maxOf(wallClock, state.updatedAtEpochMs)
            val request = LocalAlarmSettingsApplyRequest(
                publicationBindingId = publicationBindingId,
                expectedStateSha256 = state.stateSha256,
                thresholds = thresholds,
                appliedAtEpochMs = appliedAtEpochMs,
            )
            val applied = try {
                store.applySettings(request)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                return ProductAlarmSettingsReconcileResult.StorageUnavailable(failure.message)
            }
            when (applied) {
                is LocalAlarmSettingsApplyResult.Applied ->
                    return applied.settlement.validated(request, state)
                is LocalAlarmSettingsApplyResult.AlreadyApplied ->
                    return applied.settlement.validated(request, state)
                is LocalAlarmSettingsApplyResult.Obsolete -> Unit
                is LocalAlarmSettingsApplyResult.Conflict ->
                    return ProductAlarmSettingsReconcileResult.Conflict(applied.reason)
            }
        }
        return ProductAlarmSettingsReconcileResult.StorageUnavailable(
            "Alarm state kept changing while settings were applied",
        )
    }

    private suspend fun safeReadState(
        publicationBindingId: String,
    ): ProductAlarmSettingsReconcileResult = try {
        when (val loaded = store.readState(publicationBindingId)) {
            is LocalAlarmStateReadResult.Exact -> {
                if (loaded.state.publicationBindingId == publicationBindingId) {
                    ProductAlarmSettingsReconcileResult.Current(loaded.state)
                } else {
                    ProductAlarmSettingsReconcileResult.Conflict(
                        "Alarm state differs from the active product binding",
                    )
                }
            }
            LocalAlarmStateReadResult.Empty -> ProductAlarmSettingsReconcileResult.Conflict(
                "Alarm state is not initialized",
            )
            is LocalAlarmStateReadResult.Conflict ->
                ProductAlarmSettingsReconcileResult.Conflict(loaded.reason)
        }
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (failure: Exception) {
        ProductAlarmSettingsReconcileResult.StorageUnavailable(failure.message)
    }

    private fun LocalAlarmSettingsSettlement.validated(
        request: LocalAlarmSettingsApplyRequest,
        source: LocalAlarmStateRecord,
    ): ProductAlarmSettingsReconcileResult = if (
        operationId == request.operationId &&
        publicationBindingId == request.publicationBindingId &&
        approvalId == source.approvalId &&
        sourceEffectId == source.lastEffectId &&
        sourceEventId == source.lastEventId &&
        expectedStateSha256 == source.stateSha256 &&
        thresholdFingerprint == request.thresholds.fingerprint &&
        appliedAtEpochMs == request.appliedAtEpochMs
    ) {
        ProductAlarmSettingsReconcileResult.Applied(this)
    } else {
        ProductAlarmSettingsReconcileResult.Conflict(
            "Alarm settings settlement differs from the requested state",
        )
    }

    private companion object {
        const val MAX_OBSOLETE_RETRIES = 3
    }
}
