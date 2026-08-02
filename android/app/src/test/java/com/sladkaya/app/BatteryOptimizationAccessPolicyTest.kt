package com.sladkaya.app

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BatteryOptimizationAccessPolicyTest {
    @Test
    fun preMarshmallowDevicesDoNotNeedAnExemption() {
        assertFalse(
            BatteryOptimizationAccessPolicy.needsUserAction(
                sdkInt = 22,
                ignoringBatteryOptimizations = false,
            ),
        )
    }

    @Test
    fun modernDevicesNeedAnExplicitExemption() {
        assertTrue(
            BatteryOptimizationAccessPolicy.needsUserAction(
                sdkInt = 23,
                ignoringBatteryOptimizations = false,
            ),
        )
        assertFalse(
            BatteryOptimizationAccessPolicy.needsUserAction(
                sdkInt = 37,
                ignoringBatteryOptimizations = true,
            ),
        )
    }
}
