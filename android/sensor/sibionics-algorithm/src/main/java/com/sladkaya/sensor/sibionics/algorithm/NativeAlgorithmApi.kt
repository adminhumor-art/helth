package com.sladkaya.sensor.sibionics.algorithm

interface NativeAlgorithmContext

data class NativeAlgorithmSnapshot(
    val glucoseMmolL: Double,
    val trend: Int,
    val glucoseWarning: Int = 0,
    val currentWarning: Int = 0,
    val temperatureWarning: Int = 0,
)

interface NativeAlgorithmApi {
    val profile: AlgorithmProfile
    val binarySetId: String
    val supportedInitializationModes: Set<AlgorithmInitializationMode>
        get() = setOf(AlgorithmInitializationMode.STANDARD)
    val algorithmVersion: String

    fun createContext(): NativeAlgorithmContext
    fun initialize(
        context: NativeAlgorithmContext,
        sensitivityToken: String,
        mode: AlgorithmInitializationMode,
    ): Int
    fun restoreState(context: NativeAlgorithmContext, state: ByteArray): Int
    fun process(context: NativeAlgorithmContext, input: AlgorithmInput): NativeAlgorithmSnapshot
    fun exportState(context: NativeAlgorithmContext): ByteArray
    fun release(context: NativeAlgorithmContext): Int
}
