package com.sladkaya.app.service

import android.annotation.SuppressLint
import android.content.Context
import com.sladkaya.sensor.sibionics.Gs1OnboardingSnapshot
import com.sladkaya.sensor.sibionics.Gs1OnboardingSnapshotCodec
import com.sladkaya.sensor.sibionics.Gs1OnboardingSnapshotDecodeResult
import com.sladkaya.sensor.sibionics.Gs1OnboardingStateStore
import com.sladkaya.sensor.sibionics.Gs1OnboardingOpenResult
import com.sladkaya.sensor.sibionics.Gs1OnboardingState
import com.sladkaya.sensor.sibionics.Gs1OnboardingStateMachine
import com.sladkaya.sensor.sibionics.Gs1PendingDiagnosticProfile

/**
 * Production persistence for an unconfirmed GS1/GS1Sb onboarding draft.
 * Its namespace is intentionally separate from confirmed sensor configuration.
 */
internal class PendingDiagnosticGs1OnboardingStateStore(context: Context) :
    Gs1OnboardingStateStore {
    private val preferences = context.applicationContext.getSharedPreferences(
        PREFERENCES,
        Context.MODE_PRIVATE,
    )

    override fun load(): Gs1OnboardingSnapshot? {
        return synchronized(PROCESS_LOCK) {
            preferences.getString(KEY_SNAPSHOT, null)?.decodeSnapshot()
        }
    }

    @SuppressLint("UseKtx") // The KTX helper discards the synchronous commit result.
    override fun compareAndSet(
        expectedRevision: Long?,
        snapshot: Gs1OnboardingSnapshot,
    ): Boolean = synchronized(PROCESS_LOCK) {
        val currentRevision = preferences.getString(KEY_SNAPSHOT, null)
            ?.decodeSnapshot()
            ?.revision
        if (currentRevision != expectedRevision) return@synchronized false
        val expectedNextRevision = (expectedRevision ?: 0L) + 1L
        check(snapshot.revision == expectedNextRevision) {
            "Pending diagnostic onboarding revision must advance exactly once"
        }
        val encoded = Gs1OnboardingSnapshotCodec.encode(snapshot)
        check(preferences.edit().putString(KEY_SNAPSHOT, encoded).commit()) {
            "Pending diagnostic onboarding state was not persisted"
        }
        true
    }

    fun loadPendingDiagnosticProfile(): Gs1PendingDiagnosticProfile? =
        when (val opened = Gs1OnboardingStateMachine.open(this)) {
            is Gs1OnboardingOpenResult.Ready ->
                (opened.machine.state as? Gs1OnboardingState.PendingDiagnostic)?.profile
            is Gs1OnboardingOpenResult.Failure -> null
        }

    @SuppressLint("UseKtx")
    fun clearDraft(): Boolean = synchronized(PROCESS_LOCK) {
        preferences.edit().remove(KEY_SNAPSHOT).commit()
    }

    private fun String.decodeSnapshot(): Gs1OnboardingSnapshot =
        when (val decoded = Gs1OnboardingSnapshotCodec.decode(this)) {
            is Gs1OnboardingSnapshotDecodeResult.Success -> decoded.snapshot
            is Gs1OnboardingSnapshotDecodeResult.Failure -> {
                error("Invalid pending diagnostic onboarding state: ${decoded.error}")
            }
        }

    private companion object {
        const val PREFERENCES = "pending_diagnostic_gs1_onboarding"
        const val KEY_SNAPSHOT = "snapshot_current"
        val PROCESS_LOCK = Any()
    }
}
