package com.tripnav.app.trips

import com.tripnav.app.util.LatLon
import java.io.InputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory

data class GpxData(val name: String?, val waypoints: List<Pair<String, LatLon>>, val track: List<LatLon>)

object Gpx {
    fun write(name: String, points: List<LatLon>, startedAt: Long): String {
        val iso = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }
        val sb = StringBuilder()
        sb.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n")
        sb.append("<gpx version=\"1.1\" creator=\"TripNav\" xmlns=\"http://www.topografix.com/GPX/1/1\">\n")
        sb.append("  <metadata><name>").append(escape(name)).append("</name><time>")
            .append(iso.format(Date(startedAt))).append("</time></metadata>\n")
        sb.append("  <trk><name>").append(escape(name)).append("</name><trkseg>\n")
        points.forEach { sb.append("    <trkpt lat=\"%.6f\" lon=\"%.6f\"/>\n".format(Locale.US, it.lat, it.lon)) }
        sb.append("  </trkseg></trk>\n</gpx>\n")
        return sb.toString()
    }

    /** Reads waypoints, route points and track points; whichever the file has. */
    fun read(input: InputStream): GpxData {
        val parser = XmlPullParserFactory.newInstance().newPullParser()
        parser.setInput(input, null)
        var name: String? = null
        val wpts = ArrayList<Pair<String, LatLon>>()
        val track = ArrayList<LatLon>()
        val route = ArrayList<LatLon>()
        var current: LatLon? = null
        var currentTag: String? = null
        var currentName: String? = null
        var depthInWpt = false
        while (parser.next() != XmlPullParser.END_DOCUMENT) {
            when (parser.eventType) {
                XmlPullParser.START_TAG -> {
                    val tag = parser.name
                    if (tag == "wpt" || tag == "trkpt" || tag == "rtept") {
                        val lat = parser.getAttributeValue(null, "lat")?.toDoubleOrNull()
                        val lon = parser.getAttributeValue(null, "lon")?.toDoubleOrNull()
                        current = if (lat != null && lon != null) LatLon(lat, lon) else null
                        currentTag = tag; currentName = null; depthInWpt = true
                    } else if (tag == "name") {
                        val text = runCatching { parser.nextText() }.getOrNull()?.trim()
                        if (depthInWpt) currentName = text else if (name == null) name = text
                    }
                }
                XmlPullParser.END_TAG -> {
                    val tag = parser.name
                    if (tag == currentTag) {
                        current?.let {
                            when (tag) {
                                "wpt" -> wpts += (currentName ?: "Waypoint ${wpts.size + 1}") to it
                                "trkpt" -> track += it
                                "rtept" -> route += it
                            }
                        }
                        current = null; currentTag = null; depthInWpt = false
                    }
                }
            }
        }
        return GpxData(name, wpts, track.ifEmpty { route })
    }

    /**
     * Picks up to [max] evenly spaced via points so a GPX track can be re-routed with Valhalla,
     * always keeping the first and last point.
     */
    fun sampleVias(points: List<LatLon>, max: Int = 18): List<LatLon> {
        if (points.size <= max) return points
        val step = (points.size - 1).toDouble() / (max - 1)
        return (0 until max).map { points[Math.round(it * step).toInt().coerceAtMost(points.lastIndex)] }
    }

    private fun escape(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
}
