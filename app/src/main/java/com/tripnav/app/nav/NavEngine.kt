package com.tripnav.app.nav

import android.location.Location
import com.tripnav.app.location.LocationSource
import com.tripnav.app.settings.SettingsRepository
import com.tripnav.app.util.Geo
import com.tripnav.app.util.LatLon
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.launch

data class NavProgress(
    val upcomingIndex: Int,
    val upcoming: Maneuver,
    val next: Maneuver?,
    val distanceToManeuverM: Double,
    val remainingM: Double,
    val remainingS: Double,
    val etaEpochMs: Long,
    val speedLimitKmh: Int?,
    val speedKmh: Double,
    val offRouteM: Double,
    val snapped: LatLon,
    val alongM: Double,
)

sealed interface NavState {
    data object Idle : NavState
    data class Routing(val destinations: List<Destination>) : NavState
    data class Active(val route: Route, val progress: NavProgress?, val rerouting: Boolean = false) : NavState
    data class Arrived(val destination: Destination) : NavState
    data class Failed(val message: String) : NavState
}

/** Things the engine wants spoken; the voice guide decides whether and how to say them. */
fun interface Announcer { fun announce(text: String, urgent: Boolean) }

/**
 * Turn-by-turn engine: follows the rider along a Valhalla route, works out the upcoming maneuver,
 * remaining distance/time and speed limit, reroutes when off course and triggers voice prompts.
 */
class NavEngine(
    private val scope: CoroutineScope,
    private val valhalla: ValhallaClient,
    private val settings: SettingsRepository,
    private val location: LocationSource,
    private val transportFor: (List<LatLon>) -> ValhallaTransport,
    private val rightHandTraffic: () -> Boolean,
) {
    private val _state = MutableStateFlow<NavState>(NavState.Idle)
    val state: StateFlow<NavState> = _state.asStateFlow()

    var announcer: Announcer? = null

    private var follower: RouteFollower? = null
    private var trackJob: Job? = null
    private var destinationsAlong: List<Pair<Destination, Double>> = emptyList()
    private var offRouteHits = 0
    private var lastRerouteMs = 0L
    private var announcedStage = mutableMapOf<Int, Int>()
    private var overSpeedSinceMs = 0L
    private var lastSpeedWarnMs = 0L

    val isActive get() = _state.value is NavState.Active

    fun rightHand() = rightHandTraffic()

    /** Calculates a route from the current position without starting guidance (for previews). */
    suspend fun plan(destinations: List<Destination>): Result<Route> = runCatching {
        val here = location.location.value ?: throw RoutingException("Waiting for GPS fix…")
        val s = settings.settings.value
        val heading = here.takeIf { it.hasBearing() && it.speed > 2f }?.bearing
        val origin = LatLon(here.latitude, here.longitude)
        val transport = transportFor(listOf(origin) + destinations.map { it.point })
        val route = valhalla.route(transport, origin, heading, destinations, s)
        route.withSpeedLimits(valhalla.speedLimits(transport, route))
    }

    fun start(route: Route) {
        stopTracking()
        setRoute(route)
        announcer?.announce(route.maneuvers.firstOrNull()?.verbalPre ?: "Starting navigation", urgent = false)
        trackJob = scope.launch { location.location.filterNotNull().collect { onLocation(it) } }
    }

    /** Plans and starts in one go (used for share/geo intents). */
    fun navigateTo(destinations: List<Destination>) {
        _state.value = NavState.Routing(destinations)
        scope.launch {
            plan(destinations).onSuccess { start(it) }.onFailure { _state.value = NavState.Failed(it.message ?: "Routing failed") }
        }
    }

    fun stop() {
        stopTracking()
        _state.value = NavState.Idle
    }

    private fun stopTracking() {
        trackJob?.cancel(); trackJob = null
        follower = null
    }

    private fun setRoute(route: Route) {
        follower = RouteFollower(route)
        val snapper = RouteFollower(route)
        destinationsAlong = route.destinations.map { it to snapper.snap(it.point, null).alongM }
        announcedStage = mutableMapOf()
        offRouteHits = 0
        _state.value = NavState.Active(route, null)
    }

    private fun onLocation(loc: Location) {
        val active = _state.value as? NavState.Active ?: return
        val route = active.route
        val f = follower ?: return
        val here = LatLon(loc.latitude, loc.longitude)
        val heading = loc.takeIf { it.hasBearing() && it.speed > 2f }?.bearing
        val snap = f.snap(here, heading)

        // --- off-route detection ---
        val tolerance = maxOf(40.0, (if (loc.hasAccuracy()) loc.accuracy.toDouble() else 10.0) * 1.5)
        offRouteHits = if (snap.offsetM > tolerance) offRouteHits + 1 else 0
        if (offRouteHits >= 3 && !active.rerouting && System.currentTimeMillis() - lastRerouteMs > 8_000) {
            reroute(active, snap.alongM)
            return
        }

        // --- progress ---
        val idx = route.maneuvers.indexOfFirst { route.distanceAt(it.beginIndex) > snap.alongM + 1.0 }
            .let { if (it < 0) route.maneuvers.lastIndex else it }
        val upcoming = route.maneuvers.getOrNull(idx) ?: return
        val distTo = (route.distanceAt(upcoming.beginIndex) - snap.alongM).coerceAtLeast(0.0)
        val remaining = (route.shapeLengthM - snap.alongM).coerceAtLeast(0.0)
        val prev = route.maneuvers.getOrNull(idx - 1)
        val partial = if (prev != null && prev.lengthM > 1) prev.timeS * (distTo / prev.lengthM).coerceIn(0.0, 1.0) else 0.0
        val remainingS = partial + route.maneuvers.drop(idx).sumOf { it.timeS }
        val next = route.maneuvers.drop(idx + 1).firstOrNull { !ManeuverMapper.isPassive(it) }
        val speedKmh = if (loc.hasSpeed()) loc.speed * 3.6 else 0.0
        val limit = route.speedLimitAt(snap.alongM)

        val progress = NavProgress(
            upcomingIndex = idx, upcoming = upcoming, next = next, distanceToManeuverM = distTo,
            remainingM = remaining, remainingS = remainingS,
            etaEpochMs = System.currentTimeMillis() + (remainingS * 1000).toLong(),
            speedLimitKmh = limit, speedKmh = speedKmh, offRouteM = snap.offsetM, snapped = snap.point, alongM = snap.alongM,
        )
        _state.value = active.copy(progress = progress)

        if (remaining < 25 || (upcoming.type in 4..6 && distTo < 20)) {
            arrive(route)
            return
        }
        announce(idx, upcoming, distTo, loc.speed.toDouble())
        checkSpeed(limit, speedKmh)
    }

    private fun reroute(active: NavState.Active, alongM: Double) {
        lastRerouteMs = System.currentTimeMillis()
        offRouteHits = 0
        val remainingDest = destinationsAlong.filter { it.second > alongM + 30 }.map { it.first }
            .ifEmpty { listOf(active.route.destinations.last()) }
        _state.value = active.copy(rerouting = true)
        announcer?.announce("Recalculating", urgent = false)
        scope.launch {
            plan(remainingDest)
                .onSuccess { if (_state.value is NavState.Active) setRoute(it) }
                .onFailure {
                    // Keep guiding on the old route; we will retry on the next off-route streak.
                    val cur = _state.value
                    if (cur is NavState.Active) _state.value = cur.copy(rerouting = false)
                }
        }
    }

    private fun arrive(route: Route) {
        stopTracking()
        val dest = route.destinations.last()
        _state.value = NavState.Arrived(dest)
        announcer?.announce("You have arrived" + if (dest.name.isNotBlank()) " at ${dest.name}" else "", urgent = true)
        scope.launch {
            delay(15_000)
            if (_state.value is NavState.Arrived) _state.value = NavState.Idle
        }
    }

    private fun announce(idx: Int, m: Maneuver, distTo: Double, speedMs: Double) {
        if (ManeuverMapper.isPassive(m) && m.type !in 4..6) return
        val v = speedMs.coerceAtLeast(8.0)
        val far = (v * 45).coerceIn(400.0, 2_000.0)
        val near = (v * 12).coerceIn(100.0, 500.0)
        val now = (v * 3).coerceIn(20.0, 60.0)
        val stage = announcedStage[idx] ?: -1
        val imperial = settings.settings.value.imperial
        val text = m.verbalPre ?: m.instruction
        when {
            distTo <= now && stage < 2 -> { announcedStage[idx] = 2; announcer?.announce(text, urgent = true) }
            distTo <= near && distTo > now * 2 && stage < 1 -> {
                announcedStage[idx] = 1
                announcer?.announce(withDistance(distTo, imperial, m.verbalAlert ?: text), urgent = false)
            }
            distTo <= far && distTo > near * 2 && stage < 0 -> {
                announcedStage[idx] = 0
                announcer?.announce(withDistance(distTo, imperial, m.verbalAlert ?: text), urgent = false)
            }
        }
    }

    private fun checkSpeed(limit: Int?, speedKmh: Double) {
        val s = settings.settings.value
        if (!s.speedWarning || limit == null) { overSpeedSinceMs = 0; return }
        val now = System.currentTimeMillis()
        if (speedKmh > limit + s.speedWarningTolerance) {
            if (overSpeedSinceMs == 0L) overSpeedSinceMs = now
            if (now - overSpeedSinceMs > 3_000 && now - lastSpeedWarnMs > 60_000) {
                lastSpeedWarnMs = now
                val shown = if (s.imperial) Math.round(limit / 1.609).toInt() else limit
                announcer?.announce("Speed limit $shown", urgent = false)
            }
        } else overSpeedSinceMs = 0
    }

    companion object {
        fun spokenDistance(m: Double, imperial: Boolean): String = if (imperial) {
            val feet = m * 3.281
            if (feet < 1000) "${(Math.round(feet / 50) * 50).coerceAtLeast(50)} feet"
            else "%.1f miles".format(m / 1609.344).replace(".0 ", " ")
        } else {
            if (m < 1000) "${(Math.round(m / 50) * 50).coerceAtLeast(50)} meters"
            else "%.1f kilometers".format(m / 1000).replace(".0 ", " ")
        }

        private fun lowerFirst(s: String) = s.replaceFirstChar { it.lowercase() }

        /** "In 300 meters, turn left…" — only for English; Valhalla's localized instructions are spoken as-is. */
        fun withDistance(m: Double, imperial: Boolean, instruction: String): String =
            if (java.util.Locale.getDefault().language == "en") "In ${spokenDistance(m, imperial)}, ${lowerFirst(instruction)}" else instruction

        fun formatDistance(m: Double, imperial: Boolean): String = if (imperial) {
            val feet = m * 3.281
            if (feet < 1000) "${(Math.round(feet / 10) * 10)} ft" else "%.1f mi".format(m / 1609.344)
        } else {
            if (m < 1000) "${(Math.round(m / 10) * 10)} m" else if (m < 10_000) "%.1f km".format(m / 1000) else "${Math.round(m / 1000)} km"
        }

        fun distanceAlong(points: List<LatLon>): Double = points.zipWithNext().sumOf { (a, b) -> Geo.distance(a, b) }
    }
}
