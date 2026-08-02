package com.sladkaya.sensor.sibionics

import com.sladkaya.sensor.sibionics.datahandle.DataHandleBundle
import com.sladkaya.sensor.sibionics.datahandle.DataHandleCommandResult
import com.sladkaya.sensor.sibionics.datahandle.DataHandleGateway
import com.sladkaya.sensor.sibionics.datahandle.DataHandleSplitResult
import com.sladkaya.sensor.sibionics.datahandle.DataHandleVariant
import com.sladkaya.sensor.sibionics.datahandle.Gs1DataSplitResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Test

class Gs1V120DataHandleBindingTest {
    @Test
    fun oneBundleBoundGatewayCreatesBothCommandAndPacketPaths() {
        val handle = NoOpDataHandleGateway(DataHandleBundle.CHINESE)

        val binding = Gs1V120DataHandleBinding.bind(transportVariant = 2, handle = handle)

        assertEquals(DataHandleBundle.CHINESE, binding.bundle)
        assertSame(handle, binding.dataHandle)
    }

    @Test
    fun aGatewayFromAnotherMarketCannotEnterThePipeline() {
        val global = NoOpDataHandleGateway(DataHandleBundle.GLOBAL)

        assertThrows(IllegalArgumentException::class.java) {
            Gs1V120DataHandleBinding.bind(transportVariant = 2, handle = global)
        }
    }
}

private class NoOpDataHandleGateway(
    override val bundle: DataHandleBundle,
) : DataHandleGateway {
    override fun authentication(
        variant: DataHandleVariant,
        bluetoothAddress: String,
    ) = DataHandleCommandResult.Failure(
        com.sladkaya.sensor.sibionics.datahandle.DataHandleError.NATIVE_CALL_FAILED,
    )

    override fun activation(epochSeconds: Long) = authentication(
        DataHandleVariant.GLOBAL_GS1,
        "00:00:00:00:00:00",
    )
    override fun timeUpdate(epochSeconds: Long) = activation(epochSeconds)
    override fun rawData(index: Int) = activation(index.toLong())
    override fun reset() = activation(1)
    override fun split(packet: ByteArray) = DataHandleSplitResult.Failure(
        com.sladkaya.sensor.sibionics.datahandle.DataHandleError.NATIVE_CALL_FAILED,
    )
    override fun splitGs1Data(packet: ByteArray) = Gs1DataSplitResult.Failure(
        com.sladkaya.sensor.sibionics.datahandle.DataHandleError.NATIVE_CALL_FAILED,
    )
    override fun close() = Unit
}
