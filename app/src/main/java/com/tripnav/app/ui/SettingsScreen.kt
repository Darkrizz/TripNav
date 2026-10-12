package com.tripnav.app.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.tripnav.app.BuildConfig
import com.tripnav.app.settings.DataMode
import com.tripnav.app.settings.NightMode
import com.tripnav.app.settings.PodIdle
import com.tripnav.app.settings.PodSecondary
import com.tripnav.app.settings.RoutePreference

@Composable
fun SettingsScreen(vm: RideViewModel, onOpenOfflineMaps: () -> Unit) {
    val repo = vm.c.settings
    val s by repo.settings.collectAsState()
    Column(Modifier.fillMaxSize().statusBarsPadding().verticalScroll(rememberScrollState()).padding(16.dp)) {
        Text("Settings", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)

        Section("Offline")
        Row(Modifier.fillMaxWidth().clickable(onClick = onOpenOfflineMaps).padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Offline maps & internet use")
                Text(when (s.dataMode) { DataMode.AUTO -> "Automatic"; DataMode.OFFLINE -> "Offline only"; DataMode.ONLINE -> "Online only" },
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Text("›", style = MaterialTheme.typography.titleLarge)
        }

        Section("Routing")
        Choice("Route style", s.routePreference, RoutePreference.entries, {
            when (it) { RoutePreference.FASTEST -> "Fastest"; RoutePreference.AVOID_HIGHWAYS -> "Avoid motorways"; RoutePreference.CURVY -> "Curvy roads" }
        }, repo::setRoutePreference)
        Toggle("Avoid tolls", s.avoidTolls, repo::setAvoidTolls)
        Toggle("Avoid ferries", s.avoidFerries, repo::setAvoidFerries)

        Section("Pod display")
        Choice("Bottom line while navigating", s.podSecondary, PodSecondary.entries, {
            when (it) { PodSecondary.ARRIVAL_TIME -> "Arrival time"; PodSecondary.TIME_LEFT -> "Time left"; PodSecondary.DISTANCE_LEFT -> "Distance left"; PodSecondary.NEXT_DISTANCE -> "Next turn distance" }
        }, repo::setPodSecondary)
        Choice("When not navigating", s.podIdle, PodIdle.entries, { if (it == PodIdle.COMPASS) "Compass" else "Clock" }, repo::setPodIdle)
        Choice("Night mode", s.nightMode, NightMode.entries, {
            when (it) { NightMode.AUTO_SUN -> "Automatic (sunset/sunrise)"; NightMode.ALWAYS_DAY -> "Always day"; NightMode.ALWAYS_NIGHT -> "Always night" }
        }, repo::setNightMode)
        Toggle("Mirror Google Maps when TripNav isn't navigating", s.mirrorGoogleMaps, repo::setMirror)
        Toggle("Reconnect to the last pod on launch", s.autoConnect, repo::setAutoConnect)

        Section("Voice & alerts")
        Toggle("Voice guidance", s.voiceEnabled, repo::setVoice)
        Toggle("Speak street names", s.voiceStreetNames, repo::setVoiceStreets)
        Toggle("Speed-limit warning", s.speedWarning, repo::setSpeedWarning)
        if (s.speedWarning) {
            Text("Warn when ${s.speedWarningTolerance} km/h over the limit", style = MaterialTheme.typography.bodyMedium)
            Slider(value = s.speedWarningTolerance.toFloat(), onValueChange = { repo.setSpeedTolerance(it.toInt()) }, valueRange = 0f..20f, steps = 19)
        }

        Section("Units & display")
        Toggle("Imperial units (mi / mph / ft)", s.imperial, repo::setImperial)
        Toggle("24-hour clock", s.clock24h, repo::setClock24h)
        Toggle("Keep screen on while riding", s.keepScreenOn, repo::setKeepScreenOn)

        Section("Routing server")
        var url by remember(s.valhallaUrl) { mutableStateOf(s.valhallaUrl) }
        OutlinedTextField(value = url, onValueChange = { url = it }, label = { Text("Valhalla URL") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        Row {
            TextButton(onClick = { repo.setValhallaUrl(url) }) { Text("Save") }
            TextButton(onClick = { url = BuildConfig.DEFAULT_VALHALLA_URL; repo.setValhallaUrl(url) }) { Text("Reset") }
        }
        Text(
            "Map data © OpenStreetMap contributors. Maps by OpenFreeMap, routing by Valhalla, search by Photon & Nominatim.",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(8.dp))
        Text("TripNav ${BuildConfig.VERSION_NAME}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun Section(title: String) {
    Spacer(Modifier.height(20.dp))
    Text(title, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.SemiBold)
    HorizontalDivider(Modifier.padding(vertical = 6.dp))
}

@Composable
private fun Toggle(label: String, value: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().clickable { onChange(!value) }.padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(1f))
        Switch(checked = value, onCheckedChange = onChange)
    }
}

@Composable
private fun <T> Choice(label: String, value: T, options: List<T>, name: (T) -> String, onChange: (T) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Row(Modifier.fillMaxWidth().clickable { open = true }.padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(1f))
        Text(name(value), color = MaterialTheme.colorScheme.primary)
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            options.forEach { o -> DropdownMenuItem(text = { Text(name(o)) }, onClick = { onChange(o); open = false }) }
        }
    }
}
