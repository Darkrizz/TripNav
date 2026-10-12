package com.tripnav.app.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.tripnav.protocol.NavFrame
import com.tripnav.protocol.NavFrameSpec
import com.tripnav.protocol.PodIcon
import com.tripnav.protocol.PodProtocol
import com.tripnav.protocol.SecondaryField

/** Raw frame console, icon tester, route simulator and the Google Maps icon trainer. */
@Composable
fun DebugScreen(vm: RideViewModel, onBack: () -> Unit) {
    val c = vm.c
    val log by c.pod.log.collectAsState()
    val mirror by c.mirror.state.collectAsState()
    val mirrorBitmap by c.mirror.lastBitmap.collectAsState()
    val mirrorRaw by c.mirror.lastRaw.collectAsState()
    val simulating by c.location.simulating.collectAsState()
    var hex by remember { mutableStateOf("") }
    var iconIdx by remember { mutableIntStateOf(0) }
    val icons = remember { PodIcon.names.entries.toList() }
    val listState = rememberLazyListState()
    LaunchedEffect(log.size) { if (log.isNotEmpty()) listState.scrollToItem(log.lastIndex) }

    Page("Developer tools", onBack = onBack, scroll = false) {

        // Icon tester: step through every icon ID to verify how the pod renders it.
        val (id, name) = icons[iconIdx]
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            OutlinedButton(onClick = { iconIdx = (iconIdx - 1 + icons.size) % icons.size }) { Text("◀") }
            Text("$id · $name", Modifier.weight(1f))
            OutlinedButton(onClick = { iconIdx = (iconIdx + 1) % icons.size }) { Text("▶") }
            Button(onClick = { c.pod.sendRaw(NavFrame.build(NavFrameSpec(id, 120, PodIcon.STRAIGHT, SecondaryField.Distance(1500)))) }) { Text("Send") }
        }

        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            OutlinedButton(onClick = { c.pod.sendRaw(PodProtocol.pingFirmware()) }) { Text("FW") }
            OutlinedButton(onClick = { c.pod.sendRaw(PodProtocol.pingSerial()) }) { Text("Serial") }
            OutlinedButton(onClick = { c.pod.syncTime() }) { Text("Time") }
            OutlinedButton(onClick = { c.pod.sendRaw(PodProtocol.stopNav()) }) { Text("Stop") }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            if (simulating) OutlinedButton(onClick = { c.location.stopSimulation() }) { Text("Stop simulation") }
            else OutlinedButton(onClick = { vm.simulateCurrentRoute(60.0) }) { Text("Simulate route @60") }
        }

        OutlinedTextField(
            value = hex, onValueChange = { hex = it }, label = { Text("Raw hex (20 bytes; CRC auto-filled if 18)") },
            singleLine = true, modifier = Modifier.fillMaxWidth(), colors = tnFieldColors(),
        )
        Row {
            TextButton(onClick = {
                runCatching {
                    var b = PodProtocol.hex(hex)
                    if (b.size == 18) b = PodProtocol.seal(b + ByteArray(2))
                    c.pod.sendRaw(b)
                }
            }) { Text("Send raw") }
        }

        mirrorRaw?.let { Text("Maps: $it", style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace, maxLines = 3) }
        // Teach TripNav an unknown Google Maps arrow.
        mirror?.let { m ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                mirrorBitmap?.let { Image(it.asImageBitmap(), null, Modifier.size(48.dp).background(Color.Black)) }
                Spacer(Modifier.size(8.dp))
                Column(Modifier.weight(1f)) {
                    Text(m.instruction, maxLines = 1)
                    Text("→ ${PodIcon.names[m.icon] ?: m.icon} ${if (m.iconKnown) "(learned)" else "(from text)"}", style = MaterialTheme.typography.bodySmall)
                }
                var open by remember { mutableStateOf(false) }
                TextButton(onClick = { open = true }, enabled = m.iconHash != null) { Text("Teach") }
                DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
                    icons.forEach { (iid, n) ->
                        DropdownMenuItem(text = { Text("$iid · $n") }, onClick = { m.iconHash?.let { h -> c.mirror.teach(h, iid) }; open = false })
                    }
                }
            }
        }

        Spacer(Modifier.height(6.dp))
        LazyColumn(Modifier.fillMaxWidth().weight(1f).clip(RoundedCornerShape(14.dp)).background(Color(0xFF05070A)).padding(8.dp), state = listState) {
            items(log) { l ->
                val color = when (l.dir) { '>' -> TN.Info; '<' -> TN.Go; '!' -> TN.Stop; else -> TN.TextMid }
                Text("${l.time} ${l.dir} ${l.text}", color = color, fontFamily = FontFamily.Monospace, fontSize = 11.sp)
            }
        }
    }
}
