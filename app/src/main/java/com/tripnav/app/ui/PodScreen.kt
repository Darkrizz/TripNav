package com.tripnav.app.ui

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

    Column(Modifier.fillMaxSize().statusBarsPadding().verticalScroll(rememberScrollState()).padding(16.dp)) {
        Text("TripperPod", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(12.dp))

        Card(shape = RoundedCornerShape(20.dp)) {
            Column(Modifier.fillMaxWidth().padding(16.dp)) {
                val label = when (status.state) {
                    PodLinkState.READY -> "Connected"
                    PodLinkState.AWAITING_PIN -> "Waiting for PIN"
                    PodLinkState.CONNECTING -> "Connecting…"
                    PodLinkState.HANDSHAKING -> "Starting session…"
                    PodLinkState.RECONNECTING -> "Reconnecting (attempt ${status.reconnectAttempt})…"
                    PodLinkState.IDLE -> "Not connected"
                }
                Text(label, fontSize = 22.sp, fontWeight = FontWeight.Bold, color = if (status.state == PodLinkState.READY) Ok else MaterialTheme.colorScheme.onSurface)
                status.address?.let { Text("Device $it", color = MaterialTheme.colorScheme.onSurfaceVariant) }
                status.firmware?.let { Text("Firmware $it") }
                status.serial?.let { Text("Serial $it") }
                Spacer(Modifier.height(12.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (status.state == PodLinkState.IDLE) {
                        val last = settings.lastPod
                        if (last != null) Button(onClick = { connect(last) }) { Text("Connect") }
                    } else {
                        OutlinedButton(onClick = { c.pod.disconnect() }) { Text("Disconnect") }
                    }
                    if (status.state == PodLinkState.READY) {
                        FilledTonalButton(onClick = {
                            c.pod.sendRaw(NavFrame.build(NavFrameSpec(PodIcon.TURN_RIGHT, 150, PodIcon.TURN_LEFT, SecondaryField.Distance(800), imperial = settings.imperial)))
                        }) { Text("Test arrow") }
                        FilledTonalButton(onClick = { c.pod.syncTime() }) { Text("Sync clock") }
                    }
                }
            }
        }

        Spacer(Modifier.height(16.dp))
        Text("Pair a pod", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        Text("Switch the pod on. It appears as RE_DISP.", color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = {
                c.scanner.associate(context as Activity,
                    onChooser = { cdmLauncher.launch(IntentSenderRequest.Builder(it).build()) },
                    onError = { error = it })
            }) { Icon(Icons.Filled.Link, null); Spacer(Modifier.width(6.dp)); Text("Pair (recommended)") }
            OutlinedButton(onClick = { if (scanning) c.scanner.stop() else c.scanner.start() }) {
                Icon(Icons.Filled.BluetoothSearching, null); Spacer(Modifier.width(6.dp)); Text(if (scanning) "Stop scan" else "Scan")
            }
        }
        if (scanning) { Spacer(Modifier.height(8.dp)); CircularProgressIndicator(Modifier.size(24.dp)) }
        found.forEach { p ->
            Card(Modifier.fillMaxWidth().padding(top = 8.dp).clickable { connect(p.address) }, shape = RoundedCornerShape(16.dp)) {
                Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(p.name, fontWeight = FontWeight.SemiBold)
                        Text(p.address, style = MaterialTheme.typography.bodySmall)
                    }
                    Text("${p.rssi} dBm", style = MaterialTheme.typography.labelMedium)
                }
            }
        }
        error?.let { Spacer(Modifier.height(8.dp)); Text(it, color = Danger) }

        if (settings.knownPods.isNotEmpty()) {
            Spacer(Modifier.height(20.dp))
            Text("Paired pods", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            settings.knownPods.forEach { mac ->
                Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(mac, Modifier.weight(1f))
                    TextButton(onClick = { connect(mac) }) { Text("Connect") }
                    TextButton(onClick = { c.pod.forget(mac) }) { Text("Forget", color = Danger) }
                }
            }
        }

        Spacer(Modifier.height(20.dp))
        Text("Google Maps mirroring", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        val granted = NotificationManagerCompat.getEnabledListenerPackages(context).contains(context.packageName)
        Text(
            if (granted) "Enabled. Start navigation in Google Maps and the turns appear on the pod. TripNav's own navigation always takes priority."
            else "Lets TripNav show Google Maps turns on the pod. Needs notification access; nothing leaves your phone.",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (!granted) {
            Spacer(Modifier.height(8.dp))
            OutlinedButton(onClick = { context.startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)) }) { Text("Grant notification access") }
        }

        Spacer(Modifier.height(20.dp))
        TextButton(onClick = onOpenDebug) { Text("Developer tools") }
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
