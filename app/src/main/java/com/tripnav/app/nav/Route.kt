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
)

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
