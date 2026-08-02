package com.sladkaya.sensor.sibionics

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.bluetooth.le.BluetoothLeScanner
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.content.pm.PackageManager
import android.location.LocationManager
import android.os.Build
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.atomic.AtomicBoolean

/** Android BLE implementation. Each call owns one fixed, bounded scan window. */
class AndroidGs1AdvertisementScanner(context: Context) : Gs1AdvertisementScanner {
    private val appContext = context.applicationContext
    private val scanMutex = Mutex()

    override suspend fun scan(): Gs1AdvertisementScanOutcome = scanMutex.withLock {
        if (!hasRequiredPermissions()) {
            return@withLock Gs1AdvertisementScanOutcome.PermissionDenied
        }
        when (readLegacyLocationState()) {
            LegacyLocationState.EnabledOrNotRequired -> Unit
            LegacyLocationState.Disabled -> {
                return@withLock Gs1AdvertisementScanOutcome.LocationServicesDisabled
            }
            LegacyLocationState.PermissionDenied -> {
                return@withLock Gs1AdvertisementScanOutcome.PermissionDenied
            }
            LegacyLocationState.PlatformFailure -> {
                return@withLock Gs1AdvertisementScanOutcome.PlatformScanFailure(errorCode = null)
            }
        }
        val manager = appContext.getSystemService(BluetoothManager::class.java)
            ?: return@withLock Gs1AdvertisementScanOutcome.BluetoothUnavailable
        val adapter = manager.adapter
            ?: return@withLock Gs1AdvertisementScanOutcome.BluetoothUnavailable
        when (val adapterState = readAdapterState(adapter)) {
            AdapterState.Enabled -> Unit
            AdapterState.Disabled -> {
                return@withLock Gs1AdvertisementScanOutcome.BluetoothDisabled
            }
            AdapterState.PermissionDenied -> {
                return@withLock Gs1AdvertisementScanOutcome.PermissionDenied
            }
            AdapterState.PlatformFailure -> {
                return@withLock Gs1AdvertisementScanOutcome.PlatformScanFailure(errorCode = null)
            }
        }
        val scanner = try {
            adapter.bluetoothLeScanner
        } catch (_: SecurityException) {
            return@withLock Gs1AdvertisementScanOutcome.PermissionDenied
        } catch (_: RuntimeException) {
            return@withLock Gs1AdvertisementScanOutcome.PlatformScanFailure(errorCode = null)
        } ?: return@withLock when (readAdapterState(adapter)) {
            AdapterState.Disabled -> Gs1AdvertisementScanOutcome.BluetoothDisabled
            AdapterState.PermissionDenied -> Gs1AdvertisementScanOutcome.PermissionDenied
            AdapterState.PlatformFailure -> {
                Gs1AdvertisementScanOutcome.PlatformScanFailure(errorCode = null)
            }
            AdapterState.Enabled -> Gs1AdvertisementScanOutcome.BluetoothUnavailable
        }

        val outcome = scanWindow(scanner)
        if (outcome !is Gs1AdvertisementScanOutcome.Success) {
            return@withLock outcome
        }
        if (!hasRequiredPermissions()) {
            return@withLock Gs1AdvertisementScanOutcome.PermissionDenied
        }
        when (readAdapterState(adapter)) {
            AdapterState.Enabled -> outcome
            AdapterState.Disabled -> Gs1AdvertisementScanOutcome.BluetoothDisabled
            AdapterState.PermissionDenied -> Gs1AdvertisementScanOutcome.PermissionDenied
            AdapterState.PlatformFailure -> {
                Gs1AdvertisementScanOutcome.PlatformScanFailure(errorCode = null)
            }
        }
    }

    @SuppressLint("MissingPermission")
    private suspend fun scanWindow(scanner: BluetoothLeScanner): Gs1AdvertisementScanOutcome {
        val session = Gs1AdvertisementScanSession()
        var callback: ScanCallback? = null
        return try {
            withTimeoutOrNull(GS1_ADVERTISEMENT_SCAN_TIMEOUT_MILLIS) {
                suspendCancellableCoroutine { continuation ->
                    val completionClaimed = AtomicBoolean(false)
                    val ownedCallback = callback(session, continuation, completionClaimed)
                    callback = ownedCallback
                    continuation.invokeOnCancellation {
                        completionClaimed.set(true)
                        stopScanSafely(scanner, ownedCallback)
                    }
                    try {
                        scanner.startScan(
                            emptyList<ScanFilter>(),
                            SCAN_SETTINGS,
                            ownedCallback,
                        )
                    } catch (_: SecurityException) {
                        continuation.resumeOnce(
                            session.permissionDenied(),
                            completionClaimed,
                        )
                    } catch (_: RuntimeException) {
                        continuation.resumeOnce(
                            session.platformScanFailed(errorCode = null),
                            completionClaimed,
                        )
                    }
                }
            } ?: session.complete()
        } finally {
            callback?.let { stopScanSafely(scanner, it) }
        }
    }

    private fun callback(
        session: Gs1AdvertisementScanSession,
        continuation: CancellableContinuation<Gs1AdvertisementScanOutcome>,
        completionClaimed: AtomicBoolean,
    ): ScanCallback = object : ScanCallback() {
        @SuppressLint("MissingPermission")
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            record(result)
        }

        @SuppressLint("MissingPermission")
        override fun onBatchScanResults(results: MutableList<ScanResult>) {
            for (result in results) {
                record(result)
                if (completionClaimed.get()) return
            }
        }

        override fun onScanFailed(errorCode: Int) {
            continuation.resumeOnce(session.platformScanFailed(errorCode), completionClaimed)
        }

        @SuppressLint("MissingPermission")
        private fun record(result: ScanResult) {
            val address = try {
                result.device.address
            } catch (_: SecurityException) {
                continuation.resumeOnce(
                    session.permissionDenied(),
                    completionClaimed,
                )
                return
            } catch (_: RuntimeException) {
                continuation.resumeOnce(
                    session.platformScanFailed(errorCode = null),
                    completionClaimed,
                )
                return
            }
            continuation.resumeOnce(
                session.record(
                    deviceName = result.scanRecord?.deviceName,
                    bluetoothAddress = address,
                ),
                completionClaimed,
            )
        }
    }

    private fun hasRequiredPermissions(): Boolean {
        val permissions = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            arrayOf(
                Manifest.permission.BLUETOOTH_SCAN,
                Manifest.permission.BLUETOOTH_CONNECT,
            )
        } else {
            arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
        }
        return permissions.all { permission ->
            ContextCompat.checkSelfPermission(appContext, permission) ==
                PackageManager.PERMISSION_GRANTED
        }
    }

    private fun readLegacyLocationState(): LegacyLocationState {
        if (!LegacyBleLocationPolicy.requiresEnabledLocationServices(Build.VERSION.SDK_INT)) {
            return LegacyLocationState.EnabledOrNotRequired
        }
        val manager = appContext.getSystemService(LocationManager::class.java)
            ?: return LegacyLocationState.PlatformFailure
        return try {
            val enabled = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                manager.isLocationEnabled
            } else {
                manager.isProviderEnabled(LocationManager.GPS_PROVIDER) ||
                    manager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)
            }
            if (enabled) {
                LegacyLocationState.EnabledOrNotRequired
            } else {
                LegacyLocationState.Disabled
            }
        } catch (_: SecurityException) {
            LegacyLocationState.PermissionDenied
        } catch (_: RuntimeException) {
            LegacyLocationState.PlatformFailure
        }
    }

    @SuppressLint("MissingPermission")
    private fun readAdapterState(adapter: BluetoothAdapter): AdapterState = try {
        if (adapter.isEnabled) AdapterState.Enabled else AdapterState.Disabled
    } catch (_: SecurityException) {
        AdapterState.PermissionDenied
    } catch (_: RuntimeException) {
        AdapterState.PlatformFailure
    }

    @SuppressLint("MissingPermission")
    private fun stopScanSafely(scanner: BluetoothLeScanner, callback: ScanCallback) {
        try {
            scanner.stopScan(callback)
        } catch (_: RuntimeException) {
            // Cancellation and the primary scan outcome must remain authoritative.
        }
    }

    private enum class AdapterState {
        Enabled,
        Disabled,
        PermissionDenied,
        PlatformFailure,
    }

    private enum class LegacyLocationState {
        EnabledOrNotRequired,
        Disabled,
        PermissionDenied,
        PlatformFailure,
    }

    private companion object {
        val SCAN_SETTINGS: ScanSettings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .setReportDelay(0L)
            .build()
    }
}

private fun CancellableContinuation<Gs1AdvertisementScanOutcome>.resumeOnce(
    outcome: Gs1AdvertisementScanOutcome?,
    completionClaimed: AtomicBoolean,
) {
    if (outcome == null || !completionClaimed.compareAndSet(false, true)) return
    resume(outcome) { _, _, _ -> }
}
