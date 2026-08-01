package com.sladkaya.sensor.sibionics

import com.sladkaya.core.data.SensorAlgorithmCheckpointRecord
import com.sladkaya.core.data.SensorCheckpointProvenance
import com.sladkaya.core.data.SensorCoreStore
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
import com.sladkaya.sensor.sibionics.algorithm.V116ANativeAlgorithmApi
import com.sladkaya.sensor.sibionics.datahandle.SibionicsDataHandle
import java.util.concurrent.CancellationException

internal data class Gs1CoreConfiguration(
    val sensorId: String,
    val family: SensorFamily,
    val bluetoothAddress: String,
    val transportVariant: Int,
    val packageCode: String,
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
    INVALID_PACKAGE_CODE,
    SENSITIVITY_DECODE_FAILED,
    UNSUPPORTED_SENSITIVITY_ENCODING,
    STORAGE_UNAVAILABLE,
    CHECKPOINT_CALIBRATION_MISMATCH,
    CHECKPOINT_PROVENANCE_UNVERIFIED,
    CHECKPOINT_PHYSICAL_IDENTITY_MISMATCH,
    CHECKPOINT_METADATA_MISMATCH,
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
) {
    constructor(store: SensorCoreStore) : this(
        store = store,
        decodeSensitivityForProfile = { profile, token ->
            SensitivityDecoder.create(profile).decode(token)
        },
        nativeProvider = { V116ANativeAlgorithmApi() },
    )

    internal constructor(
        store: SensorCoreStore,
        decodeSensitivity: (SensitivityToken) -> SensitivityDecodeResult,
        nativeProvider: (AlgorithmProfile) -> NativeAlgorithmApi,
    ) : this(
        store = store,
        decodeSensitivityForProfile = { _, token -> decodeSensitivity(token) },
        nativeProvider = nativeProvider,
    )

    suspend fun open(configuration: Gs1CoreConfiguration): Gs1CoreOpenResult {
        if (configuration.family != SensorFamily.SIBIONICS_GS1 &&
            configuration.family != SensorFamily.SIBIONICS_GS1SB
        ) {
            return Gs1CoreOpenResult.Failure(Gs1CoreOpenError.UNSUPPORTED_FAMILY)
        }
        if (configuration.transportVariant !in VERIFIED_TRANSPORT_VARIANTS) {
            return Gs1CoreOpenResult.Failure(Gs1CoreOpenError.UNSUPPORTED_TRANSPORT_VARIANT)
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
        val decoded = try {
                decodeSensitivityForProfile(PINNED_PROFILE, token)
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
        if (sensitivity.encoding != SensitivityEncoding.NORMAL) {
            return Gs1CoreOpenResult.Failure(
                Gs1CoreOpenError.UNSUPPORTED_SENSITIVITY_ENCODING,
                sensitivity.encoding.name,
            )
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
        if (stored != null &&
            (stored.transportProtocol == SensorCheckpointProvenance.UNVERIFIED_LEGACY_V2 ||
                stored.dataHandleBinarySetId == SensorCheckpointProvenance.UNVERIFIED_LEGACY_V2 ||
                stored.bluetoothAddress == SensorCheckpointProvenance.UNVERIFIED_LEGACY_V3_IDENTITY)
        ) {
            return Gs1CoreOpenResult.Failure(Gs1CoreOpenError.CHECKPOINT_PROVENANCE_UNVERIFIED)
        }
        if (stored != null && stored.bluetoothAddress != configuration.bluetoothAddress) {
            return Gs1CoreOpenResult.Failure(
                Gs1CoreOpenError.CHECKPOINT_PHYSICAL_IDENTITY_MISMATCH,
            )
        }
        if (stored != null && !stored.calibrationMatches(configuration, sensitivity)) {
            return Gs1CoreOpenResult.Failure(Gs1CoreOpenError.CHECKPOINT_CALIBRATION_MISMATCH)
        }
        val checkpoint = stored?.toAlgorithmCheckpoint(configuration, sensitivity)
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

        val native = try {
            nativeProvider(PINNED_PROFILE)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: LinkageError) {
            return Gs1CoreOpenResult.Failure(Gs1CoreOpenError.NATIVE_LOAD_FAILED, failure.message)
        } catch (failure: Exception) {
            return Gs1CoreOpenResult.Failure(Gs1CoreOpenError.NATIVE_LOAD_FAILED, failure.message)
        }
        return when (
            val opened = SibionicsAlgorithmSession.open(
                profile = PINNED_PROFILE,
                sensitivityToken = sensitivity.token,
                initializationMode = AlgorithmInitializationMode.STANDARD,
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
                ),
                sensitivity = sensitivity,
                nextSensorIndex = nextSensorIndex,
            )
        }
    }

    private fun SensorAlgorithmCheckpointRecord.calibrationMatches(
        configuration: Gs1CoreConfiguration,
        sensitivity: DecodedSensitivity,
    ): Boolean =
        sensorId == configuration.sensorId &&
            bluetoothAddress == configuration.bluetoothAddress &&
            sensorFamily == configuration.family &&
            transportVariant == configuration.transportVariant &&
            transportProtocol == TRANSPORT_PROTOCOL &&
            dataHandleBinarySetId == SibionicsDataHandle.BINARY_SET_ID &&
            algorithmProfile == PINNED_PROFILE.name &&
            sensitivityToken == sensitivity.token.value &&
            sensitivityTokenSource == sensitivity.token.source.name &&
            sensitivityCoefficient == sensitivity.coefficient.toDouble() &&
            sensitivityEncoding == sensitivity.encoding.name &&
            initializationMode == AlgorithmInitializationMode.STANDARD.name

    private fun SensorAlgorithmCheckpointRecord.toAlgorithmCheckpoint(
        configuration: Gs1CoreConfiguration,
        sensitivity: DecodedSensitivity,
    ): AlgorithmCheckpoint? {
        if (sensorId != configuration.sensorId ||
            bluetoothAddress != configuration.bluetoothAddress ||
            sensorFamily != configuration.family ||
            transportVariant != configuration.transportVariant ||
            transportProtocol != TRANSPORT_PROTOCOL ||
            dataHandleBinarySetId != SibionicsDataHandle.BINARY_SET_ID ||
            algorithmProfile != PINNED_PROFILE.name ||
            sensorTimeEpochMs <= 0 ||
            sensorTimeEpochMs % MILLIS_PER_SECOND != 0L
        ) return null
        return AlgorithmCheckpoint(
            profile = PINNED_PROFILE,
            binarySetId = binarySetId,
            sensitivityToken = sensitivity.token,
            initializationMode = AlgorithmInitializationMode.STANDARD,
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
        val PINNED_PROFILE = AlgorithmProfile.V116A
        // The downloaded Global APK proves this exact V120 + V116A pair.
        // Other regional authentication variants remain blocked until their
        // algorithm/binary pairing is proven by an official fixture.
        val VERIFIED_TRANSPORT_VARIANTS = setOf(0)
        const val TRANSPORT_PROTOCOL = "GS1_V120"
    }
}

private val CANONICAL_BLUETOOTH_ADDRESS = Regex("^(?:[0-9A-F]{2}:){5}[0-9A-F]{2}$")
