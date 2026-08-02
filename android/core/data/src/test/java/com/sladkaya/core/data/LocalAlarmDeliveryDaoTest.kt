package com.sladkaya.core.data

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalAlarmDeliveryDaoTest {
    @Test
    fun activeEarliestLeaseBlocksLaterDeliveryUntilExactDeliveredAck() = runBlocking {
        val dao = RecordingLocalAlarmDeliveryDao().apply {
            add("11".repeat(32), effectId = 1L, notBeforeEpochMs = NOW - 1L)
            add("22".repeat(32), effectId = 2L, notBeforeEpochMs = NOW)
        }

        val first = dao.leaseDueEarliest(NOW, LEASE_A, NOW + 10_000L)
            as LocalAlarmDeliveryLeaseDecision.Leased
        assertEquals("11".repeat(32), first.value.deliveryId)
        assertEquals(first, dao.leaseDueEarliest(NOW, LEASE_A, NOW + 10_000L))
        assertTrue(
            dao.leaseDueEarliest(NOW, LEASE_B, NOW + 10_000L) is
                LocalAlarmDeliveryLeaseDecision.BlockedByActiveLease,
        )

        assertEquals(
            LocalAlarmDeliveryTransitionDecision.APPLIED,
            dao.markDelivered(first.value.deliveryId, LEASE_A, NOW + 1_000L),
        )
        assertEquals(
            LocalAlarmDeliveryTransitionDecision.ALREADY_APPLIED,
            dao.markDelivered(first.value.deliveryId, LEASE_A, NOW + 1_000L),
        )
        val second = dao.leaseDueEarliest(NOW + 1_001L, LEASE_B, NOW + 20_000L)
            as LocalAlarmDeliveryLeaseDecision.Leased
        assertEquals("22".repeat(32), second.value.deliveryId)
    }

    @Test
    fun expiredLeaseRecoversTheSameEarliestDeliveryBeforeLaterRows() = runBlocking {
        val dao = RecordingLocalAlarmDeliveryDao().apply {
            add("11".repeat(32), effectId = 1L, notBeforeEpochMs = NOW)
            add("22".repeat(32), effectId = 2L, notBeforeEpochMs = NOW)
        }

        dao.leaseDueEarliest(NOW, LEASE_A, NOW + 10_000L)
        val recovered = dao.leaseDueEarliest(NOW + 10_001L, LEASE_B, NOW + 20_000L)
            as LocalAlarmDeliveryLeaseDecision.Leased

        assertEquals("11".repeat(32), recovered.value.deliveryId)
        assertEquals(2, recovered.value.attempts)
    }

    @Test
    fun retryIsExactIdempotentAndRespectsTheNewDueTime() = runBlocking {
        val dao = RecordingLocalAlarmDeliveryDao().apply {
            add("11".repeat(32), effectId = 1L, notBeforeEpochMs = NOW)
        }
        val leased = dao.leaseDueEarliest(NOW, LEASE_A, NOW + 10_000L)
            as LocalAlarmDeliveryLeaseDecision.Leased

        assertEquals(
            LocalAlarmDeliveryTransitionDecision.APPLIED,
            dao.reschedule(
                deliveryId = leased.value.deliveryId,
                leaseToken = LEASE_A,
                rescheduledAtEpochMs = NOW + 1_000L,
                nextAttemptEpochMs = NOW + 60_000L,
            ),
        )
        assertEquals(
            LocalAlarmDeliveryTransitionDecision.ALREADY_APPLIED,
            dao.reschedule(
                deliveryId = leased.value.deliveryId,
                leaseToken = LEASE_A,
                rescheduledAtEpochMs = NOW + 1_000L,
                nextAttemptEpochMs = NOW + 60_000L,
            ),
        )
        assertTrue(
            dao.leaseDueEarliest(NOW + 59_999L, LEASE_B, NOW + 70_000L) is
                LocalAlarmDeliveryLeaseDecision.NotDue,
        )
        assertTrue(
            dao.leaseDueEarliest(NOW + 60_000L, LEASE_B, NOW + 70_000L) is
                LocalAlarmDeliveryLeaseDecision.Leased,
        )
    }

    @Test
    fun exactQuarantineCancelsOnlyThePoisonedLeaseAndUnblocksGlobalFifo() = runBlocking {
        val firstId = "11".repeat(32)
        val secondId = "22".repeat(32)
        val dao = RecordingLocalAlarmDeliveryDao().apply {
            add(firstId, effectId = 1L, notBeforeEpochMs = NOW)
            add(secondId, effectId = 2L, notBeforeEpochMs = NOW)
        }
        dao.leaseDueEarliest(NOW, LEASE_A, NOW + 10_000L)

        assertEquals(
            LocalAlarmDeliveryTransitionDecision.APPLIED,
            dao.quarantine(firstId, LEASE_A, NOW + 1_000L),
        )
        assertEquals(
            LocalAlarmDeliveryTransitionDecision.ALREADY_APPLIED,
            dao.quarantine(firstId, LEASE_A, NOW + 1_000L),
        )
        assertEquals(LocalAlarmDeliveryState.CANCELLED.wireName, dao.delivery(firstId)?.state)
        assertEquals(
            secondId,
            (dao.leaseDueEarliest(NOW + 1_001L, LEASE_B, NOW + 20_000L)
                as LocalAlarmDeliveryLeaseDecision.Leased).value.deliveryId,
        )
    }

    @Test
    fun quarantineRejectsAnyLeaseThatIsNotTheExactActiveFifoHead() = runBlocking {
        val firstId = "11".repeat(32)
        val dao = RecordingLocalAlarmDeliveryDao().apply {
            add(firstId, effectId = 1L, notBeforeEpochMs = NOW)
        }
        dao.leaseDueEarliest(NOW, LEASE_A, NOW + 10_000L)

        assertThrows(SensorCoreConflictException::class.java) {
            runBlocking { dao.quarantine(firstId, LEASE_B, NOW + 1_000L) }
        }
        assertEquals(LocalAlarmDeliveryState.LEASED.wireName, dao.delivery(firstId)?.state)
        Unit
    }

    @Test
    fun finalizedOrDifferentOperationTokenCannotBeReused() = runBlocking {
        val dao = RecordingLocalAlarmDeliveryDao().apply {
            add("11".repeat(32), effectId = 1L, notBeforeEpochMs = NOW)
        }
        dao.leaseDueEarliest(NOW, LEASE_A, NOW + 10_000L)
        dao.markDelivered("11".repeat(32), LEASE_A, NOW + 1_000L)

        assertThrows(SensorCoreConflictException::class.java) {
            runBlocking { dao.leaseDueEarliest(NOW + 2_000L, LEASE_A, NOW + 20_000L) }
        }
        assertThrows(SensorCoreConflictException::class.java) {
            runBlocking {
                dao.markDelivered("11".repeat(32), LEASE_B, NOW + 1_000L)
            }
        }
        Unit
    }

    private companion object {
        const val NOW = 1_700_000_200_000L
        const val LEASE_A = "alarm-delivery-lease-a"
        const val LEASE_B = "alarm-delivery-lease-b"
    }
}

private class RecordingLocalAlarmDeliveryDao : LocalAlarmDeliveryDao() {
    private val deliveries = linkedMapOf<String, LocalAlarmDeliveryEntity>()

    fun add(deliveryId: String, effectId: Long, notBeforeEpochMs: Long) {
        deliveries[deliveryId] = LocalAlarmDeliveryEntity(
            deliveryId = deliveryId,
            sourceEffectId = effectId,
            sourceEventId = "event-$effectId",
            approvalId = "ab".repeat(32),
            publicationBindingId = "cd".repeat(32),
            kind = LocalAlarmDeliveryKind.WIDGET.wireName,
            kindOrder = LocalAlarmDeliveryKind.WIDGET.order,
            activeKinds = "",
            episodeGeneration = 0L,
            episodeAcknowledged = false,
            resultingStateSha256 = "ef".repeat(32),
            createdAtEpochMs = DELIVERY_TEST_NOW - 1_000L,
            notBeforeEpochMs = notBeforeEpochMs,
            state = LocalAlarmDeliveryState.PENDING.wireName,
            attempts = 0,
            leaseToken = null,
            leaseExpiresAtEpochMs = null,
            lastTransitionToken = null,
            lastTransitionKind = null,
            lastTransitionAtEpochMs = null,
            deliveredAtEpochMs = null,
        )
    }

    override suspend fun earliestUndelivered(): LocalAlarmDeliveryEntity? = deliveries.values
        .filter {
            it.state != LocalAlarmDeliveryState.DELIVERED.wireName &&
                it.state != LocalAlarmDeliveryState.CANCELLED.wireName
        }
        .minWithOrNull(
            compareBy<LocalAlarmDeliveryEntity>(
                LocalAlarmDeliveryEntity::notBeforeEpochMs,
                LocalAlarmDeliveryEntity::sourceEffectId,
                LocalAlarmDeliveryEntity::kindOrder,
                LocalAlarmDeliveryEntity::deliveryId,
            ),
        )

    override suspend fun delivery(deliveryId: String): LocalAlarmDeliveryEntity? =
        deliveries[deliveryId]

    override suspend fun byOperationToken(token: String): List<LocalAlarmDeliveryEntity> =
        deliveries.values.filter { it.leaseToken == token || it.lastTransitionToken == token }

    override suspend fun recoverExpiredLeases(nowEpochMs: Long): Int {
        val expired = deliveries.values.filter {
            it.state == LocalAlarmDeliveryState.LEASED.wireName &&
                checkNotNull(it.leaseExpiresAtEpochMs) <= nowEpochMs
        }
        expired.forEach { value ->
            deliveries[value.deliveryId] = value.copy(
                state = LocalAlarmDeliveryState.PENDING.wireName,
                leaseToken = null,
                leaseExpiresAtEpochMs = null,
                lastTransitionToken = value.leaseToken,
                lastTransitionKind = LocalAlarmDeliveryTransitionKind.EXPIRED.wireName,
                lastTransitionAtEpochMs = value.leaseExpiresAtEpochMs,
            )
        }
        return expired.size
    }

    override suspend fun acquireLease(
        deliveryId: String,
        leaseToken: String,
        leaseExpiresAtEpochMs: Long,
    ): Int = transition(deliveryId) { value ->
        if (value.state != LocalAlarmDeliveryState.PENDING.wireName) return@transition null
        value.copy(
            state = LocalAlarmDeliveryState.LEASED.wireName,
            attempts = value.attempts + 1,
            leaseToken = leaseToken,
            leaseExpiresAtEpochMs = leaseExpiresAtEpochMs,
        )
    }

    override suspend fun setDelivered(
        deliveryId: String,
        leaseToken: String,
        deliveredAtEpochMs: Long,
    ): Int = transition(deliveryId) { value ->
        if (value.state != LocalAlarmDeliveryState.LEASED.wireName ||
            value.leaseToken != leaseToken
        ) return@transition null
        value.copy(
            state = LocalAlarmDeliveryState.DELIVERED.wireName,
            leaseToken = null,
            leaseExpiresAtEpochMs = null,
            lastTransitionToken = leaseToken,
            lastTransitionKind = LocalAlarmDeliveryTransitionKind.DELIVERED.wireName,
            lastTransitionAtEpochMs = deliveredAtEpochMs,
            deliveredAtEpochMs = deliveredAtEpochMs,
        )
    }

    override suspend fun setPending(
        deliveryId: String,
        leaseToken: String,
        rescheduledAtEpochMs: Long,
        nextAttemptEpochMs: Long,
    ): Int = transition(deliveryId) { value ->
        if (value.state != LocalAlarmDeliveryState.LEASED.wireName ||
            value.leaseToken != leaseToken
        ) return@transition null
        value.copy(
            state = LocalAlarmDeliveryState.PENDING.wireName,
            notBeforeEpochMs = nextAttemptEpochMs,
            leaseToken = null,
            leaseExpiresAtEpochMs = null,
            lastTransitionToken = leaseToken,
            lastTransitionKind = LocalAlarmDeliveryTransitionKind.RETRY.wireName,
            lastTransitionAtEpochMs = rescheduledAtEpochMs,
        )
    }

    override suspend fun setCancelled(
        deliveryId: String,
        leaseToken: String,
        cancelledAtEpochMs: Long,
    ): Int = transition(deliveryId) { value ->
        if (value.state != LocalAlarmDeliveryState.LEASED.wireName ||
            value.leaseToken != leaseToken
        ) return@transition null
        value.copy(
            state = LocalAlarmDeliveryState.CANCELLED.wireName,
            leaseToken = null,
            leaseExpiresAtEpochMs = null,
            lastTransitionToken = leaseToken,
            lastTransitionKind = LocalAlarmDeliveryTransitionKind.CANCELLED.wireName,
            lastTransitionAtEpochMs = cancelledAtEpochMs,
        )
    }

    private fun transition(
        deliveryId: String,
        transform: (LocalAlarmDeliveryEntity) -> LocalAlarmDeliveryEntity?,
    ): Int {
        val current = deliveries[deliveryId] ?: return 0
        val updated = transform(current) ?: return 0
        deliveries[deliveryId] = updated
        return 1
    }
}

private const val DELIVERY_TEST_NOW = 1_700_000_200_000L
