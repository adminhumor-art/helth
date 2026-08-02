package com.sladkaya.sensor.sibionics

/**
 * Performs one bounded discovery window and returns every unique advertisement.
 * Discovery never selects, verifies, pairs with, or activates a sensor.
 */
interface Gs1AdvertisementScanner {
    suspend fun scan(): Gs1AdvertisementScanOutcome
}

sealed interface Gs1AdvertisementScanOutcome {
    data class Success(
        val advertisements: List<Gs1DiscoveredAdvertisement>,
    ) : Gs1AdvertisementScanOutcome {
        init {
            require(advertisements.size <= GS1_MAX_DISCOVERED_ADVERTISEMENTS)
        }
    }

    data object PermissionDenied : Gs1AdvertisementScanOutcome

    data object BluetoothUnavailable : Gs1AdvertisementScanOutcome

    data object BluetoothDisabled : Gs1AdvertisementScanOutcome

    data object LocationServicesDisabled : Gs1AdvertisementScanOutcome

    data class PlatformScanFailure(
        val errorCode: Int?,
    ) : Gs1AdvertisementScanOutcome

    data object Overflow : Gs1AdvertisementScanOutcome
}

internal object LegacyBleLocationPolicy {
    fun requiresEnabledLocationServices(sdkInt: Int): Boolean = sdkInt <= 30
}

internal class Gs1AdvertisementScanSession(
    maxUniqueAdvertisements: Int = GS1_MAX_DISCOVERED_ADVERTISEMENTS,
) {
    private val accumulator = Gs1AdvertisementAccumulator(maxUniqueAdvertisements)
    private var terminalOutcome: Gs1AdvertisementScanOutcome? = null

    @Synchronized
    fun record(
        deviceName: String?,
        bluetoothAddress: String,
    ): Gs1AdvertisementScanOutcome? {
        if (terminalOutcome != null) return null
        return when (accumulator.record(deviceName, bluetoothAddress)) {
            Gs1AdvertisementAccumulation.Recorded -> null
            Gs1AdvertisementAccumulation.InvalidAddress -> terminate(
                Gs1AdvertisementScanOutcome.PlatformScanFailure(errorCode = null),
            )
            Gs1AdvertisementAccumulation.Overflow -> terminate(
                Gs1AdvertisementScanOutcome.Overflow,
            )
        }
    }

    @Synchronized
    fun permissionDenied(): Gs1AdvertisementScanOutcome? = terminateIfOpen(
        Gs1AdvertisementScanOutcome.PermissionDenied,
    )

    @Synchronized
    fun platformScanFailed(errorCode: Int?): Gs1AdvertisementScanOutcome? = terminateIfOpen(
        Gs1AdvertisementScanOutcome.PlatformScanFailure(errorCode),
    )

    @Synchronized
    fun complete(): Gs1AdvertisementScanOutcome = terminalOutcome ?: Gs1AdvertisementScanOutcome
        .Success(accumulator.snapshot())
        .also { terminalOutcome = it }

    private fun terminate(outcome: Gs1AdvertisementScanOutcome): Gs1AdvertisementScanOutcome {
        terminalOutcome = outcome
        return outcome
    }

    private fun terminateIfOpen(
        outcome: Gs1AdvertisementScanOutcome,
    ): Gs1AdvertisementScanOutcome? = if (terminalOutcome == null) {
        terminate(outcome)
    } else {
        null
    }
}

internal sealed interface Gs1AdvertisementAccumulation {
    data object Recorded : Gs1AdvertisementAccumulation
    data object InvalidAddress : Gs1AdvertisementAccumulation
    data object Overflow : Gs1AdvertisementAccumulation
}

internal class Gs1AdvertisementAccumulator(
    private val maxUniqueAdvertisements: Int,
) {
    private val advertisementsByAddress = linkedMapOf<String, Gs1DiscoveredAdvertisement>()

    init {
        require(maxUniqueAdvertisements in 1..GS1_MAX_DISCOVERED_ADVERTISEMENTS)
    }

    fun record(
        deviceName: String?,
        bluetoothAddress: String,
    ): Gs1AdvertisementAccumulation {
        val canonicalAddress = bluetoothAddress.canonicalBluetoothAddressOrNull()
            ?: return Gs1AdvertisementAccumulation.InvalidAddress
        val latestNonBlankName = deviceName?.takeIf(String::isNotBlank)
        val current = advertisementsByAddress[canonicalAddress]
        if (current != null) {
            if (latestNonBlankName != null && latestNonBlankName != current.deviceName) {
                advertisementsByAddress[canonicalAddress] = current.copy(
                    deviceName = latestNonBlankName,
                )
            }
            return Gs1AdvertisementAccumulation.Recorded
        }
        if (advertisementsByAddress.size == maxUniqueAdvertisements) {
            return Gs1AdvertisementAccumulation.Overflow
        }
        advertisementsByAddress[canonicalAddress] = Gs1DiscoveredAdvertisement(
            deviceName = latestNonBlankName,
            bluetoothAddress = canonicalAddress,
        )
        return Gs1AdvertisementAccumulation.Recorded
    }

    fun snapshot(): List<Gs1DiscoveredAdvertisement> = advertisementsByAddress.values.toList()
}

private fun String.canonicalBluetoothAddressOrNull(): String? =
    takeIf(BLUETOOTH_ADDRESS::matches)?.uppercase()

internal const val GS1_MAX_DISCOVERED_ADVERTISEMENTS = 256
internal const val GS1_ADVERTISEMENT_SCAN_TIMEOUT_MILLIS = 15_000L

private val BLUETOOTH_ADDRESS = Regex("^(?:[0-9A-Fa-f]{2}:){5}[0-9A-Fa-f]{2}$")
