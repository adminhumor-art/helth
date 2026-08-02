package com.sladkaya.core.data

import com.sladkaya.core.model.SensorFamily
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class SensorProtocolBindingRecordTest {
    @Test
    fun bindingKeepsTheCompleteVersionedTuple() {
        val binding = binding()

        assertEquals("sensor-a", binding.sensorId)
        assertEquals("AA:BB:CC:DD:EE:FF", binding.bluetoothAddress)
        assertEquals(SensorFamily.SIBIONICS_GS1, binding.sensorFamily)
        assertEquals(2, binding.transportVariant)
        assertEquals("V115", binding.wireProfile)
        assertEquals("GS1_V115", binding.transportProtocol)
        assertEquals("GS1_V115_WIRE_V1", binding.transportCodecId)
        assertEquals("V115G", binding.algorithmProfile)
        assertEquals("NORMAL", binding.sensitivityEncoding)
        assertEquals("VALIDATED_V115_ENVELOPE", binding.evidenceKind)
        assertEquals(1, binding.schemaVersion)
    }

    @Test
    fun bindingRejectsIncompleteOrUnversionedEvidence() {
        assertThrows(IllegalArgumentException::class.java) {
            binding().copy(evidenceSha256 = "not-a-hash")
        }
        assertThrows(IllegalArgumentException::class.java) {
            binding().copy(sensitivityToken = "short")
        }
        assertThrows(IllegalArgumentException::class.java) {
            binding().copy(schemaVersion = 0)
        }
        assertThrows(IllegalArgumentException::class.java) {
            binding().copy(bluetoothAddress = "AA:BB:CC:DD:EE")
        }
    }

    private fun binding() = SensorProtocolBindingRecord(
        sensorId = "sensor-a",
        bluetoothAddress = "AA:BB:CC:DD:EE:FF",
        sensorFamily = SensorFamily.SIBIONICS_GS1,
        transportVariant = 2,
        sensitivityToken = "ABCD1234",
        wireProfile = "V115",
        transportProtocol = "GS1_V115",
        transportCodecId = "GS1_V115_WIRE_V1",
        algorithmProfile = "V115G",
        sensitivityEncoding = "NORMAL",
        evidenceKind = "VALIDATED_V115_ENVELOPE",
        evidenceSha256 = "ab".repeat(32),
        schemaVersion = 1,
    )
}
