package com.sladkaya.sensor.sibionics.algorithm

import com.algorithm.v116a.NativeAlgorithmLibraryV116A
import com.algorithm.v1_11_15.NativeAlgorithmLibraryv1_11_15

enum class SensitivityEncoding {
    NORMAL,
    FACTION,
}

data class DecodedSensitivity(
    val token: SensitivityToken,
    val coefficient: Float,
    val encoding: SensitivityEncoding,
)

enum class SensitivityDecodeError {
    INVALID_TOKEN,
    DECODING_FAILED,
    OUT_OF_RANGE,
    NATIVE_CALL_FAILED,
}

sealed interface SensitivityDecodeResult {
    data class Success(val value: DecodedSensitivity) : SensitivityDecodeResult
    data class Failure(val error: SensitivityDecodeError) : SensitivityDecodeResult
}

class SensitivityDecoder internal constructor(
    private val native: NativeSensitivityApi,
) {
    fun decode(token: SensitivityToken): SensitivityDecodeResult {
        if (!token.isValid()) {
            return SensitivityDecodeResult.Failure(SensitivityDecodeError.INVALID_TOKEN)
        }

        val normal = runCatching { native.decodeNormal(token.value) }
            .getOrElse {
                return SensitivityDecodeResult.Failure(SensitivityDecodeError.NATIVE_CALL_FAILED)
            }
        val (coefficient, encoding) = if (normal == NATIVE_DECODE_FAILURE) {
            val faction = runCatching { native.decodeFaction(token.value) }
                .getOrElse {
                    return SensitivityDecodeResult.Failure(SensitivityDecodeError.NATIVE_CALL_FAILED)
                }
            if (faction == NATIVE_DECODE_FAILURE) {
                return SensitivityDecodeResult.Failure(SensitivityDecodeError.DECODING_FAILED)
            }
            faction to SensitivityEncoding.FACTION
        } else {
            normal to SensitivityEncoding.NORMAL
        }

        if (!coefficient.isFinite() || coefficient !in MIN_COEFFICIENT..MAX_COEFFICIENT) {
            return SensitivityDecodeResult.Failure(SensitivityDecodeError.OUT_OF_RANGE)
        }
        return SensitivityDecodeResult.Success(
            DecodedSensitivity(token, coefficient, encoding),
        )
    }

    companion object {
        fun create(
            profile: AlgorithmProfile,
            loader: NativeLibraryLoader = SystemNativeLibraryLoader,
        ): SensitivityDecoder = SensitivityDecoder(
            when (profile) {
                AlgorithmProfile.V116A -> V116ASensitivityApi(loader)
                AlgorithmProfile.V115G -> V115GSensitivityApi(loader)
            },
        )

        private const val NATIVE_DECODE_FAILURE = -1.0f
        private const val MIN_COEFFICIENT = 0.8f
        private const val MAX_COEFFICIENT = 2.5f
    }
}

internal interface NativeSensitivityApi {
    fun decodeNormal(token: String): Float
    fun decodeFaction(token: String): Float
}

private class V116ASensitivityApi(loader: NativeLibraryLoader) : NativeSensitivityApi {
    init {
        loadLibraries(loader, "native-algorithm-v1_1_6A", "native-algorithm-jni-v116A")
    }

    override fun decodeNormal(token: String): Float =
        NativeAlgorithmLibraryV116A.decryptSensitivity(token)

    override fun decodeFaction(token: String): Float =
        NativeAlgorithmLibraryV116A.decryptSensitivityFaction(token)
}

private class V115GSensitivityApi(loader: NativeLibraryLoader) : NativeSensitivityApi {
    init {
        loadLibraries(loader, "native-algorithm-v1_1_5G", "native-algorithm-jni-v115G")
    }

    override fun decodeNormal(token: String): Float =
        NativeAlgorithmLibraryv1_11_15.decryptSensitivity(token)

    override fun decodeFaction(token: String): Float =
        NativeAlgorithmLibraryv1_11_15.decryptSensitivityFaction(token)
}
