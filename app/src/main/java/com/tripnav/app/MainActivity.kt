package com.tripnav.app

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.filled.Map
import androidx.compose.material.icons.filled.Route
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import com.tripnav.app.ble.PodLinkState
import com.tripnav.app.nav.NavState
import com.tripnav.app.service.RideService
import com.tripnav.app.ui.DebugScreen
import com.tripnav.app.ui.PodScreen
import com.tripnav.app.ui.RideScreen
import com.tripnav.app.ui.RideViewModel
import com.tripnav.app.ui.SettingsScreen
import com.tripnav.app.ui.TripNavTheme
import com.tripnav.app.ui.TripsScreen

class MainActivity : ComponentActivity() {
    private val vm: RideViewModel by viewModels()
    private val c get() = (application as TripNavApp).container

    private val permissions = buildList {
        add(Manifest.permission.ACCESS_FINE_LOCATION)
        add(Manifest.permission.ACCESS_COARSE_LOCATION)
        if (Build.VERSION.SDK_INT >= 31) { add(Manifest.permission.BLUETOOTH_SCAN); add(Manifest.permission.BLUETOOTH_CONNECT) }
        if (Build.VERSION.SDK_INT >= 33) add(Manifest.permission.POST_NOTIFICATIONS)
    }.toTypedArray()

    private val permissionLauncher = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        onPermissionsSettled()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        val missing = permissions.filter { ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED }
        if (missing.isNotEmpty()) permissionLauncher.launch(missing.toTypedArray()) else onPermissionsSettled()
        if (savedInstanceState == null) handleIntent(intent)

        setContent {
            TripNavTheme {
                var tab by rememberSaveable { mutableStateOf(0) }
                var debug by rememberSaveable { mutableStateOf(false) }
                val settings by c.settings.settings.collectAsState()
                val nav by c.nav.state.collectAsState()
                LaunchedEffect(settings.keepScreenOn, nav) {
                    val keep = settings.keepScreenOn && (nav is NavState.Active || c.pod.status.value.state == PodLinkState.READY)
                    if (keep) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                    else window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                }
                BackHandler(enabled = debug || tab != 0) { if (debug) debug = false else tab = 0 }
                if (debug) {
                    DebugScreen(vm) { debug = false }
                    return@TripNavTheme
                }
                Scaffold(
                    bottomBar = {
                        if (nav !is NavState.Active) NavigationBar {
                            listOf(
                                Triple("Ride", Icons.Filled.Map, 0), Triple("Pod", Icons.Filled.Bluetooth, 1),
                                Triple("Trips", Icons.Filled.Route, 2), Triple("Settings", Icons.Filled.Settings, 3),
                            ).forEach { (label, icon, idx) ->
                                NavigationBarItem(selected = tab == idx, onClick = { tab = idx }, icon = { Icon(icon, null) }, label = { Text(label) })
                            }
                        }
                    },
                ) { pad ->
                    Box(Modifier.fillMaxSize().padding(bottom = pad.calculateBottomPadding())) {
                        when (if (nav is NavState.Active) 0 else tab) {
                            0 -> RideScreen(vm, onOpenPod = { tab = 1 })
                            1 -> PodScreen(vm, onOpenDebug = { debug = true })
                            2 -> TripsScreen(vm, onRouteImported = { tab = 0 })
                            else -> SettingsScreen(vm)
                        }
                    }
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    override fun onStart() {
        super.onStart()
        if (hasLocation()) c.location.acquire(UI_HOLDER)
    }

    override fun onStop() {
        c.location.release(UI_HOLDER)
        super.onStop()
    }

    private fun hasLocation() =
        ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED

    private fun onPermissionsSettled() {
        if (hasLocation()) c.location.acquire(UI_HOLDER)
        lifecycleScope.launch {
            val s = c.settings.load()
            val last = s.lastPod
            if (s.autoConnect && last != null && last in s.knownPods && c.pod.status.value.state == PodLinkState.IDLE && c.pod.hasPermission()) {
                RideService.start(this@MainActivity, connectTo = last)
            }
        }
    }

    private fun handleIntent(intent: Intent?) {
        when (intent?.action) {
            Intent.ACTION_VIEW -> intent.data?.toString()?.let { if (it.startsWith("geo:")) vm.handleGeo(it) else vm.handleShared(it) }
            Intent.ACTION_SEND -> intent.getStringExtra(Intent.EXTRA_TEXT)?.let { vm.handleShared(it) }
        }
    }

    companion object { private const val UI_HOLDER = "ui" }
}
