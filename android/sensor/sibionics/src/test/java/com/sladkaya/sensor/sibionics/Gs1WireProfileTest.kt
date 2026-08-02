package com.sladkaya.sensor.sibionics

import com.sladkaya.sensor.sibionics.algorithm.AlgorithmProfile
import com.sladkaya.sensor.sibionics.datahandle.DataHandleBundle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class Gs1WireProfileTest {
    @Test
    fun versionedProfilesPinOnlyTheWireTransportTuple() {
        assertEquals(
            Gs1WireProfileSpec(
                wireProfile = Gs1WireProfile.V120,
                transportProtocol = "GS1_V120",
                transportCodecId = DataHandleBundle.GLOBAL.binarySetId,
            ),
            Gs1WireProfiles.requireResolved(Gs1WireProfile.V120, 0),
        )
        assertEquals(
            Gs1WireProfileSpec(
                wireProfile = Gs1WireProfile.V115,
                transportProtocol = "GS1_V115",
                transportCodecId = "GS1_V115_WIRE_V1",
            ),
            Gs1WireProfiles.requireResolved(Gs1WireProfile.V115, 2),
        )
    }

    @Test
    fun officialMarketBundleSelectsAlgorithmIndependentlyFromWireProfile() {
        assertEquals(
            AlgorithmProfile.V116A,
            Gs1AlgorithmProfiles.requireForTransportVariant(0),
        )
        assertEquals(
            AlgorithmProfile.V115G,
            Gs1AlgorithmProfiles.requireForTransportVariant(2),
        )
    }

    @Test
    fun unsupportedMarketBundleCanNeverSelectANativeEngine() {
        assertThrows(IllegalArgumentException::class.java) {
            Gs1AlgorithmProfiles.requireForTransportVariant(1)
        }
    }

    @Test
    fun unresolvedProfileCanNeverSelectANativeEngine() {
        assertThrows(IllegalArgumentException::class.java) {
            Gs1WireProfiles.requireResolved(Gs1WireProfile.UNRESOLVED, 2)
        }
    }
}
