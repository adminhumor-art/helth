package com.sladkaya.core.data

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Transaction

internal sealed interface LocalAlarmDeliveryLeaseDecision {
    data class Leased(val value: LocalAlarmDeliveryRecord) : LocalAlarmDeliveryLeaseDecision
    data object Empty : LocalAlarmDeliveryLeaseDecision
    data class NotDue(
        val deliveryId: String,
        val notBeforeEpochMs: Long,
    ) : LocalAlarmDeliveryLeaseDecision
    data class BlockedByActiveLease(
        val deliveryId: String,
        val leaseExpiresAtEpochMs: Long,
    ) : LocalAlarmDeliveryLeaseDecision
}

internal enum class LocalAlarmDeliveryTransitionDecision {
    APPLIED,
    ALREADY_APPLIED,
}

@Dao
internal abstract class LocalAlarmDeliveryDao {
    @Query(
        "SELECT * FROM local_alarm_deliveries " +
            "WHERE state NOT IN ('DELIVERED', 'CANCELLED') " +
            "ORDER BY notBeforeEpochMs ASC, sourceEffectId ASC, kindOrder ASC, deliveryId ASC " +
            "LIMIT 1",
    )
    abstract suspend fun earliestUndelivered(): LocalAlarmDeliveryEntity?

    @Query("SELECT * FROM local_alarm_deliveries WHERE deliveryId = :deliveryId LIMIT 1")
    abstract suspend fun delivery(deliveryId: String): LocalAlarmDeliveryEntity?

    @Query(
        "SELECT * FROM local_alarm_deliveries " +
            "WHERE leaseToken = :token OR lastTransitionToken = :token " +
            "ORDER BY notBeforeEpochMs ASC, sourceEffectId ASC, kindOrder ASC, deliveryId ASC",
    )
    abstract suspend fun byOperationToken(token: String): List<LocalAlarmDeliveryEntity>

    @Query(
        "UPDATE local_alarm_deliveries SET state = 'PENDING', " +
            "lastTransitionToken = leaseToken, lastTransitionKind = 'EXPIRED', " +
            "lastTransitionAtEpochMs = leaseExpiresAtEpochMs, " +
            "leaseToken = NULL, leaseExpiresAtEpochMs = NULL " +
            "WHERE state = 'LEASED' AND leaseExpiresAtEpochMs <= :nowEpochMs",
    )
    abstract suspend fun recoverExpiredLeases(nowEpochMs: Long): Int

    @Query(
        "UPDATE local_alarm_deliveries SET state = 'LEASED', attempts = attempts + 1, " +
            "leaseToken = :leaseToken, leaseExpiresAtEpochMs = :leaseExpiresAtEpochMs " +
            "WHERE deliveryId = :deliveryId AND state = 'PENDING'",
    )
    abstract suspend fun acquireLease(
        deliveryId: String,
        leaseToken: String,
        leaseExpiresAtEpochMs: Long,
    ): Int

    @Query(
        "UPDATE local_alarm_deliveries SET state = 'DELIVERED', " +
            "leaseToken = NULL, leaseExpiresAtEpochMs = NULL, " +
            "lastTransitionToken = :leaseToken, lastTransitionKind = 'DELIVERED', " +
            "lastTransitionAtEpochMs = :deliveredAtEpochMs, " +
            "deliveredAtEpochMs = :deliveredAtEpochMs " +
            "WHERE deliveryId = :deliveryId AND state = 'LEASED' AND leaseToken = :leaseToken",
    )
    abstract suspend fun setDelivered(
        deliveryId: String,
        leaseToken: String,
        deliveredAtEpochMs: Long,
    ): Int

    @Query(
        "UPDATE local_alarm_deliveries SET state = 'PENDING', " +
            "notBeforeEpochMs = :nextAttemptEpochMs, " +
            "leaseToken = NULL, leaseExpiresAtEpochMs = NULL, " +
            "lastTransitionToken = :leaseToken, lastTransitionKind = 'RETRY', " +
            "lastTransitionAtEpochMs = :rescheduledAtEpochMs " +
            "WHERE deliveryId = :deliveryId AND state = 'LEASED' AND leaseToken = :leaseToken",
    )
    abstract suspend fun setPending(
        deliveryId: String,
        leaseToken: String,
        rescheduledAtEpochMs: Long,
        nextAttemptEpochMs: Long,
    ): Int

    @Query(
        "UPDATE local_alarm_deliveries SET state = 'CANCELLED', " +
            "leaseToken = NULL, leaseExpiresAtEpochMs = NULL, " +
            "lastTransitionToken = :leaseToken, lastTransitionKind = 'CANCELLED', " +
            "lastTransitionAtEpochMs = :cancelledAtEpochMs, deliveredAtEpochMs = NULL " +
            "WHERE deliveryId = :deliveryId AND state = 'LEASED' AND leaseToken = :leaseToken",
    )
    abstract suspend fun setCancelled(
        deliveryId: String,
        leaseToken: String,
        cancelledAtEpochMs: Long,
    ): Int

    @Transaction
    open suspend fun leaseDueEarliest(
        nowEpochMs: Long,
        leaseToken: String,
        leaseExpiresAtEpochMs: Long,
    ): LocalAlarmDeliveryLeaseDecision {
        require(nowEpochMs > 0)
        requireLeaseToken(leaseToken)
        require(leaseExpiresAtEpochMs > nowEpochMs)

        recoverExpiredLeases(nowEpochMs)
        val tokenHistory = byOperationToken(leaseToken)
        val exactActive = tokenHistory.filter { it.leaseToken == leaseToken }
        if (exactActive.size == 1 && tokenHistory.size == 1) {
            val saved = exactActive.single()
            if (saved.state == LocalAlarmDeliveryState.LEASED.wireName &&
                saved.leaseExpiresAtEpochMs == leaseExpiresAtEpochMs &&
                earliestUndelivered()?.deliveryId == saved.deliveryId
            ) {
                return LocalAlarmDeliveryLeaseDecision.Leased(validated(saved))
            }
            conflict("Alarm delivery lease token was already used for another operation")
        }
        if (tokenHistory.isNotEmpty()) {
            conflict("Alarm delivery lease token was already finalized or expired")
        }

        val earliest = earliestUndelivered()
            ?: return LocalAlarmDeliveryLeaseDecision.Empty
        when (earliest.state) {
            LocalAlarmDeliveryState.LEASED.wireName -> {
                val expiry = earliest.leaseExpiresAtEpochMs
                    ?: conflict("Earliest alarm delivery has a malformed active lease")
                validated(earliest)
                return LocalAlarmDeliveryLeaseDecision.BlockedByActiveLease(
                    earliest.deliveryId,
                    expiry,
                )
            }
            LocalAlarmDeliveryState.PENDING.wireName -> Unit
            else -> conflict("Earliest alarm delivery has an unsupported state")
        }
        if (earliest.notBeforeEpochMs > nowEpochMs) {
            validated(earliest)
            return LocalAlarmDeliveryLeaseDecision.NotDue(
                earliest.deliveryId,
                earliest.notBeforeEpochMs,
            )
        }
        if (acquireLease(earliest.deliveryId, leaseToken, leaseExpiresAtEpochMs) != 1) {
            conflict("Earliest alarm delivery changed while acquiring its lease")
        }
        return LocalAlarmDeliveryLeaseDecision.Leased(
            validated(delivery(earliest.deliveryId) ?: conflict("Leased alarm delivery disappeared")),
        )
    }

    @Transaction
    open suspend fun markDelivered(
        deliveryId: String,
        leaseToken: String,
        deliveredAtEpochMs: Long,
    ): LocalAlarmDeliveryTransitionDecision {
        requireDeliveryId(deliveryId)
        requireLeaseToken(leaseToken)
        require(deliveredAtEpochMs > 0)
        val saved = delivery(deliveryId) ?: conflict("Alarm delivery does not exist")
        if (saved.state == LocalAlarmDeliveryState.DELIVERED.wireName) {
            if (saved.lastTransitionToken == leaseToken &&
                saved.lastTransitionKind == LocalAlarmDeliveryTransitionKind.DELIVERED.wireName &&
                saved.lastTransitionAtEpochMs == deliveredAtEpochMs &&
                saved.deliveredAtEpochMs == deliveredAtEpochMs
            ) {
                validated(saved)
                return LocalAlarmDeliveryTransitionDecision.ALREADY_APPLIED
            }
            conflict("Alarm delivery was finalized by another operation")
        }
        requireExactActiveLease(saved, leaseToken, deliveredAtEpochMs)
        if (deliveredAtEpochMs < saved.notBeforeEpochMs) {
            conflict("Alarm delivery cannot be acknowledged before its due time")
        }
        if (setDelivered(deliveryId, leaseToken, deliveredAtEpochMs) != 1) {
            conflict("Alarm delivery lease changed while acknowledging delivery")
        }
        validated(delivery(deliveryId) ?: conflict("Delivered alarm row disappeared"))
        return LocalAlarmDeliveryTransitionDecision.APPLIED
    }

    @Transaction
    open suspend fun reschedule(
        deliveryId: String,
        leaseToken: String,
        rescheduledAtEpochMs: Long,
        nextAttemptEpochMs: Long,
    ): LocalAlarmDeliveryTransitionDecision {
        requireDeliveryId(deliveryId)
        requireLeaseToken(leaseToken)
        require(rescheduledAtEpochMs > 0)
        require(nextAttemptEpochMs > rescheduledAtEpochMs)
        val saved = delivery(deliveryId) ?: conflict("Alarm delivery does not exist")
        if (saved.state == LocalAlarmDeliveryState.PENDING.wireName &&
            saved.lastTransitionToken == leaseToken &&
            saved.lastTransitionKind == LocalAlarmDeliveryTransitionKind.RETRY.wireName
        ) {
            if (saved.lastTransitionAtEpochMs == rescheduledAtEpochMs &&
                saved.notBeforeEpochMs == nextAttemptEpochMs
            ) {
                validated(saved)
                return LocalAlarmDeliveryTransitionDecision.ALREADY_APPLIED
            }
            conflict("Alarm delivery retry differs from the finalized operation")
        }
        requireExactActiveLease(saved, leaseToken, rescheduledAtEpochMs)
        if (rescheduledAtEpochMs < saved.notBeforeEpochMs) {
            conflict("Alarm delivery cannot be retried before its due time")
        }
        if (setPending(
                deliveryId,
                leaseToken,
                rescheduledAtEpochMs,
                nextAttemptEpochMs,
            ) != 1
        ) {
            conflict("Alarm delivery lease changed while scheduling its retry")
        }
        validated(delivery(deliveryId) ?: conflict("Retried alarm row disappeared"))
        return LocalAlarmDeliveryTransitionDecision.APPLIED
    }

    /** Permanently removes only the exact leased FIFO head after its typed work is irrecoverable. */
    @Transaction
    open suspend fun quarantine(
        deliveryId: String,
        leaseToken: String,
        cancelledAtEpochMs: Long,
    ): LocalAlarmDeliveryTransitionDecision {
        requireDeliveryId(deliveryId)
        requireLeaseToken(leaseToken)
        require(cancelledAtEpochMs > 0)
        val saved = delivery(deliveryId) ?: conflict("Alarm delivery does not exist")
        if (saved.state == LocalAlarmDeliveryState.CANCELLED.wireName &&
            saved.lastTransitionToken == leaseToken &&
            saved.lastTransitionKind == LocalAlarmDeliveryTransitionKind.CANCELLED.wireName
        ) {
            if (saved.lastTransitionAtEpochMs == cancelledAtEpochMs) {
                validated(saved)
                return LocalAlarmDeliveryTransitionDecision.ALREADY_APPLIED
            }
            conflict("Alarm delivery quarantine differs from the finalized operation")
        }
        requireExactActiveLease(saved, leaseToken, cancelledAtEpochMs)
        if (cancelledAtEpochMs < saved.notBeforeEpochMs) {
            conflict("Alarm delivery cannot be quarantined before its due time")
        }
        if (setCancelled(deliveryId, leaseToken, cancelledAtEpochMs) != 1) {
            conflict("Alarm delivery lease changed while quarantining delivery")
        }
        validated(delivery(deliveryId) ?: conflict("Quarantined alarm row disappeared"))
        return LocalAlarmDeliveryTransitionDecision.APPLIED
    }

    private suspend fun requireExactActiveLease(
        saved: LocalAlarmDeliveryEntity,
        leaseToken: String,
        transitionAtEpochMs: Long,
    ) {
        if (saved.state != LocalAlarmDeliveryState.LEASED.wireName ||
            saved.leaseToken != leaseToken
        ) {
            conflict("Alarm delivery transition requires its exact active lease")
        }
        if (earliestUndelivered()?.deliveryId != saved.deliveryId) {
            conflict("Only the earliest alarm delivery may transition")
        }
        val expiry = saved.leaseExpiresAtEpochMs
            ?: conflict("Alarm delivery lease expiry is missing")
        if (transitionAtEpochMs >= expiry) {
            conflict("Alarm delivery transition time is outside its active lease")
        }
        validated(saved)
    }

    private fun validated(value: LocalAlarmDeliveryEntity): LocalAlarmDeliveryRecord = try {
        value.toRecord()
    } catch (_: IllegalArgumentException) {
        conflict("Stored alarm delivery is malformed")
    } catch (_: NoSuchElementException) {
        conflict("Stored alarm delivery contains unsupported typed data")
    }

    private fun requireDeliveryId(value: String) {
        require(Regex("^[0-9a-f]{64}$").matches(value))
    }

    private fun conflict(message: String): Nothing = throw SensorCoreConflictException(message)
}
