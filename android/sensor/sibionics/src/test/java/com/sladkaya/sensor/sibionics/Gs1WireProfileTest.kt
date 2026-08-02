package com.sladkaya.sensor.sibionics

import com.sladkaya.sensor.sibionics.algorithm.AlgorithmProfile
import com.sladkaya.sensor.sibionics.datahandle.SibionicsDataHandle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class Gs1WireProfileTest {
    @Test
    fun versionedProfilesPinTheWholeTransportAndAlgorithmTuple() {
        assertEquals(
            Gs1WireProfileSpec(
                wireProfile = Gs1WireProfile.V120,
                transportProtocol = "GS1_V120",
                transportCodecId = SibionicsDataHandle.BINARY_SET_ID,
                algorithmProfile = AlgorithmProfile.V116A,
            ),
            Gs1WireProfiles.requireResolved(Gs1WireProfile.V120),
        )
        assertEquals(
            Gs1WireProfileSpec(
                wireProfile = Gs1WireProfile.V115,
                transportProtocol = "GS1_V115",
                transportCodecId = "GS1_V115_WIRE_V1",
                algorithmProfile = AlgorithmProfile.V115G,
            ),
            Gs1WireProfiles.requireResolved(Gs1WireProfile.V115),
        )
    }

    @Test
    fun unresolvedProfileCanNeverSelectANativeEngine() {
        assertThrows(IllegalArgumentException::class.java) {
            Gs1WireProfiles.requireResolved(Gs1WireProfile.UNRESOLVED)
        }
    }
}
