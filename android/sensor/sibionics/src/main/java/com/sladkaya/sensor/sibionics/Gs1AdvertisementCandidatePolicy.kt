package com.sladkaya.sensor.sibionics

internal data class Gs1AdvertisementCandidate(
    val deviceName: String?,
    val bluetoothAddress: String,
)

internal enum class Gs1AdvertisementInvalidReason {
    MALFORMED_BLUETOOTH_ADDRESS,
}

/** A suffix match is only a discovery candidate, never a verified sensor identity. */
internal sealed interface Gs1AdvertisementCandidateResult {
    data class CandidateMatch(
        val deviceName: String,
        val canonicalBluetoothAddress: String,
    ) : Gs1AdvertisementCandidateResult

    data object NoMatch : Gs1AdvertisementCandidateResult

    data class Invalid(
        val reason: Gs1AdvertisementInvalidReason,
    ) : Gs1AdvertisementCandidateResult
}

internal sealed interface Gs1AdvertisementSelection {
    data object None : Gs1AdvertisementSelection

    data class Single(
        val candidate: Gs1AdvertisementCandidateResult.CandidateMatch,
    ) : Gs1AdvertisementSelection

    data class Ambiguous(
        val candidates: List<Gs1AdvertisementCandidateResult.CandidateMatch>,
    ) : Gs1AdvertisementSelection {
        init {
            require(candidates.size >= 2)
        }
    }
}

/**
 * Pure GS1/GS1Sb advertisement filter. It applies the compatibility-trace
 * name-suffix rule pending physical validation, plus canonical MAC syntax.
 * Successful output still requires pairing and identity verification.
 */
internal class Gs1AdvertisementCandidatePolicy(
    packageCode: String,
) {
    private val requiredNameSuffix: String

    init {
        require(packageCode.length == PACKAGE_CODE_LENGTH)
        require(packageCode.all(Char::isAsciiLetterOrDigit))
        requiredNameSuffix = packageCode.take(NAME_SUFFIX_LENGTH)
    }

    fun evaluate(candidate: Gs1AdvertisementCandidate): Gs1AdvertisementCandidateResult {
        val name = candidate.deviceName
        if (name == null || name.length < NAME_SUFFIX_LENGTH) {
            return Gs1AdvertisementCandidateResult.NoMatch
        }
        if (!name.endsWith(requiredNameSuffix, ignoreCase = false)) {
            return Gs1AdvertisementCandidateResult.NoMatch
        }
        if (!BLUETOOTH_ADDRESS.matches(candidate.bluetoothAddress)) {
            return Gs1AdvertisementCandidateResult.Invalid(
                Gs1AdvertisementInvalidReason.MALFORMED_BLUETOOTH_ADDRESS,
            )
        }
        return Gs1AdvertisementCandidateResult.CandidateMatch(
            deviceName = name,
            canonicalBluetoothAddress = candidate.bluetoothAddress.uppercase(),
        )
    }

    fun select(candidates: List<Gs1AdvertisementCandidate>): Gs1AdvertisementSelection {
        val matches = candidates.asSequence()
            .map(::evaluate)
            .filterIsInstance<Gs1AdvertisementCandidateResult.CandidateMatch>()
            .distinctBy { it.canonicalBluetoothAddress }
            .toList()
        return when (matches.size) {
            0 -> Gs1AdvertisementSelection.None
            1 -> Gs1AdvertisementSelection.Single(matches.single())
            else -> Gs1AdvertisementSelection.Ambiguous(matches)
        }
    }

    private companion object {
        const val PACKAGE_CODE_LENGTH = 8
        const val NAME_SUFFIX_LENGTH = 4
        val BLUETOOTH_ADDRESS = Regex("^(?:[0-9A-Fa-f]{2}:){5}[0-9A-Fa-f]{2}$")
    }
}

private fun Char.isAsciiLetterOrDigit(): Boolean =
    this in '0'..'9' || this in 'A'..'Z' || this in 'a'..'z'
