package com.tripnav.app.offline

import android.database.sqlite.SQLiteDatabase
import com.tripnav.app.search.Place
import com.tripnav.app.util.Geo
import com.tripnav.app.util.LatLon
import kotlin.math.cos

/** Place search and reverse geocoding against the zone search databases built by tools/packbuilder. */
class OfflineSearch {
    private val open = HashMap<String, SQLiteDatabase>()

    @Synchronized
    private fun db(zone: InstalledZone): SQLiteDatabase = open.getOrPut(zone.zone.id) {
        SQLiteDatabase.openDatabase(
            zone.searchDb.absolutePath, null,
            SQLiteDatabase.OPEN_READONLY or SQLiteDatabase.NO_LOCALIZED_COLLATORS,
        )
    }

    /** Releases database handles before a zone is deleted or replaced. */
    @Synchronized
    fun close(zoneId: String? = null) {
        open.filterKeys { zoneId == null || it == zoneId }.forEach { (k, d) -> runCatching { d.close() }; open.remove(k) }
    }

    fun search(zones: List<InstalledZone>, query: String, near: LatLon?, limit: Int = 12): List<Place> {
        val tokens = query.lowercase().split(Regex("[^\\p{L}\\p{N}]+")).filter { it.isNotBlank() }
        if (tokens.isEmpty()) return emptyList()
        val match = tokens.joinToString(" ") { "$it*" }
        val q = query.trim().lowercase()
        val scored = ArrayList<Pair<Double, Place>>()
        zones.forEach { z ->
            runCatching {
                db(z).rawQuery(
                    "SELECT p.name, p.local, p.kind, p.rank, p.lat, p.lon, p.context FROM places_fts f " +
                        "JOIN places p ON p.id = f.rowid WHERE places_fts MATCH ? LIMIT 300",
                    arrayOf(match),
                ).use { c ->
                    while (c.moveToNext()) {
                        val name = c.getString(0)
                        val p = LatLon(c.getDouble(4), c.getDouble(5))
                        val dist = near?.let { Geo.distance(it, p) }
                        val lname = name.lowercase()
                        var score = c.getInt(3).toDouble()
                        if (lname == q) score += 80 else if (lname.startsWith(q)) score += 40
                        if (dist != null) score -= (dist / 1000).coerceAtMost(400.0) * 0.15
                        val address = listOfNotNull(describe(c.getString(2)), c.getString(6), c.getString(1))
                            .distinct().joinToString(" · ")
                        scored += score to Place(name, address, p, dist)
                    }
                }
            }
        }
        return scored.sortedByDescending { it.first }.map { it.second }
            .distinctBy { "${it.name.lowercase()}|${Math.round(it.point.lat * 500)}|${Math.round(it.point.lon * 500)}" }
            .take(limit)
    }

    /** Nearest named place to [p] (within ~5 km), used for dropped pins. */
    /** Speed cameras inside the box, or null when a pack predates camera data (built before cameras were added). */
    fun cameras(zones: List<InstalledZone>, minLat: Double, minLon: Double, maxLat: Double, maxLon: Double): List<LatLon>? {
        val out = ArrayList<LatLon>()
        for (z in zones) {
            val rows = runCatching {
                db(z).rawQuery(
                    "SELECT lat, lon FROM cameras WHERE lat BETWEEN ? AND ? AND lon BETWEEN ? AND ?",
                    arrayOf(minLat.toString(), maxLat.toString(), minLon.toString(), maxLon.toString()),
                ).use { c -> buildList { while (c.moveToNext()) add(LatLon(c.getDouble(0), c.getDouble(1))) } }
            }.getOrNull() ?: return null
            out += rows
        }
        return out
    }

    fun reverse(zones: List<InstalledZone>, p: LatLon): Place? {
        val zone = zones.firstOrNull { it.zone.contains(p) } ?: return null
        val d = 0.05
        val k = cos(Math.toRadians(p.lat))
        return runCatching {
            db(zone).rawQuery(
                "SELECT name, kind, lat, lon, context FROM places WHERE lat BETWEEN ? AND ? AND lon BETWEEN ? AND ? " +
                    "ORDER BY ((lat - ?) * (lat - ?) + (lon - ?) * (lon - ?) * ?) LIMIT 1",
                arrayOf(p.lat - d, p.lat + d, p.lon - d, p.lon + d, p.lat, p.lat, p.lon, p.lon, k * k).map { it.toString() }.toTypedArray(),
            ).use { c ->
                if (!c.moveToFirst()) null else {
                    val near = LatLon(c.getDouble(2), c.getDouble(3))
                    val meters = Geo.distance(p, near)
                    val label = if (meters < 150) c.getString(0) else "Near ${c.getString(0)}"
                    Place(label, listOfNotNull(c.getString(4), "%.5f, %.5f".format(p.lat, p.lon)).joinToString(" · "), p)
                }
            }
        }.getOrNull()
    }

    private fun describe(kind: String): String? {
        val v = kind.substringAfter(':')
        return when {
            kind.startsWith("place:") -> v.replaceFirstChar { it.uppercase() }
            kind.startsWith("road:") -> "Road"
            v == "fuel" -> "Fuel"
            v == "aerodrome" -> "Airport"
            v == "station" -> "Railway station"
            else -> v.replace('_', ' ').replaceFirstChar { it.uppercase() }
        }
    }
}
