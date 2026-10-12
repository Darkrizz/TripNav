package com.tripnav.app.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.FileUpload
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.tripnav.app.data.TripEntity
import com.tripnav.app.nav.NavEngine
import com.tripnav.app.trips.Gpx
import com.tripnav.app.util.Polyline
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun TripsScreen(vm: RideViewModel, onRouteImported: () -> Unit) {
    val c = vm.c
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val tripsFlow = remember { c.db.trips().all() }
    val trips by tripsFlow.collectAsState(initial = emptyList())
    val favorites by vm.favorites.collectAsState()
    val settings by c.settings.settings.collectAsState()
    var exporting by remember { mutableStateOf<TripEntity?>(null) }

    val exportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/gpx+xml")) { uri ->
        val trip = exporting ?: return@rememberLauncherForActivityResult
        exporting = null
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch(Dispatchers.IO) {
            val gpx = Gpx.write(trip.title, Polyline.decode(trip.track, 5), trip.startedAt)
            context.contentResolver.openOutputStream(uri)?.use { it.write(gpx.toByteArray()) }
        }
    }
    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            val data = withContext(Dispatchers.IO) {
                runCatching { context.contentResolver.openInputStream(uri)?.use { Gpx.read(it) } }.getOrNull()
            }
            if (data != null) { vm.routeGpx(data); onRouteImported() }
        }
    }

    val k = if (settings.imperial) 1 / 1.609 else 1.0
    val unit = if (settings.imperial) "mph" else "km/h"
    val totalM = trips.sumOf { it.distanceM }

    Page("Trips", scroll = false) {
        LazyColumn(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            item {
                Spacer(Modifier.height(8.dp))
                Panel(padding = 20.dp) {
                    Row {
                        Stat("${trips.size}", "Rides", Modifier.weight(1f))
                        Stat(NavEngine.formatDistance(totalM, settings.imperial), "Total", Modifier.weight(1f), color = TN.Ember)
                        Stat(formatDuration(trips.sumOf { it.movingSeconds }.toDouble()), "Moving", Modifier.weight(1.2f))
                    }
                }
                Spacer(Modifier.height(4.dp))
                Button(onClick = { importLauncher.launch(arrayOf("*/*")) }, modifier = Modifier.fillMaxWidth().height(52.dp)) {
                    Icon(Icons.Filled.FileUpload, null); Spacer(Modifier.width(8.dp)); Text("Ride a GPX route")
                }
            }
            if (favorites.isNotEmpty()) {
                item { SectionLabel("Favourites") }
                item {
                    GroupPanel {
                        favorites.forEachIndexed { i, p ->
                            if (i > 0) RowDivider()
                            ListRow(p.name, p.address.ifBlank { null }, icon = Icons.Filled.Star, iconTint = TN.Ember,
                                onClick = { vm.choose(p); onRouteImported() }) {
                                IconButton(onClick = { vm.deletePlace(p) }) { Icon(Icons.Filled.Delete, "Remove", tint = TN.TextLo) }
                            }
                        }
                    }
                }
            }
            item { SectionLabel("Recorded rides") }
            if (trips.isEmpty()) item {
                Panel {
                    Text("No rides yet", style = MaterialTheme.typography.titleMedium)
                    Text("Rides are recorded automatically while TripNav is running.", style = MaterialTheme.typography.bodyMedium, color = TN.TextMid)
                }
            }
            items(trips, key = { it.id }) { t ->
                val avg = if (t.movingSeconds > 0) t.distanceM / t.movingSeconds * 3.6 else 0.0
                Panel {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(t.title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                        IconButton(onClick = { exporting = t; exportLauncher.launch(t.title.replace(Regex("[^A-Za-z0-9 _-]"), "") + ".gpx") }) {
                            Icon(Icons.Filled.Share, "Export GPX", tint = TN.TextMid)
                        }
                        IconButton(onClick = { scope.launch { c.db.trips().delete(t.id) } }) { Icon(Icons.Filled.Delete, "Delete", tint = TN.TextLo) }
                    }
                    Spacer(Modifier.height(6.dp))
                    Row {
                        Stat(NavEngine.formatDistance(t.distanceM, settings.imperial), "Distance", Modifier.weight(1f), color = TN.Ember)
                        Stat(formatDuration(t.movingSeconds.toDouble()), "Moving", Modifier.weight(1f))
                        Stat("${(avg * k).toInt()}", "Avg $unit", Modifier.weight(0.8f))
                        Stat("${(t.maxSpeedKmh * k).toInt()}", "Max $unit", Modifier.weight(0.8f))
                    }
                }
            }
        }
    }
}
