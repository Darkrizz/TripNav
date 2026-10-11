package com.tripnav.app.ble

import android.annotation.SuppressLint
import android.app.Activity
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.companion.AssociationRequest
import android.companion.BluetoothLeDeviceFilter
import android.companion.CompanionDeviceManager
import android.content.Context
import android.content.IntentSender
import android.os.Handler
import android.os.Looper
import android.os.ParcelUuid
import com.tripnav.protocol.PodProtocol
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.util.regex.Pattern

data class FoundPod(val address: String, val name: String, val rssi: Int)

/** Finds nearby pods. They advertise as `RE_DISP`; some firmwares also advertise the service UUID. */
@SuppressLint("MissingPermission")
class PodScanner(private val context: Context) {
    private val main = Handler(Looper.getMainLooper())
    private val _found = MutableStateFlow<List<FoundPod>>(emptyList())
    val found: StateFlow<List<FoundPod>> = _found.asStateFlow()
    private val _scanning = MutableStateFlow(false)
    val scanning: StateFlow<Boolean> = _scanning.asStateFlow()

    private val callback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val name = result.scanRecord?.deviceName ?: result.device.name ?: ""
            val hasService = result.scanRecord?.serviceUuids?.contains(ParcelUuid(PodProtocol.SERVICE_UUID)) == true
            if (!name.startsWith(PodProtocol.DEVICE_NAME) && !hasService) return
            val pod = FoundPod(result.device.address, name.ifBlank { "TripperPod" }, result.rssi)
            _found.update { list -> (list.filterNot { it.address == pod.address } + pod).sortedByDescending { it.rssi } }
        }

        override fun onScanFailed(errorCode: Int) { _scanning.value = false }
    }

    fun start(timeoutMs: Long = 15_000) {
        val scanner = context.getSystemService(BluetoothManager::class.java)?.adapter?.bluetoothLeScanner ?: return
        stop()
        _found.value = emptyList()
        // Unfiltered scan: filtering on name in hardware misses pods whose name is only in the scan response.
        val settings = ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build()
        runCatching { scanner.startScan(emptyList<ScanFilter>(), settings, callback) }.onSuccess {
            _scanning.value = true
            main.postDelayed(::stop, timeoutMs)
        }
    }

    fun stop() {
        main.removeCallbacksAndMessages(null)
        if (!_scanning.value) return
        runCatching { context.getSystemService(BluetoothManager::class.java)?.adapter?.bluetoothLeScanner?.stopScan(callback) }
        _scanning.value = false
    }

    /**
     * Pairs through the system Companion Device picker. Besides a nicer UX, an association lets TripNav
     * keep the link alive and restart its service from the background without OEM battery hacks.
     */
    fun associate(activity: Activity, onChooser: (IntentSender) -> Unit, onError: (String) -> Unit) {
        val cdm = activity.getSystemService(CompanionDeviceManager::class.java) ?: return onError("Companion pairing unavailable")
        val filter = BluetoothLeDeviceFilter.Builder()
            .setNamePattern(Pattern.compile("${PodProtocol.DEVICE_NAME}.*"))
            .build()
        val request = AssociationRequest.Builder().addDeviceFilter(filter).setSingleDevice(false).build()
        @Suppress("DEPRECATION")
        cdm.associate(request, object : CompanionDeviceManager.Callback() {
            @Deprecated("Deprecated in API 33")
            override fun onDeviceFound(chooserLauncher: IntentSender) = onChooser(chooserLauncher)
            override fun onAssociationPending(intentSender: IntentSender) = onChooser(intentSender)
            override fun onFailure(error: CharSequence?) = onError(error?.toString() ?: "Pairing failed")
        }, null)
    }
}
