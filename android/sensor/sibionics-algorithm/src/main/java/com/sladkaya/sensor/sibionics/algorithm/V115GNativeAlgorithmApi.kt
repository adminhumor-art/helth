package com.sladkaya.sensor.sibionics.algorithm

import com.algorithm.v1_1_5_g.AlgorithmContext
import com.algorithm.v1_11_15_1g.NativeAlgorithmLibraryV1_11_15G

class V115GNativeAlgorithmApi(
    private val binarySet: NativeBinarySet = NativeBinarySets.resolve(AlgorithmProfile.V115G),
    loader: NativeLibraryLoader = SystemNativeLibraryLoader,
) : NativeAlgorithmApi {
    init {
        require(binarySet.profile == AlgorithmProfile.V115G)
        loadLibraries(loader, "native-algorithm-v1_1_5G", "native-algorithm-jni-v115G")
    }

    override val profile = AlgorithmProfile.V115G
    override val binarySetId: String = binarySet.id
    override val algorithmVersion: String by lazy {
        requireNotNull(NativeAlgorithmLibraryV1_11_15G.getAlgorithmVersion()) {
            "v115G returned a null algorithm version"
        }.also {
            require(it.isConcreteAlgorithmVersion()) {
                "v115G returned a blank or unknown algorithm version"
            }
        }
    }
    override val supportedInitializationModes = AlgorithmInitializationMode.entries.toSet()

    override fun createContext(): NativeAlgorithmContext = V115GContext(
        requireNotNull(NativeAlgorithmLibraryV1_11_15G.getAlgorithmContextFromNative()) {
            "v115G returned a null context"
        },
    )

    override fun initialize(
        context: NativeAlgorithmContext,
        sensitivityToken: String,
        mode: AlgorithmInitializationMode,
    ): Int = when (mode) {
        AlgorithmInitializationMode.STANDARD ->
            NativeAlgorithmLibraryV1_11_15G.initAlgorithmContext(context.unwrap(), 0, sensitivityToken)
        AlgorithmInitializationMode.FACTION ->
            NativeAlgorithmLibraryV1_11_15G.initAlgorithmContextFaction(
                context.unwrap(),
                0,
                sensitivityToken,
            )
    }

    override fun restoreState(context: NativeAlgorithmContext, state: ByteArray): Int {
        require(state.size == profile.stateSize) { "v115G state must contain ${profile.stateSize} bytes" }
        return NativeAlgorithmLibraryV1_11_15G.setBinaryStructAlgorithmContext(context.unwrap(), state)
    }

    override fun process(context: NativeAlgorithmContext, input: AlgorithmInput): NativeAlgorithmSnapshot {
        val value = context.unwrap()
        val glucose = NativeAlgorithmLibraryV1_11_15G.processAlgorithmContext(
            value,
            input.index,
            input.signal,
            input.temperatureCelsius,
            0.0,
            profile.targetLowMmolL,
            profile.targetHighMmolL,
        )
        return NativeAlgorithmSnapshot(
            glucoseMmolL = glucose,
            trend = value.ig_trend,
            glucoseWarning = value.glucoseWarning,
            currentWarning = value.currentWarning,
            temperatureWarning = value.temperatureWarning,
        )
    }

    override fun exportState(context: NativeAlgorithmContext): ByteArray =
        requireNotNull(NativeAlgorithmLibraryV1_11_15G.getBinaryStructAlgorithmContext(context.unwrap()))

    override fun release(context: NativeAlgorithmContext): Int =
        NativeAlgorithmLibraryV1_11_15G.releaseAlgorithmContext(context.unwrap())

    private fun NativeAlgorithmContext.unwrap(): AlgorithmContext =
        (this as? V115GContext)?.value ?: error("Context does not belong to v115G")
}

private data class V115GContext(val value: AlgorithmContext) : NativeAlgorithmContext
