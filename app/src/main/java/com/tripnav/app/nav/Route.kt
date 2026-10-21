package com.tripnav.app.nav

import com.tripnav.app.util.Geo
import com.tripnav.app.util.LatLon

data class Maneuver(
    val type: Int,
    val instruction: String,
    val verbalPre: String?,
    val verbalAlert: String?,
    val street: String?,
    val lengthM: Double,
    val timeS: Double,
    val beginIndex: Int,
    val endIndex: Int,
    val roundaboutExit: Int?,
    /** For a roundabout: where the exit points relative to the way in, in degrees (0 straight on, +90 right, -90 left). */
    val exitAngle: Int? = null,
)

/** Works out the real exit direction of each roundabout from the route shape, so the icon can draw it. */
object Roundabouts {
    private const val SAMPLE_M = 25.0

    fun annotate(points: List<LatLon>, maneuvers: List<Maneuver>): List<Maneuver> = maneuvers.map { m ->
        if (m.type != 26 || points.size < 2) m else m.copy(exitAngle = exitAngle(points, m.beginIndex, m.endIndex))
    }

    fun exitAngle(points: List<LatLon>, entry: Int, exit: Int): Int? {
        if (entry !in points.indices || exit !in points.indices || exit <= entry) return null
        val before = walk(points, entry, -1) ?: return null
        val after = walk(points, exit, +1) ?: return null
        val inBearing = Geo.bearing(before, points[entry])
        val outBearing = Geo.bearing(points[exit], after)
        return Math.round(((outBearing - inBearing) % 360 + 540) % 360 - 180).toInt()
    }

    /** The point about [SAMPLE_M] from [from] along the shape in direction [step], or null at a shape end. */
    private fun walk(points: List<LatLon>, from: Int, step: Int): LatLon? {
        var i = from
        var d = 0.0
        while (i + step in points.indices) {
            d += Geo.distance(points[i], points[i + step])
            i += step
            if (d >= SAMPLE_M) break
        }
        return if (i == from) null else points[i]
    }
}

/** Speed limit (km/h) valid from [fromM] to [toM] metres along the route. */
data class SpeedLimitSpan(val fromM: Double, val toM: Double, val kmh: Int)

data class Destination(val name: String, val point: LatLon)

class Route(
    val points: List<LatLon>,
    val maneuvers: List<Maneuver>,
    val lengthM: Double,
    val timeS: Double,
    val destinations: List<Destination>,
    val speedLimits: List<SpeedLimitSpan> = emptyList(),
) {
    /** Cumulative distance in metres at each shape point. */
    val cumDist: DoubleArray = DoubleArray(points.size).also { arr ->
        for (i in 1 until points.size) arr[i] = arr[i - 1] + Geo.distance(points[i - 1], points[i])
    }
    val shapeLengthM: Double get() = cumDist.lastOrNull() ?: 0.0

    fun distanceAt(index: Int): Double = cumDist[index.coerceIn(0, cumDist.size - 1)]

    fun speedLimitAt(alongM: Double): Int? = speedLimits.firstOrNull { alongM >= it.fromM && alongM < it.toM }?.kmh

    fun withSpeedLimits(spans: List<SpeedLimitSpan>) = Route(points, maneuvers, lengthM, timeS, destinations, spans)
}
