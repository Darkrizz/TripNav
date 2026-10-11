package com.tripnav.app.location

import android.annotation.SuppressLint
import android.content.Context
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Looper
import com.google.android.gms.common.ConnectionResult
import com.google.android.gms.common.GoogleApiAvailability
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.tripnav.app.util.Geo
import com.tripnav.app.util.LatLon
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Single source of truth for the rider's position. Uses the fused provider when Play services exist,
 * falls back to raw GPS otherwise, and can be replaced by a simulated drive for testing at the desk.
 */
@SuppressLint("MissingPermission")
class LocationSource(private val context: Context, private val scope: CoroutineScope) {
    private val _location = MutableStateFlow<Location?>(null)
    val location: StateFlow<Location?> = _location.asStateFlow()
    private val _simulating = MutableStateFlow(false)
    val simulating: StateFlow<Boolean> = _simulating.asStateFlow()

    private var running = false
    private var simJob: Job? = null
    private val fused by lazy { LocationServices.getFusedLocationProviderClient(context) }
    private val lm by lazy { context.getSystemService(LocationManager::class.java) }
    private val hasPlay by lazy {
        GoogleApiAvailability.getInstance().isGooglePlayServicesAvailable(context) == ConnectionResult.SUCCESS
    }

    private val fusedCallback = object : LocationCallback() {
        override fun onLocationResult(result: LocationResult) { result.lastLocation?.let(::onReal) }
    }
    private val gpsListener = LocationListener { onReal(it) }

    private fun onReal(loc: Location) { if (!_simulating.value) _location.value = loc }

    private val holders = mutableSetOf<String>()

    /** Location runs while anyone (UI, ride service) holds it. */
    @Synchronized fun acquire(holder: String) { holders += holder; start() }

    @Synchronized fun release(holder: String) { holders -= holder; if (holders.isEmpty()) stop() }

    private fun start() {
        if (running) return
        running = true
        runCatching {
            if (hasPlay) {
                val req = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 1_000)
                    .setMinUpdateIntervalMillis(500).build()
                fused.requestLocationUpdates(req, fusedCallback, Looper.getMainLooper())
                fused.lastLocation.addOnSuccessListener { it?.let(::onReal) }
            } else {
                lm.requestLocationUpdates(LocationManager.GPS_PROVIDER, 1_000L, 0f, gpsListener, Looper.getMainLooper())
                lm.getLastKnownLocation(LocationManager.GPS_PROVIDER)?.let(::onReal)
            }
        }.onFailure { running = false }
    }

    private fun stop() {
        if (!running) return
        running = false
        runCatching { if (hasPlay) fused.removeLocationUpdates(fusedCallback) else lm.removeUpdates(gpsListener) }
    }

    /** Drives along [path] at [speedKmh], emitting a fix every second. */
    fun simulate(path: List<LatLon>, speedKmh: Double = 50.0) {
        stopSimulation()
        if (path.size < 2) return
        _simulating.value = true
        simJob = scope.launch {
            val step = speedKmh / 3.6
            var i = 0; var t = 0.0
            while (isActive && i < path.size - 1) {
                val a = path[i]; val b = path[i + 1]
                val segLen = Geo.distance(a, b).coerceAtLeast(0.01)
                val p = Geo.interpolate(a, b, t / segLen)
                _location.value = Location("sim").apply {
                    latitude = p.lat; longitude = p.lon; speed = step.toFloat(); accuracy = 5f
                    bearing = Geo.bearing(a, b).toFloat(); time = System.currentTimeMillis()
                    elapsedRealtimeNanos = android.os.SystemClock.elapsedRealtimeNanos()
                }
                t += step
                while (i < path.size - 1 && t >= Geo.distance(path[i], path[i + 1])) {
                    t -= Geo.distance(path[i], path[i + 1]); i++
                }
                delay(1_000)
            }
            _simulating.value = false
        }
    }

    fun stopSimulation() {
        simJob?.cancel(); simJob = null
        _simulating.value = false
    }
}
