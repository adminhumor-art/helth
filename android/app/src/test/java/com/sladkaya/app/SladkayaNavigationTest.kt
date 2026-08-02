package com.sladkaya.app

import org.junit.Assert.assertEquals
import org.junit.Test

class SladkayaNavigationTest {
    @Test
    fun familyAccessReturnsToAlarmSettingsAndSettingsReturnsToDashboard() {
        assertEquals(
            SladkayaDestination.AlarmSettings,
            SladkayaNavigation.backFrom(SladkayaDestination.FamilyAccess),
        )
        assertEquals(
            SladkayaDestination.Dashboard,
            SladkayaNavigation.backFrom(SladkayaDestination.AlarmSettings),
        )
    }
}
