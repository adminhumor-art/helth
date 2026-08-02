package com.sladkaya.core.data

import android.content.Context

interface LocalReadingEffectStore {
    suspend fun leaseEarliest(
        nowEpochMs: Long,
        leaseToken: String,
        leaseExpiresAtEpochMs: Long,
    ): LocalReadingEffectLeaseResult
}

class LocalReadingEffectRepository private constructor(
    private val dao: LocalReadingEffectDao,
) : LocalReadingEffectStore {
    override suspend fun leaseEarliest(
        nowEpochMs: Long,
        leaseToken: String,
        leaseExpiresAtEpochMs: Long,
    ): LocalReadingEffectLeaseResult = try {
        when (val result = dao.leaseEarliest(nowEpochMs, leaseToken, leaseExpiresAtEpochMs)) {
            is LocalReadingEffectLeaseDecision.Leased ->
                LocalReadingEffectLeaseResult.Leased(result.value)
            LocalReadingEffectLeaseDecision.Empty -> LocalReadingEffectLeaseResult.Empty
            is LocalReadingEffectLeaseDecision.BlockedByActiveLease ->
                LocalReadingEffectLeaseResult.BlockedByActiveLease(
                    effectId = result.effectId,
                    leaseExpiresAtEpochMs = result.leaseExpiresAtEpochMs,
                )
        }
    } catch (conflict: SensorCoreConflictException) {
        LocalReadingEffectLeaseResult.Conflict(
            conflict.message?.takeIf { it.isNotBlank() } ?: "Local effect lease conflict",
        )
    } catch (_: IllegalArgumentException) {
        LocalReadingEffectLeaseResult.Conflict("Local effect lease request is malformed")
    }

    companion object {
        fun create(context: Context): LocalReadingEffectStore = LocalReadingEffectRepository(
            SladkayaDatabase.get(context.applicationContext).localReadingEffects(),
        )
    }
}
