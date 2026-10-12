package com.tripnav.app.search

import com.tripnav.app.util.Geo
import com.tripnav.app.util.LatLon
import java.net.URLDecoder
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.double
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request

data class Place(val name: String, val address: String, val point: LatLon, val distanceM: Double? = null)

/**
 * Forward search via Photon (OSM, typo tolerant, location biased) and reverse geocoding via Nominatim.
 * Also understands raw coordinates, `geo:` URIs and shared Google Maps links.
 */
class Geocoder(private val http: OkHttpClient) {
    private val json = Json { ignoreUnknownKeys = true }
    private val lang = Locale.getDefault().language.takeIf { it in setOf("en", "de", "fr", "it") } ?: "en"

    suspend fun search(query: String, near: LatLon?): List<Place> = withContext(Dispatchers.IO) {
        parseCoordinates(query)?.let { return@withContext listOf(Place("Dropped pin", "%.5f, %.5f".format(it.lat, it.lon), it)) }
        if (query.isBlank()) return@withContext emptyList()
        val url = "https://photon.komoot.io/api/".toHttpUrl().newBuilder()
            .addQueryParameter("q", query).addQueryParameter("limit", "12").addQueryParameter("lang", lang)
            .apply { near?.let { addQueryParameter("lat", it.lat.toString()); addQueryParameter("lon", it.lon.toString()) } }
            .build()
        val body = get(url.toString()) ?: return@withContext emptyList()
        val features = json.parseToJsonElement(body).jsonObject["features"]?.jsonArray ?: return@withContext emptyList()
        features.mapNotNull { f ->
            val o = f.jsonObject
            val coords = o["geometry"]?.jsonObject?.get("coordinates")?.jsonArray ?: return@mapNotNull null
            val p = LatLon(coords[1].jsonPrimitive.double, coords[0].jsonPrimitive.double)
            val props = o["properties"]?.jsonObject ?: JsonObject(emptyMap())
            fun s(k: String) = props[k]?.jsonPrimitive?.contentOrNull
            val street = listOfNotNull(s("street"), s("housenumber")).joinToString(" ").ifBlank { null }
            val name = s("name") ?: street ?: s("city") ?: return@mapNotNull null
            val address = listOfNotNull(street.takeIf { it != name }, s("city"), s("state"), s("country"))
                .distinct().joinToString(", ")
            Place(name, address, p, near?.let { Geo.distance(it, p) })
        }
    }

    suspend fun reverse(p: LatLon): Place = withContext(Dispatchers.IO) {
        val url = "https://nominatim.openstreetmap.org/reverse?format=jsonv2&zoom=18&lat=${p.lat}&lon=${p.lon}"
        val body = get(url)
        val o = body?.let { runCatching { json.parseToJsonElement(it).jsonObject }.getOrNull() }
        val name = o?.get("name")?.jsonPrimitive?.contentOrNull?.ifBlank { null }
        val display = o?.get("display_name")?.jsonPrimitive?.contentOrNull
        Place(name ?: display?.substringBefore(',') ?: "Dropped pin", display ?: "%.5f, %.5f".format(p.lat, p.lon), p)
    }

    /** ISO country code at [p], used to pick the traffic side for roundabout icons. */
    suspend fun countryCode(p: LatLon): String? = withContext(Dispatchers.IO) {
        val body = get("https://nominatim.openstreetmap.org/reverse?format=jsonv2&zoom=3&lat=${p.lat}&lon=${p.lon}") ?: return@withContext null
        runCatching {
            json.parseToJsonElement(body).jsonObject["address"]?.jsonObject?.get("country_code")?.jsonPrimitive?.contentOrNull
        }.getOrNull()
    }

    /** Resolves text shared from another app (Google Maps, browser, messages) to a place. */
    suspend fun resolveShared(text: String, near: LatLon?): Place? = withContext(Dispatchers.IO) {
        parseCoordinates(text)?.let { return@withContext reverse(it) }
        val url = Regex("https?://\\S+").find(text)?.value
        if (url != null) {
            val finalUrl = runCatching {
                http.newCall(Request.Builder().url(url).header("User-Agent", UA).get().build()).execute()
                    .use { it.request.url.toString() }
            }.getOrNull()
            val decoded = finalUrl?.let { URLDecoder.decode(it, "UTF-8") }
            decoded?.let(::parseCoordinates)?.let { p ->
                val label = text.substringBefore(url).lines().firstOrNull { it.isNotBlank() }?.trim()
                return@withContext reverse(p).let { if (!label.isNullOrBlank()) it.copy(name = label) else it }
            }
            decoded?.let { Regex("/place/([^/@]+)").find(it)?.groupValues?.get(1)?.replace('+', ' ') }
                ?.let { q -> search(q, near).firstOrNull()?.let { return@withContext it } }
        }
        val query = text.replace(Regex("https?://\\S+"), " ").trim()
        if (query.isBlank()) null else search(query, near).firstOrNull()
    }

    private fun get(url: String): String? = runCatching {
        http.newCall(Request.Builder().url(url).header("User-Agent", UA).build()).execute().use {
            if (it.isSuccessful) it.body?.string() else null
        }
    }.getOrNull()

    companion object {
        const val UA = "TripNav/1.0 (Android; motorcycle navigation)"
        private val coord = Regex("(-?\\d{1,2}\\.\\d{3,})\\s*,\\s*(-?\\d{1,3}\\.\\d{3,})")
        private val atCoord = Regex("@(-?\\d{1,2}\\.\\d+),(-?\\d{1,3}\\.\\d+)")
        private val dataCoord = Regex("!3d(-?\\d{1,2}\\.\\d+)!4d(-?\\d{1,3}\\.\\d+)")

        /** Accepts "lat, lon", `geo:lat,lon?q=…`, and Google Maps URL forms (`@lat,lon`, `!3d…!4d…`, `q=lat,lon`). */
        fun parseCoordinates(text: String): LatLon? {
            val m = dataCoord.find(text) ?: Regex("geo:0,0\\?q=(-?\\d+\\.\\d+),(-?\\d+\\.\\d+)").find(text)
                ?: coord.find(text) ?: atCoord.find(text) ?: return null
            val lat = m.groupValues[1].toDoubleOrNull() ?: return null
            val lon = m.groupValues[2].toDoubleOrNull() ?: return null
            if (lat !in -90.0..90.0 || lon !in -180.0..180.0) return null
            if (lat == 0.0 && lon == 0.0) return null
            return LatLon(lat, lon)
        }
    }
}
