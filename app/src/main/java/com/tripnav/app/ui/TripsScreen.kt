package com.tripnav.app.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.FileUpload
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.ui.text.font.FontWeight
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
    val trips by c.db.trips().all().collectAsState(initial = emptyList())
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

    LazyColumn(Modifier.fillMaxSize().statusBarsPadding().padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item {
            Spacer(Modifier.height(16.dp))
            Text("Trips & places", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(8.dp))
            OutlinedButton(onClick = { importLauncher.launch(arrayOf("*/*")) }) {
                Icon(Icons.Filled.FileUpload, null); Text("  Ride a GPX route")
            }
        }
        if (favorites.isNotEmpty()) {
            item { Text("Favourites", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold) }
            items(favorites, key = { "f${it.id}" }) { p ->
                Card(shape = RoundedCornerShape(16.dp), onClick = { vm.choose(p); onRouteImported() }) {
                    Row(Modifier.fillMaxWidth().padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Filled.Star, null, tint = Brand)
                        Column(Modifier.weight(1f).padding(start = 12.dp)) {
                            Text(p.name, fontWeight = FontWeight.SemiBold)
                            if (p.address.isNotBlank()) Text(p.address, style = MaterialTheme.typography.bodySmall)
                        }
                        IconButton(onClick = { vm.deletePlace(p) }) { Icon(Icons.Filled.Delete, "Remove") }
                    }
                }
            }
        }
        item { Text("Recorded rides", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold) }
        if (trips.isEmpty()) item {
            Text("Rides are recorded automatically while TripNav is running.", color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        items(trips, key = { it.id }) { t ->
            Card(shape = RoundedCornerShape(16.dp)) {
                Row(Modifier.fillMaxWidth().padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(t.title, fontWeight = FontWeight.SemiBold)
                        val avg = if (t.movingSeconds > 0) t.distanceM / t.movingSeconds * 3.6 else 0.0
                        val k = if (settings.imperial) 1 / 1.609 else 1.0
                        val unit = if (settings.imperial) "mph" else "km/h"
                        Text(
                            "${NavEngine.formatDistance(t.distanceM, settings.imperial)} · ${formatDuration(t.movingSeconds.toDouble())} moving · " +
                                "avg ${(avg * k).toInt()} · max ${(t.maxSpeedKmh * k).toInt()} $unit",
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                    IconButton(onClick = { exporting = t; exportLauncher.launch(t.title.replace(Regex("[^A-Za-z0-9 _-]"), "") + ".gpx") }) {
                        Icon(Icons.Filled.Share, "Export GPX")
                    }
                    IconButton(onClick = { scope.launch { c.db.trips().delete(t.id) } }) { Icon(Icons.Filled.Delete, "Delete") }
                }
            }
        }
        item { Spacer(Modifier.height(24.dp)) }
    }
}
