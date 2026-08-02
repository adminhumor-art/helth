package com.sladkaya.sensor.sibionics.datahandle

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SibionicsDataHandleTest {
    @Test
    fun authenticationRejectsAProtocolVariantFromAnotherNativeBundle() {
        val native = RecordingDataHandleApi()
        val handle = SibionicsDataHandle(DataHandleBundle.GLOBAL, native)

        val result = handle.authentication(
            DataHandleVariant.CHINESE_GS1,
            "AA:BB:CC:DD:EE:FF",
        ) as DataHandleCommandResult.Failure

        assertEquals(DataHandleError.NATIVE_BUNDLE_MISMATCH, result.error)
        assertTrue(native.calls.isEmpty())
    }

    @Test
    fun authenticationRegistersPinnedIdentityAndUsesReversedBluetoothAddress() {
        val native = RecordingDataHandleApi().apply {
            commandBytes = byteArrayOf(0x19, 0x01, 0x02)
        }
        val handle = SibionicsDataHandle(native)

        val result = handle.authentication(
            DataHandleVariant.GLOBAL_GS1,
            "AA:BB:CC:DD:EE:FF",
        ) as DataHandleCommandResult.Success

        assertArrayEquals(byteArrayOf(0x19, 0x01, 0x02), result.bytes)
        assertEquals(2, native.calls.size)
        assertEquals("register-pinned-protocol-material", native.calls[0])
        assertArrayEquals(
            DataHandleVariant.GLOBAL_GS1.pinnedProtocolRegistrationMaterial.encodeToByteArray(),
            checkNotNull(native.registeredProtocolMaterial),
        )
        assertArrayEquals(
            DataHandleVariant.GLOBAL_GS1.pinnedProtocolApplicationId.encodeToByteArray(),
            checkNotNull(native.registeredProtocolApplicationId),
        )
        assertEquals("auth:1:true:0:FFEEDDCCBBAA:50", native.calls[1])
    }

    @Test
    fun registrationMaterialIsPinnedOnlyForSupportedGs1ProtocolVariants() {
        val expected = listOf(
            Triple(
                DataHandleVariant.GLOBAL_GS1,
                "com.sisensing.sijoy",
                "56CE249349040C94F8B4B2375A8752D5CBE7A17814B502D9132489C0BFDFC99F0CAC670E8CBB085AF1C780B3D282E3",
            ),
            Triple(
                DataHandleVariant.RUSSIAN_GS1,
                "com.sisensing.rusibionics",
                "60B05FEB7C0A148DEED2B3375A8754D9D0E6A5751BCE02D9132489C0BFDFC99F0CAC670E8DA7115CEACF87B7DE8FD4612E1B7638C2",
            ),
            Triple(
                DataHandleVariant.CHINESE_GS1,
                "com.sisensing.sisensingcgm",
                "4E8E1CAF43051F97EEC9C1475A8752D5C387D17A65B002D9132489C0BFDFC99F0CAC670E8CBB1150E6D581B7D08FC03404052C57AD58",
            ),
            Triple(
                DataHandleVariant.ECO_GS1,
                "com.sisensing.eco",
                "068449FA5C1B1F97EEC9C1475A8752D5C387D17A65B002D9132489C0BFDFC99F0CAC670E9AB10D62FDE0B2B1E7",
            ),
        )

        expected.forEach { (variant, protocolApplicationId, registrationMaterial) ->
            assertEquals(protocolApplicationId, variant.pinnedProtocolApplicationId)
            assertEquals(registrationMaterial, variant.pinnedProtocolRegistrationMaterial)
        }
        assertEquals(listOf(0, 1, 2, 3), DataHandleVariant.entries.map { it.protocolCode })
        assertTrue(DataHandleVariant.entries.all { it.name.endsWith("_GS1") })
    }

    @Test
    fun invalidBluetoothAddressFailsBeforeNativeCode() {
        val native = RecordingDataHandleApi()

        val result = SibionicsDataHandle(native).authentication(
            DataHandleVariant.GLOBAL_GS1,
            "not-an-address",
        ) as DataHandleCommandResult.Failure

        assertEquals(DataHandleError.INVALID_BLUETOOTH_ADDRESS, result.error)
        assertTrue(native.calls.isEmpty())
    }

    @Test
    fun commandBuildersUseTheExactObservedNativeArguments() {
        val native = RecordingDataHandleApi().apply { commandBytes = byteArrayOf(7, 6, 5, 4) }
        val handle = SibionicsDataHandle(native)

        assertTrue(handle.activation(1_700_000_000L) is DataHandleCommandResult.Success)
        assertTrue(handle.timeUpdate(1_700_000_001L) is DataHandleCommandResult.Success)
        assertTrue(handle.rawData(index = 321) is DataHandleCommandResult.Success)
        assertTrue(handle.reset() is DataHandleCommandResult.Success)

        assertEquals(
            listOf(
                "activation:0:true:0000:1700000000:1234:50",
                "time:0:true:0000:1700000001:50",
                "raw:0:true:0000:321:0:30",
                "reset:0:true:0000:0:1024",
            ),
            native.calls,
        )
    }

    @Test
    fun impossibleNativeLengthFailsWithoutExposingBufferContents() {
        val native = RecordingDataHandleApi().apply { forcedLength = 51 }

        val result = SibionicsDataHandle(native).timeUpdate(1_700_000_000L)
            as DataHandleCommandResult.Failure

        assertEquals(DataHandleError.INVALID_NATIVE_RESULT, result.error)
        assertEquals(51, result.nativeCode)
    }

    @Test
    fun splitRetriesWithoutDecryptionOnlyAfterNegativeNativeResult() {
        val native = RecordingDataHandleApi().apply {
            splitResults += -3
            splitResults += 2
        }
        val packet = byteArrayOf(1, 2, 3, 4, 5)

        val result = SibionicsDataHandle(native).split(packet) as DataHandleSplitResult.Success

        assertEquals(2, result.recordCount)
        assertEquals(false, result.decrypted)
        assertArrayEquals(intArrayOf(0xC007, 0), result.metadata)
        assertEquals(7_168, result.formattedPayload.size)
        assertEquals(2, result.workspace.size)
        assertEquals(
            listOf(
                "split:0:0102030405:true:5:2:7232:2",
                "split:0:0102030405:false:5:2:7232:2",
            ),
            native.calls,
        )
    }

    @Test
    fun zeroRecordsIsAValidEmptyResponseWithoutRetry() {
        val native = RecordingDataHandleApi().apply { splitResults += 0 }

        val result = SibionicsDataHandle(native).split(byteArrayOf(1, 2, 3))

        assertEquals(0, (result as DataHandleSplitResult.Success).recordCount)
        assertEquals(1, native.calls.size)
    }

    @Test
    fun typedGs1SplitParsesEveryOfficialFieldInOrder() {
        val native = RecordingDataHandleApi().apply {
            splitResults += 2
            payloadJson =
                "{\"index\":41,\"temp\":321,\"current\":58,\"dump\":9,\"reindex\":3," +
                "\"glouse\":105,\"trend\":2,\"gwarn\":1,\"twarn\":0,\"cwarn\":0,\"itime\":1700000000}," +
                "{\"index\":42,\"temp\":322,\"current\":59,\"dump\":10,\"reindex\":2," +
                "\"glouse\":106,\"trend\":3,\"gwarn\":0,\"twarn\":1,\"cwarn\":1,\"itime\":1700000060}"
        }

        val result = SibionicsDataHandle(native).splitGs1Data(ByteArray(28) { 1 })
            as Gs1DataSplitResult.Success

        assertEquals(2, result.records.size)
        assertEquals(
            Gs1NativeRecord(
                index = 41,
                temperature10 = 321,
                current10 = 58,
                dump = 9,
                reindex = 3,
                embeddedGlucose = 105,
                trend = 2,
                glucoseWarning = 1,
                temperatureWarning = 0,
                currentWarning = 0,
                sensorTimeEpochSeconds = 1_700_000_000L,
            ),
            result.records.first(),
        )
        assertEquals(42, result.records.last().index)
        assertEquals(false, result.records.last().reindex == result.records.first().reindex)
    }

    @Test
    fun typedGs1SplitRejectsMalformedJsonAndCanaryMutation() {
        val malformed = RecordingDataHandleApi().apply {
            splitResults += 1
            payloadJson = "{\"index\":41}"
        }
        val overflowing = RecordingDataHandleApi().apply {
            splitResults += 1
            payloadJson = "{\"index\":41}"
            mutateCanary = true
        }

        val malformedResult = SibionicsDataHandle(malformed).splitGs1Data(ByteArray(20) { 1 })
            as Gs1DataSplitResult.Failure
        val overflowResult = SibionicsDataHandle(overflowing).split(ByteArray(20) { 1 })
            as DataHandleSplitResult.Failure

        assertEquals(DataHandleError.MALFORMED_NATIVE_PAYLOAD, malformedResult.error)
        assertEquals(DataHandleError.NATIVE_BUFFER_OVERFLOW, overflowResult.error)
    }

    @Test
    fun invalidPacketSizeFailsBeforeNativeCode() {
        val native = RecordingDataHandleApi()

        val empty = SibionicsDataHandle(native).split(byteArrayOf()) as DataHandleSplitResult.Failure
        val oversized = SibionicsDataHandle(native).split(ByteArray(251)) as DataHandleSplitResult.Failure

        assertEquals(DataHandleError.INVALID_PACKET_SIZE, empty.error)
        assertEquals(DataHandleError.INVALID_PACKET_SIZE, oversized.error)
        assertTrue(native.calls.isEmpty())
    }

    @Test
    fun impossibleSplitRecordCountIsRejected() {
        val native = RecordingDataHandleApi().apply { splitResults += 251 }

        val result = SibionicsDataHandle(native).split(byteArrayOf(1, 2, 3))
            as DataHandleSplitResult.Failure

        assertEquals(DataHandleError.INVALID_NATIVE_RESULT, result.error)
        assertEquals(251, result.nativeCode)
    }
}

private class RecordingDataHandleApi : NativeDataHandleApi {
    val calls = mutableListOf<String>()
    val splitResults = ArrayDeque<Int>()
    var registeredProtocolMaterial: ByteArray? = null
    var registeredProtocolApplicationId: ByteArray? = null
    var commandBytes = byteArrayOf(1, 2, 3)
    var forcedLength: Int? = null
    var payloadJson: String = ""
    var mutateCanary: Boolean = false

    override fun registerKey(input: ByteArray, length: Int, output: ByteArray): Int {
        registeredProtocolMaterial = input.copyOf(length)
        registeredProtocolApplicationId = output.copyOf()
        calls += "register-pinned-protocol-material"
        return 0
    }

    override fun applyAuthentication(
        command: Int,
        encrypted: Boolean,
        value: Int,
        input: ByteArray,
        output: ByteArray,
        outputLength: Int,
    ): Int = fill(output, "auth:$command:$encrypted:$value:${input.hex()}:$outputLength")

    override fun activation(
        command: Int,
        encrypted: Boolean,
        input: ByteArray,
        unixTime: Long,
        value: Int,
        output: ByteArray,
        outputLength: Int,
    ): Int = fill(output, "activation:$command:$encrypted:${input.hex()}:$unixTime:$value:$outputLength")

    override fun timeUpdate(
        command: Int,
        encrypted: Boolean,
        input: ByteArray,
        unixTime: Long,
        output: ByteArray,
        outputLength: Int,
    ): Int = fill(output, "time:$command:$encrypted:${input.hex()}:$unixTime:$outputLength")

    override fun rawData(
        command: Int,
        encrypted: Boolean,
        input: ByteArray,
        value: Long,
        index: Int,
        output: ByteArray,
        outputLength: Int,
    ): Int = fill(output, "raw:$command:$encrypted:${input.hex()}:$value:$index:$outputLength")

    override fun reset(
        command: Int,
        encrypted: Boolean,
        input: ByteArray,
        value: Int,
        output: ByteArray,
        outputLength: Int,
    ): Int = fill(output, "reset:$command:$encrypted:${input.hex()}:$value:$outputLength")

    override fun splitData(
        command: Int,
        packet: ByteArray,
        metadata: IntArray,
        formattedPayload: ByteArray,
        encrypted: Boolean,
        workspace: ByteArray,
        workspaceLength: Int,
    ): Int {
        calls += "split:$command:${packet.hex()}:$encrypted:$workspaceLength:${metadata.size}:${formattedPayload.size}:${workspace.size}"
        metadata[0] = 0xC007
        payloadJson.encodeToByteArray().copyInto(formattedPayload)
        if (mutateCanary) formattedPayload[7_168] = 0
        return splitResults.removeFirstOrNull() ?: 1
    }

    private fun fill(output: ByteArray, call: String): Int {
        calls += call
        commandBytes.copyInto(output)
        return forcedLength ?: commandBytes.size
    }
}

private fun ByteArray.hex(): String = joinToString("") { "%02X".format(it.toInt() and 0xff) }
