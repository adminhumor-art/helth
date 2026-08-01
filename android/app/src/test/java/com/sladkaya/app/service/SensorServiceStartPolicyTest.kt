package com.sladkaya.app.service

import org.junit.Assert.assertEquals
import org.junit.Test

class SensorServiceStartPolicyTest {
    private val policy = SensorServiceStartPolicy()

    @Test
    fun ordinaryStartWithoutConfirmedConfigurationRequiresSetup() {
        assertEquals(
            SensorServiceStartMode.SetupRequired,
            policy.select(
                action = SensorServiceActions.START,
                hasConfirmedConfiguration = false,
            ),
        )
    }

    @Test
    fun missingOrUnknownActionCannotEnableDemo() {
        listOf(false, true).forEach { hasConfirmedConfiguration ->
            listOf(null, "", "unexpected.action").forEach { action ->
                assertEquals(
                    SensorServiceStartMode.SetupRequired,
                    policy.select(
                        action = action,
                        hasConfirmedConfiguration = hasConfirmedConfiguration,
                    ),
                )
            }
        }
    }

    @Test
    fun demoRequiresItsDedicatedExplicitAction() {
        assertEquals(
            SensorServiceStartMode.Demo,
            policy.select(
                action = SensorServiceActions.START_DEMO,
                hasConfirmedConfiguration = false,
            ),
        )
    }

    @Test
    fun ordinaryStartWithConfirmedConfigurationSelectsConfiguredSensor() {
        assertEquals(
            SensorServiceStartMode.ConfiguredSensor,
            policy.select(
                action = SensorServiceActions.START,
                hasConfirmedConfiguration = true,
            ),
        )
    }

    @Test
    fun demoActionDoesNotDependOnSavedConfiguration() {
        assertEquals(
            SensorServiceStartMode.Demo,
            policy.select(
                action = SensorServiceActions.START_DEMO,
                hasConfirmedConfiguration = true,
            ),
        )
    }

    @Test
    fun backgroundStartRequiresBothConfigurationAndBlePermission() {
        assertEquals(true, SensorBackgroundStartPolicy.shouldStart(true, true))
        assertEquals(false, SensorBackgroundStartPolicy.shouldStart(false, true))
        assertEquals(false, SensorBackgroundStartPolicy.shouldStart(true, false))
    }

}
