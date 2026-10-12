package com.tripnav.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.material.icons.filled.Navigation
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.StarBorder
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.automirrored.filled.VolumeOff
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.filled.BluetoothConnected
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.tripnav.app.ble.PodLinkState
import com.tripnav.app.nav.ManeuverMapper
import com.tripnav.app.nav.NavEngine
import com.tripnav.app.nav.NavProgress
import com.tripnav.app.nav.NavState
import com.tripnav.app.pod.PodSource
import com.tripnav.app.search.Place
import com.tripnav.app.settings.AppSettings
import com.tripnav.app.util.LatLon
import com.tripnav.app.util.Sun
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun RideScreen(vm: RideViewModel, onOpenPod: () -> Unit) {
    val c = vm.c
    val nav by c.nav.state.collectAsState()
    val loc by c.location.location.collectAsState()
    val settings by c.settings.settings.collectAsState()
    val pod by c.pod.status.collectAsState()
    val mirror by c.mirror.state.collectAsState()
    val preview by vm.preview.collectAsState()
    val query by vm.query.collectAsState()
    val results by vm.results.collectAsState()
    val searching by vm.searching.collectAsState()
    val favorites by vm.favorites.collectAsState()
    val recents by vm.recents.collectAsState()
    val message by vm.message.collectAsState()
    val live by c.recorder.live.collectAsState()

    var follow by remember { mutableStateOf(true) }
    var searchFocused by remember { mutableStateOf(false) }
    var addingStop by remember { mutableStateOf(false) }
    val focus = remember { FocusRequester() }

    val active = nav as? NavState.Active
    val progress = active?.progress
    val night = loc?.let { Sun.isNight(it.latitude, it.longitude) } ?: false
    val installed by c.packs.installed.collectAsState()
    val here = loc?.let { LatLon(it.latitude, it.longitude) }
    val inZone = here?.let { p -> installed.any { it.zone.contains(p) } }
    val mapStyle = remember(night, inZone, settings.dataMode, installed) {
        c.data.mapStyle(night, here, STYLE_DAY, STYLE_NIGHT)
    }
    LaunchedEffect(active != null) { if (active != null) follow = true }

    val routePts = active?.route?.points ?: preview.route?.points
    val pins = (active?.route?.destinations ?: preview.stops).map { it.point }

    Box(Modifier.fillMaxSize()) {
        RideMap(
            modifier = Modifier.fillMaxSize(),
            style = mapStyle,
            location = loc,
            route = routePts,
            traveledToIndex = if (active != null && progress != null) active.route.cumDist.indexOfFirst { it > progress.alongM }.coerceAtLeast(0) else 0,
            pins = pins,
            follow = follow && preview.route == null,
            fitRoute = preview.route != null && active == null,
            onLongPress = { vm.dropPin(it) },
            onUserPan = { follow = false },
        )

        Column(Modifier.statusBarsPadding().padding(12.dp).fillMaxWidth()) {
            when {
                active != null -> ManeuverBanner(active, progress, settings, c.nav.rightHand())
                nav is NavState.Routing -> InfoCard("Calculating route…", busy = true)
                nav is NavState.Arrived -> InfoCard("You have arrived at ${(nav as NavState.Arrived).destination.name}")
                nav is NavState.Failed -> InfoCard((nav as NavState.Failed).message)
                preview.stops.isEmpty() || addingStop -> SearchBox(
                    query = query, searching = searching, focus = focus,
                    onQuery = vm::setQuery, onFocus = { searchFocused = it },
                    onClear = { vm.clearSearch(); addingStop = false },
                )
            }
            if (active == null && (searchFocused || query.isNotBlank()) && (preview.stops.isEmpty() || addingStop)) {
                Spacer(Modifier.height(8.dp))
                SearchResults(
                    results = results,
                    favorites = if (query.isBlank()) favorites.map { Place(it.name, it.address, LatLon(it.lat, it.lon)) } else emptyList(),
                    recents = if (query.isBlank()) recents.map { Place(it.name, it.address, LatLon(it.lat, it.lon)) } else emptyList(),
                    imperial = settings.imperial,
                    onPick = { vm.choose(it, asStop = addingStop); addingStop = false; searchFocused = false },
                )
            }
            message?.let { Spacer(Modifier.height(8.dp)); InfoCard(it); LaunchedEffect(it) { kotlinx.coroutines.delay(3_000); vm.consumeMessage() } }
            if (active == null) {
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    PodChip(pod.state, c.presenter.source, mirror != null && c.mirror.isFresh(), onOpenPod)
                    if (mapStyle.json != null) OfflineChip(settings.dataMode == com.tripnav.app.settings.DataMode.OFFLINE)
                }
            }
        }

        Column(
            Modifier.align(Alignment.BottomCenter).padding(12.dp).fillMaxWidth(),
            horizontalAlignment = Alignment.End,
        ) {
            if (!follow) {
                FloatingActionButton(onClick = { follow = true }, containerColor = MaterialTheme.colorScheme.surface) {
                    Icon(Icons.Filled.MyLocation, "Recenter")
                }
                Spacer(Modifier.height(12.dp))
            }
            when {
                active != null -> NavHud(progress, settings, voiceOn = settings.voiceEnabled,
                    onVoice = { c.settings.setVoice(!settings.voiceEnabled) }, onStop = vm::stopNavigation)
                preview.stops.isNotEmpty() && !addingStop -> PreviewCard(
                    preview, settings.imperial,
                    onStart = vm::startNavigation, onCancel = vm::cancelPreview,
                    onAddStop = { addingStop = true; focus.requestFocus() },
                    onRemoveStop = vm::removeStop, onRetry = vm::replan,
                    onFavorite = { s -> vm.toggleFavorite(Place(s.name, "", s.point)) },
                )
                else -> IdleHud(loc?.speed?.times(3.6) ?: 0.0, settings, live.distanceM, live.recording)
            }
        }
    }
}

@Composable
private fun SearchBox(
    query: String, searching: Boolean, focus: FocusRequester,
    onQuery: (String) -> Unit, onFocus: (Boolean) -> Unit, onClear: () -> Unit,
) {
    Surface(shape = RoundedCornerShape(28.dp), shadowElevation = 6.dp, color = MaterialTheme.colorScheme.surface) {
        TextField(
            value = query, onValueChange = onQuery,
            modifier = Modifier.fillMaxWidth().focusRequester(focus).onFocusChanged { onFocus(it.isFocused) },
            placeholder = { Text("Where to? Search or long-press the map") },
            leadingIcon = { if (searching) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp) else Icon(Icons.Filled.Search, null) },
            trailingIcon = { if (query.isNotEmpty()) IconButton(onClick = onClear) { Icon(Icons.Filled.Close, "Clear") } },
            singleLine = true,
            colors = TextFieldDefaults.colors(
                focusedContainerColor = Color.Transparent, unfocusedContainerColor = Color.Transparent,
                focusedIndicatorColor = Color.Transparent, unfocusedIndicatorColor = Color.Transparent,
            ),
        )
    }
}

@Composable
private fun SearchResults(
    results: List<Place>, favorites: List<Place>, recents: List<Place>, imperial: Boolean, onPick: (Place) -> Unit,
) {
    val rows = buildList {
        favorites.forEach { add(Triple(it, Icons.Filled.Star, true)) }
        recents.filter { r -> favorites.none { it.point == r.point } }.forEach { add(Triple(it, Icons.Filled.History, false)) }
        results.forEach { add(Triple(it, Icons.Filled.Search, false)) }
    }
    if (rows.isEmpty()) return
    Card(shape = RoundedCornerShape(20.dp), elevation = CardDefaults.cardElevation(6.dp)) {
        LazyColumn(Modifier.heightIn(max = 380.dp)) {
            items(rows) { (p, icon, _) ->
                Row(Modifier.fillMaxWidth().clickable { onPick(p) }.padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    Icon(icon, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.width(14.dp))
                    Column(Modifier.weight(1f)) {
                        Text(p.name, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        if (p.address.isNotBlank()) Text(p.address, style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    p.distanceM?.let { Text(NavEngine.formatDistance(it, imperial), style = MaterialTheme.typography.labelMedium) }
                }
                HorizontalDivider(color = MaterialTheme.colorScheme.surfaceVariant)
            }
        }
    }
}

@Composable
private fun ManeuverBanner(active: NavState.Active, p: NavProgress?, s: AppSettings, rht: Boolean) {
    Surface(shape = RoundedCornerShape(24.dp), color = Color(0xFF12161C), shadowElevation = 8.dp) {
        Column(Modifier.fillMaxWidth().padding(16.dp)) {
            if (active.rerouting || p == null) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(Modifier.size(28.dp), color = Brand)
                    Spacer(Modifier.width(16.dp))
                    Text(if (active.rerouting) "Recalculating…" else "Waiting for GPS…", color = Color.White, fontSize = 22.sp)
                }
                return@Column
            }
            val icon = ManeuverMapper.podIcon(p.upcoming, rht)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(maneuverVector(icon), null, tint = Brand, modifier = Modifier.size(72.dp))
                    roundaboutExit(icon)?.let { Text("$it", color = Color.White, fontWeight = FontWeight.Black, fontSize = 18.sp) }
                }
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f)) {
                    Text(NavEngine.formatDistance(p.distanceToManeuverM, s.imperial), color = Color.White,
                        fontSize = 38.sp, fontWeight = FontWeight.Black)
                    Text(p.upcoming.street ?: p.upcoming.instruction, color = Color(0xFFCCD3DB), fontSize = 18.sp,
                        maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
            }
            p.next?.let { n ->
                Spacer(Modifier.height(8.dp))
                Row(Modifier.clip(RoundedCornerShape(12.dp)).background(Color(0xFF1E252E)).padding(horizontal = 10.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    Text("Then", color = Color(0xFF9DA7B3), fontSize = 14.sp)
                    Spacer(Modifier.width(8.dp))
                    Icon(maneuverVector(ManeuverMapper.podIcon(n, rht)), null, tint = Color.White, modifier = Modifier.size(22.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(n.street ?: "", color = Color.White, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
    }
}

@Composable
private fun NavHud(p: NavProgress?, s: AppSettings, voiceOn: Boolean, onVoice: () -> Unit, onStop: () -> Unit) {
    Surface(shape = RoundedCornerShape(24.dp), color = MaterialTheme.colorScheme.surface, shadowElevation = 8.dp) {
        Row(Modifier.fillMaxWidth().padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
            Speedo(p?.speedKmh ?: 0.0, p?.speedLimitKmh, s)
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                if (p != null) {
                    val eta = SimpleDateFormat(if (s.clock24h) "HH:mm" else "h:mm a", Locale.getDefault()).format(Date(p.etaEpochMs))
                    Text(eta, fontSize = 26.sp, fontWeight = FontWeight.Bold)
                    Text("${formatDuration(p.remainingS)} · ${NavEngine.formatDistance(p.remainingM, s.imperial)}",
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            IconButton(onClick = onVoice) {
                Icon(if (voiceOn) Icons.AutoMirrored.Filled.VolumeUp else Icons.AutoMirrored.Filled.VolumeOff, "Voice")
            }
            FilledTonalButton(onClick = onStop, colors = androidx.compose.material3.ButtonDefaults.filledTonalButtonColors(containerColor = Danger, contentColor = Color.White)) {
                Icon(Icons.Filled.Stop, null); Spacer(Modifier.width(4.dp)); Text("End")
            }
        }
    }
}

@Composable
private fun Speedo(speedKmh: Double, limitKmh: Int?, s: AppSettings) {
    val shown = if (s.imperial) speedKmh / 1.609 else speedKmh
    val limitShown = limitKmh?.let { if (s.imperial) Math.round(it / 1.609).toInt() else it }
    val over = limitKmh != null && speedKmh > limitKmh + s.speedWarningTolerance
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text("${shown.toInt()}", fontSize = 34.sp, fontWeight = FontWeight.Black, color = if (over) Danger else MaterialTheme.colorScheme.onSurface)
            Text(if (s.imperial) "mph" else "km/h", style = MaterialTheme.typography.labelSmall)
        }
        if (limitShown != null) {
            Spacer(Modifier.width(8.dp))
            Box(Modifier.size(44.dp).clip(CircleShape).background(Color.White).border(5.dp, Danger, CircleShape),
                contentAlignment = Alignment.Center) {
                Text("$limitShown", color = Color.Black, fontWeight = FontWeight.Bold, fontSize = 16.sp)
            }
        }
    }
}

@Composable
private fun IdleHud(speedKmh: Double, s: AppSettings, tripM: Double, recording: Boolean) {
    Surface(shape = RoundedCornerShape(24.dp), color = MaterialTheme.colorScheme.surface, shadowElevation = 6.dp) {
        Row(Modifier.padding(horizontal = 18.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Speedo(speedKmh, null, s)
            if (recording) {
                Spacer(Modifier.width(18.dp))
                Column {
                    Text("Trip", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(NavEngine.formatDistance(tripM, s.imperial), fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}

@Composable
private fun PreviewCard(
    preview: PreviewState, imperial: Boolean,
    onStart: () -> Unit, onCancel: () -> Unit, onAddStop: () -> Unit, onRemoveStop: (Int) -> Unit,
    onRetry: () -> Unit, onFavorite: (com.tripnav.app.nav.Destination) -> Unit,
) {
    Card(shape = RoundedCornerShape(24.dp), elevation = CardDefaults.cardElevation(8.dp)) {
        Column(Modifier.fillMaxWidth().padding(16.dp)) {
            preview.stops.forEachIndexed { i, s ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(if (i == preview.stops.lastIndex) "🏁" else "${i + 1}", modifier = Modifier.width(28.dp), textAlign = TextAlign.Center)
                    Text(s.name.ifBlank { "Via point" }, Modifier.weight(1f), fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    if (i == preview.stops.lastIndex) IconButton(onClick = { onFavorite(s) }) { Icon(Icons.Filled.StarBorder, "Favourite") }
                    if (preview.stops.size > 1) IconButton(onClick = { onRemoveStop(i) }) { Icon(Icons.Filled.Close, "Remove") }
                }
            }
            Spacer(Modifier.height(8.dp))
            when {
                preview.planning -> Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp); Spacer(Modifier.width(10.dp)); Text("Calculating route…")
                }
                preview.error != null -> Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(preview.error, color = Danger, modifier = Modifier.weight(1f)); OutlinedButton(onClick = onRetry) { Text("Retry") }
                }
                preview.route != null -> Text(
                    "${formatDuration(preview.route.timeS)} · ${NavEngine.formatDistance(preview.route.lengthM, imperial)}",
                    fontSize = 22.sp, fontWeight = FontWeight.Bold,
                )
            }
            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = onCancel) { Text("Cancel") }
                OutlinedButton(onClick = onAddStop) { Icon(Icons.Filled.Add, null); Text("Stop") }
                Button(onClick = onStart, enabled = preview.route != null, modifier = Modifier.weight(1f)) {
                    Icon(Icons.Filled.Navigation, null); Spacer(Modifier.width(6.dp)); Text("Ride")
                }
            }
        }
    }
}

@Composable
private fun PodChip(state: PodLinkState, source: PodSource, mirroring: Boolean, onClick: () -> Unit) {
    val (label, color) = when (state) {
        PodLinkState.READY -> (if (mirroring && source == PodSource.GOOGLE_MAPS) "Pod · mirroring Google Maps" else "Pod connected") to Ok
        PodLinkState.AWAITING_PIN -> "Pod: enter PIN" to Brand
        PodLinkState.CONNECTING, PodLinkState.HANDSHAKING -> "Pod connecting…" to Brand
        PodLinkState.RECONNECTING -> "Pod reconnecting…" to Brand
        PodLinkState.IDLE -> "Pod not connected" to MaterialTheme.colorScheme.onSurfaceVariant
    }
    Surface(shape = RoundedCornerShape(50), color = MaterialTheme.colorScheme.surface, shadowElevation = 4.dp,
        modifier = Modifier.clickable(onClick = onClick)) {
        Row(Modifier.padding(horizontal = 12.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(if (state == PodLinkState.READY) Icons.Filled.BluetoothConnected else Icons.Filled.Bluetooth, null, tint = color, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(6.dp))
            Text(label, style = MaterialTheme.typography.labelLarge)
        }
    }
}

@Composable
private fun OfflineChip(strict: Boolean) {
    Surface(shape = RoundedCornerShape(50), color = MaterialTheme.colorScheme.surface, shadowElevation = 4.dp) {
        Row(Modifier.padding(horizontal = 12.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Filled.CloudOff, null, tint = Ok, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(6.dp))
            Text(if (strict) "Offline only" else "Offline map", style = MaterialTheme.typography.labelLarge)
        }
    }
}

@Composable
fun InfoCard(text: String, busy: Boolean = false) {
    Surface(shape = RoundedCornerShape(20.dp), color = MaterialTheme.colorScheme.surface, shadowElevation = 6.dp) {
        Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            if (busy) { CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp); Spacer(Modifier.width(12.dp)) }
            Text(text)
        }
    }
}

fun formatDuration(seconds: Double): String {
    val m = Math.round(seconds / 60).toInt()
    return if (m < 60) "$m min" else "${m / 60} h ${m % 60} min"
}

