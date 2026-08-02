package com.sladkaya.core.data

import android.content.Context

interface LocalAlarmStore {
    suspend fun initializeMonitoring(
        request: LocalAlarmMonitoringStartRequest,
    ): LocalAlarmMonitoringStartResult = LocalAlarmMonitoringStartResult.Conflict(
        "Local monitoring start is unavailable",
    )

    suspend fun leaseEarliest(
        nowEpochMs: Long,
        leaseToken: String,
        leaseExpiresAtEpochMs: Long,
    ): LocalReadingEffectLeaseResult

    suspend fun applyLeased(request: LocalAlarmApplyRequest): LocalAlarmApplyResult

    suspend fun readSettlement(
        eventId: String,
        approvalId: String,
        publicationBindingId: String,
    ): LocalAlarmSettlementReadResult

    suspend fun readState(publicationBindingId: String): LocalAlarmStateReadResult

    suspend fun acknowledgeEpisode(
        publicationBindingId: String,
        expectedEpisodeGeneration: Long,
        acknowledgedAtEpochMs: Long,
    ): LocalAlarmEpisodeAcknowledgeResult

    suspend fun applyWatchdog(
        publicationBindingId: String,
        expectedStateSha256: String,
        nowEpochMs: Long,
    ): LocalAlarmWatchdogResult

    suspend fun applySettings(
        request: LocalAlarmSettingsApplyRequest,
    ): LocalAlarmSettingsApplyResult = LocalAlarmSettingsApplyResult.Conflict(
        "Local alarm settings mutation is unavailable",
    )
}

class LocalAlarmRepository private constructor(
    private val effectDao: LocalReadingEffectDao,
    private val alarmDao: LocalAlarmDao,
) : LocalAlarmStore {
    override suspend fun initializeMonitoring(
        request: LocalAlarmMonitoringStartRequest,
    ): LocalAlarmMonitoringStartResult = try {
        when (val result = alarmDao.initializeMonitoring(request)) {
            is LocalAlarmMonitoringStartDecision.Initialized ->
                LocalAlarmMonitoringStartResult.Initialized(result.settlement)
            is LocalAlarmMonitoringStartDecision.AlreadyInitialized ->
                LocalAlarmMonitoringStartResult.AlreadyInitialized(result.settlement)
        }
    } catch (conflict: SensorCoreConflictException) {
        LocalAlarmMonitoringStartResult.Conflict(
            conflict.safeLocalAlarmReason("Local monitoring start conflict"),
        )
    } catch (_: IllegalArgumentException) {
        LocalAlarmMonitoringStartResult.Conflict("Local monitoring start request is malformed")
    }

    override suspend fun leaseEarliest(
        nowEpochMs: Long,
        leaseToken: String,
        leaseExpiresAtEpochMs: Long,
    ): LocalReadingEffectLeaseResult = try {
        when (val result = effectDao.leaseEarliest(nowEpochMs, leaseToken, leaseExpiresAtEpochMs)) {
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
        LocalReadingEffectLeaseResult.Conflict(conflict.safeLocalAlarmReason("Local effect lease conflict"))
    } catch (_: IllegalArgumentException) {
        LocalReadingEffectLeaseResult.Conflict("Local effect lease request is malformed")
    }

    override suspend fun applyLeased(
        request: LocalAlarmApplyRequest,
    ): LocalAlarmApplyResult = try {
        when (val result = alarmDao.applyLeased(request)) {
            is LocalAlarmApplyDecision.Applied -> LocalAlarmApplyResult.Applied(result.settlement)
            is LocalAlarmApplyDecision.AlreadyApplied ->
                LocalAlarmApplyResult.AlreadyApplied(result.settlement)
        }
    } catch (conflict: SensorCoreConflictException) {
        LocalAlarmApplyResult.Conflict(conflict.safeLocalAlarmReason("Local alarm apply conflict"))
    } catch (_: IllegalArgumentException) {
        LocalAlarmApplyResult.Conflict("Local alarm apply request is malformed")
    }

    override suspend fun readSettlement(
        eventId: String,
        approvalId: String,
        publicationBindingId: String,
    ): LocalAlarmSettlementReadResult = try {
        when (val result = alarmDao.readSettlement(eventId, approvalId, publicationBindingId)) {
            is LocalAlarmSettlementReadDecision.Exact ->
                LocalAlarmSettlementReadResult.Exact(result.settlement)
            LocalAlarmSettlementReadDecision.Missing -> LocalAlarmSettlementReadResult.Missing
            is LocalAlarmSettlementReadDecision.Mismatch ->
                LocalAlarmSettlementReadResult.Conflict(result.reason)
        }
    } catch (conflict: SensorCoreConflictException) {
        LocalAlarmSettlementReadResult.Conflict(
            conflict.safeLocalAlarmReason("Local alarm settlement conflict"),
        )
    } catch (_: IllegalArgumentException) {
        LocalAlarmSettlementReadResult.Conflict("Local alarm settlement identity is malformed")
    }

    override suspend fun readState(
        publicationBindingId: String,
    ): LocalAlarmStateReadResult = try {
        require(SHA256.matches(publicationBindingId))
        when (val state = alarmDao.verifiedAlarmState(publicationBindingId)) {
            null -> LocalAlarmStateReadResult.Empty
            else -> LocalAlarmStateReadResult.Exact(state)
        }
    } catch (conflict: SensorCoreConflictException) {
        LocalAlarmStateReadResult.Conflict(
            conflict.safeLocalAlarmReason("Local alarm state conflict"),
        )
    } catch (_: IllegalArgumentException) {
        LocalAlarmStateReadResult.Conflict("Stored local alarm state is malformed")
    } catch (_: NoSuchElementException) {
        LocalAlarmStateReadResult.Conflict("Stored local alarm state has unsupported alarm kinds")
    }

    override suspend fun acknowledgeEpisode(
        publicationBindingId: String,
        expectedEpisodeGeneration: Long,
        acknowledgedAtEpochMs: Long,
    ): LocalAlarmEpisodeAcknowledgeResult = try {
        when (
            val result = alarmDao.acknowledgeEpisode(
                publicationBindingId,
                expectedEpisodeGeneration,
                acknowledgedAtEpochMs,
            )
        ) {
            is LocalAlarmEpisodeAcknowledgeDecision.Applied ->
                LocalAlarmEpisodeAcknowledgeResult.Applied(result.state)
            is LocalAlarmEpisodeAcknowledgeDecision.AlreadyApplied ->
                LocalAlarmEpisodeAcknowledgeResult.AlreadyApplied(result.state)
            is LocalAlarmEpisodeAcknowledgeDecision.Stale ->
                LocalAlarmEpisodeAcknowledgeResult.Stale(
                    result.currentEpisodeGeneration,
                    result.currentStateSha256,
                )
        }
    } catch (conflict: SensorCoreConflictException) {
        LocalAlarmEpisodeAcknowledgeResult.Conflict(
            conflict.safeLocalAlarmReason("Local alarm acknowledgement conflict"),
        )
    } catch (_: IllegalArgumentException) {
        LocalAlarmEpisodeAcknowledgeResult.Conflict(
            "Local alarm acknowledgement request is malformed",
        )
    }

    override suspend fun applyWatchdog(
        publicationBindingId: String,
        expectedStateSha256: String,
        nowEpochMs: Long,
    ): LocalAlarmWatchdogResult = try {
        when (
            val result = alarmDao.applyWatchdog(
                publicationBindingId,
                expectedStateSha256,
                nowEpochMs,
            )
        ) {
            is LocalAlarmWatchdogDecision.Applied ->
                LocalAlarmWatchdogResult.Applied(result.settlement)
            is LocalAlarmWatchdogDecision.AlreadyApplied ->
                LocalAlarmWatchdogResult.AlreadyApplied(result.settlement)
            is LocalAlarmWatchdogDecision.Obsolete ->
                LocalAlarmWatchdogResult.Obsolete(result.currentStateSha256)
        }
    } catch (conflict: SensorCoreConflictException) {
        LocalAlarmWatchdogResult.Conflict(
            conflict.safeLocalAlarmReason("Local alarm watchdog conflict"),
        )
    } catch (_: IllegalArgumentException) {
        LocalAlarmWatchdogResult.Conflict("Local alarm watchdog request is malformed")
    }

    override suspend fun applySettings(
        request: LocalAlarmSettingsApplyRequest,
    ): LocalAlarmSettingsApplyResult = try {
        when (val result = alarmDao.applySettings(request)) {
            is LocalAlarmSettingsApplyDecision.Applied ->
                LocalAlarmSettingsApplyResult.Applied(result.settlement)
            is LocalAlarmSettingsApplyDecision.AlreadyApplied ->
                LocalAlarmSettingsApplyResult.AlreadyApplied(result.settlement)
            is LocalAlarmSettingsApplyDecision.Obsolete ->
                LocalAlarmSettingsApplyResult.Obsolete(result.currentStateSha256)
        }
    } catch (conflict: SensorCoreConflictException) {
        LocalAlarmSettingsApplyResult.Conflict(
            conflict.safeLocalAlarmReason("Local alarm settings conflict"),
        )
    } catch (_: IllegalArgumentException) {
        LocalAlarmSettingsApplyResult.Conflict("Local alarm settings request is malformed")
    }

    companion object {
        fun create(context: Context): LocalAlarmStore {
            val database = SladkayaDatabase.get(context.applicationContext)
            return LocalAlarmRepository(
                effectDao = database.localReadingEffects(),
                alarmDao = database.localAlarms(),
            )
        }

        private val SHA256 = Regex("^[0-9a-f]{64}$")
    }
}

private fun SensorCoreConflictException.safeLocalAlarmReason(fallback: String): String =
    message?.takeIf { it.isNotBlank() } ?: fallback
