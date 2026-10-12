package com.tripnav.packbuilder

import com.onthegomap.planetiler.FeatureCollector
import com.onthegomap.planetiler.Planetiler
import com.onthegomap.planetiler.Profile
import com.onthegomap.planetiler.config.Arguments
import com.onthegomap.planetiler.geo.GeoUtils
import com.ibm.icu.text.Transliterator
import com.onthegomap.planetiler.reader.SourceFeature
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.locationtech.jts.geom.Point
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.sql.DriverManager
import java.time.LocalDate
import java.util.concurrent.ConcurrentLinkedQueue
import kotlin.math.cos
import kotlin.math.floor

/**
 * TripNav offline pack builder.
 *
 *   search   <zone.osm.pbf> <out.sqlite>            offline place search index
 *   bbox     <zone.poly>                            prints minLon,minLat,maxLon,maxLat
 *   manifest <packsDir> <zones.json> <out.json>     packs.json describing built packs
 */
fun main(args: Array<String>) {
    when (args.firstOrNull()) {
        "search" -> SearchIndexBuilder.build(Path.of(args[1]), File(args[2]))
        "bbox" -> println(PolyBbox.read(File(args[1])).joinToString(","))
        "manifest" -> Manifest.write(File(args[1]), File(args[2]), File(args[3]))
        else -> {
            System.err.println("usage: search <pbf> <out.sqlite> | bbox <poly> | manifest <packsDir> <zones.json> <out.json>")
            kotlin.system.exitProcess(2)
        }
    }
}

data class Camera(val lat: Double, val lon: Double, val maxspeed: Int?)

data class Entry(
    val name: String, val local: String?, val translit: String?, val kind: String, val rank: Int, val lat: Double, val lon: Double,
)

/** Extracts settlements, useful POIs and named roads into an FTS4 SQLite database the app queries offline. */
object SearchIndexBuilder {
    private val placeRank = mapOf(
        "city" to 100, "town" to 80, "suburb" to 60, "village" to 50, "quarter" to 40,
        "neighbourhood" to 30, "hamlet" to 30, "locality" to 20, "island" to 40,
    )
    private val poiKeys = mapOf(
        "amenity" to setOf(
            "fuel", "charging_station", "hospital", "clinic", "doctors", "pharmacy", "police", "restaurant", "cafe",
            "fast_food", "food_court", "parking", "toilets", "atm", "bank", "bus_station", "college", "university",
            "school", "cinema", "marketplace", "place_of_worship", "townhall", "post_office", "fire_station",
        ),
        "tourism" to setOf(
            "hotel", "motel", "guest_house", "hostel", "attraction", "viewpoint", "museum", "camp_site", "resort",
            "theme_park", "zoo", "picnic_site",
        ),
        "shop" to setOf("motorcycle", "motorcycle_repair", "car_repair", "tyres", "mall", "supermarket", "car_parts"),
        "craft" to setOf("motorcycle_repair"),
        "historic" to setOf("fort", "castle", "monument", "ruins", "memorial", "archaeological_site"),
        "natural" to setOf("peak", "waterfall", "beach", "cave_entrance", "lake"),
        "leisure" to setOf("park", "stadium", "nature_reserve"),
        "railway" to setOf("station", "halt"),
        "aeroway" to setOf("aerodrome", "terminal"),
        "waterway" to setOf("dam"),
    )
    private val poiRank = mapOf(
        "aerodrome" to 90, "station" to 70, "hospital" to 55, "fort" to 55, "attraction" to 50, "viewpoint" to 45,
        "waterfall" to 50, "beach" to 50, "peak" to 45, "fuel" to 45, "mall" to 45, "university" to 45, "dam" to 45,
    )
    private val roadClasses = setOf(
        "motorway", "trunk", "primary", "secondary", "tertiary", "unclassified", "residential", "living_street",
        "motorway_link", "trunk_link", "primary_link",
    )

    fun build(pbf: Path, out: File) {
        val rows = ConcurrentLinkedQueue<Entry>()
        val cameras = ConcurrentLinkedQueue<Camera>()
        val profile = object : Profile {
            override fun name() = "tripnav-search"
            override fun processFeature(sf: SourceFeature, features: FeatureCollector) {
                runCatching { camera(sf)?.let(cameras::add) }
                runCatching { collect(sf)?.let(rows::add) }
            }
        }
        // Temp files next to the output (the system temp drive may be small).
        val tmp = Files.createTempDirectory(out.absoluteFile.parentFile.toPath(), "search-tmp")
        Planetiler.create(Arguments.of(mapOf("tmpdir" to tmp.toString())))
            .setProfile(profile)
            .addOsmSource("osm", pbf)
            .overwriteOutput(tmp.resolve("unused.mbtiles"))
            .run()
        write(dedupe(rows.toList()), cameras.toList(), out)
        tmp.toFile().deleteRecursively()
    }

    private val translit = ThreadLocal.withInitial { Transliterator.getInstance("Any-Latin; Latin-ASCII; Lower") }

    private fun isLatin(s: String) = s.all { it.code < 0x0250 }

    /**
     * Display name (English/Latin when OSM has one, otherwise the local-script name), the local name, and a
     * transliteration of non-Latin names so "mandir" or "jaisalmer" still match Devanagari/Gujarati-only places.
     */
    private fun names(sf: SourceFeature): Triple<String, String?, String?>? {
        val local = sf.getString("name")?.trim()?.ifBlank { null }
        val latin = (sf.getString("name:en") ?: sf.getString("int_name"))?.trim()?.ifBlank { null }
            ?: local?.takeIf(::isLatin)
        val display = latin ?: local ?: return null
        val nonLatin = local?.takeIf { !isLatin(it) }
        return Triple(display, local?.takeIf { it != display }, nonLatin?.let { translit.get().transliterate(it) })
    }

    private fun lonLat(p: org.locationtech.jts.geom.Geometry): Pair<Double, Double> {
        val c = (p as Point).coordinate
        return GeoUtils.getWorldLon(c.x) to GeoUtils.getWorldLat(c.y)
    }

    /** Fixed speed cameras (OSM highway=speed_camera nodes), used for the pod's camera warning. */
    private fun camera(sf: SourceFeature): Camera? {
        if (!sf.isPoint || sf.getString("highway") != "speed_camera") return null
        val (lon, lat) = lonLat(sf.worldGeometry())
        val limit = sf.getString("maxspeed")?.trim()?.takeWhile { it.isDigit() }?.toIntOrNull()
        return Camera(lat, lon, limit)
    }

    private fun collect(sf: SourceFeature): Entry? {
        val (name, local, tr) = names(sf) ?: return null
        sf.getString("place")?.let { place ->
            val rank = placeRank[place] ?: return null
            val (lon, lat) = lonLat(if (sf.isPoint) sf.worldGeometry() else sf.pointOnSurface())
            return Entry(name, local, tr, "place:$place", rank, lat, lon)
        }
        for ((key, values) in poiKeys) {
            val v = sf.getString(key) ?: continue
            if (v !in values) continue
            val geom = when {
                sf.isPoint -> sf.worldGeometry()
                sf.canBePolygon() -> sf.pointOnSurface()
                sf.canBeLine() -> sf.lineMidpoint()
                else -> return null
            }
            val (lon, lat) = lonLat(geom)
            return Entry(name, local, tr, "$key:$v", poiRank[v] ?: 30, lat, lon)
        }
        val highway = sf.getString("highway")
        if (highway != null && highway in roadClasses && sf.canBeLine()) {
            val (lon, lat) = lonLat(sf.lineMidpoint())
            return Entry(name, local, tr, "road:$highway", if (highway.startsWith("motorway") || highway.startsWith("trunk")) 35 else 20, lat, lon)
        }
        return null
    }

    /** One entry per road name per ~2 km cell, so long roads split into many ways appear once per area. */
    private fun dedupe(all: List<Entry>): List<Entry> {
        val seen = HashSet<String>()
        return all.sortedByDescending { it.rank }.filter { e ->
            val cell = if (e.kind.startsWith("road:")) "${floor(e.lat / 0.02)}:${floor(e.lon / 0.02)}" else "${e.lat}:${e.lon}"
            seen.add("${e.kind.substringBefore(':')}|${e.name.lowercase()}|$cell")
        }
    }

    /** Label shown under each result: the nearest town or city within ~40 km, else the nearest village within 8 km. */
    private fun contexts(entries: List<Entry>): List<String?> {
        val big = setOf("place:city", "place:town")
        val towns = entries.filter { it.kind in big || it.kind == "place:village" }
        val grid = HashMap<Long, MutableList<Entry>>()
        fun key(lat: Double, lon: Double) = (floor(lat / 0.25).toLong() shl 32) xor (floor(lon / 0.25).toLong() and 0xffffffffL)
        towns.forEach { grid.getOrPut(key(it.lat, it.lon)) { mutableListOf() }.add(it) }
        return entries.map { e ->
            if (e.kind == "place:city") return@map null
            var best: Entry? = null; var bestScore = Double.MAX_VALUE
            for (dy in -1..1) for (dx in -1..1) {
                grid[key(e.lat + dy * 0.25, e.lon + dx * 0.25)]?.forEach { t ->
                    if (t === e || t.name == e.name) return@forEach
                    val kx = cos(Math.toRadians(e.lat))
                    val d = Math.hypot((t.lat - e.lat), (t.lon - e.lon) * kx) * 111.0
                    // Towns/cities always beat villages; villages only count when very close.
                    val ok = if (t.kind in big) d < 40 else d < 8
                    val score = if (t.kind in big) d - (if (t.kind == "place:city") 10 else 0) else 1_000 + d
                    if (ok && score < bestScore) { bestScore = score; best = t }
                }
            }
            best?.name
        }
    }

    private fun write(entries: List<Entry>, cameras: List<Camera>, out: File) {
        out.delete()
        val ctx = contexts(entries)
        DriverManager.getConnection("jdbc:sqlite:${out.absolutePath}").use { c ->
            c.autoCommit = false
            c.createStatement().use { s ->
                s.executeUpdate("CREATE TABLE meta(key TEXT PRIMARY KEY, value TEXT)")
                s.executeUpdate(
                    "CREATE TABLE places(id INTEGER PRIMARY KEY, name TEXT NOT NULL, local TEXT, translit TEXT, kind TEXT NOT NULL, " +
                        "rank INTEGER NOT NULL, lat REAL NOT NULL, lon REAL NOT NULL, context TEXT)",
                )
            }
            c.prepareStatement("INSERT INTO places(name, local, translit, kind, rank, lat, lon, context) VALUES (?,?,?,?,?,?,?,?)").use { ps ->
                entries.forEachIndexed { i, e ->
                    ps.setString(1, e.name); ps.setString(2, e.local); ps.setString(3, e.translit); ps.setString(4, e.kind)
                    ps.setInt(5, e.rank); ps.setDouble(6, e.lat); ps.setDouble(7, e.lon); ps.setString(8, ctx[i])
                    ps.addBatch()
                    if (i % 5000 == 0) ps.executeBatch()
                }
                ps.executeBatch()
            }
            c.createStatement().use { s ->
                // FTS4 + unicode61(remove_diacritics=1) works on every Android version TripNav supports.
                s.executeUpdate(
                    "CREATE VIRTUAL TABLE places_fts USING fts4(content=\"places\", name, local, translit, context, " +
                        "tokenize=unicode61 \"remove_diacritics=1\", prefix=\"2,3,4\")",
                )
                s.executeUpdate("INSERT INTO places_fts(places_fts) VALUES('rebuild')")
                s.executeUpdate("CREATE INDEX places_geo ON places(lat, lon)")
                s.executeUpdate("CREATE TABLE cameras(lat REAL NOT NULL, lon REAL NOT NULL, maxspeed INTEGER)")
                s.executeUpdate("CREATE INDEX cameras_geo ON cameras(lat, lon)")
                s.executeUpdate("INSERT INTO meta VALUES ('schema','1'), ('built','${LocalDate.now()}'), ('count','${entries.size}')")
            }
            c.prepareStatement("INSERT INTO cameras(lat, lon, maxspeed) VALUES (?,?,?)").use { ps ->
                cameras.distinct().forEach { cam ->
                    ps.setDouble(1, cam.lat); ps.setDouble(2, cam.lon)
                    if (cam.maxspeed != null) ps.setInt(3, cam.maxspeed) else ps.setNull(3, java.sql.Types.INTEGER)
                    ps.addBatch()
                }
                ps.executeBatch()
            }
            c.commit()
            c.autoCommit = true
            c.createStatement().use { it.executeUpdate("VACUUM") }
        }
        println("speed cameras: ${cameras.distinct().size}")
        println("search index: ${entries.size} entries -> ${out.length() / 1_048_576} MB")
    }
}

/** Bounding box of a Geofabrik .poly file. */
object PolyBbox {
    fun read(f: File): List<Double> {
        var minLon = 180.0; var minLat = 90.0; var maxLon = -180.0; var maxLat = -90.0
        f.readLines().forEach { line ->
            val parts = line.trim().split(Regex("\\s+"))
            if (parts.size == 2) {
                val lon = parts[0].toDoubleOrNull() ?: return@forEach
                val lat = parts[1].toDoubleOrNull() ?: return@forEach
                minLon = minOf(minLon, lon); maxLon = maxOf(maxLon, lon); minLat = minOf(minLat, lat); maxLat = maxOf(maxLat, lat)
            }
        }
        return listOf(minLon, minLat, maxLon, maxLat).map { Math.round(it * 10_000) / 10_000.0 }
    }
}

/**
 * packs.json: one entry per zone listing its files with size and SHA-256. File names are relative to the
 * manifest URL, so the same manifest works from GitHub Releases, any web server, or a USB copy.
 */
object Manifest {
    private val kinds = mapOf("routing.tar" to "routing", "map.pmtiles" to "map", "search.sqlite" to "search")

    fun write(dir: File, zonesFile: File, out: File) {
        val zones = Json.parseToJsonElement(zonesFile.readText()).jsonArray
        val json = buildJsonObject {
            put("schema", 1)
            put("generated", LocalDate.now().toString())
            put("zones", buildJsonArray {
                zones.forEach { z ->
                    val zone = z.jsonObject
                    val id = zone["id"]!!.jsonPrimitive.content
                    val files = kinds.mapNotNull { (suffix, kind) ->
                        val f = File(dir, "$id-$suffix").takeIf { it.exists() } ?: return@mapNotNull null
                        buildJsonObject {
                            put("kind", kind); put("name", f.name); put("size", f.length()); put("sha256", sha256(f))
                        }
                    }
                    if (files.size < kinds.size) { System.err.println("skipping $id: missing files"); return@forEach }
                    val bbox = File(dir, "$id.bbox").takeIf { it.exists() }?.readText()?.trim()?.split(",")?.map { it.toDouble() }
                    add(JsonObject(zone.filterKeys { it !in setOf("pbf", "poly") } + mapOf(
                        "bbox" to JsonArray((bbox ?: emptyList()).map { JsonPrimitive(it) }),
                        "files" to JsonArray(files),
                    )))
                }
            })
        }
        out.writeText(Json { prettyPrint = true }.encodeToString(JsonObject.serializer(), json))
        println("wrote ${out.path}")
    }

    private fun sha256(f: File): String {
        val md = MessageDigest.getInstance("SHA-256")
        f.inputStream().use { input ->
            val buf = ByteArray(1 shl 20)
            while (true) { val n = input.read(buf); if (n < 0) break; md.update(buf, 0, n) }
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }
}
