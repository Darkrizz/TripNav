package com.tripnav.app.nav

import com.tripnav.app.util.Geo
import com.tripnav.app.util.LatLon

data class Snap(val segment: Int, val alongM: Double, val offsetM: Double, val point: LatLon)

/**
 * Tracks progress along a route. Searches a window ahead of the last match first so that
 * overlapping or parallel road sections do not make the progress jump backwards.
 */
class RouteFollower(private val route: Route) {
    private var lastSegment = 0

    fun snap(p: LatLon, headingDeg: Float?): Snap {
        val pts = route.points
        if (pts.size < 2) return Snap(0, 0.0, Geo.distance(p, pts.firstOrNull() ?: p), p)
        val windowed = best(p, headingDeg, (lastSegment - 3).coerceAtLeast(0), (lastSegment + 80).coerceAtMost(pts.size - 2))
        val result = if (windowed.offsetM <= 60) windowed else {
            val global = best(p, headingDeg, 0, pts.size - 2)
            if (global.offsetM + 10 < windowed.offsetM) global else windowed
        }
        lastSegment = result.segment
        return result
    }

    private fun best(p: LatLon, headingDeg: Float?, from: Int, to: Int): Snap {
        val pts = route.points
        var bestScore = Double.MAX_VALUE
        var best = Snap(from, route.distanceAt(from), Double.MAX_VALUE, pts[from])
        for (i in from..to) {
            val a = pts[i]; val b = pts[i + 1]
            val (t, off) = Geo.projectOnSegment(p, a, b)
            var score = off
            // Penalise segments pointing the other way (opposite carriageway, U-shaped roads).
            if (headingDeg != null && off < 50) {
                val segBearing = Geo.bearing(a, b)
                if (Geo.angleDiff(segBearing, headingDeg.toDouble()) > 100) score += 25
            }
            if (score < bestScore) {
                bestScore = score
                val along = route.cumDist[i] + (route.cumDist[i + 1] - route.cumDist[i]) * t
                best = Snap(i, along, off, Geo.interpolate(a, b, t))
            }
        }
        return best
    }
}
