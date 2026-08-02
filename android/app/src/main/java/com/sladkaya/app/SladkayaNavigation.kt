package com.sladkaya.app

internal enum class SladkayaDestination {
    Dashboard,
    AlarmSettings,
    FamilyAccess,
}

internal object SladkayaNavigation {
    fun backFrom(destination: SladkayaDestination): SladkayaDestination = when (destination) {
        SladkayaDestination.FamilyAccess -> SladkayaDestination.AlarmSettings
        SladkayaDestination.AlarmSettings,
        SladkayaDestination.Dashboard,
        -> SladkayaDestination.Dashboard
    }
}
