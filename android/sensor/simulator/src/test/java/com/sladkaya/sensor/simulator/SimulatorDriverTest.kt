package com.sladkaya.sensor.simulator

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SimulatorDriverTest {
    @Test
    fun nightLowScenarioContainsCriticalValueAndRecovery() {
        val driver = SimulatorDriver()
        val values = (0L until 12L).map { with(driver) { SimulationScenario.NIGHT_LOW.point(it)!!.first } }
        assertEquals(58, values.min())
        assertEquals(86, values.last())
    }

    @Test
    fun signalLossStopsProducingReadings() {
        val driver = SimulatorDriver()
        assertEquals(110, with(driver) { SimulationScenario.SIGNAL_LOSS.point(2)!!.first })
        assertNull(with(driver) { SimulationScenario.SIGNAL_LOSS.point(3) })
    }
}
