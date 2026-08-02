package com.sladkaya.core.data

import com.sladkaya.core.model.GlucoseReading
import com.sladkaya.core.model.ReadingQuality

enum class LocalReadingEffectState(val wireName: String) {
    PENDING("PENDING"),
    LEASED("LEASED"),
    ACKNOWLEDGED("ACKNOWLEDGED"),
}

/** Durable pointer to one product reading whose local effects still need settlement. */
data class LocalReadingEffectRecord(
    val effectId: Long,
    val eventId: String,
    val approvalId: String,
    val publicationBindingId: String,
    val state: LocalReadingEffectState,
    val attempts: Int,
    val enqueuedAtEpochMs: Long,
    val leaseToken: String?,
    val leaseExpiresAtEpochMs: Long?,
    val lastTransitionToken: String?,
    val acknowledgedAtEpochMs: Long?,
) {
    init {
        require(effectId > 0)
        require(eventId.isNotBlank())
        require(SHA256.matches(approvalId))
        require(SHA256.matches(publicationBindingId))
        require(attempts >= 0)
        require(enqueuedAtEpochMs > 0)
        if (lastTransitionToken != null) requireLeaseToken(lastTransitionToken)
        when (state) {
            LocalReadingEffectState.PENDING -> {
                require(leaseToken == null)
                require(leaseExpiresAtEpochMs == null)
                require(acknowledgedAtEpochMs == null)
            }

            LocalReadingEffectState.LEASED -> {
                requireLeaseToken(requireNotNull(leaseToken))
                require(requireNotNull(leaseExpiresAtEpochMs) > 0)
                require(acknowledgedAtEpochMs == null)
                require(attempts > 0)
            }

            LocalReadingEffectState.ACKNOWLEDGED -> {
                require(leaseToken == null)
                require(leaseExpiresAtEpochMs == null)
                requireNotNull(lastTransitionToken)
                require(requireNotNull(acknowledgedAtEpochMs) > 0)
                require(attempts > 0)
            }
        }
    }

    private companion object {
        val SHA256 = Regex("^[0-9a-f]{64}$")
    }
}

data class LeasedLocalReadingEffect(
    val effect: LocalReadingEffectRecord,
    val reading: GlucoseReading,
) {
    init {
        require(effect.state == LocalReadingEffectState.LEASED)
        require(effect.eventId == reading.eventId)
        require(effect.enqueuedAtEpochMs == reading.phoneTimeEpochMs)
        require(reading.quality == ReadingQuality.VALID)
        reading.requireProductPublication()
    }
}

sealed interface LocalReadingEffectLeaseResult {
    data class Leased(val value: LeasedLocalReadingEffect) : LocalReadingEffectLeaseResult
    data object Empty : LocalReadingEffectLeaseResult
    data class BlockedByActiveLease(
        val effectId: Long,
        val leaseExpiresAtEpochMs: Long,
    ) : LocalReadingEffectLeaseResult
    data class Conflict(val reason: String) : LocalReadingEffectLeaseResult
}
