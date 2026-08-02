package com.sladkaya.sensor.sibionics

import android.os.Build
import com.sladkaya.sensor.sibionics.algorithm.AlgorithmProfile
import com.sladkaya.sensor.sibionics.algorithm.NativeBinarySets
import com.sladkaya.sensor.sibionics.datahandle.SibionicsDataHandle
import java.security.MessageDigest

internal data class Gs1NativeArtifactIdentity(
    val algorithmBinarySetSha256: String,
    val datahandleBinarySetSha256: String,
) {
    init {
        require(SHA256.matches(algorithmBinarySetSha256))
        require(SHA256.matches(datahandleBinarySetSha256))
    }
}

/** Shared by product opening and the future physical-approval coordinator. */
internal fun interface Gs1NativeArtifactIdentityProvider {
    fun resolve(profile: AlgorithmProfile): Gs1NativeArtifactIdentity
}

internal object Gs1InstalledNativeArtifactIdentityProvider : Gs1NativeArtifactIdentityProvider {
    override fun resolve(profile: AlgorithmProfile): Gs1NativeArtifactIdentity =
        resolve(profile, Build.SUPPORTED_ABIS.toList())

    internal fun resolve(
        profile: AlgorithmProfile,
        supportedAbis: List<String>,
    ): Gs1NativeArtifactIdentity {
        val algorithmSet = NativeBinarySets.resolve(profile, supportedAbis)
        val algorithmManifest = algorithmSet.files.toSortedMap().entries
            .joinToString("\n") { (name, hash) -> "$name=$hash" }
        val datahandleId = SibionicsDataHandle.BINARY_SET_ID
        val datahandleHash = datahandleId.removePrefix(DATAHANDLE_SHA256_PREFIX)
        require(
            datahandleId == "$DATAHANDLE_SHA256_PREFIX$datahandleHash" &&
                SHA256.matches(datahandleHash),
        ) { "Native datahandle binary-set identity is malformed" }
        return Gs1NativeArtifactIdentity(
            algorithmBinarySetSha256 = MessageDigest.getInstance("SHA-256")
                .digest(algorithmManifest.encodeToByteArray())
                .joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) },
            datahandleBinarySetSha256 = datahandleHash,
        )
    }
}

private val SHA256 = Regex("^[0-9a-f]{64}$")
private const val DATAHANDLE_SHA256_PREFIX = "sha256:"
