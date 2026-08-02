package com.sladkaya.app.onboarding

import com.sladkaya.sensor.sibionics.Gs1MarketProfile
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SensorSetupMarketPresentationTest {
    @Test
    fun userLabelsContainOnlyBoxRegionsAndNoInternalProtocolOrPackageNames() {
        val forbidden = listOf("com.", "V115", "V120", "package")

        Gs1MarketProfile.entries.forEach { profile ->
            val label = profile.userLabel()
            assertTrue(label.isNotBlank())
            forbidden.forEach { token ->
                assertFalse("$profile label exposes $token", label.contains(token, ignoreCase = true))
            }
        }
    }

    @Test
    fun globalAndChineseExplainAutomaticInternalResolutionWhileOthersStayBlocked() {
        listOf(Gs1MarketProfile.GLOBAL, Gs1MarketProfile.CHINESE).forEach { profile ->
            val message = profile.diagnosticAvailabilityMessage()
            assertTrue(message.contains("доступно"))
            assertTrue(message.contains("само"))
        }
        listOf(Gs1MarketProfile.RUSSIAN, Gs1MarketProfile.ECO_SPLIT).forEach { profile ->
            assertTrue(profile.diagnosticAvailabilityMessage().contains("не запускается"))
        }
    }
}
