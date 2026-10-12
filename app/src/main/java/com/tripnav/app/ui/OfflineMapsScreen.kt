package com.tripnav.app.ui

import androidx.activity.compose.rememberLauncherForActivityResult
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
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.tripnav.app.offline.Zone
import com.tripnav.app.offline.ZoneJob
import com.tripnav.app.settings.DataMode
import kotlinx.coroutines.launch

@Composable
fun OfflineMapsScreen(vm: RideViewModel, onBack: () -> Unit) {
    val c = vm.c
    val s by c.settings.settings.collectAsState()
    val installed by c.packs.installed.collectAsState()
    val available by c.packs.available.collectAsState()
    val jobs by c.packs.jobs.collectAsState()
    val scope = rememberCoroutineScope()
    var listError by remember { mutableStateOf<String?>(null) }
    var loadingList by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    var confirmDelete by remember { mutableStateOf<Zone?>(null) }

    fun refresh() {
        if (s.dataMode == DataMode.OFFLINE) return
        loadingList = true
        scope.launch {
            c.packs.refreshAvailable(s.packsUrl).onFailure { listError = it.message }.onSuccess { listError = null }
            loadingList = false
        }
    }
    LaunchedEffect(s.packsUrl, s.dataMode) { refresh() }

    val folderPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        message = "Installing…"
        scope.launch {
            message = c.packs.importFolder(uri).fold({ "Installed: ${it.joinToString()}" }, { it.message ?: "Import failed" })
        }
    }

    Column(Modifier.fillMaxSize().statusBarsPadding().verticalScroll(rememberScrollState()).padding(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Offline maps", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
            TextButton(onClick = onBack) { Text("Done") }
        }

        Text("Internet use", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        ModeRow(s.dataMode == DataMode.AUTO, "Automatic (recommended)",
            "Downloaded zones for maps, routing and search; internet only outside them.") { c.settings.setDataMode(DataMode.AUTO) }
        ModeRow(s.dataMode == DataMode.OFFLINE, "Offline only",
            "Never uses the internet. Works only inside downloaded zones.") { c.settings.setDataMode(DataMode.OFFLINE) }
        ModeRow(s.dataMode == DataMode.ONLINE, "Online only",
            "Always uses online maps and routing; downloaded zones are ignored.") { c.settings.setDataMode(DataMode.ONLINE) }

        Spacer(Modifier.height(16.dp))
        Text("On this phone", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        if (installed.isEmpty()) Text("No zones downloaded yet.", color = MaterialTheme.colorScheme.onSurfaceVariant)
        installed.forEach { iz ->
            val newer = available?.zones?.firstOrNull { it.id == iz.zone.id }?.takeIf { !c.packs.isUpToDate(it) }
            ZoneCard(iz.zone, subtitle = "Installed · ${size(iz.zone.totalBytes)}" + if (newer != null) " · update available" else "") {
                if (newer != null) Button(onClick = { c.packs.download(newer, s.packsUrl) }, enabled = jobs[newer.id] == null) { Text("Update") }
                OutlinedButton(onClick = { confirmDelete = iz.zone }) { Text("Delete") }
                jobs[iz.zone.id]?.let { JobView(it) { c.packs.cancel(iz.zone.id) } }
            }
        }

        Spacer(Modifier.height(16.dp))
        Text("Download", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        when {
            s.dataMode == DataMode.OFFLINE -> Text("Switch to Automatic to download zones, or install them from a folder below.",
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            loadingList -> CircularProgressIndicator()
            listError != null -> Column {
                Text("Couldn't load the zone list: $listError", color = Danger)
                TextButton(onClick = ::refresh) { Text("Retry") }
            }
        }
        available?.zones?.filter { z -> installed.none { it.zone.id == z.id } }?.forEach { z ->
            ZoneCard(z, subtitle = size(z.totalBytes)) {
                val job = jobs[z.id]
                if (job == null || job is ZoneJob.Failed) Button(onClick = { c.packs.download(z, s.packsUrl) }) { Text(if (job == null) "Download" else "Retry") }
                job?.let { JobView(it) { c.packs.cancel(z.id) } }
            }
        }

        Spacer(Modifier.height(16.dp))
        Text("Install from a folder", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        Text("Copy packs.json and the zone files to the phone (e.g. over USB), then pick that folder.",
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        OutlinedButton(onClick = { folderPicker.launch(null) }) { Text("Choose folder") }
        message?.let { Text(it, modifier = Modifier.padding(top = 6.dp)) }

        Spacer(Modifier.height(16.dp))
        var url by remember(s.packsUrl) { mutableStateOf(s.packsUrl) }
        OutlinedTextField(value = url, onValueChange = { url = it }, label = { Text("Zone list URL (packs.json)") },
            singleLine = true, modifier = Modifier.fillMaxWidth())
        TextButton(onClick = { c.settings.setPacksUrl(url) }) { Text("Save") }
        Text("Map data © OpenStreetMap contributors (ODbL).", style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(24.dp))
    }

    confirmDelete?.let { z ->
        AlertDialog(
            onDismissRequest = { confirmDelete = null },
            title = { Text("Delete ${z.name}?") },
            text = { Text("Frees ${size(z.totalBytes)}. You can download it again later.") },
            confirmButton = { TextButton(onClick = { c.deleteZone(z.id); confirmDelete = null }) { Text("Delete", color = Danger) } },
            dismissButton = { TextButton(onClick = { confirmDelete = null }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun ModeRow(selected: Boolean, title: String, detail: String, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        RadioButton(selected = selected, onClick = onClick)
        Column {
            Text(title, fontWeight = FontWeight.SemiBold)
            Text(detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun ZoneCard(zone: Zone, subtitle: String, actions: @Composable () -> Unit) {
    Card(Modifier.fillMaxWidth().padding(top = 8.dp), shape = RoundedCornerShape(16.dp)) {
        Column(Modifier.padding(14.dp)) {
            Text(zone.name, fontWeight = FontWeight.SemiBold)
            if (zone.description.isNotBlank()) Text(zone.description, style = MaterialTheme.typography.bodySmall)
            Text(subtitle, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) { actions() }
        }
    }
}

@Composable
private fun JobView(job: ZoneJob, onCancel: () -> Unit) {
    when (job) {
        is ZoneJob.Downloading -> Column(Modifier.fillMaxWidth()) {
            LinearProgressIndicator(progress = { if (job.total > 0) job.bytes.toFloat() / job.total else 0f }, modifier = Modifier.fillMaxWidth())
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("${size(job.bytes)} of ${size(job.total)}", style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
                TextButton(onClick = onCancel) { Text("Cancel") }
            }
        }
        ZoneJob.Verifying -> Row(verticalAlignment = Alignment.CenterVertically) {
            CircularProgressIndicator(); Text("  Verifying…")
        }
        is ZoneJob.Failed -> Text(job.message, color = Danger)
    }
}

private fun size(bytes: Long): String = when {
    bytes >= 1L shl 30 -> "%.1f GB".format(bytes / (1L shl 30).toDouble())
    else -> "${bytes / (1L shl 20)} MB"
}
