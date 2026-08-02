package com.sladkaya.sensor.sibionics

import com.sladkaya.core.data.PhysicalSensorApprovalRecord
import com.sladkaya.core.data.SensorAlgorithmCheckpointRecord
import com.sladkaya.core.data.SensorCoreStore
import com.sladkaya.core.data.SensorProtocolBindingRecord
import com.sladkaya.core.model.SensorFamily
import com.sladkaya.sensor.sibionics.algorithm.AlgorithmCheckpoint
import com.sladkaya.sensor.sibionics.algorithm.AlgorithmInitializationMode
import com.sladkaya.sensor.sibionics.algorithm.AlgorithmOpenResult
import com.sladkaya.sensor.sibionics.algorithm.AlgorithmProfile
import com.sladkaya.sensor.sibionics.algorithm.DecodedSensitivity
import com.sladkaya.sensor.sibionics.algorithm.NativeAlgorithmApi
import com.sladkaya.sensor.sibionics.algorithm.SensitivityDecodeResult
import com.sladkaya.sensor.sibionics.algorithm.SensitivityDecoder
import com.sladkaya.sensor.sibionics.algorithm.SensitivityEncoding
import com.sladkaya.sensor.sibionics.algorithm.SensitivityToken
import com.sladkaya.sensor.sibionics.algorithm.SensitivityTokenPolicy
import com.sladkaya.sensor.sibionics.algorithm.SensitivityTokenValidation
import com.sladkaya.sensor.sibionics.algorithm.SibionicsAlgorithmSession
import com.sladkaya.sensor.sibionics.algorithm.V115GNativeAlgorithmApi
import com.sladkaya.sensor.sibionics.algorithm.V116ANativeAlgorithmApi
import java.util.concurrent.CancellationException

internal data class Gs1CoreConfiguration(
    val sensorId: String,
    val family: SensorFamily,
    val bluetoothAddress: String,
    val transportVariant: Int,
    val packageCode: String,
    val wireProfile: Gs1WireProfile,
) {
    init {
        require(sensorId.isNotBlank() && sensorId.length <= 128)
        require(CANONICAL_BLUETOOTH_ADDRESS.matches(bluetoothAddress))
        require(transportVariant >= 0)
    }
}

internal enum class Gs1CoreOpenError {
    UNSUPPORTED_FAMILY,
    UNSUPPORTED_TRANSPORT_VARIANT,
    UNSUPPORTED_WIRE_PROFILE,
    PROTOCOL_BINDING_REQUIRED,
    PROTOCOL_BINDING_MISMATCH,
    INVALID_PACKAGE_CODE,
    SENSITIVITY_DECODE_FAILED,
    UNSUPPORTED_SENSITIVITY_ENCODING,
    STORAGE_UNAVAILABLE,
    CHECKPOINT_CALIBRATION_MISMATCH,
    CHECKPOINT_PHYSICAL_IDENTITY_MISMATCH,
    CHECKPOINT_METADATA_MISMATCH,
    CHECKPOINT_APPROVAL_ANCHOR_MISMATCH,
    CHECKPOINT_APPROVAL_LINEAGE_MISMATCH,
    DIAGNOSTIC_OPEN_FOR_APPROVED_LINEAGE,
    PRODUCT_PERMIT_PROFILE_MISMATCH,
    PRODUCT_APPROVAL_CONFIGURATION_MISMATCH,
    PRODUCT_NATIVE_BINARY_SET_MISMATCH,
    SENSOR_SEQUENCE_EXHAUSTED,
    NATIVE_LOAD_FAILED,
    ALGORITHM_OPEN_FAILED,
}

internal sealed interface Gs1CoreOpenResult {
    data class Success(
        val coordinator: Gs1ProcessingCoordinator,
        val sensitivity: DecodedSensitivity,
        val nextSensorIndex: Int,
    ) : Gs1CoreOpenResult

    data class Failure(
        val error: Gs1CoreOpenError,
        val detail: String? = null,
    ) : Gs1CoreOpenResult
}

internal class Gs1CoreFactory private constructor(
    private val store: SensorCoreStore,
    private val decodeSensitivityForProfile: (AlgorithmProfile, SensitivityToken) -> SensitivityDecodeResult,
    private val nativeProvider: (AlgorithmProfile) -> NativeAlgorithmApi,
    private val nativeArtifactIdentityProvider: Gs1NativeArtifactIdentityProvider,
) {
    private val protocolBindingResolver = Gs1ProtocolBindingResolver(store)

    constructor(store: SensorCoreStore) : this(
        store = store,
        decodeSensitivityForProfile = { profile, token ->
            SensitivityDecoder.create(profile).decode(token)
        },
        nativeProvider = { profile ->
            when (profile) {
                AlgorithmProfile.V115G -> V115GNativeAlgorithmApi()
                AlgorithmProfile.V116A -> V116ANativeAlgorithmApi()
            }
        },
        nativeArtifactIdentityProvider = Gs1InstalledNativeArtifactIdentityProvider,
    )

    internal constructor(
        store: SensorCoreStore,
        decodeSensitivity: (SensitivityToken) -> SensitivityDecodeResult,
        nativeProvider: (AlgorithmProfile) -> NativeAlgorithmApi,
    ) : this(
        store = store,
        decodeSensitivityForProfile = { _, token -> decodeSensitivity(token) },
        nativeProvider = nativeProvider,
        nativeArtifactIdentityProvider = Gs1InstalledNativeArtifactIdentityProvider,
    )

    internal constructor(
        store: SensorCoreStore,
        decodeSensitivity: (SensitivityToken) -> SensitivityDecodeResult,
        nativeProvider: (AlgorithmProfile) -> NativeAlgorithmApi,
        nativeArtifactIdentityProvider: Gs1NativeArtifactIdentityProvider,
    ) : this(
        store = store,
        decodeSensitivityForProfile = { _, token -> decodeSensitivity(token) },
        nativeProvider = nativeProvider,
        nativeArtifactIdentityProvider = nativeArtifactIdentityProvider,
    )

    suspend fun inspectProtocol(
        profile: Gs1DiagnosticActivationProfile,
    ): Gs1ProtocolResolution = protocolBindingResolver.inspect(profile)

    /** Decodes calibration, then commits evidence before any checkpoint/native access. */
    suspend fun bindProtocolEvidence(
        profile: Gs1DiagnosticActivationProfile,
        wireProfile: Gs1WireProfile,
        evidenceKind: String,
        evidence: ByteArray,
    ): Gs1ProtocolResolution {
        val spec = try {
            Gs1WireProfiles.requireResolved(wireProfile)
        } catch (invalid: IllegalArgumentException) {
            return Gs1ProtocolResolution.Failure(
                code = "PROTOCOL_BINDING_PROFILE_INVALID",
                detail = invalid.message,
            )
        }
        val token = when (
            val validation = SensitivityTokenPolicy.validatePackageCode(profile.packageCode)
        ) {
            is SensitivityTokenValidation.Valid -> validation.token
            is SensitivityTokenValidation.Invalid -> return Gs1ProtocolResolution.Failure(
                code = "INVALID_PACKAGE_CODE",
                detail = validation.error.name,
            )
        }
        val decoded = try {
            decodeSensitivityForProfile(spec.algorithmProfile, token)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: LinkageError) {
            return Gs1ProtocolResolution.Failure(
                code = "SENSITIVITY_DECODE_FAILED",
                detail = failure.message,
            )
        } catch (failure: Exception) {
            return Gs1ProtocolResolution.Failure(
                code = "SENSITIVITY_DECODE_FAILED",
                detail = failure.message,
            )
        }
        val sensitivity = when (decoded) {
            is SensitivityDecodeResult.Success -> decoded.value
            is SensitivityDecodeResult.Failure -> return Gs1ProtocolResolution.Failure(
                code = "SENSITIVITY_DECODE_FAILED",
                detail = decoded.error.name,
            )
        }
        return protocolBindingResolver.bind(
            profile = profile,
            wireProfile = wireProfile,
            evidenceKind = evidenceKind,
            evidence = evidence,
            sensitivityEncoding = sensitivity.encoding.name,
        )
    }

    suspend fun openBound(
        profile: Gs1DiagnosticActivationProfile,
        resolution: Gs1ProtocolResolution.Resolved,
    ): Gs1CoreOpenResult {
        val binding = resolution.binding
            ?: return Gs1CoreOpenResult.Failure(Gs1CoreOpenError.PROTOCOL_BINDING_REQUIRED)
        return open(profile.coreConfiguration(resolution.wireProfile), binding)
    }

    suspend fun openApproved(
        profile: Gs1DiagnosticActivationProfile,
        permit: Gs1ProductPermit,
    ): Gs1CoreOpenResult {
        if (!permit.belongsTo(profile)) {
            return Gs1CoreOpenResult.Failure(Gs1CoreOpenError.PRODUCT_PERMIT_PROFILE_MISMATCH)
        }
        return when (val resolution = inspectProtocol(profile)) {
            is Gs1ProtocolResolution.Failure -> Gs1CoreOpenResult.Failure(
                error = if (resolution.code == "PROTOCOL_BINDING_MISMATCH") {
                    Gs1CoreOpenError.PROTOCOL_BINDING_MISMATCH
                } else {
                    Gs1CoreOpenError.PROTOCOL_BINDING_REQUIRED
                },
                detail = resolution.detail ?: resolution.code,
            )
            Gs1ProtocolResolution.Unresolved ->
                Gs1CoreOpenResult.Failure(Gs1CoreOpenError.PROTOCOL_BINDING_REQUIRED)
            is Gs1ProtocolResolution.Resolved -> {
                val binding = resolution.binding ?: return Gs1CoreOpenResult.Failure(
                    Gs1CoreOpenError.PROTOCOL_BINDING_REQUIRED,
                )
                openInternal(
                    configuration = profile.coreConfiguration(resolution.wireProfile),
                    protocolBinding = binding,
                    productPermit = permit,
                )
            }
        }
    }

    suspend fun open(
        configuration: Gs1CoreConfiguration,
        protocolBinding: SensorProtocolBindingRecord,
    ): Gs1CoreOpenResult = openInternal(configuration, protocolBinding, productPermit = null)

    private suspend fun openInternal(
        configuration: Gs1CoreConfiguration,
        protocolBinding: SensorProtocolBindingRecord,
        productPermit: Gs1ProductPermit?,
    ): Gs1CoreOpenResult {
        if (configuration.family != SensorFamily.SIBIONICS_GS1 &&
            configuration.family != SensorFamily.SIBIONICS_GS1SB
        ) {
            return Gs1CoreOpenResult.Failure(Gs1CoreOpenError.UNSUPPORTED_FAMILY)
        }
        if (configuration.transportVariant !in VERIFIED_TRANSPORT_VARIANTS ||
            configuration.transportVariant == GLOBAL_VARIANT &&
            configuration.wireProfile != Gs1WireProfile.V120
        ) {
            return Gs1CoreOpenResult.Failure(Gs1CoreOpenError.UNSUPPORTED_TRANSPORT_VARIANT)
        }
        val spec = try {
            Gs1WireProfiles.requireResolved(configuration.wireProfile)
        } catch (invalid: IllegalArgumentException) {
            return Gs1CoreOpenResult.Failure(
                Gs1CoreOpenError.UNSUPPORTED_WIRE_PROFILE,
                invalid.message,
            )
        }
        val token = when (val validation = SensitivityTokenPolicy.validatePackageCode(configuration.packageCode)) {
            is SensitivityTokenValidation.Valid -> validation.token
            is SensitivityTokenValidation.Invalid -> {
                return Gs1CoreOpenResult.Failure(
                    Gs1CoreOpenError.INVALID_PACKAGE_CODE,
                    validation.error.name,
                )
            }
        }
        if (!protocolBinding.matches(configuration, spec, token)) {
            return Gs1CoreOpenResult.Failure(Gs1CoreOpenError.PROTOCOL_BINDING_MISMATCH)
        }
        if (productPermit != null &&
            !productPermit.active.approval.matchesStaticProductConfiguration(
                configuration = configuration,
                protocolBinding = protocolBinding,
                spec = spec,
            )
        ) {
            return Gs1CoreOpenResult.Failure(
                Gs1CoreOpenError.PRODUCT_APPROVAL_CONFIGURATION_MISMATCH,
            )
        }
        val decoded = try {
                decodeSensitivityForProfile(spec.algorithmProfile, token)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: LinkageError) {
            return Gs1CoreOpenResult.Failure(
                Gs1CoreOpenError.SENSITIVITY_DECODE_FAILED,
                failure.message,
            )
        } catch (failure: Exception) {
            return Gs1CoreOpenResult.Failure(
                Gs1CoreOpenError.SENSITIVITY_DECODE_FAILED,
                failure.message,
            )
        }
        val sensitivity = when (decoded) {
            is SensitivityDecodeResult.Success -> decoded.value
            is SensitivityDecodeResult.Failure -> {
                return Gs1CoreOpenResult.Failure(
                    Gs1CoreOpenError.SENSITIVITY_DECODE_FAILED,
                    decoded.error.name,
                )
            }
        }
        if (protocolBinding.sensitivityEncoding != sensitivity.encoding.name) {
            return Gs1CoreOpenResult.Failure(Gs1CoreOpenError.PROTOCOL_BINDING_MISMATCH)
        }
        val initializationMode = sensitivity.initializationMode()

        val native: NativeAlgorithmApi
        val nativeAlgorithmVersion: String
        val nativeArtifactIdentity: Gs1NativeArtifactIdentity?
        try {
            native = nativeProvider(spec.algorithmProfile)
            nativeAlgorithmVersion = native.algorithmVersion
            require(nativeAlgorithmVersion.isNotBlank() &&
                !nativeAlgorithmVersion.trim().equals("unknown", ignoreCase = true)
            ) { "Native algorithm version is absent or unknown" }
            nativeArtifactIdentity = productPermit?.let {
                nativeArtifactIdentityProvider.resolve(spec.algorithmProfile)
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: LinkageError) {
            return Gs1CoreOpenResult.Failure(Gs1CoreOpenError.NATIVE_LOAD_FAILED, failure.message)
        } catch (failure: Exception) {
            return Gs1CoreOpenResult.Failure(Gs1CoreOpenError.NATIVE_LOAD_FAILED, failure.message)
        }

        if (productPermit != null) {
            val approval = productPermit.active.approval
            val identity = checkNotNull(nativeArtifactIdentity)
            if (identity.algorithmBinarySetSha256 != approval.nativeBinarySetSha256 ||
                identity.datahandleBinarySetSha256 != approval.nativeDatahandleBinarySetSha256
            ) {
                return Gs1CoreOpenResult.Failure(
                    Gs1CoreOpenError.PRODUCT_NATIVE_BINARY_SET_MISMATCH,
                )
            }
            if (!approval.matchesDynamicProductConfiguration(
                    sensitivity = sensitivity,
                    initializationMode = initializationMode,
                    nativeBinarySetId = native.binarySetId,
                    nativeAlgorithmVersion = nativeAlgorithmVersion,
                )
            ) {
                return Gs1CoreOpenResult.Failure(
                    Gs1CoreOpenError.PRODUCT_APPROVAL_CONFIGURATION_MISMATCH,
                )
            }
        }

        val (stored, physicalCheckpoint) = try {
            store.checkpoint(configuration.sensorId) to
                store.checkpointByBluetoothAddress(configuration.bluetoothAddress)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            return Gs1CoreOpenResult.Failure(Gs1CoreOpenError.STORAGE_UNAVAILABLE, failure.message)
        }
        if (physicalCheckpoint != null && physicalCheckpoint.sensorId != configuration.sensorId) {
            return Gs1CoreOpenResult.Failure(
                Gs1CoreOpenError.CHECKPOINT_PHYSICAL_IDENTITY_MISMATCH,
                "Bluetooth address is already bound to another sensor identity",
            )
        }
        if (stored != null && stored.bluetoothAddress != configuration.bluetoothAddress) {
            return Gs1CoreOpenResult.Failure(
                Gs1CoreOpenError.CHECKPOINT_PHYSICAL_IDENTITY_MISMATCH,
            )
        }
        if (productPermit == null && stored?.publicationApprovalId != null) {
            return Gs1CoreOpenResult.Failure(
                Gs1CoreOpenError.DIAGNOSTIC_OPEN_FOR_APPROVED_LINEAGE,
            )
        }
        if (stored != null && !stored.calibrationMatches(
                configuration,
                sensitivity,
                spec,
                initializationMode,
                native.binarySetId,
                nativeAlgorithmVersion,
            )
        ) {
            return Gs1CoreOpenResult.Failure(Gs1CoreOpenError.CHECKPOINT_CALIBRATION_MISMATCH)
        }
        if (productPermit != null) {
            val approval = productPermit.active.approval
            when (stored?.publicationApprovalId) {
                null -> if (stored == null || !stored.matchesApprovalAnchor(approval)) {
                    return Gs1CoreOpenResult.Failure(
                        Gs1CoreOpenError.CHECKPOINT_APPROVAL_ANCHOR_MISMATCH,
                    )
                }
                approval.approvalId -> if (!stored.matchesApprovedLineage(approval)) {
                    return Gs1CoreOpenResult.Failure(
                        Gs1CoreOpenError.CHECKPOINT_APPROVAL_LINEAGE_MISMATCH,
                    )
                }
                else -> return Gs1CoreOpenResult.Failure(
                    Gs1CoreOpenError.CHECKPOINT_APPROVAL_LINEAGE_MISMATCH,
                )
            }
        }
        val checkpoint = stored?.toAlgorithmCheckpoint(
            configuration,
            sensitivity,
            spec,
            initializationMode,
        )
            ?: if (stored == null) null else {
                return Gs1CoreOpenResult.Failure(Gs1CoreOpenError.CHECKPOINT_METADATA_MISMATCH)
            }
        if (checkpoint?.lastProcessedIndex == MAX_SENSOR_INDEX) {
            return Gs1CoreOpenResult.Failure(Gs1CoreOpenError.SENSOR_SEQUENCE_EXHAUSTED)
        }
        val nextSensorIndex = checkpoint?.lastProcessedIndex?.plus(1) ?: FIRST_SENSOR_INDEX
        if (nextSensorIndex !in FIRST_SENSOR_INDEX..MAX_SENSOR_INDEX) {
            return Gs1CoreOpenResult.Failure(Gs1CoreOpenError.CHECKPOINT_METADATA_MISMATCH)
        }

        return when (
            val opened = SibionicsAlgorithmSession.open(
                profile = spec.algorithmProfile,
                sensitivityToken = sensitivity.token,
                initializationMode = initializationMode,
                checkpoint = checkpoint,
                native = native,
            )
        ) {
            is AlgorithmOpenResult.Failure -> Gs1CoreOpenResult.Failure(
                Gs1CoreOpenError.ALGORITHM_OPEN_FAILED,
                "${opened.error.code.name}: ${opened.error.message}",
            )
            is AlgorithmOpenResult.Success -> Gs1CoreOpenResult.Success(
                coordinator = Gs1ProcessingCoordinator(
                    sensorId = configuration.sensorId,
                    bluetoothAddress = configuration.bluetoothAddress,
                    family = configuration.family,
                    transportVariant = configuration.transportVariant,
                    algorithm = opened.session,
                    sensitivity = sensitivity,
                    store = store,
                    transportProtocol = spec.transportProtocol,
                    transportCodecId = spec.transportCodecId,
                    algorithmProfile = spec.algorithmProfile,
                    initialSensorStartTimeEpochMs = stored?.sensorStartTimeEpochMs,
                    productContext = if (productPermit == null) {
                        null
                    } else {
                        val identity = checkNotNull(nativeArtifactIdentity)
                        productPermit.active.verifiedRuntimeContext(
                            nativeBinarySetSha256 = identity.algorithmBinarySetSha256,
                            nativeDatahandleBinarySetSha256 = identity.datahandleBinarySetSha256,
                        )
                    },
                ),
                sensitivity = sensitivity,
                nextSensorIndex = nextSensorIndex,
            )
        }
    }

    private fun SensorAlgorithmCheckpointRecord.calibrationMatches(
        configuration: Gs1CoreConfiguration,
        sensitivity: DecodedSensitivity,
        spec: Gs1WireProfileSpec,
        initializationMode: AlgorithmInitializationMode,
        nativeBinarySetId: String,
        nativeAlgorithmVersion: String,
    ): Boolean =
        sensorId == configuration.sensorId &&
            bluetoothAddress == configuration.bluetoothAddress &&
            sensorFamily == configuration.family &&
            transportVariant == configuration.transportVariant &&
            transportProtocol == spec.transportProtocol &&
            transportCodecId == spec.transportCodecId &&
            algorithmProfile == spec.algorithmProfile.name &&
            sensitivityToken == sensitivity.token.value &&
            sensitivityTokenSource == sensitivity.token.source.name &&
            sensitivityCoefficient == sensitivity.coefficient.toDouble() &&
            sensitivityEncoding == sensitivity.encoding.name &&
            this.initializationMode == initializationMode.name &&
            binarySetId == nativeBinarySetId &&
            algorithmVersion == nativeAlgorithmVersion

    private fun SensorAlgorithmCheckpointRecord.toAlgorithmCheckpoint(
        configuration: Gs1CoreConfiguration,
        sensitivity: DecodedSensitivity,
        spec: Gs1WireProfileSpec,
        initializationMode: AlgorithmInitializationMode,
    ): AlgorithmCheckpoint? {
        if (sensorId != configuration.sensorId ||
            bluetoothAddress != configuration.bluetoothAddress ||
            sensorFamily != configuration.family ||
            transportVariant != configuration.transportVariant ||
            transportProtocol != spec.transportProtocol ||
            transportCodecId != spec.transportCodecId ||
            algorithmProfile != spec.algorithmProfile.name ||
            sensorTimeEpochMs <= 0 ||
            sensorTimeEpochMs % MILLIS_PER_SECOND != 0L
        ) return null
        return AlgorithmCheckpoint(
            profile = spec.algorithmProfile,
            binarySetId = binarySetId,
            sensitivityToken = sensitivity.token,
            initializationMode = initializationMode,
            lastProcessedIndex = sequence,
            lastSensorTimeEpochSeconds = sensorTimeEpochMs / MILLIS_PER_SECOND,
            nativeState = stateCopy(),
            nativeStateSha256 = stateSha256,
            displayOffsetMmolL = displayOffsetMmolL,
            schemaVersion = schemaVersion,
            algorithmVersion = algorithmVersion,
        )
    }

    private companion object {
        const val MILLIS_PER_SECOND = 1_000L
        const val FIRST_SENSOR_INDEX = 1
        const val MAX_SENSOR_INDEX = 0xffff
        const val GLOBAL_VARIANT = 0
        const val CHINESE_VARIANT = 2
        val VERIFIED_TRANSPORT_VARIANTS = setOf(GLOBAL_VARIANT, CHINESE_VARIANT)
    }
}

private fun SensorProtocolBindingRecord.matches(
    configuration: Gs1CoreConfiguration,
    spec: Gs1WireProfileSpec,
    sensitivityToken: SensitivityToken,
): Boolean = sensorId == configuration.sensorId &&
    bluetoothAddress == configuration.bluetoothAddress &&
    sensorFamily == configuration.family &&
    transportVariant == configuration.transportVariant &&
    this.sensitivityToken == sensitivityToken.value &&
    wireProfile == spec.wireProfile.name &&
    transportProtocol == spec.transportProtocol &&
    transportCodecId == spec.transportCodecId &&
    algorithmProfile == spec.algorithmProfile.name &&
    schemaVersion == SensorProtocolBindingRecord.SCHEMA_VERSION

private fun PhysicalSensorApprovalRecord.matchesStaticProductConfiguration(
    configuration: Gs1CoreConfiguration,
    protocolBinding: SensorProtocolBindingRecord,
    spec: Gs1WireProfileSpec,
): Boolean = sensorId == configuration.sensorId &&
    bluetoothAddress == configuration.bluetoothAddress &&
    sensorFamily == configuration.family &&
    transportVariant == configuration.transportVariant &&
    sensitivityToken == configuration.packageCode &&
    wireProfile == spec.wireProfile.name &&
    transportProtocol == spec.transportProtocol &&
    transportCodecId == spec.transportCodecId &&
    algorithmProfile == spec.algorithmProfile.name &&
    protocolEvidenceKind == protocolBinding.evidenceKind &&
    protocolEvidenceSha256 == protocolBinding.evidenceSha256 &&
    sensitivityEncoding == protocolBinding.sensitivityEncoding &&
    checkpointSchemaVersion == SibionicsAlgorithmSession.CHECKPOINT_SCHEMA_VERSION

private fun PhysicalSensorApprovalRecord.matchesDynamicProductConfiguration(
    sensitivity: DecodedSensitivity,
    initializationMode: AlgorithmInitializationMode,
    nativeBinarySetId: String,
    nativeAlgorithmVersion: String,
): Boolean = sensitivityToken == sensitivity.token.value &&
    sensitivityTokenSource == sensitivity.token.source.name &&
    sensitivityCoefficient == sensitivity.coefficient.toDouble() &&
    sensitivityEncoding == sensitivity.encoding.name &&
    this.initializationMode == initializationMode.name &&
    binarySetId == nativeBinarySetId &&
    algorithmVersion == nativeAlgorithmVersion

private fun SensorAlgorithmCheckpointRecord.matchesApprovalAnchor(
    approval: PhysicalSensorApprovalRecord,
): Boolean = publicationApprovalId == null &&
    sequence == approval.approvedSequence &&
    sensorTimeEpochMs == approval.approvedSensorTimeEpochMs &&
    sensorStartTimeEpochMs == approval.sensorStartTimeEpochMs &&
    stateSha256 == approval.approvedCheckpointStateSha256 &&
    displayOffsetMmolL == approval.displayOffsetMmolL

private fun SensorAlgorithmCheckpointRecord.matchesApprovedLineage(
    approval: PhysicalSensorApprovalRecord,
): Boolean = publicationApprovalId == approval.approvalId &&
    sensorStartTimeEpochMs == approval.sensorStartTimeEpochMs &&
    sequence > approval.approvedSequence &&
    sensorTimeEpochMs >= approval.approvedSensorTimeEpochMs

private fun DecodedSensitivity.initializationMode(): AlgorithmInitializationMode =
    when (encoding) {
        SensitivityEncoding.NORMAL -> AlgorithmInitializationMode.STANDARD
        SensitivityEncoding.FACTION -> AlgorithmInitializationMode.FACTION
    }

private val CANONICAL_BLUETOOTH_ADDRESS = Regex("^(?:[0-9A-F]{2}:){5}[0-9A-F]{2}$")
