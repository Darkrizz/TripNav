package com.tripnav.app.ui

import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.BluetoothConnected
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.IconButton
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.background
import androidx.compose.foundation.BorderStroke
import androidx.lifecycle.compose.LifecycleResumeEffect
import android.app.Activity
import android.bluetooth.BluetoothDevice
import android.bluetooth.le.ScanResult
import android.companion.AssociationInfo
import android.companion.CompanionDeviceManager
import android.content.Intent
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BluetoothSearching
import androidx.compose.material.icons.filled.Link
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.app.NotificationManagerCompat
import com.tripnav.app.ble.PodLinkState
import com.tripnav.app.service.RideService
import com.tripnav.protocol.NavFrame
import com.tripnav.protocol.NavFrameSpec
import com.tripnav.protocol.PodIcon
import com.tripnav.protocol.SecondaryField

@Composable
fun PodScreen(vm: RideViewModel, onOpenDebug: () -> Unit) {
    val c = vm.c
    val context = LocalContext.current
    val status by c.pod.status.collectAsState()
    val found by c.scanner.found.collectAsState()
    val scanning by c.scanner.scanning.collectAsState()
    val settings by c.settings.settings.collectAsState()
    var error by remember { mutableStateOf<String?>(null) }

    fun connect(address: String) {
        c.scanner.stop()
        RideService.start(context, connectTo = address)
    }

    val cdmLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { res ->
        if (res.resultCode != Activity.RESULT_OK) return@rememberLauncherForActivityResult
        val data = res.data ?: return@rememberLauncherForActivityResult
        val fromAssociation = if (Build.VERSION.SDK_INT >= 33) {
            data.getParcelableExtra(CompanionDeviceManager.EXTRA_ASSOCIATION, AssociationInfo::class.java)
                ?.deviceMacAddress?.toString()?.uppercase()
        } else null
        @Suppress("DEPRECATION")
        val address = fromAssociation ?: when (val d = data.getParcelableExtra<android.os.Parcelable>(CompanionDeviceManager.EXTRA_DEVICE)) {
            is BluetoothDevice -> d.address
            is ScanResult -> d.device.address
            else -> null
        }
        address?.let(::connect) ?: run { error = "Pairing returned no device" }
    }

    // Re-checked on resume so the status updates after returning from the system settings page.
    var granted by remember { mutableStateOf(false) }
    LifecycleResumeEffect(Unit) {
        granted = NotificationManagerCompat.getEnabledListenerPackages(context).contains(context.packageName)
        onPauseOrDispose { }
    }
    val mirrored by c.mirror.state.collectAsState()
    val mirrorRaw by c.mirror.lastRaw.collectAsState()

    Page("TripperPod", action = {
        IconButton(onClick = onOpenDebug) { Icon(Icons.Filled.Tune, "Developer tools", tint = TN.TextMid) }
    }) {
        val (label, accent) = when (status.state) {
            PodLinkState.READY -> "Connected" to TN.Go
            PodLinkState.AWAITING_PIN -> "Enter PIN" to TN.Warn
            PodLinkState.CONNECTING -> "Connecting" to TN.Warn
            PodLinkState.HANDSHAKING -> "Starting session" to TN.Warn
            PodLinkState.RECONNECTING -> "Reconnecting" to TN.Warn
            PodLinkState.IDLE -> "Not connected" to TN.TextLo
        }
        Spacer(Modifier.height(8.dp))
        Panel(accent = accent.takeIf { status.state != PodLinkState.IDLE }, padding = 20.dp) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(52.dp).clip(RoundedCornerShape(16.dp)).background(accent.copy(alpha = 0.14f)), contentAlignment = Alignment.Center) {
                    if (status.state in listOf(PodLinkState.CONNECTING, PodLinkState.HANDSHAKING, PodLinkState.RECONNECTING))
                        CircularProgressIndicator(Modifier.size(26.dp), color = accent, strokeWidth = 3.dp)
                    else Icon(if (status.state == PodLinkState.READY) Icons.Filled.BluetoothConnected else Icons.Filled.Bluetooth,
                        null, tint = accent, modifier = Modifier.size(28.dp))
                }
                Spacer(Modifier.width(16.dp))
                Column(Modifier.weight(1f)) {
                    Text(label.uppercase(), style = MaterialTheme.typography.headlineSmall, color = if (status.state == PodLinkState.READY) TN.Go else TN.TextHi)
                    Text(
                        when {
                            status.state == PodLinkState.RECONNECTING -> "Attempt ${status.reconnectAttempt} · ${status.address ?: ""}"
                            status.address != null -> status.address!!
                            else -> "Switch the pod on, then pair it below"
                        },
                        style = MaterialTheme.typography.bodySmall, color = TN.TextMid,
                    )
                }
            }
            if (status.firmware != null || status.serial != null) {
                Spacer(Modifier.height(16.dp))
                Row {
                    status.firmware?.let { Stat(it, "Firmware", Modifier.weight(1f)) }
                    status.serial?.let { Stat(it, "Serial", Modifier.weight(1f)) }
                }
            }
            Spacer(Modifier.height(16.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                if (status.state == PodLinkState.IDLE) {
                    settings.lastPod?.let { last -> Button(onClick = { connect(last) }) { Text("Connect") } }
                } else {
                    OutlinedButton(onClick = { c.pod.disconnect() }, border = BorderStroke(1.dp, TN.OutlineStrong)) { Text("Disconnect", color = TN.TextHi) }
                }
                if (status.state == PodLinkState.READY) {
                    FilledTonalButton(onClick = {
                        c.pod.sendRaw(NavFrame.build(NavFrameSpec(PodIcon.TURN_RIGHT, 150, PodIcon.TURN_LEFT, SecondaryField.Distance(800), imperial = settings.imperial)))
                    }) { Text("Test arrow") }
                    FilledTonalButton(onClick = { c.pod.syncTime() }) { Text("Sync clock") }
                }
            }
        }

        SectionLabel("Pair a pod")
        Panel {
            Text("Switch the pod on. It shows up as RE_DISP.", style = MaterialTheme.typography.bodyMedium, color = TN.TextMid)
            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = {
                    c.scanner.associate(context as Activity,
                        onChooser = { cdmLauncher.launch(IntentSenderRequest.Builder(it).build()) },
                        onError = { error = it })
                }) { Icon(Icons.Filled.Link, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text("Pair") }
                OutlinedButton(onClick = { if (scanning) c.scanner.stop() else c.scanner.start() }, border = BorderStroke(1.dp, TN.OutlineStrong)) {
                    if (scanning) CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp, color = TN.Info)
                    else Icon(Icons.Filled.BluetoothSearching, null, Modifier.size(18.dp), tint = TN.TextHi)
                    Spacer(Modifier.width(6.dp)); Text(if (scanning) "Stop scan" else "Scan", color = TN.TextHi)
                }
            }
            error?.let { Spacer(Modifier.height(8.dp)); Text(it, color = TN.Stop, style = MaterialTheme.typography.bodySmall) }
        }
        if (found.isNotEmpty()) {
            Spacer(Modifier.height(8.dp))
            GroupPanel {
                found.forEachIndexed { i, p ->
                    if (i > 0) RowDivider()
                    ListRow(p.name, p.address, icon = Icons.Filled.Bluetooth, iconTint = TN.Info, onClick = { connect(p.address) }) {
                        Text("${p.rssi} dBm", style = MaterialTheme.typography.labelMedium, color = TN.TextMid)
                    }
                }
            }
        }

        if (settings.knownPods.isNotEmpty()) {
            SectionLabel("Paired pods")
            GroupPanel {
                settings.knownPods.forEachIndexed { i, mac ->
                    if (i > 0) RowDivider()
                    ListRow("RE_DISP", mac + if (mac == settings.lastPod) " · last used" else "", icon = Icons.Filled.Memory) {
                        TextButton(onClick = { connect(mac) }) { Text("Connect") }
                        TextButton(onClick = { c.pod.forget(mac) }) { Text("Forget", color = TN.Stop) }
                    }
                }
            }
        }

        SectionLabel("Google Maps mirroring")
        Panel(accent = TN.Go.takeIf { mirrored != null }) {
            Text(
                if (granted) "Start navigation in Google Maps and its turns appear on the pod. TripNav's own navigation always takes priority."
                else "Show Google Maps turns on the pod. Needs notification access; nothing leaves your phone.",
                style = MaterialTheme.typography.bodyMedium, color = TN.TextMid,
            )
            Spacer(Modifier.height(12.dp))
            if (granted) {
                Pill(
                    mirrored?.let { m -> if (m.distanceM > 0) "${m.instruction} · ${m.distanceM} m" else m.instruction }
                        ?: if (mirrorRaw != null) "Notification seen but not understood" else "Waiting for Google Maps…",
                    color = when { mirrored != null -> TN.Go; mirrorRaw != null -> TN.Warn; else -> TN.TextLo },
                )
            } else {
                Button(onClick = { context.startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)) }) { Text("Grant notification access") }
                Spacer(Modifier.height(8.dp))
                Text(
                    "Switch greyed out? Android Settings › Apps › TripNav › ⋮ › Allow restricted settings, then try again.",
                    style = MaterialTheme.typography.bodySmall, color = TN.TextLo,
                )
            }
        }
    }

    if (status.state == PodLinkState.AWAITING_PIN) PinDialog(
        rejected = status.pinRejected,
        onSubmit = { c.pod.submitPin(it) },
        onResend = { c.pod.requestPinAgain() },
        onCancel = { c.pod.disconnect() },
    )
}

@Composable
private fun PinDialog(rejected: Boolean, onSubmit: (String) -> Unit, onResend: () -> Unit, onCancel: () -> Unit) {
    var pin by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = {},
        title = { Text("Enter pod PIN") },
        text = {
            Column {
                Text("Type the 6-digit code shown on the pod's screen.")
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(
                    value = pin, onValueChange = { v -> pin = v.filter { it.isDigit() }.take(6) },
                    singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                    textStyle = MaterialTheme.typography.headlineMedium.copy(textAlign = TextAlign.Center, letterSpacing = 8.sp),
                    isError = rejected,
                )
                if (rejected) Text("Wrong PIN, try again.", color = Danger)
            }
        },
        confirmButton = { Button(onClick = { onSubmit(pin) }, enabled = pin.length == 6) { Text("Pair") } },
        dismissButton = {
            Row {
                TextButton(onClick = onResend) { Text("Show PIN again") }
                TextButton(onClick = onCancel) { Text("Cancel") }
            }
        },
    )
}
