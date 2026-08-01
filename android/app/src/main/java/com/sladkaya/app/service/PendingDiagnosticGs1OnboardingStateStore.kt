package com.sladkaya.app.service

import android.annotation.SuppressLint
import android.content.Context
import com.sladkaya.sensor.sibionics.Gs1OnboardingSnapshot
import com.sladkaya.sensor.sibionics.Gs1OnboardingSnapshotCodec
import com.sladkaya.sensor.sibionics.Gs1OnboardingSnapshotDecodeResult
import com.sladkaya.sensor.sibionics.Gs1OnboardingStateStore

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

    private fun String.decodeSnapshot(): Gs1OnboardingSnapshot =
        when (val decoded = Gs1OnboardingSnapshotCodec.decode(this)) {
            is Gs1OnboardingSnapshotDecodeResult.Success -> decoded.snapshot
            is Gs1OnboardingSnapshotDecodeResult.Failure -> {
                error("Invalid pending diagnostic onboarding state: ${decoded.error}")
            }
        }

    private companion object {
        const val PREFERENCES = "pending_diagnostic_gs1_onboarding"
        const val KEY_SNAPSHOT = "snapshot_v1"
        val PROCESS_LOCK = Any()
    }
}
