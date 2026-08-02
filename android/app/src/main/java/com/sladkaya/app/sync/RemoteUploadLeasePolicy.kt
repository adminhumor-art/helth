package com.sladkaya.app.sync

import java.time.Duration
import java.util.UUID

internal object RemoteUploadLeasePolicy {
    const val BATCH_SIZE = 50
    private val LEASE_DURATION = Duration.ofMinutes(2)
    private val INITIAL_RETRY = Duration.ofSeconds(30)
    private val MAX_RETRY = Duration.ofHours(6)

    fun newOperationToken(): String = "upload-${UUID.randomUUID()}"

    fun leaseExpiryEpochMs(nowEpochMs: Long): Long {
        require(nowEpochMs > 0)
        return saturatingAdd(nowEpochMs, LEASE_DURATION.toMillis())
    }

    fun nextAttemptEpochMs(nowEpochMs: Long, attempts: Int): Long {
        require(nowEpochMs > 0)
        require(attempts > 0)
        val exponent = (attempts - 1).coerceAtMost(30)
        val multiplier = 1L shl exponent
        val uncapped = INITIAL_RETRY.toMillis().let { base ->
            if (multiplier > Long.MAX_VALUE / base) Long.MAX_VALUE else base * multiplier
        }
        return saturatingAdd(nowEpochMs, minOf(uncapped, MAX_RETRY.toMillis()))
    }

    private fun saturatingAdd(left: Long, right: Long): Long =
        if (left > Long.MAX_VALUE - right) Long.MAX_VALUE else left + right
}
