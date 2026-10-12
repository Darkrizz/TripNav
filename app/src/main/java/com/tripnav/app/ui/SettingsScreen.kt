package com.tripnav.app.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BluetoothConnected
import androidx.compose.material.icons.filled.DarkMode
import androidx.compose.material.icons.filled.DirectionsBoat
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Explore
import androidx.compose.material.icons.filled.LightMode
import androidx.compose.material.icons.filled.Map
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material.icons.filled.RecordVoiceOver
import androidx.compose.material.icons.filled.Route
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Signpost
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.Straighten
import androidx.compose.material.icons.filled.Toll
import androidx.compose.material.icons.filled.ViewAgenda
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.tripnav.app.BuildConfig
import com.tripnav.app.R
import com.tripnav.app.settings.DataMode
import com.tripnav.app.settings.NightMode
import com.tripnav.app.settings.PodIdle
import com.tripnav.app.settings.PodSecondary
import com.tripnav.app.settings.RoutePreference

@Composable
fun SettingsScreen(vm: RideViewModel, onOpenOfflineMaps: () -> Unit) {
    val repo = vm.c.settings
    val s by repo.settings.collectAsState()
    Page("Settings") {
        SectionLabel("Offline")
        GroupPanel {
            ListRow(
                "Offline maps & internet use",
                when (s.dataMode) { DataMode.AUTO -> "Automatic"; DataMode.OFFLINE -> "Offline only"; DataMode.ONLINE -> "Online only" },
                icon = Icons.Filled.Download, iconTint = TN.Info, onClick = onOpenOfflineMaps,
            ) { Chevron() }
        }

        SectionLabel("Routing")
        GroupPanel {
            Choice("Route style", s.routePreference, RoutePreference.entries, {
                when (it) { RoutePreference.FASTEST -> "Fastest"; RoutePreference.AVOID_HIGHWAYS -> "Avoid motorways"; RoutePreference.CURVY -> "Curvy roads" }
            }, repo::setRoutePreference, Icons.Filled.Route)
            RowDivider()
            Toggle("Avoid tolls", s.avoidTolls, repo::setAvoidTolls, Icons.Filled.Toll)
            RowDivider()
            Toggle("Avoid ferries", s.avoidFerries, repo::setAvoidFerries, Icons.Filled.DirectionsBoat)
        }

        SectionLabel("Pod display")
        GroupPanel {
            Choice("Bottom line while navigating", s.podSecondary, PodSecondary.entries, {
                when (it) {
                    PodSecondary.ARRIVAL_TIME -> "Arrival time"; PodSecondary.TIME_LEFT -> "Time left"
                    PodSecondary.DISTANCE_LEFT -> "Distance left"; PodSecondary.NEXT_DISTANCE -> "Next turn"
                }
            }, repo::setPodSecondary, Icons.Filled.ViewAgenda)
            RowDivider()
            Choice("When not navigating", s.podIdle, PodIdle.entries, { if (it == PodIdle.COMPASS) "Compass" else "Clock" }, repo::setPodIdle, Icons.Filled.Explore)
            RowDivider()
            Choice("Night mode", s.nightMode, NightMode.entries, {
                when (it) { NightMode.AUTO_SUN -> "Sunset/sunrise"; NightMode.ALWAYS_DAY -> "Always day"; NightMode.ALWAYS_NIGHT -> "Always night" }
            }, repo::setNightMode, Icons.Filled.DarkMode)
            RowDivider()
            Toggle("Mirror Google Maps", s.mirrorGoogleMaps, repo::setMirror, Icons.Filled.Map, "When TripNav isn't navigating")
            RowDivider()
            Toggle("Reconnect on launch", s.autoConnect, repo::setAutoConnect, Icons.Filled.BluetoothConnected, "Connects to the last pod automatically")
        }

        SectionLabel("Voice & alerts")
        GroupPanel {
            Toggle("Voice guidance", s.voiceEnabled, repo::setVoice, Icons.Filled.RecordVoiceOver)
            RowDivider()
            Toggle("Speak street names", s.voiceStreetNames, repo::setVoiceStreets, Icons.Filled.Signpost)
            RowDivider()
            Toggle("Speed-camera alerts", s.cameraAlerts, repo::setCameraAlerts, Icons.Filled.PhotoCamera,
                "Spoken warning, and a countdown on the pod")
            RowDivider()
            Toggle("Speed-limit warning", s.speedWarning, repo::setSpeedWarning, Icons.Filled.Speed)
            if (s.speedWarning) {
                // Dragging updates the label live; the setting is saved once, when the thumb is released.
                var tolerance by remember(s.speedWarningTolerance) { mutableStateOf(s.speedWarningTolerance.toFloat()) }
                Column(Modifier.padding(start = 66.dp, end = 16.dp, bottom = 10.dp)) {
                    val over = if (s.imperial) "${Math.round(tolerance / 1.609)} mph" else "${tolerance.toInt()} km/h"
                    Text("Warn at $over over the limit", style = MaterialTheme.typography.bodySmall, color = TN.TextMid)
                    Slider(
                        value = tolerance, onValueChange = { tolerance = it },
                        onValueChangeFinished = { repo.setSpeedTolerance(tolerance.toInt()) },
                        valueRange = 0f..20f, steps = 19,
                        colors = SliderDefaults.colors(
                            thumbColor = TN.Ember, activeTrackColor = TN.Ember, inactiveTrackColor = TN.Surface3,
                            activeTickColor = TN.EmberHot, inactiveTickColor = TN.OutlineStrong,
                        ),
                    )
                }
            }
        }

        SectionLabel("Units & display")
        GroupPanel {
            Toggle("Imperial units", s.imperial, repo::setImperial, Icons.Filled.Straighten, "mi · mph · ft")
            RowDivider()
            Toggle("24-hour clock", s.clock24h, repo::setClock24h, Icons.Filled.Schedule)
            RowDivider()
            Toggle("Keep screen on while riding", s.keepScreenOn, repo::setKeepScreenOn, Icons.Filled.LightMode)
        }

        SectionLabel("Online routing server")
        Panel {
            var url by remember(s.valhallaUrl) { mutableStateOf(s.valhallaUrl) }
            OutlinedTextField(
                value = url, onValueChange = { url = it }, label = { Text("Valhalla URL") }, singleLine = true,
                modifier = Modifier.fillMaxWidth(), colors = tnFieldColors(),
            )
            Row(Modifier.padding(top = 6.dp)) {
                TextButton(onClick = { repo.setValhallaUrl(url) }) { Text("Save") }
                TextButton(onClick = { url = BuildConfig.DEFAULT_VALHALLA_URL; repo.setValhallaUrl(url) }) { Text("Reset", color = TN.TextMid) }
            }
        }

        Spacer(Modifier.height(28.dp))
        Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
            Image(painterResource(R.drawable.ic_launcher_foreground), null, Modifier.size(84.dp))
            Text(
                "TRIPNAV",
                style = TextStyle(brush = TN.BrassGradient, fontFamily = BarlowCondensed, fontWeight = FontWeight.ExtraBold,
                    fontSize = 22.sp, letterSpacing = 6.sp),
            )
            Text("Version ${BuildConfig.VERSION_NAME}", style = MaterialTheme.typography.labelSmall, color = TN.TextLo)
            Spacer(Modifier.height(14.dp))
            Text(
                "Map data © OpenStreetMap contributors. Maps by OpenFreeMap, routing by Valhalla, search by Photon & Nominatim. Barlow typeface under the SIL OFL.",
                style = MaterialTheme.typography.bodySmall, color = TN.TextLo, textAlign = TextAlign.Center,
                modifier = Modifier.padding(horizontal = 12.dp),
            )
        }
    }
}

@Composable
private fun Toggle(label: String, value: Boolean, onChange: (Boolean) -> Unit, icon: ImageVector, detail: String? = null) =
    ListRow(label, detail, icon = icon, onClick = { onChange(!value) }) { TNSwitch(value, onChange) }

@Composable
private fun <T> Choice(label: String, value: T, options: List<T>, name: (T) -> String, onChange: (T) -> Unit, icon: ImageVector) {
    var open by remember { mutableStateOf(false) }
    Box {
        ListRow(label, icon = icon, onClick = { open = true }) {
            Text(name(value), style = MaterialTheme.typography.labelLarge, color = TN.Ember)
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }, modifier = Modifier.background(TN.Surface2)) {
            options.forEach { o ->
                DropdownMenuItem(
                    text = { Text(name(o), color = if (o == value) TN.Ember else TN.TextHi) },
                    onClick = { onChange(o); open = false },
                )
            }
        }
    }
}

@Composable
fun tnFieldColors() = OutlinedTextFieldDefaults.colors(
    focusedBorderColor = TN.Ember, unfocusedBorderColor = TN.OutlineStrong,
    focusedLabelColor = TN.Ember, unfocusedLabelColor = TN.TextMid, cursorColor = TN.Ember,
    focusedTextColor = TN.TextHi, unfocusedTextColor = TN.TextHi,
    focusedContainerColor = TN.Surface2, unfocusedContainerColor = TN.Surface2,
)
