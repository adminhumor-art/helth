package com.sladkaya.core.data

import android.content.Context

interface LocalAlarmDeliveryStore {
    suspend fun leaseDueEarliest(
        nowEpochMs: Long,
        leaseToken: String,
        leaseExpiresAtEpochMs: Long,
    ): LocalAlarmDeliveryLeaseResult

    suspend fun markDelivered(
        deliveryId: String,
        leaseToken: String,
        deliveredAtEpochMs: Long,
    ): LocalAlarmDeliveryTransitionResult

    suspend fun reschedule(
        deliveryId: String,
        leaseToken: String,
        rescheduledAtEpochMs: Long,
        nextAttemptEpochMs: Long,
    ): LocalAlarmDeliveryTransitionResult

    suspend fun quarantine(
        deliveryId: String,
        leaseToken: String,
        quarantinedAtEpochMs: Long,
    ): LocalAlarmDeliveryTransitionResult
}

class LocalAlarmDeliveryRepository private constructor(
    private val dao: LocalAlarmDeliveryDao,
) : LocalAlarmDeliveryStore {
    override suspend fun leaseDueEarliest(
        nowEpochMs: Long,
        leaseToken: String,
        leaseExpiresAtEpochMs: Long,
    ): LocalAlarmDeliveryLeaseResult = try {
        when (val result = dao.leaseDueEarliest(nowEpochMs, leaseToken, leaseExpiresAtEpochMs)) {
            is LocalAlarmDeliveryLeaseDecision.Leased ->
                LocalAlarmDeliveryLeaseResult.Leased(result.value)
            LocalAlarmDeliveryLeaseDecision.Empty -> LocalAlarmDeliveryLeaseResult.Empty
            is LocalAlarmDeliveryLeaseDecision.NotDue -> LocalAlarmDeliveryLeaseResult.NotDue(
                result.deliveryId,
                result.notBeforeEpochMs,
            )
            is LocalAlarmDeliveryLeaseDecision.BlockedByActiveLease ->
                LocalAlarmDeliveryLeaseResult.BlockedByActiveLease(
                    result.deliveryId,
                    result.leaseExpiresAtEpochMs,
                )
        }
    } catch (conflict: SensorCoreConflictException) {
        LocalAlarmDeliveryLeaseResult.Conflict(
            conflict.message?.takeIf(String::isNotBlank) ?: "Alarm delivery lease conflict",
        )
    } catch (_: IllegalArgumentException) {
        LocalAlarmDeliveryLeaseResult.Conflict("Alarm delivery lease request is malformed")
    }

    override suspend fun markDelivered(
        deliveryId: String,
        leaseToken: String,
        deliveredAtEpochMs: Long,
    ): LocalAlarmDeliveryTransitionResult = transition {
        dao.markDelivered(deliveryId, leaseToken, deliveredAtEpochMs)
    }

    override suspend fun reschedule(
        deliveryId: String,
        leaseToken: String,
        rescheduledAtEpochMs: Long,
        nextAttemptEpochMs: Long,
    ): LocalAlarmDeliveryTransitionResult = transition {
        dao.reschedule(
            deliveryId,
            leaseToken,
            rescheduledAtEpochMs,
            nextAttemptEpochMs,
        )
    }

    override suspend fun quarantine(
        deliveryId: String,
        leaseToken: String,
        quarantinedAtEpochMs: Long,
    ): LocalAlarmDeliveryTransitionResult = transition {
        dao.quarantine(deliveryId, leaseToken, quarantinedAtEpochMs)
    }

    private suspend fun transition(
        operation: suspend () -> LocalAlarmDeliveryTransitionDecision,
    ): LocalAlarmDeliveryTransitionResult = try {
        when (operation()) {
            LocalAlarmDeliveryTransitionDecision.APPLIED ->
                LocalAlarmDeliveryTransitionResult.Applied
            LocalAlarmDeliveryTransitionDecision.ALREADY_APPLIED ->
                LocalAlarmDeliveryTransitionResult.AlreadyApplied
        }
    } catch (conflict: SensorCoreConflictException) {
        LocalAlarmDeliveryTransitionResult.Conflict(
            conflict.message?.takeIf(String::isNotBlank) ?: "Alarm delivery transition conflict",
        )
    } catch (_: IllegalArgumentException) {
        LocalAlarmDeliveryTransitionResult.Conflict("Alarm delivery transition request is malformed")
    }

    companion object {
        fun create(context: Context): LocalAlarmDeliveryStore = LocalAlarmDeliveryRepository(
            SladkayaDatabase.get(context.applicationContext).localAlarmDeliveries(),
        )
    }
}
