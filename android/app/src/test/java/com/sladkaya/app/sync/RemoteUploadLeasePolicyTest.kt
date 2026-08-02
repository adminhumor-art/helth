package com.sladkaya.app.sync

import java.time.Duration
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RemoteUploadLeasePolicyTest {
    @Test
    fun retryBackoffIsExponentialAndBounded() {
        val now = 1_800_000_000_000L

        assertEquals(now + 30_000L, RemoteUploadLeasePolicy.nextAttemptEpochMs(now, attempts = 1))
        assertEquals(now + 60_000L, RemoteUploadLeasePolicy.nextAttemptEpochMs(now, attempts = 2))
        assertEquals(now + Duration.ofHours(6).toMillis(), RemoteUploadLeasePolicy.nextAttemptEpochMs(now, 100))
        assertEquals(Long.MAX_VALUE, RemoteUploadLeasePolicy.nextAttemptEpochMs(Long.MAX_VALUE - 10, 100))
    }

    @Test
    fun leaseIsBoundedAndOperationTokenContainsNoMedicalIdentity() {
        val now = 1_800_000_000_000L
        assertEquals(now + Duration.ofMinutes(2).toMillis(), RemoteUploadLeasePolicy.leaseExpiryEpochMs(now))

        val token = RemoteUploadLeasePolicy.newOperationToken()
        assertTrue(Regex("^upload-[0-9a-f-]{36}$").matches(token))
    }
}
