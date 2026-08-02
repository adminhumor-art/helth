package com.sladkaya.sensor.sibionics

import com.sladkaya.core.data.ActiveProductPublicationConfiguration
import com.sladkaya.core.data.ProductPublicationConfigurationReader
import com.sladkaya.core.model.SensorFamily
import java.util.concurrent.CancellationException

internal enum class Gs1ProductPermitError {
    ACTIVE_CONFIGURATION_REQUIRED,
    PROFILE_APPROVAL_MISMATCH,
    CONFIGURATION_STORAGE_UNAVAILABLE,
}

internal sealed interface Gs1ProductPermitIssueResult {
    data class Granted(val permit: Gs1ProductPermit) : Gs1ProductPermitIssueResult
    data class Denied(
        val error: Gs1ProductPermitError,
        val detail: String? = null,
    ) : Gs1ProductPermitIssueResult
}

/**
 * Opaque snapshot issued only from the active durable approval and publication
 * binding. Ordinary onboarding data cannot construct this product capability.
 */
internal class Gs1ProductPermit private constructor(
    internal val active: ActiveProductPublicationConfiguration,
    private val profileIdentity: ProfileIdentity,
) {
    internal fun belongsTo(profile: Gs1DiagnosticActivationProfile): Boolean =
        profileIdentity == ProfileIdentity.from(profile)

    companion object {
        internal fun verified(
            active: ActiveProductPublicationConfiguration,
            profile: Gs1DiagnosticActivationProfile,
        ): Gs1ProductPermit? {
            val approval = active.approval
            if (approval.sensorFamily != SensorFamily.SIBIONICS_GS1 &&
                approval.sensorFamily != SensorFamily.SIBIONICS_GS1SB
            ) return null
            val identity = ProfileIdentity.from(profile)
            val exact = approval.sensorId == identity.sensorId &&
                approval.sensorFamily == identity.family &&
                approval.bluetoothAddress == identity.bluetoothAddress &&
                approval.transportVariant == identity.transportVariant &&
                approval.sensitivityToken == identity.packageCode
            return if (exact) Gs1ProductPermit(active, identity) else null
        }
    }

    private data class ProfileIdentity(
        val sensorId: String,
        val family: SensorFamily,
        val bluetoothAddress: String,
        val transportVariant: Int,
        val packageCode: String,
    ) {
        companion object {
            fun from(profile: Gs1DiagnosticActivationProfile) = ProfileIdentity(
                sensorId = profile.sensorId,
                family = profile.family,
                bluetoothAddress = profile.bluetoothAddress,
                transportVariant = profile.transportVariant,
                packageCode = profile.packageCode,
            )
        }
    }
}

internal class Gs1ProductPermitIssuer(
    private val reader: ProductPublicationConfigurationReader,
) {
    suspend fun issue(
        profile: Gs1DiagnosticActivationProfile,
    ): Gs1ProductPermitIssueResult {
        val active = try {
            reader.active()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            return Gs1ProductPermitIssueResult.Denied(
                error = Gs1ProductPermitError.CONFIGURATION_STORAGE_UNAVAILABLE,
                detail = failure.message,
            )
        } ?: return Gs1ProductPermitIssueResult.Denied(
            Gs1ProductPermitError.ACTIVE_CONFIGURATION_REQUIRED,
        )
        val permit = Gs1ProductPermit.verified(active, profile)
            ?: return Gs1ProductPermitIssueResult.Denied(
                Gs1ProductPermitError.PROFILE_APPROVAL_MISMATCH,
            )
        return Gs1ProductPermitIssueResult.Granted(permit)
    }
}
