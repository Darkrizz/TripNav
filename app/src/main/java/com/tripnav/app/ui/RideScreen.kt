package com.tripnav.app.ui

import androidx.compose.material.icons.filled.DarkMode
import androidx.compose.material.icons.filled.LightMode
import androidx.compose.material.icons.filled.BrightnessAuto
import com.tripnav.app.settings.NightMode
import androidx.compose.material3.TextButton
import androidx.compose.material.icons.filled.AddLocationAlt
import androidx.compose.material.icons.filled.Flag
import androidx.compose.material.icons.filled.Place
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.BorderStroke
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
    val night = when (settings.mapTheme) {
        NightMode.ALWAYS_DAY -> false
        NightMode.ALWAYS_NIGHT -> true
        NightMode.AUTO_SUN -> loc?.let { Sun.isNight(it.latitude, it.longitude) } ?: false
    }
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
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    MapThemeButton(settings.mapTheme) { c.settings.setMapTheme(it) }
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
                FloatingActionButton(onClick = { follow = true }, containerColor = TN.Surface2, contentColor = TN.Ember, shape = CircleShape) {
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
    Glass(shape = RoundedCornerShape(20.dp)) {
        TextField(
            value = query, onValueChange = onQuery,
            modifier = Modifier.fillMaxWidth().focusRequester(focus).onFocusChanged { onFocus(it.isFocused) },
            placeholder = { Text("Where to?", style = MaterialTheme.typography.titleMedium, color = TN.TextMid) },
            leadingIcon = {
                if (searching) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp, color = TN.Ember)
                else Icon(Icons.Filled.Search, null, tint = TN.Ember)
            },
            trailingIcon = { if (query.isNotEmpty()) IconButton(onClick = onClear) { Icon(Icons.Filled.Close, "Clear", tint = TN.TextMid) } },
            singleLine = true,
            textStyle = MaterialTheme.typography.titleMedium,
            colors = TextFieldDefaults.colors(
                focusedContainerColor = Color.Transparent, unfocusedContainerColor = Color.Transparent,
                focusedIndicatorColor = Color.Transparent, unfocusedIndicatorColor = Color.Transparent,
                focusedTextColor = TN.TextHi, unfocusedTextColor = TN.TextHi, cursorColor = TN.Ember,
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
        results.forEach { add(Triple(it, Icons.Filled.Place, false)) }
    }
    if (rows.isEmpty()) return
    Glass(shape = RoundedCornerShape(20.dp)) {
        LazyColumn(Modifier.heightIn(max = 400.dp).padding(vertical = 4.dp)) {
            items(rows) { (p, icon, fav) ->
                Row(Modifier.fillMaxWidth().clickable { onPick(p) }.padding(horizontal = 14.dp, vertical = 11.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(36.dp).clip(RoundedCornerShape(10.dp)).background(TN.Surface2), contentAlignment = Alignment.Center) {
                        Icon(icon, null, tint = if (fav) TN.Ember else TN.TextMid, modifier = Modifier.size(20.dp))
                    }
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(p.name, style = MaterialTheme.typography.titleSmall, color = TN.TextHi, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        if (p.address.isNotBlank()) Text(p.address, style = MaterialTheme.typography.bodySmall,
                            color = TN.TextMid, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    p.distanceM?.let { Text(NavEngine.formatDistance(it, imperial), style = MaterialTheme.typography.labelMedium, color = TN.TextMid) }
                }
            }
        }
    }
}

/** Turn card: ember tile with the arrow, huge condensed distance, street name, and the following maneuver. */
@Composable
private fun ManeuverBanner(active: NavState.Active, p: NavProgress?, s: AppSettings, rht: Boolean) {
    Surface(shape = RoundedCornerShape(26.dp), color = TN.Surface1, contentColor = TN.TextHi, shadowElevation = 12.dp,
        border = BorderStroke(1.dp, TN.Outline)) {
        Column {
            if (active.rerouting || p == null) {
                Row(Modifier.padding(20.dp), verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(Modifier.size(28.dp), color = TN.Ember, strokeWidth = 3.dp)
                    Spacer(Modifier.width(16.dp))
                    Text((if (active.rerouting) "Recalculating" else "Waiting for GPS").uppercase(), style = MaterialTheme.typography.headlineSmall)
                }
                return@Column
            }
            val icon = ManeuverMapper.podIcon(p.upcoming, rht)
            Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(92.dp).clip(RoundedCornerShape(20.dp)).background(TN.EmberGradient), contentAlignment = Alignment.Center) {
                    Icon(maneuverVector(icon), null, tint = Color.White, modifier = Modifier.size(70.dp))
                    roundaboutExit(icon)?.let {
                        Text("$it", color = TN.Ember, style = MaterialTheme.typography.titleMedium,
                            modifier = Modifier.clip(CircleShape).background(Color.White).padding(horizontal = 7.dp))
                    }
                }
                Spacer(Modifier.width(16.dp))
                Column(Modifier.weight(1f)) {
                    Text(NavEngine.formatDistance(p.distanceToManeuverM, s.imperial), style = MaterialTheme.typography.displayMedium, color = TN.TextHi, maxLines = 1)
                    Text(p.upcoming.street ?: p.upcoming.instruction, style = MaterialTheme.typography.titleLarge, color = TN.TextMid,
                        maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
            }
            p.next?.let { n ->
                Row(Modifier.fillMaxWidth().background(TN.Surface2).padding(horizontal = 18.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    Text("THEN", style = MaterialTheme.typography.labelSmall, color = TN.TextLo)
                    Spacer(Modifier.width(10.dp))
                    Icon(maneuverVector(ManeuverMapper.podIcon(n, rht)), null, tint = TN.EmberHot, modifier = Modifier.size(24.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(n.street ?: n.instruction, style = MaterialTheme.typography.bodyLarge, color = TN.TextHi, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
    }
}

@Composable
private fun NavHud(p: NavProgress?, s: AppSettings, voiceOn: Boolean, onVoice: () -> Unit, onStop: () -> Unit) {
    Glass {
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Speedo(p?.speedKmh ?: 0.0, p?.speedLimitKmh, s)
            Box(Modifier.padding(horizontal = 14.dp).width(1.dp).height(44.dp).background(TN.Outline))
            Column(Modifier.weight(1f)) {
                if (p != null) {
                    val eta = SimpleDateFormat(if (s.clock24h) "HH:mm" else "h:mm a", Locale.getDefault()).format(Date(p.etaEpochMs))
                    Text(eta, style = MaterialTheme.typography.headlineMedium, color = TN.Go)
                    Text("${formatDuration(p.remainingS)} · ${NavEngine.formatDistance(p.remainingM, s.imperial)}",
                        style = MaterialTheme.typography.bodyMedium, color = TN.TextMid)
                }
            }
            IconButton(onClick = onVoice, modifier = Modifier.clip(CircleShape).background(TN.Surface2)) {
                Icon(if (voiceOn) Icons.AutoMirrored.Filled.VolumeUp else Icons.AutoMirrored.Filled.VolumeOff, "Voice",
                    tint = if (voiceOn) TN.TextHi else TN.TextLo)
            }
            Spacer(Modifier.width(8.dp))
            IconButton(onClick = onStop, modifier = Modifier.size(48.dp).clip(CircleShape).background(TN.Stop)) {
                Icon(Icons.Filled.Close, "End navigation", tint = Color.White)
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
        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.width(IntrinsicSize.Max)) {
            Text("${shown.toInt()}", style = MaterialTheme.typography.displaySmall, color = if (over) TN.Stop else TN.TextHi)
            Text(if (s.imperial) "MPH" else "KM/H", style = MaterialTheme.typography.labelSmall, color = TN.TextLo)
        }
        if (limitShown != null) {
            Spacer(Modifier.width(10.dp))
            // Standard red-ring speed-limit sign; kept white so it reads like the real thing.
            Box(Modifier.size(46.dp).clip(CircleShape).background(Color.White).border(5.dp, TN.Stop, CircleShape),
                contentAlignment = Alignment.Center) {
                Text("$limitShown", color = Color(0xFF111111), style = MaterialTheme.typography.titleMedium.copy(fontFamily = BarlowCondensed, fontWeight = FontWeight.ExtraBold))
            }
        }
    }
}

@Composable
private fun IdleHud(speedKmh: Double, s: AppSettings, tripM: Double, recording: Boolean) {
    Glass {
        Row(Modifier.padding(horizontal = 18.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Speedo(speedKmh, null, s)
            if (recording) {
                Box(Modifier.padding(horizontal = 16.dp).width(1.dp).height(40.dp).background(TN.Outline))
                Column {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(7.dp).clip(CircleShape).background(TN.Stop))
                        Spacer(Modifier.width(6.dp))
                        Text("TRIP", style = MaterialTheme.typography.labelSmall, color = TN.TextLo)
                    }
                    Text(NavEngine.formatDistance(tripM, s.imperial), style = MaterialTheme.typography.headlineSmall)
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
    Glass(shape = RoundedCornerShape(26.dp)) {
        Column(Modifier.fillMaxWidth().padding(16.dp)) {
            preview.stops.forEachIndexed { i, s ->
                val last = i == preview.stops.lastIndex
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(28.dp).clip(CircleShape).background(if (last) TN.Ember else TN.Surface3), contentAlignment = Alignment.Center) {
                        if (last) Icon(Icons.Filled.Flag, null, tint = Color.White, modifier = Modifier.size(16.dp))
                        else Text("${i + 1}", style = MaterialTheme.typography.labelMedium, color = TN.TextHi)
                    }
                    Spacer(Modifier.width(12.dp))
                    Text(s.name.ifBlank { "Via point" }, Modifier.weight(1f), style = MaterialTheme.typography.titleMedium,
                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                    if (last) IconButton(onClick = { onFavorite(s) }) { Icon(Icons.Filled.StarBorder, "Favourite", tint = TN.TextMid) }
                    if (preview.stops.size > 1) IconButton(onClick = { onRemoveStop(i) }) { Icon(Icons.Filled.Close, "Remove", tint = TN.TextLo) }
                }
            }
            Spacer(Modifier.height(10.dp))
            when {
                preview.planning -> Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp, color = TN.Ember)
                    Spacer(Modifier.width(10.dp)); Text("Calculating route…", color = TN.TextMid)
                }
                preview.error != null -> Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(preview.error, color = TN.Stop, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                    TextButton(onClick = onRetry) { Text("Retry") }
                }
                preview.route != null -> Row(verticalAlignment = Alignment.Bottom) {
                    Text(formatDuration(preview.route.timeS), style = MaterialTheme.typography.displaySmall, color = TN.Go)
                    Spacer(Modifier.width(12.dp))
                    Text(NavEngine.formatDistance(preview.route.lengthM, imperial), style = MaterialTheme.typography.titleLarge,
                        color = TN.TextMid, modifier = Modifier.padding(bottom = 6.dp))
                }
            }
            Spacer(Modifier.height(14.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onCancel, modifier = Modifier.size(52.dp).clip(RoundedCornerShape(16.dp)).background(TN.Surface2)) {
                    Icon(Icons.Filled.Close, "Cancel", tint = TN.TextHi)
                }
                IconButton(onClick = onAddStop, modifier = Modifier.size(52.dp).clip(RoundedCornerShape(16.dp)).background(TN.Surface2)) {
                    Icon(Icons.Filled.AddLocationAlt, "Add stop", tint = TN.TextHi)
                }
                Button(onClick = onStart, enabled = preview.route != null, modifier = Modifier.weight(1f).height(52.dp),
                    shape = RoundedCornerShape(16.dp)) {
                    Icon(Icons.Filled.Navigation, null); Spacer(Modifier.width(8.dp))
                    Text("RIDE", style = MaterialTheme.typography.titleLarge.copy(fontFamily = BarlowCondensed, fontWeight = FontWeight.ExtraBold, letterSpacing = 1.5.sp))
                }
            }
        }
    }
}

@Composable
private fun PodChip(state: PodLinkState, source: PodSource, mirroring: Boolean, onClick: () -> Unit) {
    val (label, color) = when (state) {
        PodLinkState.READY -> (if (mirroring && source == PodSource.GOOGLE_MAPS) "Pod · mirroring Maps" else "Pod connected") to TN.Go
        PodLinkState.AWAITING_PIN -> "Pod: enter PIN" to TN.Warn
        PodLinkState.CONNECTING, PodLinkState.HANDSHAKING -> "Pod connecting…" to TN.Warn
        PodLinkState.RECONNECTING -> "Pod reconnecting…" to TN.Warn
        PodLinkState.IDLE -> "Pod not connected" to TN.TextLo
    }
    Pill(label, color, if (state == PodLinkState.READY) Icons.Filled.BluetoothConnected else Icons.Filled.Bluetooth, onClick = onClick)
}

/** Cycles the map style Auto (sunset/sunrise) → Day → Night. */
@Composable
private fun MapThemeButton(mode: NightMode, onChange: (NightMode) -> Unit) {
    val (icon, label, next) = when (mode) {
        NightMode.AUTO_SUN -> Triple(Icons.Filled.BrightnessAuto, "Map theme: automatic", NightMode.ALWAYS_DAY)
        NightMode.ALWAYS_DAY -> Triple(Icons.Filled.LightMode, "Map theme: day", NightMode.ALWAYS_NIGHT)
        NightMode.ALWAYS_NIGHT -> Triple(Icons.Filled.DarkMode, "Map theme: night", NightMode.AUTO_SUN)
    }
    Box(Modifier.size(36.dp).clip(CircleShape).background(TN.Glass).border(1.dp, TN.OutlineStrong, CircleShape)
        .clickable { onChange(next) }, contentAlignment = Alignment.Center) {
        Icon(icon, label, tint = if (mode == NightMode.AUTO_SUN) TN.TextHi else TN.Ember, modifier = Modifier.size(19.dp))
    }
}

@Composable
private fun OfflineChip(strict: Boolean) = Pill(if (strict) "Offline only" else "Offline map", TN.Info, Icons.Filled.CloudOff)

@Composable
fun InfoCard(text: String, busy: Boolean = false) {
    Glass(shape = RoundedCornerShape(20.dp)) {
        Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            if (busy) { CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp, color = TN.Ember); Spacer(Modifier.width(12.dp)) }
            Text(text, style = MaterialTheme.typography.bodyLarge)
        }
    }
}

fun formatDuration(seconds: Double): String {
    val m = Math.round(seconds / 60).toInt()
    return if (m < 60) "$m min" else "${m / 60} h ${m % 60} min"
}
