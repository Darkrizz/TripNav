package com.tripnav.app.nav

import com.tripnav.app.util.Geo
import com.tripnav.app.util.LatLon
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/** Places fixed speed cameras on a route, as distances along it. */
object CameraMatcher {
    /**
     * Distances along [route] (metres, ascending) of every camera within [maxOffsetM] of the route line.
     * Cameras near several parts of the route (e.g. an out-and-back) are reported once per pass.
     */
    fun along(route: Route, cameras: List<LatLon>, maxOffsetM: Double = 30.0): DoubleArray {
        val pts = route.points
        if (pts.size < 2 || cameras.isEmpty()) return DoubleArray(0)
        val pad = maxOffsetM / 111_000.0 * 2
        val out = ArrayList<Double>()
        for (cam in cameras) {
            var lastHit = Double.NEGATIVE_INFINITY
            for (i in 0 until pts.size - 1) {
                val a = pts[i]; val b = pts[i + 1]
                if (cam.lat < minOf(a.lat, b.lat) - pad || cam.lat > maxOf(a.lat, b.lat) + pad) continue
                val padLon = pad / kotlin.math.cos(Math.toRadians(cam.lat))
                if (cam.lon < minOf(a.lon, b.lon) - padLon || cam.lon > maxOf(a.lon, b.lon) + padLon) continue
                val (t, off) = Geo.projectOnSegment(cam, a, b)
                if (off > maxOffsetM) continue
                val d = route.cumDist[i] + t * (route.cumDist[i + 1] - route.cumDist[i])
                if (d - lastHit > 200) { out += d; lastHit = d }
            }
        }
        return out.sorted().toDoubleArray()
    }

    /** Distance from [alongM] to the next camera ahead, if one is within [rangeM]. */
    fun nextAhead(cams: DoubleArray, alongM: Double, rangeM: Double = 1_000.0): Double? =
        cams.firstOrNull { it >= alongM - 5 }?.let { it - alongM }?.takeIf { it <= rangeM }?.coerceAtLeast(0.0)
}

/**
 * Watches the active route: loads speed cameras for it (offline pack first, then online), and warns once per
 * camera by voice. The pod reads [nextCameraM] to show its camera countdown.
 */
class SpeedCameraWatch(
    private val scope: CoroutineScope,
    private val nav: NavEngine,
    private val source: suspend (minLat: Double, minLon: Double, maxLat: Double, maxLon: Double) -> List<LatLon>,
    private val enabled: () -> Boolean,
    private val announce: (String) -> Unit,
) {
    @Volatile private var route: Route? = null
    @Volatile private var cams = DoubleArray(0)
    private var warned = HashSet<Int>()
    private var job: Job? = null

    fun start() {
        scope.launch {
            nav.state.collect { s ->
                val active = s as? NavState.Active
                if (active?.route !== route) load(active?.route)
                val p = active?.progress ?: return@collect
                val idx = cams.indexOfFirst { it >= p.alongM - 5 }
                if (idx >= 0 && cams[idx] - p.alongM <= WARN_M && warned.add(idx) && enabled()) announce("Speed camera ahead")
            }
        }
    }

    /** Metres to the next camera on the route within 1 km, or null. */
    fun nextCameraM(alongM: Double): Double? = CameraMatcher.nextAhead(cams, alongM)

    private fun load(r: Route?) {
        route = r
        cams = DoubleArray(0)
        warned = HashSet()
        job?.cancel()
        if (r == null || r.points.size < 2) return
        job = scope.launch {
            val lats = r.points.map { it.lat }; val lons = r.points.map { it.lon }
            val found = runCatching { source(lats.min() - 0.01, lons.min() - 0.01, lats.max() + 0.01, lons.max() + 0.01) }.getOrDefault(emptyList())
            if (route === r) cams = CameraMatcher.along(r, found)
        }
    }

    companion object { const val WARN_M = 500.0 }
}
