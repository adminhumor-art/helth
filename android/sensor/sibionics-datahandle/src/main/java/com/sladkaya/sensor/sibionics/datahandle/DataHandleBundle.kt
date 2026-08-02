package com.sladkaya.sensor.sibionics.datahandle

import java.security.MessageDigest

enum class DataHandleBundle(
    val transportVariant: Int,
    val binarySetId: String,
    internal val libraryName: String,
    internal val serviceClassName: String,
) {
    GLOBAL(
        transportVariant = 0,
        binarySetId = DataHandleBundleBinaryIdentity.calculate(
            GeneratedDataHandleBinaryManifest.global,
        ),
        libraryName = "sladkaya-datahandle-global",
        serviceClassName =
            "com.sladkaya.sensor.sibionics.datahandle.ipc.GlobalDataHandleService",
    ),
    CHINESE(
        transportVariant = 2,
        binarySetId = DataHandleBundleBinaryIdentity.calculate(
            GeneratedDataHandleBinaryManifest.chinese,
        ),
        libraryName = "sladkaya-datahandle-cn",
        serviceClassName =
            "com.sladkaya.sensor.sibionics.datahandle.ipc.ChineseDataHandleService",
    ),
}

internal object DataHandleBundleBinaryIdentity {
    fun calculate(entries: Map<String, String>): String {
        require(entries.isNotEmpty()) { "A native binary set cannot be empty" }
        entries.forEach { (path, hash) ->
            require(path.isNotBlank() && !path.contains('\n') && !path.contains('\r')) {
                "Invalid native binary path"
            }
            require(hash.matches(Regex("[0-9a-f]{64}"))) {
                "Invalid native binary hash"
            }
        }
        val canonicalManifest = entries.toSortedMap().entries.joinToString(
            separator = "\n",
            postfix = "\n",
        ) { (path, hash) -> "$hash  $path" }
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(canonicalManifest.encodeToByteArray())
            .joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }
        return "sha256:$digest"
    }
}

object DataHandleBundles {
    fun resolve(transportVariant: Int): DataHandleBundle? = when (transportVariant) {
        DataHandleBundle.GLOBAL.transportVariant -> DataHandleBundle.GLOBAL
        DataHandleBundle.CHINESE.transportVariant -> DataHandleBundle.CHINESE
        else -> null
    }

    fun require(transportVariant: Int): DataHandleBundle =
        requireNotNull(resolve(transportVariant)) {
            "No pinned official data-handle bundle for transport variant $transportVariant"
        }
}

interface DataHandleGateway : AutoCloseable {
    val bundle: DataHandleBundle

    fun authentication(
        variant: DataHandleVariant,
        bluetoothAddress: String,
    ): DataHandleCommandResult

    fun activation(epochSeconds: Long): DataHandleCommandResult
    fun timeUpdate(epochSeconds: Long): DataHandleCommandResult
    fun rawData(index: Int): DataHandleCommandResult
    fun reset(): DataHandleCommandResult
    fun split(packet: ByteArray): DataHandleSplitResult
    fun splitGs1Data(packet: ByteArray): Gs1DataSplitResult
}
