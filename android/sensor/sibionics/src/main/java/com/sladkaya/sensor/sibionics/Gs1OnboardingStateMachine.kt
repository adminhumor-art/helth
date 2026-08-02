package com.sladkaya.sensor.sibionics

import com.sladkaya.core.model.SensorFamily

enum class Gs1PackageCodeSource {
    MANUAL,
    DATA_MATRIX,
}

/**
 * Market identity selected by the user from the box or the manufacturer's app.
 * It is deliberately independent from GS1/GS1Sb and from the package code.
 */
enum class Gs1MarketProfile(
    val transportVariant: Int,
) {
    GLOBAL(0),
    RUSSIAN(1),
    CHINESE(2),
    ECO_SPLIT(3),
}

sealed interface Gs1PackageCodeInput {
    val value: String
    val source: Gs1PackageCodeSource

    data class Manual(override val value: String) : Gs1PackageCodeInput {
        override val source = Gs1PackageCodeSource.MANUAL
    }

    data class DataMatrix(override val value: String) : Gs1PackageCodeInput {
        override val source = Gs1PackageCodeSource.DATA_MATRIX
    }
}

data class Gs1DiscoveredAdvertisement(
    val deviceName: String?,
    val bluetoothAddress: String,
)

data class Gs1ResolvedAdvertisement(
    val deviceName: String,
    val canonicalBluetoothAddress: String,
)

enum class Gs1OnboardingProfileStatus {
    PENDING_DIAGNOSTIC,
}

/**
 * A candidate profile that may open only the quarantined diagnostic runtime.
 * Physical release is intentionally absent from this type and cannot be
 * inferred from package-code or advertisement matching.
 */
@ConsistentCopyVisibility
data class Gs1PendingDiagnosticProfile internal constructor(
    val sensorId: String,
    val family: SensorFamily,
    val marketProfile: Gs1MarketProfile,
    val deviceName: String,
    val canonicalBluetoothAddress: String,
    val transportVariant: Int,
    val packageCode: String,
    val codeSource: Gs1PackageCodeSource,
) {
    val status: Gs1OnboardingProfileStatus
        get() = Gs1OnboardingProfileStatus.PENDING_DIAGNOSTIC

    val physicalEvidenceVerified: Boolean
        get() = false

    val eligibleForConfirmedConfiguration: Boolean
        get() = false

    val eligibleForProductPublication: Boolean
        get() = false

    init {
        require(sensorId.isNotBlank() && sensorId.length <= MAX_SENSOR_ID_CHARS)
        require(family.isGs1Family())
        require(marketProfile.supportsDiagnosticAttempt())
        require(deviceName.length >= 4)
        require(CANONICAL_BLUETOOTH_ADDRESS.matches(canonicalBluetoothAddress))
        require(transportVariant == marketProfile.transportVariant)
        require(packageCode.isValidPackageCode())
    }

    /** The returned profile remains diagnostic because the runtime factory is fail-closed. */
    fun diagnosticActivationProfile(): Gs1DiagnosticActivationProfile =
        when (
            val validation = Gs1DiagnosticActivationProfile.validate(
                sensorId = sensorId,
                family = family,
                bluetoothAddress = canonicalBluetoothAddress,
                transportVariant = transportVariant,
                packageCode = packageCode,
            )
        ) {
            is Gs1DiagnosticActivationProfileValidation.Valid -> validation.profile
            is Gs1DiagnosticActivationProfileValidation.Invalid -> {
                error("Pending diagnostic profile became invalid: ${validation.error}")
            }
        }
}

@ConsistentCopyVisibility
data class Gs1OnboardingRequest internal constructor(
    val family: SensorFamily,
    val marketProfile: Gs1MarketProfile,
    val packageCode: String,
    val source: Gs1PackageCodeSource,
)

enum class Gs1OnboardingRejectionReason {
    INVALID_PACKAGE_CODE_LENGTH,
    INVALID_PACKAGE_CODE_CHARACTER,
    UNSUPPORTED_FAMILY,
    MARKET_PROFILE_REQUIRED,
    PROFILE_NOT_PHYSICALLY_VERIFIED,
    INVALID_TRANSITION,
    NO_MATCHING_CANDIDATE,
    MALFORMED_BLUETOOTH_ADDRESS,
    AMBIGUOUS_CANDIDATES,
    TOO_MANY_CANDIDATES,
    PROFILE_VALIDATION_FAILED,
    STORAGE_UNAVAILABLE,
    STORAGE_CONFLICT,
}

sealed interface Gs1OnboardingState {
    data object AwaitingPackageCode : Gs1OnboardingState

    data class ProfileBlocked(
        val request: Gs1OnboardingRequest,
        val reason: Gs1OnboardingRejectionReason =
            Gs1OnboardingRejectionReason.PROFILE_NOT_PHYSICALLY_VERIFIED,
    ) : Gs1OnboardingState {
        init {
            require(reason == Gs1OnboardingRejectionReason.PROFILE_NOT_PHYSICALLY_VERIFIED)
            require(!request.marketProfile.supportsDiagnosticAttempt())
        }
    }

    data class Discovering(
        val request: Gs1OnboardingRequest,
    ) : Gs1OnboardingState {
        init {
            require(request.marketProfile.supportsDiagnosticAttempt())
        }
    }

    data class ResolutionBlocked(
        val request: Gs1OnboardingRequest,
        val reason: Gs1OnboardingRejectionReason,
        val candidates: List<Gs1ResolvedAdvertisement> = emptyList(),
    ) : Gs1OnboardingState {
        init {
            require(request.marketProfile.supportsDiagnosticAttempt())
            require(reason in CANDIDATE_RESOLUTION_REASONS)
            require(candidates.size <= MAX_ONBOARDING_CANDIDATES)
            require(
                (reason == Gs1OnboardingRejectionReason.AMBIGUOUS_CANDIDATES &&
                    candidates.size >= 2) ||
                    (reason != Gs1OnboardingRejectionReason.AMBIGUOUS_CANDIDATES &&
                        candidates.isEmpty()),
            )
        }
    }

    data class PendingDiagnostic(
        val profile: Gs1PendingDiagnosticProfile,
    ) : Gs1OnboardingState
}

sealed interface Gs1OnboardingActionResult {
    data class Advanced(val state: Gs1OnboardingState) : Gs1OnboardingActionResult

    data class Rejected(
        val reason: Gs1OnboardingRejectionReason,
        val state: Gs1OnboardingState,
        val detail: String? = null,
    ) : Gs1OnboardingActionResult
}

enum class Gs1OnboardingStage {
    AWAITING_PACKAGE_CODE,
    PROFILE_BLOCKED,
    DISCOVERING,
    RESOLUTION_BLOCKED,
    PENDING_DIAGNOSTIC,
}

/** Versioned snapshot suitable for an app-owned atomic persistence adapter. */
data class Gs1OnboardingSnapshot(
    val revision: Long = 0,
    val schemaVersion: Int = CURRENT_ONBOARDING_SCHEMA_VERSION,
    val stage: Gs1OnboardingStage = Gs1OnboardingStage.AWAITING_PACKAGE_CODE,
    val family: SensorFamily? = null,
    val marketProfile: Gs1MarketProfile? = null,
    val codeSource: Gs1PackageCodeSource? = null,
    val packageCode: String? = null,
    val rejectionReason: Gs1OnboardingRejectionReason? = null,
    val candidates: List<Gs1ResolvedAdvertisement> = emptyList(),
    val selectedDeviceName: String? = null,
    val selectedBluetoothAddress: String? = null,
)

/** Stores draft onboarding state only; it is not a confirmed-configuration store. */
interface Gs1OnboardingStateStore {
    fun load(): Gs1OnboardingSnapshot?

    /**
     * Atomically replaces the snapshot only when its current revision equals
     * [expectedRevision]. `null` means that no snapshot may exist yet.
     */
    fun compareAndSet(
        expectedRevision: Long?,
        snapshot: Gs1OnboardingSnapshot,
    ): Boolean
}

enum class Gs1OnboardingOpenError {
    STORAGE_UNAVAILABLE,
    UNSUPPORTED_SAVED_SCHEMA,
    INVALID_SAVED_STATE,
}

sealed interface Gs1OnboardingOpenResult {
    data class Ready(val machine: Gs1OnboardingStateMachine) : Gs1OnboardingOpenResult

    data class Failure(
        val error: Gs1OnboardingOpenError,
        val detail: String? = null,
    ) : Gs1OnboardingOpenResult
}

/**
 * Strict code-first GS1/GS1Sb onboarding. A unique advertisement can produce
 * only [Gs1OnboardingState.PendingDiagnostic]; this API has no confirmation or
 * product-publication transition.
 */
class Gs1OnboardingStateMachine private constructor(
    private val store: Gs1OnboardingStateStore,
    initialState: Gs1OnboardingState,
    initialRevision: Long?,
) {
    @Volatile
    private var currentState: Gs1OnboardingState = initialState
    private var currentRevision: Long? = initialRevision

    val state: Gs1OnboardingState
        get() = currentState

    @Synchronized
    fun submitPackageCode(
        family: SensorFamily,
        input: Gs1PackageCodeInput,
        marketProfile: Gs1MarketProfile?,
    ): Gs1OnboardingActionResult {
        if (currentState != Gs1OnboardingState.AwaitingPackageCode) {
            return rejected(Gs1OnboardingRejectionReason.INVALID_TRANSITION)
        }
        if (!family.isGs1Family()) {
            return rejected(Gs1OnboardingRejectionReason.UNSUPPORTED_FAMILY)
        }
        if (input.value.length != PACKAGE_CODE_LENGTH) {
            return rejected(Gs1OnboardingRejectionReason.INVALID_PACKAGE_CODE_LENGTH)
        }
        if (!input.value.all(Char::isAsciiLetterOrDigit)) {
            return rejected(Gs1OnboardingRejectionReason.INVALID_PACKAGE_CODE_CHARACTER)
        }
        val selectedMarket = marketProfile
            ?: return rejected(Gs1OnboardingRejectionReason.MARKET_PROFILE_REQUIRED)
        val request = Gs1OnboardingRequest(
            family = family,
            marketProfile = selectedMarket,
            packageCode = input.value,
            source = input.source,
        )
        if (!selectedMarket.supportsDiagnosticAttempt()) return blockProfile(request)
        return advance(Gs1OnboardingState.Discovering(request))
    }

    @Synchronized
    fun resolveAdvertisements(
        advertisements: List<Gs1DiscoveredAdvertisement>,
    ): Gs1OnboardingActionResult {
        val stateBeforeResolution = currentState
        val request = when (stateBeforeResolution) {
            is Gs1OnboardingState.Discovering -> stateBeforeResolution.request
            is Gs1OnboardingState.ResolutionBlocked -> stateBeforeResolution.request
            else -> return rejected(Gs1OnboardingRejectionReason.INVALID_TRANSITION)
        }
        val stickyCandidates = if (
            stateBeforeResolution is Gs1OnboardingState.ResolutionBlocked &&
            stateBeforeResolution.reason == Gs1OnboardingRejectionReason.AMBIGUOUS_CANDIDATES
        ) {
            stateBeforeResolution.candidates
        } else {
            emptyList()
        }
        if (advertisements.size > GS1_MAX_DISCOVERED_ADVERTISEMENTS) {
            return if (stickyCandidates.isNotEmpty()) {
                rejected(Gs1OnboardingRejectionReason.TOO_MANY_CANDIDATES)
            } else {
                blockResolution(
                    request = request,
                    reason = Gs1OnboardingRejectionReason.TOO_MANY_CANDIDATES,
                )
            }
        }

        val policy = Gs1AdvertisementCandidatePolicy(request.packageCode)
        val resolvedByAddress = linkedMapOf<String, Gs1ResolvedAdvertisement>()
        stickyCandidates.forEach { candidate ->
            resolvedByAddress[candidate.canonicalBluetoothAddress] = candidate
        }
        var hasMalformedMatchingAddress = false
        for (advertisement in advertisements) {
            when (
                val evaluated = policy.evaluate(
                    Gs1AdvertisementCandidate(
                        deviceName = advertisement.deviceName,
                        bluetoothAddress = advertisement.bluetoothAddress,
                    ),
                )
            ) {
                is Gs1AdvertisementCandidateResult.CandidateMatch -> {
                    if (!resolvedByAddress.containsKey(evaluated.canonicalBluetoothAddress) &&
                        resolvedByAddress.size == MAX_ONBOARDING_CANDIDATES
                    ) {
                        return if (stickyCandidates.isNotEmpty()) {
                            rejected(Gs1OnboardingRejectionReason.TOO_MANY_CANDIDATES)
                        } else {
                            blockResolution(
                                request = request,
                                reason = Gs1OnboardingRejectionReason.TOO_MANY_CANDIDATES,
                            )
                        }
                    }
                    resolvedByAddress.putIfAbsent(
                        evaluated.canonicalBluetoothAddress,
                        evaluated.toResolvedAdvertisement(),
                    )
                }

                is Gs1AdvertisementCandidateResult.Invalid -> {
                    hasMalformedMatchingAddress = true
                }

                Gs1AdvertisementCandidateResult.NoMatch -> Unit
            }
        }
        if (hasMalformedMatchingAddress) {
            return if (stickyCandidates.isNotEmpty()) {
                rejected(Gs1OnboardingRejectionReason.MALFORMED_BLUETOOTH_ADDRESS)
            } else {
                blockResolution(
                    request = request,
                    reason = Gs1OnboardingRejectionReason.MALFORMED_BLUETOOTH_ADDRESS,
                )
            }
        }

        val resolvedCandidates = resolvedByAddress.values.toList()
        return when (resolvedCandidates.size) {
            0 -> blockResolution(
                request = request,
                reason = Gs1OnboardingRejectionReason.NO_MATCHING_CANDIDATE,
            )

            1 -> {
                val profile = pendingProfile(request, resolvedCandidates.single())
                    ?: return rejected(Gs1OnboardingRejectionReason.PROFILE_VALIDATION_FAILED)
                advance(Gs1OnboardingState.PendingDiagnostic(profile))
            }

            else -> blockResolution(
                request = request,
                reason = Gs1OnboardingRejectionReason.AMBIGUOUS_CANDIDATES,
                candidates = resolvedCandidates,
            )
        }
    }

    @Synchronized
    fun reset(): Gs1OnboardingActionResult = advance(Gs1OnboardingState.AwaitingPackageCode)

    private fun blockProfile(request: Gs1OnboardingRequest): Gs1OnboardingActionResult {
        val blocked = Gs1OnboardingState.ProfileBlocked(request)
        return when (val persisted = advance(blocked)) {
            is Gs1OnboardingActionResult.Advanced -> Gs1OnboardingActionResult.Rejected(
                reason = blocked.reason,
                state = persisted.state,
            )
            is Gs1OnboardingActionResult.Rejected -> persisted
        }
    }

    private fun blockResolution(
        request: Gs1OnboardingRequest,
        reason: Gs1OnboardingRejectionReason,
        candidates: List<Gs1ResolvedAdvertisement> = emptyList(),
    ): Gs1OnboardingActionResult {
        val blocked = Gs1OnboardingState.ResolutionBlocked(
            request = request,
            reason = reason,
            candidates = candidates,
        )
        return when (val persisted = advance(blocked)) {
            is Gs1OnboardingActionResult.Advanced -> Gs1OnboardingActionResult.Rejected(
                reason = reason,
                state = persisted.state,
            )
            is Gs1OnboardingActionResult.Rejected -> persisted
        }
    }

    private fun advance(next: Gs1OnboardingState): Gs1OnboardingActionResult {
        val previousRevision = currentRevision
        if (previousRevision == Long.MAX_VALUE) {
            return rejected(Gs1OnboardingRejectionReason.STORAGE_CONFLICT)
        }
        val nextRevision = (previousRevision ?: 0L) + 1L
        try {
            if (!store.compareAndSet(previousRevision, next.toSnapshot(nextRevision))) {
                return rejected(Gs1OnboardingRejectionReason.STORAGE_CONFLICT)
            }
        } catch (failure: Exception) {
            return rejected(
                reason = Gs1OnboardingRejectionReason.STORAGE_UNAVAILABLE,
                detail = failure.message,
            )
        }
        currentRevision = nextRevision
        currentState = next
        return Gs1OnboardingActionResult.Advanced(next)
    }

    private fun rejected(
        reason: Gs1OnboardingRejectionReason,
        detail: String? = null,
    ): Gs1OnboardingActionResult.Rejected = Gs1OnboardingActionResult.Rejected(
        reason = reason,
        state = currentState,
        detail = detail,
    )

    companion object {
        fun open(store: Gs1OnboardingStateStore): Gs1OnboardingOpenResult {
            val snapshot = try {
                store.load()
            } catch (failure: Exception) {
                return Gs1OnboardingOpenResult.Failure(
                    error = Gs1OnboardingOpenError.STORAGE_UNAVAILABLE,
                    detail = failure.message,
                )
            }
            if (snapshot == null) {
                return Gs1OnboardingOpenResult.Ready(
                    Gs1OnboardingStateMachine(
                        store,
                        Gs1OnboardingState.AwaitingPackageCode,
                        initialRevision = null,
                    ),
                )
            }
            if (snapshot.revision < 0) {
                return Gs1OnboardingOpenResult.Failure(
                    Gs1OnboardingOpenError.INVALID_SAVED_STATE,
                )
            }
            if (snapshot.schemaVersion != CURRENT_ONBOARDING_SCHEMA_VERSION) {
                return Gs1OnboardingOpenResult.Failure(
                    Gs1OnboardingOpenError.UNSUPPORTED_SAVED_SCHEMA,
                )
            }
            val restored = restore(snapshot)
                ?: return Gs1OnboardingOpenResult.Failure(
                    Gs1OnboardingOpenError.INVALID_SAVED_STATE,
                )
            return Gs1OnboardingOpenResult.Ready(
                Gs1OnboardingStateMachine(store, restored, snapshot.revision),
            )
        }

        private fun restore(snapshot: Gs1OnboardingSnapshot): Gs1OnboardingState? {
            return when (snapshot.stage) {
                Gs1OnboardingStage.AWAITING_PACKAGE_CODE -> {
                    if (snapshot.hasNoRequestOrResolutionData()) {
                        Gs1OnboardingState.AwaitingPackageCode
                    } else {
                        null
                    }
                }

                Gs1OnboardingStage.PROFILE_BLOCKED -> restoreProfileBlocked(snapshot)

                Gs1OnboardingStage.DISCOVERING -> {
                    val request = snapshot.validDiagnosticRequest() ?: return null
                    if (snapshot.rejectionReason == null &&
                        snapshot.candidates.isEmpty() &&
                        snapshot.selectedDeviceName == null &&
                        snapshot.selectedBluetoothAddress == null
                    ) {
                        Gs1OnboardingState.Discovering(request)
                    } else {
                        null
                    }
                }

                Gs1OnboardingStage.RESOLUTION_BLOCKED -> restoreBlocked(snapshot)
                Gs1OnboardingStage.PENDING_DIAGNOSTIC -> restorePending(snapshot)
            }
        }

        private fun restoreProfileBlocked(snapshot: Gs1OnboardingSnapshot): Gs1OnboardingState? {
            val request = snapshot.validRequest() ?: return null
            if (request.marketProfile.supportsDiagnosticAttempt() ||
                snapshot.rejectionReason !=
                Gs1OnboardingRejectionReason.PROFILE_NOT_PHYSICALLY_VERIFIED ||
                snapshot.candidates.isNotEmpty() ||
                snapshot.selectedDeviceName != null ||
                snapshot.selectedBluetoothAddress != null
            ) {
                return null
            }
            return Gs1OnboardingState.ProfileBlocked(request)
        }

        private fun restoreBlocked(snapshot: Gs1OnboardingSnapshot): Gs1OnboardingState? {
            val request = snapshot.validDiagnosticRequest() ?: return null
            val reason = snapshot.rejectionReason
                ?.takeIf { it in CANDIDATE_RESOLUTION_REASONS }
                ?: return null
            if (snapshot.selectedDeviceName != null || snapshot.selectedBluetoothAddress != null) {
                return null
            }
            if (reason != Gs1OnboardingRejectionReason.AMBIGUOUS_CANDIDATES) {
                return if (snapshot.candidates.isEmpty()) {
                    Gs1OnboardingState.ResolutionBlocked(request, reason)
                } else {
                    null
                }
            }
            val policy = Gs1AdvertisementCandidatePolicy(request.packageCode)
            val restoredCandidates = snapshot.candidates
                .takeIf { it.size in 2..MAX_ONBOARDING_CANDIDATES }
                ?.takeIf {
                    it.distinctBy(Gs1ResolvedAdvertisement::canonicalBluetoothAddress).size == it.size
                }
                ?.takeIf { candidates ->
                    candidates.all { candidate ->
                        policy.isExactPersistedCandidate(candidate)
                    }
                }
                ?: return null
            return Gs1OnboardingState.ResolutionBlocked(request, reason, restoredCandidates)
        }

        private fun restorePending(snapshot: Gs1OnboardingSnapshot): Gs1OnboardingState? {
            val request = snapshot.validDiagnosticRequest() ?: return null
            if (snapshot.rejectionReason != null || snapshot.candidates.isNotEmpty()) return null
            val name = snapshot.selectedDeviceName ?: return null
            val address = snapshot.selectedBluetoothAddress ?: return null
            val policy = Gs1AdvertisementCandidatePolicy(request.packageCode)
            val evaluated = policy.evaluate(Gs1AdvertisementCandidate(name, address))
            if (evaluated !is Gs1AdvertisementCandidateResult.CandidateMatch) return null
            if (evaluated.deviceName != name || evaluated.canonicalBluetoothAddress != address) {
                return null
            }
            val profile = pendingProfile(request, evaluated.toResolvedAdvertisement()) ?: return null
            return Gs1OnboardingState.PendingDiagnostic(profile)
        }
    }
}

private fun Gs1OnboardingState.toSnapshot(revision: Long): Gs1OnboardingSnapshot = when (this) {
    Gs1OnboardingState.AwaitingPackageCode -> Gs1OnboardingSnapshot(revision = revision)
    is Gs1OnboardingState.ProfileBlocked -> request.snapshot(
        revision = revision,
        stage = Gs1OnboardingStage.PROFILE_BLOCKED,
        rejectionReason = reason,
    )
    is Gs1OnboardingState.Discovering -> request.snapshot(
        revision = revision,
        stage = Gs1OnboardingStage.DISCOVERING,
    )
    is Gs1OnboardingState.ResolutionBlocked -> request.snapshot(
        revision = revision,
        stage = Gs1OnboardingStage.RESOLUTION_BLOCKED,
        rejectionReason = reason,
        candidates = candidates,
    )
    is Gs1OnboardingState.PendingDiagnostic -> Gs1OnboardingSnapshot(
        revision = revision,
        stage = Gs1OnboardingStage.PENDING_DIAGNOSTIC,
        family = profile.family,
        marketProfile = profile.marketProfile,
        codeSource = profile.codeSource,
        packageCode = profile.packageCode,
        selectedDeviceName = profile.deviceName,
        selectedBluetoothAddress = profile.canonicalBluetoothAddress,
    )
}

private fun Gs1OnboardingRequest.snapshot(
    revision: Long,
    stage: Gs1OnboardingStage,
    rejectionReason: Gs1OnboardingRejectionReason? = null,
    candidates: List<Gs1ResolvedAdvertisement> = emptyList(),
) = Gs1OnboardingSnapshot(
    revision = revision,
    stage = stage,
    family = family,
    marketProfile = marketProfile,
    codeSource = source,
    packageCode = packageCode,
    rejectionReason = rejectionReason,
    candidates = candidates,
)

private fun Gs1OnboardingSnapshot.validRequest(): Gs1OnboardingRequest? {
    val restoredFamily = family?.takeIf { it.isGs1Family() } ?: return null
    val restoredMarket = marketProfile ?: return null
    val restoredSource = codeSource ?: return null
    val restoredCode = packageCode?.takeIf(String::isValidPackageCode) ?: return null
    return Gs1OnboardingRequest(restoredFamily, restoredMarket, restoredCode, restoredSource)
}

private fun Gs1OnboardingSnapshot.validDiagnosticRequest(): Gs1OnboardingRequest? =
    validRequest()?.takeIf { it.marketProfile.supportsDiagnosticAttempt() }

private fun Gs1OnboardingSnapshot.hasNoRequestOrResolutionData(): Boolean =
    family == null &&
        marketProfile == null &&
        codeSource == null &&
        packageCode == null &&
        rejectionReason == null &&
        candidates.isEmpty() &&
        selectedDeviceName == null &&
        selectedBluetoothAddress == null

private fun Gs1AdvertisementCandidateResult.CandidateMatch.toResolvedAdvertisement() =
    Gs1ResolvedAdvertisement(
        deviceName = deviceName,
        canonicalBluetoothAddress = canonicalBluetoothAddress,
    )

private fun Gs1AdvertisementCandidatePolicy.isExactPersistedCandidate(
    candidate: Gs1ResolvedAdvertisement,
): Boolean {
    val evaluated = evaluate(
        Gs1AdvertisementCandidate(candidate.deviceName, candidate.canonicalBluetoothAddress),
    )
    return evaluated is Gs1AdvertisementCandidateResult.CandidateMatch &&
        evaluated.deviceName == candidate.deviceName &&
        evaluated.canonicalBluetoothAddress == candidate.canonicalBluetoothAddress
}

private fun pendingProfile(
    request: Gs1OnboardingRequest,
    resolved: Gs1ResolvedAdvertisement,
): Gs1PendingDiagnosticProfile? {
    val sensorId = "${request.family.wireName}:${resolved.canonicalBluetoothAddress.replace(":", "")}"
    val validation = Gs1DiagnosticActivationProfile.validate(
        sensorId = sensorId,
        family = request.family,
        bluetoothAddress = resolved.canonicalBluetoothAddress,
        transportVariant = request.marketProfile.transportVariant,
        packageCode = request.packageCode,
    )
    if (validation !is Gs1DiagnosticActivationProfileValidation.Valid) return null
    return Gs1PendingDiagnosticProfile(
        sensorId = validation.profile.sensorId,
        family = validation.profile.family,
        marketProfile = request.marketProfile,
        canonicalBluetoothAddress = validation.profile.bluetoothAddress,
        transportVariant = validation.profile.transportVariant,
        packageCode = validation.profile.packageCode,
        codeSource = request.source,
        deviceName = resolved.deviceName,
    )
}

private fun SensorFamily.isGs1Family(): Boolean =
    this == SensorFamily.SIBIONICS_GS1 || this == SensorFamily.SIBIONICS_GS1SB

private fun Gs1MarketProfile.supportsDiagnosticAttempt(): Boolean =
    this == Gs1MarketProfile.GLOBAL || this == Gs1MarketProfile.CHINESE

private fun String.isValidPackageCode(): Boolean =
    length == PACKAGE_CODE_LENGTH && all(Char::isAsciiLetterOrDigit)

private fun Char.isAsciiLetterOrDigit(): Boolean =
    this in '0'..'9' || this in 'A'..'Z' || this in 'a'..'z'

// The product has not shipped; this is the first and only onboarding schema.
private const val CURRENT_ONBOARDING_SCHEMA_VERSION = 1
private const val PACKAGE_CODE_LENGTH = 8
private const val MAX_SENSOR_ID_CHARS = 128
private val CANONICAL_BLUETOOTH_ADDRESS = Regex("^(?:[0-9A-F]{2}:){5}[0-9A-F]{2}$")
private val CANDIDATE_RESOLUTION_REASONS = setOf(
    Gs1OnboardingRejectionReason.NO_MATCHING_CANDIDATE,
    Gs1OnboardingRejectionReason.MALFORMED_BLUETOOTH_ADDRESS,
    Gs1OnboardingRejectionReason.AMBIGUOUS_CANDIDATES,
    Gs1OnboardingRejectionReason.TOO_MANY_CANDIDATES,
)

internal const val MAX_ONBOARDING_CANDIDATES = 64
