package com.tripnav.app.ui

import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.material3.RadioButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.AutoMode
import androidx.compose.material.icons.Icons
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.BorderStroke
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

    Page("Offline maps", subtitle = "Maps, routing and search that work with no signal.", onBack = onBack) {
        SectionLabel("Internet use")
        GroupPanel {
            ModeRow(s.dataMode == DataMode.AUTO, "Automatic", "Recommended. Uses downloaded zones; internet only outside them.",
                Icons.Filled.AutoMode) { c.settings.setDataMode(DataMode.AUTO) }
            RowDivider()
            ModeRow(s.dataMode == DataMode.OFFLINE, "Offline only", "Never uses the internet. Works only inside downloaded zones.",
                Icons.Filled.CloudOff) { c.settings.setDataMode(DataMode.OFFLINE) }
            RowDivider()
            ModeRow(s.dataMode == DataMode.ONLINE, "Online only", "Always online maps and routing; downloaded zones are ignored.",
                Icons.Filled.Cloud) { c.settings.setDataMode(DataMode.ONLINE) }
        }

        SectionLabel("On this phone")
        if (installed.isEmpty()) Panel { Text("No zones downloaded yet.", color = TN.TextMid) }
        installed.forEach { iz ->
            val newer = available?.zones?.firstOrNull { it.id == iz.zone.id }?.takeIf { !c.packs.isUpToDate(it) }
            ZoneCard(iz.zone, "Installed · ${size(iz.zone.totalBytes)}" + if (newer != null) " · update available" else "",
                installed = true) {
                if (newer != null) Button(onClick = { c.packs.download(newer, s.packsUrl) }, enabled = jobs[newer.id] == null) { Text("Update") }
                OutlinedButton(onClick = { confirmDelete = iz.zone }, border = BorderStroke(1.dp, TN.OutlineStrong)) { Text("Delete", color = TN.TextHi) }
                jobs[iz.zone.id]?.let { JobView(it) { c.packs.cancel(iz.zone.id) } }
            }
        }

        SectionLabel("Download")
        when {
            s.dataMode == DataMode.OFFLINE -> Panel {
                Text("Switch to Automatic to download zones, or install them from a folder below.", color = TN.TextMid)
            }
            loadingList -> CircularProgressIndicator(color = TN.Ember)
            listError != null -> Panel(accent = TN.Stop) {
                Text("Couldn't load the zone list", style = MaterialTheme.typography.titleSmall)
                Text(listError!!, style = MaterialTheme.typography.bodySmall, color = TN.TextMid)
                TextButton(onClick = ::refresh) { Text("Retry") }
            }
        }
        val downloadable = available?.zones?.filter { z -> installed.none { it.zone.id == z.id } }.orEmpty()
        if (s.dataMode != DataMode.OFFLINE && !loadingList && listError == null && downloadable.isEmpty() && installed.isNotEmpty())
            Panel { Text("All available zones are installed.", color = TN.TextMid) }
        downloadable.forEach { z ->
            ZoneCard(z, size(z.totalBytes), installed = false) {
                val job = jobs[z.id]
                if (job == null || job is ZoneJob.Failed) Button(onClick = { c.packs.download(z, s.packsUrl) }) {
                    Icon(Icons.Filled.Download, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text(if (job == null) "Download" else "Retry")
                }
                job?.let { JobView(it) { c.packs.cancel(z.id) } }
            }
        }

        SectionLabel("Install from a folder")
        Panel {
            Text("Copy packs.json and the zone files into one folder on the phone (e.g. over USB), then pick that folder.",
                style = MaterialTheme.typography.bodyMedium, color = TN.TextMid)
            Spacer(Modifier.height(12.dp))
            OutlinedButton(onClick = { folderPicker.launch(null) }, border = BorderStroke(1.dp, TN.OutlineStrong)) {
                Icon(Icons.Filled.FolderOpen, null, Modifier.size(18.dp), tint = TN.TextHi); Spacer(Modifier.width(6.dp)); Text("Choose folder", color = TN.TextHi)
            }
            message?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = TN.Info, modifier = Modifier.padding(top = 8.dp)) }
        }

        SectionLabel("Advanced")
        Panel {
            var url by remember(s.packsUrl) { mutableStateOf(s.packsUrl) }
            OutlinedTextField(value = url, onValueChange = { url = it }, label = { Text("Zone list URL (packs.json)") },
                singleLine = true, modifier = Modifier.fillMaxWidth(), colors = tnFieldColors())
            TextButton(onClick = { c.settings.setPacksUrl(url) }) { Text("Save") }
        }
        Spacer(Modifier.height(12.dp))
        Text("Map data © OpenStreetMap contributors (ODbL).", style = MaterialTheme.typography.bodySmall, color = TN.TextLo,
            modifier = Modifier.padding(horizontal = 4.dp))
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
private fun ModeRow(selected: Boolean, title: String, detail: String, icon: ImageVector, onClick: () -> Unit) =
    ListRow(title, detail, icon = icon, iconTint = if (selected) TN.Ember else TN.TextMid, onClick = onClick) {
        RadioButton(selected = selected, onClick = onClick,
            colors = RadioButtonDefaults.colors(selectedColor = TN.Ember, unselectedColor = TN.OutlineStrong))
    }

@Composable
private fun ZoneCard(zone: Zone, subtitle: String, installed: Boolean, actions: @Composable () -> Unit) {
    Panel(Modifier.padding(bottom = 8.dp), accent = TN.Go.takeIf { installed }) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(zone.name, style = MaterialTheme.typography.titleLarge)
                if (zone.description.isNotBlank()) Text(zone.description, style = MaterialTheme.typography.bodySmall, color = TN.TextMid)
            }
            if (installed) Icon(Icons.Filled.CheckCircle, "Installed", tint = TN.Go)
        }
        Spacer(Modifier.height(6.dp))
        Text(subtitle.uppercase(), style = MaterialTheme.typography.labelSmall, color = if (installed) TN.Go else TN.TextLo)
        Spacer(Modifier.height(12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) { actions() }
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
