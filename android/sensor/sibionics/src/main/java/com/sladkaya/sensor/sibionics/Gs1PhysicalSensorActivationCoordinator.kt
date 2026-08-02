package com.sladkaya.sensor.sibionics

import android.content.Context
import com.sladkaya.core.data.PhysicalSensorActivationCommand
import com.sladkaya.core.data.PhysicalSensorActivationCommitResult
import com.sladkaya.core.data.PhysicalSensorActivationIdentity
import com.sladkaya.core.data.PhysicalSensorActivationRepository
import com.sladkaya.core.data.PhysicalSensorActivationStore
import com.sladkaya.core.data.PhysicalSensorDiagnosticAnchor
import com.sladkaya.sensor.sibionics.algorithm.AlgorithmProfile
import java.util.concurrent.CancellationException

sealed interface Gs1PhysicalSensorActivationResult {
    data class Activated(
        val approvalId: String,
        val publicationBindingId: String,
    ) : Gs1PhysicalSensorActivationResult

    data class AlreadyActive(
        val approvalId: String,
        val publicationBindingId: String,
    ) : Gs1PhysicalSensorActivationResult

    data object EvidenceMissing : Gs1PhysicalSensorActivationResult
    data object EvidenceMismatch : Gs1PhysicalSensorActivationResult
    data object ReadingNotEligible : Gs1PhysicalSensorActivationResult
    data class NativeRuntimeUnavailable(val detail: String? = null) :
        Gs1PhysicalSensorActivationResult
    data class StorageUnavailable(val detail: String? = null) :
        Gs1PhysicalSensorActivationResult
    data class Conflict(val detail: String) : Gs1PhysicalSensorActivationResult
}

/** Promotes one exact stored diagnostic point only after an explicit UI action. */
class Gs1PhysicalSensorActivationCoordinator internal constructor(
    private val store: PhysicalSensorActivationStore,
    private val nativeIdentityProvider: Gs1NativeArtifactIdentityProvider,
) {
    constructor(context: Context) : this(
        store = PhysicalSensorActivationRepository.create(context.applicationContext),
        nativeIdentityProvider = Gs1InstalledNativeArtifactIdentityProvider,
    )

    suspend fun activate(
        profile: Gs1DiagnosticActivationProfile,
        diagnosticEventId: String,
    ): Gs1PhysicalSensorActivationResult {
        val anchor = try {
            store.diagnosticAnchor(diagnosticEventId)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            return Gs1PhysicalSensorActivationResult.StorageUnavailable(failure.message)
        } ?: return Gs1PhysicalSensorActivationResult.EvidenceMissing
        if (!anchor.matches(profile)) {
            return Gs1PhysicalSensorActivationResult.EvidenceMismatch
        }
        if (!anchor.isEligibleForLocalPrototypeActivation()) {
            return Gs1PhysicalSensorActivationResult.ReadingNotEligible
        }
        val algorithmProfile = try {
            AlgorithmProfile.valueOf(anchor.checkpoint.algorithmProfile)
        } catch (_: IllegalArgumentException) {
            return Gs1PhysicalSensorActivationResult.EvidenceMismatch
        }
        val nativeIdentity = try {
            nativeIdentityProvider.resolve(
                algorithmProfile,
                anchor.protocol.transportVariant,
            )
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: LinkageError) {
            return Gs1PhysicalSensorActivationResult.NativeRuntimeUnavailable(failure.message)
        } catch (failure: Exception) {
            return Gs1PhysicalSensorActivationResult.NativeRuntimeUnavailable(failure.message)
        }
        val approval = PhysicalSensorActivationIdentity.localPrototypeApproval(
            anchor = anchor,
            nativeBinarySetSha256 = nativeIdentity.algorithmBinarySetSha256,
            nativeDatahandleBinarySetSha256 = nativeIdentity.datahandleBinarySetSha256,
        )
        val publicationBindingId =
            PhysicalSensorActivationIdentity.localPublicationBindingId(approval.approvalId)
        return when (
            val committed = store.approveAndActivate(
                PhysicalSensorActivationCommand(
                    diagnosticEventId = diagnosticEventId,
                    approval = approval,
                    publicationBindingId = publicationBindingId,
                ),
            )
        ) {
            PhysicalSensorActivationCommitResult.Activated ->
                Gs1PhysicalSensorActivationResult.Activated(
                    approval.approvalId,
                    publicationBindingId,
                )
            PhysicalSensorActivationCommitResult.AlreadyActive ->
                Gs1PhysicalSensorActivationResult.AlreadyActive(
                    approval.approvalId,
                    publicationBindingId,
                )
            is PhysicalSensorActivationCommitResult.Conflict ->
                Gs1PhysicalSensorActivationResult.Conflict(committed.reason)
            is PhysicalSensorActivationCommitResult.StorageUnavailable ->
                Gs1PhysicalSensorActivationResult.StorageUnavailable(committed.detail)
        }
    }
}

private fun PhysicalSensorDiagnosticAnchor.matches(
    profile: Gs1DiagnosticActivationProfile,
): Boolean = protocol.sensorId == profile.sensorId &&
    protocol.bluetoothAddress == profile.bluetoothAddress &&
    protocol.sensorFamily == profile.family &&
    protocol.transportVariant == profile.transportVariant &&
    protocol.sensitivityToken == profile.packageCode &&
    raw.sensorId == profile.sensorId &&
    raw.sensorFamily == profile.family &&
    raw.transportVariant == profile.transportVariant &&
    checkpoint.bluetoothAddress == profile.bluetoothAddress
