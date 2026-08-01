package com.sladkaya.sensor.sibionics.algorithm

import java.util.concurrent.CancellationException

class SibionicsAlgorithmSession private constructor(
    private val profile: AlgorithmProfile,
    private val sensitivityToken: SensitivityToken,
    private val initializationMode: AlgorithmInitializationMode,
    private val native: NativeAlgorithmApi,
    private val context: NativeAlgorithmContext,
    private val binarySetId: String,
    private val algorithmVersion: String,
    checkpoint: AlgorithmCheckpoint?,
) : AutoCloseable {
    private var lastProcessedIndex: Int? = checkpoint?.lastProcessedIndex
    private var lastProcessedSensorTimeEpochSeconds: Long? = checkpoint?.lastSensorTimeEpochSeconds
    private var displayOffsetMmolL: Double = checkpoint?.displayOffsetMmolL ?: 0.0
    private var pendingCheckpointFingerprint: String? = null
    private var closed = false

    @Synchronized
    fun process(input: AlgorithmInput): AlgorithmStepResult {
        if (closed) return failure(AlgorithmErrorCode.CLOSED, "Algorithm session is closed")
        if (pendingCheckpointFingerprint != null) {
            return failure(
                AlgorithmErrorCode.CHECKPOINT_COMMIT_REQUIRED,
                "The previous algorithm checkpoint must be persisted before processing another input",
            )
        }
        if (!input.isValid()) return failure(AlgorithmErrorCode.INVALID_INPUT, "Algorithm input is invalid")

        val previousIndex = lastProcessedIndex
        if (previousIndex == null && input.index != FIRST_SENSOR_INDEX) {
            return failure(
                AlgorithmErrorCode.INITIAL_HISTORY_REQUIRED,
                "A fresh algorithm context must start at sensor index $FIRST_SENSOR_INDEX",
            )
        }
        if (previousIndex != null && input.index != previousIndex + 1) {
            return failure(
                AlgorithmErrorCode.NON_SEQUENTIAL_INDEX,
                "Expected index ${previousIndex + 1}, received ${input.index}",
            )
        }

        val previousSensorTime = lastProcessedSensorTimeEpochSeconds
        if (previousSensorTime != null && input.sensorTimeEpochSeconds != previousSensorTime + SECONDS_PER_SAMPLE) {
            return failure(
                AlgorithmErrorCode.NON_SEQUENTIAL_SENSOR_TIME,
                "Expected sensor time ${previousSensorTime + SECONDS_PER_SAMPLE}, received ${input.sensorTimeEpochSeconds}",
            )
        }

        val snapshot = try {
            native.process(context, input)
        } catch (cancelled: CancellationException) {
            invalidateSession()
            throw cancelled
        } catch (failure: LinkageError) {
            return terminalFailure(
                AlgorithmErrorCode.NATIVE_PROCESS_FAILED,
                failure.message ?: "Native process linkage failed",
            )
        } catch (failure: Exception) {
            return terminalFailure(
                AlgorithmErrorCode.NATIVE_PROCESS_FAILED,
                failure.message ?: "Native process failed",
            )
        }

        val state = try {
            native.exportState(context)
        } catch (cancelled: CancellationException) {
            invalidateSession()
            throw cancelled
        } catch (failure: LinkageError) {
            return terminalFailure(
                AlgorithmErrorCode.NATIVE_STATE_FAILED,
                failure.message ?: "Native state export linkage failed",
            )
        } catch (failure: Exception) {
            return terminalFailure(
                AlgorithmErrorCode.NATIVE_STATE_FAILED,
                failure.message ?: "Native state export failed",
            )
        }

        if (state.size != profile.stateSize) {
            return terminalFailure(
                AlgorithmErrorCode.NATIVE_STATE_FAILED,
                "Expected ${profile.stateSize} state bytes, received ${state.size}",
            )
        }
        if (!snapshot.glucoseMmolL.isFinite()) {
            return terminalFailure(
                AlgorithmErrorCode.NON_FINITE_NATIVE_OUTPUT,
                "Native algorithm returned a non-finite glucose value",
            )
        }

        // The verified reference path updates its raw-to-glucose offset for any
        // native value above the lower bound, then rejects the displayed value
        // separately if it is 30 mmol/L or higher. Folding both checks together
        // could turn an invalid native anchor into a plausible raw fallback.
        val isAnchor = snapshot.glucoseMmolL > 1.8 &&
            (!profile.fiveMinuteAnchors || input.index % 5 == 0)
        val displayedValue = if (isAnchor) {
            displayOffsetMmolL = snapshot.glucoseMmolL - input.signal
            snapshot.glucoseMmolL
        } else {
            input.signal + displayOffsetMmolL
        }

        val newCheckpoint = AlgorithmCheckpoint(
            profile = profile,
            binarySetId = binarySetId,
            sensitivityToken = sensitivityToken,
            initializationMode = initializationMode,
            lastProcessedIndex = input.index,
            lastSensorTimeEpochSeconds = input.sensorTimeEpochSeconds,
            nativeState = state.copyOf(),
            nativeStateSha256 = sha256(state),
            displayOffsetMmolL = displayOffsetMmolL,
            schemaVersion = CHECKPOINT_SCHEMA_VERSION,
            algorithmVersion = algorithmVersion,
        )

        val diagnosticOutput = AlgorithmOutput(
            index = input.index,
            sensorTimeEpochSeconds = input.sensorTimeEpochSeconds,
            glucoseMmolL = displayedValue,
            nativeGlucoseMmolL = snapshot.glucoseMmolL,
            trend = snapshot.trend,
            warnings = AlgorithmWarnings(
                glucose = snapshot.glucoseWarning,
                current = snapshot.currentWarning,
                temperature = snapshot.temperatureWarning,
            ),
            algorithmProfile = profile,
            algorithmVersion = algorithmVersion,
            tokenSource = sensitivityToken.source,
            initializationMode = initializationMode,
        )

        val fingerprint = try {
            checkpointFingerprint(newCheckpoint)
        } catch (failure: Exception) {
            return terminalFailure(
                AlgorithmErrorCode.NATIVE_STATE_FAILED,
                failure.message ?: "Checkpoint construction failed after native processing",
            )
        }
        lastProcessedIndex = input.index
        lastProcessedSensorTimeEpochSeconds = input.sensorTimeEpochSeconds
        pendingCheckpointFingerprint = fingerprint

        if (!displayedValue.isFinite() || displayedValue <= 1.8 || displayedValue >= 30.0) {
            return failure(
                AlgorithmErrorCode.INVALID_GLUCOSE,
                "Algorithm output is outside the accepted range",
                newCheckpoint,
                diagnosticOutput,
            )
        }

        return AlgorithmStepResult.Success(
            output = diagnosticOutput,
            checkpoint = newCheckpoint,
        )
    }

    @Synchronized
    fun confirmPersisted(checkpoint: AlgorithmCheckpoint): AlgorithmCommitResult {
        if (closed) {
            return AlgorithmCommitResult.Failure(
                AlgorithmError(AlgorithmErrorCode.CLOSED, "Algorithm session is closed"),
            )
        }
        val expected = pendingCheckpointFingerprint
        if (expected == null || checkpointFingerprint(checkpoint) != expected) {
            invalidateSession()
            return AlgorithmCommitResult.Failure(
                AlgorithmError(
                    AlgorithmErrorCode.CHECKPOINT_COMMIT_MISMATCH,
                    "Persisted checkpoint does not match the current native algorithm state",
                ),
            )
        }
        pendingCheckpointFingerprint = null
        return AlgorithmCommitResult.Success
    }

    @Synchronized
    override fun close() {
        if (closed) return
        closed = true
        runCatching { native.release(context) }
    }

    private fun invalidateSession() {
        close()
    }

    private fun terminalFailure(
        code: AlgorithmErrorCode,
        message: String,
    ): AlgorithmStepResult.Failure {
        invalidateSession()
        return failure(code, message)
    }

    private fun failure(
        code: AlgorithmErrorCode,
        message: String,
        checkpoint: AlgorithmCheckpoint? = null,
        diagnosticOutput: AlgorithmOutput? = null,
    ) = AlgorithmStepResult.Failure(
        error = AlgorithmError(code, message),
        checkpoint = checkpoint,
        diagnosticOutput = diagnosticOutput,
    )

    private fun checkpointFingerprint(checkpoint: AlgorithmCheckpoint): String {
        val canonical = listOf(
            checkpoint.schemaVersion.toString(),
            checkpoint.profile.name,
            checkpoint.binarySetId,
            checkpoint.sensitivityToken.value,
            checkpoint.sensitivityToken.source.name,
            checkpoint.initializationMode.name,
            checkpoint.lastProcessedIndex.toString(),
            checkpoint.lastSensorTimeEpochSeconds.toString(),
            checkpoint.nativeState.size.toString(),
            sha256(checkpoint.nativeState),
            checkpoint.nativeStateSha256.lowercase(),
            checkpoint.displayOffsetMmolL.toBits().toString(),
            checkpoint.algorithmVersion,
        ).joinToString("\u0000")
        return sha256(canonical.encodeToByteArray())
    }

    companion object {
        const val CHECKPOINT_SCHEMA_VERSION = 3
        private const val FIRST_SENSOR_INDEX = 1
        private const val SECONDS_PER_SAMPLE = 60L

        fun open(
            profile: AlgorithmProfile,
            sensitivityToken: SensitivityToken,
            initializationMode: AlgorithmInitializationMode,
            checkpoint: AlgorithmCheckpoint?,
            native: NativeAlgorithmApi,
        ): AlgorithmOpenResult {
            val metadata = try {
                NativeAlgorithmMetadata(
                    profile = native.profile,
                    binarySetId = native.binarySetId,
                    algorithmVersion = native.algorithmVersion,
                    supportedInitializationModes = native.supportedInitializationModes.toSet(),
                )
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: LinkageError) {
                return AlgorithmOpenResult.Failure(
                    AlgorithmError(
                        AlgorithmErrorCode.NATIVE_METADATA_FAILED,
                        failure.message ?: "Native metadata linkage failed",
                    ),
                )
            } catch (failure: Exception) {
                return AlgorithmOpenResult.Failure(
                    AlgorithmError(
                        AlgorithmErrorCode.NATIVE_METADATA_FAILED,
                        failure.message ?: "Native metadata could not be read",
                    ),
                )
            }
            validateBeforeNative(profile, sensitivityToken, initializationMode, checkpoint, metadata)?.let {
                return AlgorithmOpenResult.Failure(it)
            }

            val context = try {
                native.createContext()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: LinkageError) {
                return AlgorithmOpenResult.Failure(
                    AlgorithmError(
                        AlgorithmErrorCode.NATIVE_CREATE_FAILED,
                        failure.message ?: "Native context linkage failed",
                    ),
                )
            } catch (failure: Exception) {
                return AlgorithmOpenResult.Failure(
                    AlgorithmError(
                        AlgorithmErrorCode.NATIVE_CREATE_FAILED,
                        failure.message ?: "Native context creation failed",
                    ),
                )
            }

            val initialized = try {
                native.initialize(context, sensitivityToken.value, initializationMode)
            } catch (cancelled: CancellationException) {
                releaseAfterFailedOpen(native, context)
                throw cancelled
            } catch (_: LinkageError) {
                0
            } catch (_: Exception) {
                0
            }
            if (initialized != 1) {
                releaseAfterFailedOpen(native, context)
                return AlgorithmOpenResult.Failure(
                    AlgorithmError(AlgorithmErrorCode.NATIVE_INIT_FAILED, "Native algorithm initialization failed"),
                )
            }

            if (checkpoint != null) {
                val restored = try {
                    native.restoreState(context, checkpoint.nativeState.copyOf())
                } catch (cancelled: CancellationException) {
                    releaseAfterFailedOpen(native, context)
                    throw cancelled
                } catch (_: LinkageError) {
                    0
                } catch (_: Exception) {
                    0
                }
                if (restored != 1) {
                    releaseAfterFailedOpen(native, context)
                    return AlgorithmOpenResult.Failure(
                        AlgorithmError(AlgorithmErrorCode.NATIVE_RESTORE_FAILED, "Native state restore failed"),
                    )
                }
            }

            return AlgorithmOpenResult.Success(
                SibionicsAlgorithmSession(
                    profile,
                    sensitivityToken,
                    initializationMode,
                    native,
                    context,
                    metadata.binarySetId,
                    metadata.algorithmVersion,
                    checkpoint,
                ),
            )
        }

        private fun validateBeforeNative(
            profile: AlgorithmProfile,
            sensitivityToken: SensitivityToken,
            initializationMode: AlgorithmInitializationMode,
            checkpoint: AlgorithmCheckpoint?,
            metadata: NativeAlgorithmMetadata,
        ): AlgorithmError? {
            if (!sensitivityToken.isValid()) {
                return AlgorithmError(
                    AlgorithmErrorCode.INVALID_SENSITIVITY_TOKEN,
                    "Sensitivity token must contain exactly eight ASCII letters or digits",
                )
            }
            if (initializationMode !in metadata.supportedInitializationModes) {
                return AlgorithmError(
                    AlgorithmErrorCode.UNSUPPORTED_INITIALIZATION_MODE,
                    "The requested initialization branch is not enabled for the verified application flow",
                )
            }
            if (metadata.profile != profile) {
                return AlgorithmError(AlgorithmErrorCode.PROFILE_MISMATCH, "Native profile does not match requested profile")
            }
            if (checkpoint == null) return null
            if (checkpoint.schemaVersion != CHECKPOINT_SCHEMA_VERSION) {
                return AlgorithmError(
                    AlgorithmErrorCode.NATIVE_STATE_FAILED,
                    "Unsupported checkpoint schema version ${checkpoint.schemaVersion}",
                )
            }
            if (checkpoint.profile != profile) {
                return AlgorithmError(AlgorithmErrorCode.PROFILE_MISMATCH, "Checkpoint profile does not match requested profile")
            }
            if (checkpoint.binarySetId != metadata.binarySetId) {
                return AlgorithmError(AlgorithmErrorCode.BINARY_SET_MISMATCH, "Checkpoint belongs to a different native binary set")
            }
            if (checkpoint.sensitivityToken != sensitivityToken) {
                return AlgorithmError(
                    AlgorithmErrorCode.SENSITIVITY_TOKEN_MISMATCH,
                    "Checkpoint belongs to a different sensitivity token",
                )
            }
            if (checkpoint.initializationMode != initializationMode) {
                return AlgorithmError(
                    AlgorithmErrorCode.INITIALIZATION_MODE_MISMATCH,
                    "Checkpoint belongs to a different algorithm initialization mode",
                )
            }
            if (checkpoint.nativeState.size != profile.stateSize) {
                return AlgorithmError(
                    AlgorithmErrorCode.STATE_SIZE_MISMATCH,
                    "Expected ${profile.stateSize} state bytes, received ${checkpoint.nativeState.size}",
                )
            }
            if (sha256(checkpoint.nativeState) != checkpoint.nativeStateSha256.lowercase()) {
                return AlgorithmError(AlgorithmErrorCode.STATE_HASH_MISMATCH, "Checkpoint state hash does not match its contents")
            }
            return null
        }

        private fun releaseAfterFailedOpen(
            native: NativeAlgorithmApi,
            context: NativeAlgorithmContext,
        ) {
            try {
                native.release(context)
            } catch (_: LinkageError) {
                Unit
            } catch (_: Exception) {
                Unit
            }
        }
    }
}

private data class NativeAlgorithmMetadata(
    val profile: AlgorithmProfile,
    val binarySetId: String,
    val algorithmVersion: String,
    val supportedInitializationModes: Set<AlgorithmInitializationMode>,
)
