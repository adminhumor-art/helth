package com.sladkaya.sensor.sibionics

import com.sladkaya.core.data.SensorCoreStore
import com.sladkaya.core.data.SensorProtocolBindingCommitResult
import com.sladkaya.core.data.SensorProtocolBindingRecord
import java.security.MessageDigest
import java.util.concurrent.CancellationException

internal sealed interface Gs1ProtocolResolution {
    data class Resolved(
        val wireProfile: Gs1WireProfile,
        val binding: SensorProtocolBindingRecord? = null,
    ) : Gs1ProtocolResolution

    data object Unresolved : Gs1ProtocolResolution

    data class Failure(
        val code: String,
        val detail: String? = null,
        val retryable: Boolean = false,
    ) : Gs1ProtocolResolution
}

/**
 * Resolves the internal GS1 wire tuple without exposing protocol choices in UX.
 * Chinese auto-detection is insert-only: once evidence is committed, an
 * opposite tuple can never replace it for the same physical sensor.
 */
internal class Gs1ProtocolBindingResolver(
    private val bindingBySensorId: suspend (String) -> SensorProtocolBindingRecord?,
    private val bindingByBluetoothAddress: suspend (String) -> SensorProtocolBindingRecord?,
    private val commit: suspend (SensorProtocolBindingRecord) -> SensorProtocolBindingCommitResult,
) {
    constructor(store: SensorCoreStore) : this(
        bindingBySensorId = store::protocolBinding,
        bindingByBluetoothAddress = store::protocolBindingByBluetoothAddress,
        commit = store::bindProtocol,
    )

    suspend fun inspect(profile: Gs1DiagnosticActivationProfile): Gs1ProtocolResolution {
        if (profile.transportVariant != GLOBAL_VARIANT &&
            profile.transportVariant != CHINESE_VARIANT
        ) {
            return failure("UNSUPPORTED_TRANSPORT_VARIANT")
        }
        val logical: SensorProtocolBindingRecord?
        val physical: SensorProtocolBindingRecord?
        try {
            logical = bindingBySensorId(profile.sensorId)
            physical = bindingByBluetoothAddress(profile.bluetoothAddress)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (storage: Exception) {
            return failure("PROTOCOL_BINDING_STORAGE_UNAVAILABLE", storage.message, retryable = true)
        }
        if (logical == null && physical == null) {
            return if (profile.transportVariant == GLOBAL_VARIANT) {
                Gs1ProtocolResolution.Resolved(Gs1WireProfile.V120)
            } else {
                Gs1ProtocolResolution.Unresolved
            }
        }
        if (logical == null || physical == null || logical != physical) {
            return failure(
                "PROTOCOL_BINDING_IDENTITY_CONFLICT",
                "Logical and physical protocol identities do not resolve to one record",
            )
        }
        return validate(profile, logical)
    }

    suspend fun bind(
        profile: Gs1DiagnosticActivationProfile,
        wireProfile: Gs1WireProfile,
        evidenceKind: String,
        evidence: ByteArray,
        sensitivityEncoding: String,
    ): Gs1ProtocolResolution {
        if (profile.transportVariant != CHINESE_VARIANT &&
            !(profile.transportVariant == GLOBAL_VARIANT && wireProfile == Gs1WireProfile.V120)
        ) {
            return failure("PROTOCOL_BINDING_PROFILE_INVALID")
        }
        if (evidenceKind.isBlank() || evidence.isEmpty()) {
            return failure("PROTOCOL_BINDING_EVIDENCE_INVALID")
        }
        val spec = try {
            Gs1WireProfiles.requireResolved(wireProfile, profile.transportVariant)
        } catch (invalid: IllegalArgumentException) {
            return failure("PROTOCOL_BINDING_PROFILE_INVALID", invalid.message)
        }
        val algorithmProfile = Gs1AlgorithmProfiles.resolveForTransportVariant(
            profile.transportVariant,
        ) ?: return failure("ALGORITHM_BUNDLE_UNSUPPORTED")
        val record = SensorProtocolBindingRecord(
            sensorId = profile.sensorId,
            bluetoothAddress = profile.bluetoothAddress,
            sensorFamily = profile.family,
            transportVariant = profile.transportVariant,
            sensitivityToken = profile.packageCode,
            wireProfile = wireProfile.name,
            transportProtocol = spec.transportProtocol,
            transportCodecId = spec.transportCodecId,
            algorithmProfile = algorithmProfile.name,
            sensitivityEncoding = sensitivityEncoding,
            evidenceKind = evidenceKind,
            evidenceSha256 = evidence.sha256(),
            schemaVersion = SensorProtocolBindingRecord.SCHEMA_VERSION,
        )
        val committed = try {
            commit(record)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (storage: Exception) {
            return failure("PROTOCOL_BINDING_STORAGE_UNAVAILABLE", storage.message, retryable = true)
        }
        return when (committed) {
            SensorProtocolBindingCommitResult.Bound,
            SensorProtocolBindingCommitResult.AlreadyBound,
            -> Gs1ProtocolResolution.Resolved(wireProfile, record)

            is SensorProtocolBindingCommitResult.Conflict -> failure(
                "PROTOCOL_BINDING_CONFLICT",
                committed.reason,
            )
        }
    }

    private fun validate(
        profile: Gs1DiagnosticActivationProfile,
        binding: SensorProtocolBindingRecord,
    ): Gs1ProtocolResolution {
        val wireProfile = Gs1WireProfile.entries.firstOrNull { it.name == binding.wireProfile }
            ?: return failure("PROTOCOL_BINDING_MISMATCH", "Unknown wire profile")
        val spec = runCatching {
            Gs1WireProfiles.requireResolved(wireProfile, profile.transportVariant)
        }.getOrNull()
            ?: return failure("PROTOCOL_BINDING_MISMATCH", "Unresolved wire profile was persisted")
        val algorithmProfile = Gs1AlgorithmProfiles.resolveForTransportVariant(
            profile.transportVariant,
        ) ?: return failure("ALGORITHM_BUNDLE_UNSUPPORTED")
        val exact = binding.sensorId == profile.sensorId &&
            binding.bluetoothAddress == profile.bluetoothAddress &&
            binding.sensorFamily == profile.family &&
            binding.transportVariant == profile.transportVariant &&
            binding.sensitivityToken == profile.packageCode &&
            binding.transportProtocol == spec.transportProtocol &&
            binding.transportCodecId == spec.transportCodecId &&
            binding.algorithmProfile == algorithmProfile.name &&
            binding.schemaVersion == SensorProtocolBindingRecord.SCHEMA_VERSION
        return if (exact) {
            Gs1ProtocolResolution.Resolved(wireProfile, binding)
        } else {
            failure("PROTOCOL_BINDING_MISMATCH", "Stored protocol tuple does not match activation")
        }
    }

    private fun failure(
        code: String,
        detail: String? = null,
        retryable: Boolean = false,
    ) = Gs1ProtocolResolution.Failure(code, detail, retryable)

    private fun ByteArray.sha256(): String = MessageDigest.getInstance("SHA-256")
        .digest(this)
        .joinToString("") { "%02x".format(it.toInt() and 0xff) }

    private companion object {
        const val GLOBAL_VARIANT = 0
        const val CHINESE_VARIANT = 2
    }
}
