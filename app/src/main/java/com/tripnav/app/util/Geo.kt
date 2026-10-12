package com.tripnav.app.util

import kotlin.math.PI
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

data class LatLon(val lat: Double, val lon: Double)

object Geo {
    private const val R = 6_371_008.8

    fun distance(a: LatLon, b: LatLon): Double = distance(a.lat, a.lon, b.lat, b.lon)

    fun distance(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)
        val h = sin(dLat / 2).let { it * it } +
            cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) * sin(dLon / 2).let { it * it }
        return 2 * R * asin(sqrt(h.coerceIn(0.0, 1.0)))
    }

    /** Initial bearing from [a] to [b], degrees 0..360. */
    fun bearing(a: LatLon, b: LatLon): Double {
        val p1 = Math.toRadians(a.lat); val p2 = Math.toRadians(b.lat)
        val dl = Math.toRadians(b.lon - a.lon)
        val y = sin(dl) * cos(p2)
        val x = cos(p1) * sin(p2) - sin(p1) * cos(p2) * cos(dl)
        return (Math.toDegrees(atan2(y, x)) + 360.0) % 360.0
    }

    fun angleDiff(a: Double, b: Double): Double {
        val d = ((a - b) % 360 + 540) % 360 - 180
        return kotlin.math.abs(d)
    }

    /**
     * Projects point [p] onto segment [a]-[b] using a local equirectangular approximation
     * (accurate to well under a metre for segment lengths found in routes).
     * Returns the clamped fraction along the segment and the perpendicular distance in metres.
     */
    fun projectOnSegment(p: LatLon, a: LatLon, b: LatLon): Pair<Double, Double> {
        val kx = cos(Math.toRadians(p.lat)) * R * PI / 180
        val ky = R * PI / 180
        val ax = (a.lon - p.lon) * kx; val ay = (a.lat - p.lat) * ky
        val bx = (b.lon - p.lon) * kx; val by = (b.lat - p.lat) * ky
        val dx = bx - ax; val dy = by - ay
        val len2 = dx * dx + dy * dy
        val t = if (len2 == 0.0) 0.0 else (-(ax * dx + ay * dy) / len2).coerceIn(0.0, 1.0)
        val cx = ax + t * dx; val cy = ay + t * dy
        return t to sqrt(cx * cx + cy * cy)
    }

    fun interpolate(a: LatLon, b: LatLon, t: Double) = LatLon(a.lat + (b.lat - a.lat) * t, a.lon + (b.lon - a.lon) * t)
}

/** Decodes Valhalla/OSRM encoded polylines (precision 6 for Valhalla). */
object Polyline {
    fun decode(encoded: String, precision: Int = 6): List<LatLon> {
        val factor = Math.pow(10.0, precision.toDouble())
        val out = ArrayList<LatLon>(encoded.length / 4)
        var index = 0; var lat = 0L; var lon = 0L
        while (index < encoded.length) {
            var result = 0L; var shift = 0; var b: Int
            do { b = encoded[index++].code - 63; result = result or ((b and 0x1f).toLong() shl shift); shift += 5 } while (b >= 0x20)
            lat += if (result and 1L != 0L) (result shr 1).inv() else result shr 1
            result = 0; shift = 0
            do { b = encoded[index++].code - 63; result = result or ((b and 0x1f).toLong() shl shift); shift += 5 } while (b >= 0x20)
            lon += if (result and 1L != 0L) (result shr 1).inv() else result shr 1
            out += LatLon(lat / factor, lon / factor)
        }
        return out
    }

    fun encode(points: List<LatLon>, precision: Int = 6): String {
        val factor = Math.pow(10.0, precision.toDouble())
        val sb = StringBuilder()
        var pLat = 0L; var pLon = 0L
        fun put(v: Long) {
            var x = if (v < 0) (v shl 1).inv() else v shl 1
            while (x >= 0x20) { sb.append(((0x20 or (x and 0x1f).toInt()) + 63).toChar()); x = x shr 5 }
            sb.append((x + 63).toInt().toChar())
        }
        for (p in points) {
            val lat = Math.round(p.lat * factor); val lon = Math.round(p.lon * factor)
            put(lat - pLat); put(lon - pLon); pLat = lat; pLon = lon
        }
        return sb.toString()
    }
}

/** NOAA-style sunrise/sunset, good to a couple of minutes — used for automatic night mode. */
object Sun {
    /** Returns true if the sun is below the horizon (civil twilight excluded) at [epochMs] for this position. */
    fun isNight(lat: Double, lon: Double, epochMs: Long = System.currentTimeMillis()): Boolean {
        val days = epochMs / 86_400_000.0 + 2440587.5 - 2451545.0
        // Use the solar day whose noon is nearest to now. (Flooring the UTC-based day count picked
        // yesterday's sun for anywhere east of Greenwich before UTC noon, e.g. India before 17:30 IST.)
        val n = kotlin.math.round(days + lon / 360.0)
        val jStar = n - lon / 360.0
        val m = (357.5291 + 0.98560028 * jStar) % 360
        val mr = Math.toRadians(m)
        val c = 1.9148 * sin(mr) + 0.02 * sin(2 * mr) + 0.0003 * sin(3 * mr)
        val lambda = Math.toRadians((m + c + 180 + 102.9372) % 360)
        val jTransit = 2451545.0 + jStar + 0.0053 * sin(mr) - 0.0069 * sin(2 * lambda)
        val decl = asin(sin(lambda) * sin(Math.toRadians(23.44)))
        val latR = Math.toRadians(lat)
        val cosW = (sin(Math.toRadians(-0.833)) - sin(latR) * sin(decl)) / (cos(latR) * cos(decl))
        if (cosW > 1) return true // polar night
        if (cosW < -1) return false // midnight sun
        val w = Math.toDegrees(kotlin.math.acos(cosW))
        val rise = jTransit - w / 360.0
        val set = jTransit + w / 360.0
        val now = epochMs / 86_400_000.0 + 2440587.5
        return now < rise || now > set
    }
}
