package com.tripnav.app.ble

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothGattServer
import android.bluetooth.BluetoothGattServerCallback
import android.bluetooth.BluetoothGattService
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.PowerManager
import android.os.SystemClock
import android.util.Log
import androidx.core.content.ContextCompat
import com.tripnav.app.settings.SettingsRepository
import com.tripnav.protocol.PodProtocol
import com.tripnav.protocol.PodResponse
import java.text.SimpleDateFormat
import java.util.ArrayDeque
import java.util.Calendar
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

enum class PodLinkState { IDLE, CONNECTING, HANDSHAKING, AWAITING_PIN, READY, RECONNECTING }

data class PodStatus(
    val state: PodLinkState = PodLinkState.IDLE,
    val address: String? = null,
    val firmware: String? = null,
    val serial: String? = null,
    val pinRejected: Boolean = false,
    val reconnectAttempt: Int = 0,
)

data class PodLogLine(val time: String, val dir: Char, val text: String)

/**
 * Owns the GATT link to the TripperPod: connection, PIN pairing, the serialized write queue,
 * 1 Hz keepalive and automatic reconnection. All GATT work runs on a dedicated looper thread.
 */
@SuppressLint("MissingPermission")
class PodConnection(private val context: Context, private val settings: SettingsRepository) {
    private val tag = "PodConnection"
    private val thread = HandlerThread("pod-gatt").apply { start() }
    private val handler = Handler(thread.looper)
    private val btManager = context.getSystemService(BluetoothManager::class.java)

    private val _status = MutableStateFlow(PodStatus())
    val status: StateFlow<PodStatus> = _status.asStateFlow()

    private val _log = MutableStateFlow<List<PodLogLine>>(emptyList())
    val log: StateFlow<List<PodLogLine>> = _log.asStateFlow()

    private var gatt: BluetoothGatt? = null
    private var gattServer: BluetoothGattServer? = null
    private var writeChar: BluetoothGattCharacteristic? = null
    private val queue = ArrayDeque<ByteArray>()
    private var writeBusy = false

    /** The screen the pod should be showing; re-sent by the keepalive so the pod never times out. */
    @Volatile private var screen: ByteArray = PodProtocol.navIdle()
    @Volatile private var clockMode = false
    @Volatile private var callActive = false
    /** True while the pod shows turn-by-turn guidance; a call then only flashes instead of taking over. */
    @Volatile var guiding = false
    private var callFlashUntil = 0L
    /** A short-lived alert screen (e.g. phone battery low) shown over the normal screen. */
    private var alertFrame: ByteArray? = null
    private var alertUntil = 0L
    @Volatile private var night = false
    private var keepaliveTick = 0

    private var userDisconnected = true
    private var reconnectDeadline = 0L
    private val wakeLock = context.getSystemService(PowerManager::class.java)
        .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "TripNav:PodKeepalive").apply { setReferenceCounted(false) }

    /** Invoked on the GATT thread whenever the link becomes READY. */
    var onReady: (() -> Unit)? = null

    val isReady get() = _status.value.state == PodLinkState.READY

    fun hasPermission(): Boolean = Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
        ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED

    // ---- public API (thread-safe; everything hops onto the GATT thread) ----

    fun connect(address: String) = handler.post {
        if (!hasPermission()) { log('!', "BLUETOOTH_CONNECT not granted"); return@post }
        val adapter = btManager?.adapter ?: return@post
        if (!adapter.isEnabled) { log('!', "Bluetooth is off"); return@post }
        userDisconnected = false
        reconnectDeadline = 0L
        handler.removeCallbacks(reconnectRunnable)
        _status.update { it.copy(state = PodLinkState.CONNECTING, address = address, pinRejected = false) }
        settings.setLastPod(address)
        openGatt(adapter.getRemoteDevice(address))
    }

    fun disconnect() = handler.post {
        userDisconnected = true
        handler.removeCallbacks(reconnectRunnable)
        stopKeepalive()
        if (writeChar != null) {
            enqueue(PodProtocol.closeSession())
            handler.postDelayed({ teardown(); _status.update { PodStatus(address = it.address) } }, 300)
        } else {
            teardown(); _status.update { PodStatus(address = it.address) }
        }
    }

    fun submitPin(code: String) = handler.post {
        if (_status.value.state != PodLinkState.AWAITING_PIN) return@post
        _status.update { it.copy(pinRejected = false) }
        enqueue(PodProtocol.pin(code))
        handler.postDelayed({ enqueue(timeFrame()) }, 150)
        handler.postDelayed({ enqueue(PodProtocol.pingFirmware()) }, 300)
        handler.postDelayed({ enqueue(PodProtocol.pingSerial()) }, 500)
        // Some firmwares never answer the PIN frame; the reference app treats silence as success.
        handler.postDelayed(pinTimeoutRunnable, 5_000)
    }

    fun requestPinAgain() = handler.post {
        if (writeChar != null) { enqueue(PodProtocol.showPin()); _status.update { it.copy(state = PodLinkState.AWAITING_PIN) } }
    }

    /** Sets the persistent screen (sent now and repeated by the keepalive). */
    fun show(frame: ByteArray) = handler.post {
        clockMode = false
        val f = PodProtocol.withNight(frame, night)
        if (f.contentEquals(screen) && isReady) return@post
        screen = f
        if (isReady && overlay() == null) enqueue(f)
    }

    /** Clock mode: the pod shows its own clock; we only ping every 15 s to keep the link alive. */
    fun showClock() = handler.post {
        clockMode = true
        keepaliveTick = 0
        if (isReady) { enqueue(PodProtocol.navStopped()); enqueue(timeFrame()) }
    }

    fun setNight(value: Boolean) = handler.post {
        if (night == value) return@post
        night = value
        screen = PodProtocol.withNight(screen, value)
        if (isReady && !clockMode && overlay() == null) enqueue(screen)
    }

    fun setCallActive(active: Boolean) = handler.post {
        if (callActive == active) return@post
        callActive = active
        if (active) callFlashUntil = SystemClock.uptimeMillis() + CALL_FLASH_MS
        if (!isReady) return@post
        if (active) enqueue(PodProtocol.callIcon()) else restoreScreen()
    }

    /** Puts the normal screen back after an overlay: the last frame, or the pod's own clock. */
    private fun restoreScreen() {
        if (clockMode) { enqueue(PodProtocol.navStopped()); enqueue(timeFrame()) } else enqueue(screen)
    }

    /** Shows [frame] for [durationMs], then returns to the normal screen. A call screen still takes priority. */
    fun flash(frame: ByteArray, durationMs: Long) = handler.post {
        alertFrame = PodProtocol.withNight(frame, night)
        alertUntil = SystemClock.uptimeMillis() + durationMs
        if (isReady && !showingCall()) enqueue(alertFrame!!)
        handler.postDelayed({ if (isReady && overlay() == null) restoreScreen() }, durationMs + 50)
    }

    /** Frame that currently overrides the normal screen, if any. */
    private fun overlay(): ByteArray? = when {
        showingCall() -> PodProtocol.callIcon()
        SystemClock.uptimeMillis() < alertUntil -> alertFrame
        else -> null
    }

    /**
     * The pod can't overlay a call badge on the arrow, and has no text to show a caller's name. So while guiding,
     * a call only flashes the call screen briefly and navigation carries on; otherwise the call screen stays up.
     */
    private fun showingCall() = callActive && (!guiding || SystemClock.uptimeMillis() < callFlashUntil)

    /** One-off frame (debug console, control frames). */
    fun sendRaw(frame: ByteArray) = handler.post { if (writeChar != null) enqueue(frame) }

    fun syncTime() = handler.post { if (writeChar != null) enqueue(timeFrame()) }

    fun forget(address: String) {
        settings.forgetPod(address)
        if (_status.value.address == address) disconnect()
    }

    // ---- internals ----

    private fun timeFrame(): ByteArray {
        val c = Calendar.getInstance()
        return PodProtocol.setTime(c.get(Calendar.HOUR_OF_DAY), c.get(Calendar.MINUTE), settings.settings.value.clock24h)
    }

    private fun openGatt(device: BluetoothDevice) {
        teardown()
        startGattServer()
        handler.postDelayed({
            log('i', "connectGatt ${device.address}")
            gatt = device.connectGatt(context, false, callback, BluetoothDevice.TRANSPORT_LE)
            if (gatt == null) scheduleReconnect()
        }, 200)
    }

    private fun teardown() {
        stopKeepalive()
        queue.clear(); writeBusy = false; writeChar = null
        runCatching { gatt?.disconnect() }
        runCatching { gatt?.close() }
        gatt = null
        runCatching { gattServer?.close() }
        gattServer = null
    }

    /** The reference app exposes the same service locally; the pod can write replies to it. */
    private fun startGattServer() {
        if (gattServer != null) return
        val server = btManager?.openGattServer(context, serverCallback) ?: return
        val service = BluetoothGattService(PodProtocol.SERVICE_UUID, BluetoothGattService.SERVICE_TYPE_PRIMARY)
        service.addCharacteristic(
            BluetoothGattCharacteristic(
                PodProtocol.CHAR_UUID,
                BluetoothGattCharacteristic.PROPERTY_WRITE or BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE,
                BluetoothGattCharacteristic.PERMISSION_WRITE,
            ),
        )
        server.addService(service)
        gattServer = server
    }

    private fun enqueue(frame: ByteArray) {
        queue.addLast(frame)
        pump()
    }

    private fun pump() {
        if (writeBusy) return
        val ch = writeChar ?: return
        val g = gatt ?: return
        val frame = queue.pollFirst() ?: return
        writeBusy = true
        val ok = if (Build.VERSION.SDK_INT >= 33) {
            g.writeCharacteristic(ch, frame, BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE) == BluetoothGatt.GATT_SUCCESS
        } else {
            @Suppress("DEPRECATION")
            run { ch.writeType = BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE; ch.value = frame; g.writeCharacteristic(ch) }
        }
        if (ok) log('>', PodProtocol.toHex(frame)) else { queue.addFirst(frame); log('!', "write busy, retrying") }
        handler.postDelayed({ writeBusy = false; pump() }, PodProtocol.WRITE_GAP_MS)
    }

    private val keepaliveRunnable = object : Runnable {
        override fun run() {
            if (writeChar == null) return
            if (queue.isEmpty() && !writeBusy) {
                when {
                    overlay() != null -> enqueue(overlay()!!)
                    clockMode -> if (keepaliveTick % 15 == 0) enqueue(PodProtocol.pingFirmware())
                    else -> enqueue(screen)
                }
            }
            keepaliveTick++
            handler.postDelayed(this, PodProtocol.KEEPALIVE_MS)
        }
    }

    private fun startKeepalive() {
        handler.removeCallbacks(keepaliveRunnable)
        keepaliveTick = 0
        runCatching { wakeLock.acquire(6 * 60 * 60 * 1000L) }
        handler.postDelayed(keepaliveRunnable, PodProtocol.KEEPALIVE_MS)
    }

    private fun stopKeepalive() {
        handler.removeCallbacks(keepaliveRunnable)
        runCatching { if (wakeLock.isHeld) wakeLock.release() }
    }

    private val reconnectRunnable = Runnable {
        val address = _status.value.address ?: return@Runnable
        if (userDisconnected) return@Runnable
        if (reconnectDeadline in 1 until System.currentTimeMillis()) {
            log('!', "Giving up reconnecting")
            _status.update { PodStatus(address = address) }
            return@Runnable
        }
        _status.update { it.copy(state = PodLinkState.RECONNECTING, reconnectAttempt = it.reconnectAttempt + 1) }
        val adapter = btManager?.adapter
        if (adapter == null || !adapter.isEnabled) { scheduleReconnect(); return@Runnable }
        openGatt(adapter.getRemoteDevice(address))
    }

    private fun scheduleReconnect() {
        if (userDisconnected) return
        if (reconnectDeadline == 0L) reconnectDeadline = System.currentTimeMillis() + RECONNECT_WINDOW_MS
        handler.removeCallbacks(reconnectRunnable)
        handler.postDelayed(reconnectRunnable, RECONNECT_DELAY_MS)
    }

    private fun startHandshake() {
        val address = _status.value.address ?: return
        if (address in settings.settings.value.knownPods) {
            _status.update { it.copy(state = PodLinkState.HANDSHAKING) }
            enqueue(PodProtocol.closeSession())
            handler.postDelayed({
                enqueue(timeFrame())
                handler.postDelayed({
                    enqueue(PodProtocol.pingFirmware()); enqueue(PodProtocol.pingFirmware())
                    handler.postDelayed({ becomeReady() }, 300)
                }, 150)
            }, 200)
        } else {
            enqueue(PodProtocol.showPin())
            _status.update { it.copy(state = PodLinkState.AWAITING_PIN) }
        }
    }

    private val pinTimeoutRunnable = Runnable {
        if (_status.value.state == PodLinkState.AWAITING_PIN && !_status.value.pinRejected) pinAccepted()
    }

    private fun pinAccepted() {
        handler.removeCallbacks(pinTimeoutRunnable)
        _status.value.address?.let { settings.addKnownPod(it) }
        becomeReady()
    }

    private fun becomeReady() {
        if (writeChar == null) return
        _status.update { it.copy(state = PodLinkState.READY, reconnectAttempt = 0, pinRejected = false) }
        reconnectDeadline = 0L
        log('i', "READY")
        if (clockMode) enqueue(timeFrame()) else enqueue(screen)
        startKeepalive()
        onReady?.invoke()
    }

    private fun handleIncoming(value: ByteArray) {
        if (value.isEmpty()) return
        log('<', PodProtocol.toHex(value))
        when (val r = PodResponse.parse(value)) {
            is PodResponse.PinResult -> if (_status.value.state == PodLinkState.AWAITING_PIN) {
                if (r.accepted) pinAccepted() else {
                    handler.removeCallbacks(pinTimeoutRunnable)
                    _status.update { it.copy(pinRejected = true) }
                }
            }
            is PodResponse.Firmware -> _status.update { it.copy(firmware = r.version) }
            is PodResponse.Serial -> if (r.serial.isNotBlank()) _status.update { it.copy(serial = r.serial) }
            is PodResponse.Nack -> log('!', "NACK")
            else -> Unit
        }
    }

    private val callback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) = runOnLooper {
            if (g != gatt) { runCatching { g.close() }; return@runOnLooper }
            log('i', "state=$newState status=$status")
            if (newState == BluetoothProfile.STATE_CONNECTED && status == BluetoothGatt.GATT_SUCCESS) {
                _status.update { it.copy(state = PodLinkState.HANDSHAKING) }
                g.requestConnectionPriority(BluetoothGatt.CONNECTION_PRIORITY_BALANCED)
                g.discoverServices()
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                stopKeepalive()
                writeChar = null; queue.clear(); writeBusy = false
                runCatching { g.close() }
                gatt = null
                if (userDisconnected) _status.update { PodStatus(address = it.address) }
                else { _status.update { it.copy(state = PodLinkState.RECONNECTING) }; scheduleReconnect() }
            }
        }

        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) = runOnLooper {
            val ch = g.getService(PodProtocol.SERVICE_UUID)?.getCharacteristic(PodProtocol.CHAR_UUID)
            if (status != BluetoothGatt.GATT_SUCCESS || ch == null) {
                log('!', "TripperPod service not found (status=$status)")
                g.disconnect(); return@runOnLooper
            }
            writeChar = ch
            g.setCharacteristicNotification(ch, true)
            val cccd = ch.getDescriptor(PodProtocol.CCCD_UUID) ?: ch.descriptors.firstOrNull()
            if (cccd != null) {
                if (Build.VERSION.SDK_INT >= 33) {
                    g.writeDescriptor(cccd, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)
                } else {
                    @Suppress("DEPRECATION")
                    run { cccd.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE; g.writeDescriptor(cccd) }
                }
            }
            handler.postDelayed({ startHandshake() }, 200)
        }

        override fun onCharacteristicChanged(g: BluetoothGatt, c: BluetoothGattCharacteristic, value: ByteArray) =
            runOnLooper { handleIncoming(value) }

        @Deprecated("pre-33 path")
        override fun onCharacteristicChanged(g: BluetoothGatt, c: BluetoothGattCharacteristic) {
            if (Build.VERSION.SDK_INT >= 33) return
            @Suppress("DEPRECATION") val v = c.value?.copyOf() ?: return
            runOnLooper { handleIncoming(v) }
        }
    }

    private val serverCallback = object : BluetoothGattServerCallback() {
        override fun onCharacteristicWriteRequest(
            device: BluetoothDevice, requestId: Int, characteristic: BluetoothGattCharacteristic,
            preparedWrite: Boolean, responseNeeded: Boolean, offset: Int, value: ByteArray?,
        ) {
            if (responseNeeded) runCatching { gattServer?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, offset, null) }
            val v = value?.copyOf() ?: return
            runOnLooper { handleIncoming(v) }
        }
    }

    private fun runOnLooper(block: () -> Unit) {
        if (Thread.currentThread() == thread) block() else handler.post(block)
    }

    private val timeFmt = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)

    private fun log(dir: Char, text: String) {
        Log.d(tag, "$dir $text")
        val line = PodLogLine(timeFmt.format(Date()), dir, text)
        _log.update { (it + line).takeLast(400) }
    }

    companion object {
        private const val RECONNECT_DELAY_MS = 5_000L
        private const val CALL_FLASH_MS = 3_000L
        private const val RECONNECT_WINDOW_MS = 10 * 60_000L
    }
}
