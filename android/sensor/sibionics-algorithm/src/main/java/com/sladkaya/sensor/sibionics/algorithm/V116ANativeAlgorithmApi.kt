package com.sladkaya.sensor.sibionics.algorithm

import com.algorithm.v116a.AlgorithmContext
import com.algorithm.v116a.NativeAlgorithmLibraryV116A

class V116ANativeAlgorithmApi(
    private val binarySet: NativeBinarySet = NativeBinarySets.resolve(AlgorithmProfile.V116A),
    loader: NativeLibraryLoader = SystemNativeLibraryLoader,
) : NativeAlgorithmApi {
    init {
        require(binarySet.profile == AlgorithmProfile.V116A)
        loadLibraries(loader, "native-algorithm-v1_1_6A", "native-algorithm-jni-v116A")
    }

    override val profile = AlgorithmProfile.V116A
    override val binarySetId: String = binarySet.id
    override val algorithmVersion: String by lazy {
        requireNotNull(NativeAlgorithmLibraryV116A.getAlgorithmVersion()) {
            "v116A returned a null algorithm version"
        }.also {
            require(it.isConcreteAlgorithmVersion()) {
                "v116A returned a blank or unknown algorithm version"
            }
        }
    }
    override val supportedInitializationModes = AlgorithmInitializationMode.entries.toSet()

    override fun createContext(): NativeAlgorithmContext = V116AContext(
        requireNotNull(NativeAlgorithmLibraryV116A.getAlgorithmContextFromNative()) {
            "v116A returned a null context"
        },
    )

    override fun initialize(
        context: NativeAlgorithmContext,
        sensitivityToken: String,
        mode: AlgorithmInitializationMode,
    ): Int = when (mode) {
        AlgorithmInitializationMode.STANDARD ->
            NativeAlgorithmLibraryV116A.initAlgorithmContext(context.unwrap(), 0, sensitivityToken)
        AlgorithmInitializationMode.FACTION ->
            NativeAlgorithmLibraryV116A.initAlgorithmContextFaction(
                context.unwrap(),
                0,
                sensitivityToken,
            )
    }

    override fun restoreState(context: NativeAlgorithmContext, state: ByteArray): Int {
        require(state.size == profile.stateSize) { "v116A state must contain ${profile.stateSize} bytes" }
        return NativeAlgorithmLibraryV116A.setBinaryStructAlgorithmContext(context.unwrap(), state)
    }

    override fun process(context: NativeAlgorithmContext, input: AlgorithmInput): NativeAlgorithmSnapshot {
        val value = context.unwrap()
        val glucose = NativeAlgorithmLibraryV116A.processAlgorithmContext(
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
        requireNotNull(NativeAlgorithmLibraryV116A.getBinaryStructAlgorithmContext(context.unwrap()))

    override fun release(context: NativeAlgorithmContext): Int =
        NativeAlgorithmLibraryV116A.releaseAlgorithmContext(context.unwrap())

    private fun NativeAlgorithmContext.unwrap(): AlgorithmContext =
        (this as? V116AContext)?.value ?: error("Context does not belong to v116A")
}

private data class V116AContext(val value: AlgorithmContext) : NativeAlgorithmContext

internal fun loadLibraries(
    loader: NativeLibraryLoader,
    algorithmLibrary: String,
    jniLibrary: String,
) {
    loader.load("native-struct2json")
    loader.load("native-encrypy-decrypt-v110")
    loader.load("native-sensitivity-v110")
    loader.load(algorithmLibrary)
    loader.load(jniLibrary)
}
