package com.sladkaya.sensor.sibionics.algorithm

import android.os.Build
import java.security.MessageDigest

data class NativeBinarySet(
    val profile: AlgorithmProfile,
    val abi: String,
    val files: Map<String, String>,
) {
    val id: String by lazy {
        val fingerprint = MessageDigest.getInstance("SHA-256")
            .digest(files.toSortedMap().entries.joinToString("\n") { "${it.key}=${it.value}" }.encodeToByteArray())
            .joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }
            .take(16)
        "${profile.name.lowercase()}-$abi-$fingerprint"
    }
}

object NativeBinarySets {
    private val sets = listOf(
        NativeBinarySet(
            profile = AlgorithmProfile.V116A,
            abi = "arm64-v8a",
            files = mapOf(
                "native-algorithm-jni-v116A" to "33f25693386d10bc0f63fbebc18cc721bd9bca47e612d664af2723d52d9f3b5d",
                "native-algorithm-v1_1_6A" to "3eb160078da015190eaf7e70c775d197082c70c55644b78deb1da2f82d75016b",
                "native-encrypy-decrypt-v110" to "3a35e64dbf78f0bc960d8ed5d984a06be8eecdd187db1b0ca37fd20df4cd0d6b",
                "native-sensitivity-v110" to "e2801b4d0c5e67f4efb1b93cf56c05b9eae9dc30dfa07bab86a2f07405843b98",
                "native-struct2json" to "170c23fcc1d2df2b809bf7f685dc0207ca98fb0aea81c3942db1d8ab3f6efccb",
            ),
        ),
        NativeBinarySet(
            profile = AlgorithmProfile.V115G,
            abi = "arm64-v8a",
            files = mapOf(
                "native-algorithm-jni-v115G" to "c08e1c2626aff583a835c91c0f70e7e479c495098767461181560d049627ae45",
                "native-algorithm-v1_1_5G" to "3df46f71611b0cb590ca97bdb3424ae9e20a57cad058afa632aec4e1d54cf488",
                "native-encrypy-decrypt-v110" to "3a35e64dbf78f0bc960d8ed5d984a06be8eecdd187db1b0ca37fd20df4cd0d6b",
                "native-sensitivity-v110" to "e2801b4d0c5e67f4efb1b93cf56c05b9eae9dc30dfa07bab86a2f07405843b98",
                "native-struct2json" to "170c23fcc1d2df2b809bf7f685dc0207ca98fb0aea81c3942db1d8ab3f6efccb",
            ),
        ),
        NativeBinarySet(
            profile = AlgorithmProfile.V116A,
            abi = "armeabi-v7a",
            files = mapOf(
                "native-algorithm-jni-v116A" to "c99c471b05556db330edb14711f44a085a3f9b10c3ba924803d9d81b1b6f3589",
                "native-algorithm-v1_1_6A" to "f1ba75ca79c0e511ff3a29ebf47f8a9bd037ddd69e45d06f0f8d6de0f0c1ca98",
                "native-encrypy-decrypt-v110" to "43d38cae04c1cde75523a041ac0993a90e293175e0df38a7cc9239dd076e437f",
                "native-sensitivity-v110" to "aab01b2af9446b3b8f805912060d7578ebf387d790b7441f17260691495a1da7",
                "native-struct2json" to "040e5b90df5ca81008f43be9ef4d48f0255a63a957b29c9630d60d030010e6a0",
            ),
        ),
        NativeBinarySet(
            profile = AlgorithmProfile.V115G,
            abi = "armeabi-v7a",
            files = mapOf(
                "native-algorithm-jni-v115G" to "39fa818555318f5c785bb09818c6ccb810cc36da3a49751905f0e154bacceaab",
                "native-algorithm-v1_1_5G" to "7ca5bc53d1b077dc219e96a893d811513e8cec8471086c0e8b888a675bb38c1b",
                "native-encrypy-decrypt-v110" to "43d38cae04c1cde75523a041ac0993a90e293175e0df38a7cc9239dd076e437f",
                "native-sensitivity-v110" to "aab01b2af9446b3b8f805912060d7578ebf387d790b7441f17260691495a1da7",
                "native-struct2json" to "040e5b90df5ca81008f43be9ef4d48f0255a63a957b29c9630d60d030010e6a0",
            ),
        ),
    )

    fun resolve(
        profile: AlgorithmProfile,
        supportedAbis: List<String> = Build.SUPPORTED_ABIS.toList(),
    ): NativeBinarySet = supportedAbis
        .firstNotNullOfOrNull { abi -> sets.firstOrNull { it.profile == profile && it.abi == abi } }
        ?: error("No native ${profile.name} library is available for ${supportedAbis.joinToString()}")

    internal fun all(): List<NativeBinarySet> = sets
}

fun interface NativeLibraryLoader {
    fun load(name: String)
}

object SystemNativeLibraryLoader : NativeLibraryLoader {
    override fun load(name: String) = System.loadLibrary(name)
}
