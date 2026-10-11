package com.tripnav.app.nav

import com.tripnav.app.settings.AppSettings
import com.tripnav.app.settings.RoutePreference
import com.tripnav.app.util.LatLon
import com.tripnav.app.util.Polyline
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.util.Locale

class RoutingException(message: String) : IOException(message)

/** Thin client for the Valhalla `/route` and `/trace_attributes` endpoints using motorcycle costing. */
class ValhallaClient(private val http: OkHttpClient) {
    private val json = Json { ignoreUnknownKeys = true; explicitNulls = false }

    @Serializable private data class RouteResponse(val trip: Trip? = null, val error: String? = null)
    @Serializable private data class Trip(val legs: List<Leg>, val summary: Summary)
    @Serializable private data class Summary(val length: Double, val time: Double)
    @Serializable private data class Leg(val maneuvers: List<VManeuver>, val shape: String)
    @Serializable private data class VManeuver(
        val type: Int,
        val instruction: String = "",
        @SerialName("verbal_pre_transition_instruction") val verbalPre: String? = null,
        @SerialName("verbal_transition_alert_instruction") val verbalAlert: String? = null,
        @SerialName("street_names") val streetNames: List<String>? = null,
        @SerialName("begin_street_names") val beginStreetNames: List<String>? = null,
        val length: Double = 0.0,
        val time: Double = 0.0,
        @SerialName("begin_shape_index") val begin: Int = 0,
        @SerialName("end_shape_index") val end: Int = 0,
        @SerialName("roundabout_exit_count") val roundaboutExit: Int? = null,
    )

    suspend fun route(
        baseUrl: String,
        origin: LatLon,
        headingDeg: Float?,
        destinations: List<Destination>,
        settings: AppSettings,
    ): Route = withContext(Dispatchers.IO) {
        require(destinations.isNotEmpty())
        val body = buildJsonObject {
            putJsonArray("locations") {
                add(buildJsonObject {
                    put("lat", origin.lat); put("lon", origin.lon); put("type", "break")
                    if (headingDeg != null) { put("heading", headingDeg.toInt()); put("heading_tolerance", 60) }
                })
                destinations.forEachIndexed { i, d ->
                    add(buildJsonObject {
                        put("lat", d.point.lat); put("lon", d.point.lon)
                        put("type", if (i == destinations.lastIndex) "break" else "via")
                        if (d.name.isNotBlank()) put("name", d.name)
                    })
                }
            }
            put("costing", "motorcycle")
            putJsonObject("costing_options") {
                putJsonObject("motorcycle") {
                    when (settings.routePreference) {
                        RoutePreference.FASTEST -> { put("use_highways", 1.0); put("use_primary", 0.5) }
                        RoutePreference.AVOID_HIGHWAYS -> { put("use_highways", 0.0); put("use_primary", 0.5) }
                        RoutePreference.CURVY -> { put("use_highways", 0.0); put("use_primary", 0.1) }
                    }
                    put("use_tolls", if (settings.avoidTolls) 0.0 else 0.5)
                    put("use_ferry", if (settings.avoidFerries) 0.0 else 0.5)
                    put("use_trails", 0.0)
                }
            }
            putJsonObject("directions_options") {
                put("units", "kilometers")
                put("language", Locale.getDefault().toLanguageTag())
            }
        }
        val res: RouteResponse = post("$baseUrl/route", body)
        val trip = res.trip ?: throw RoutingException(res.error ?: "No route found")
        var offset = 0
        val points = ArrayList<LatLon>()
        val maneuvers = ArrayList<Maneuver>()
        trip.legs.forEachIndexed { legIdx, leg ->
            val shape = Polyline.decode(leg.shape, 6)
            // Consecutive legs share their joint point; drop the duplicate.
            val skip = if (legIdx > 0 && shape.isNotEmpty()) 1 else 0
            val base = offset - skip
            points.addAll(shape.drop(skip))
            leg.maneuvers.forEach { m ->
                // Intermediate "destination" + next "start" maneuvers are noise mid-route.
                if (legIdx < trip.legs.lastIndex && m.type in 4..6) return@forEach
                if (legIdx > 0 && m.type in 1..3) return@forEach
                maneuvers += Maneuver(
                    type = m.type, instruction = m.instruction, verbalPre = m.verbalPre, verbalAlert = m.verbalAlert,
                    street = (m.streetNames ?: m.beginStreetNames)?.firstOrNull(),
                    lengthM = m.length * 1000, timeS = m.time,
                    beginIndex = m.begin + base, endIndex = m.end + base, roundaboutExit = m.roundaboutExit,
                )
            }
            offset = points.size
        }
        Route(points, maneuvers, trip.summary.length * 1000, trip.summary.time, destinations)
    }

    /** Fetches per-edge speed limits along [route]; failures are non-fatal and return an empty list. */
    suspend fun speedLimits(baseUrl: String, route: Route): List<SpeedLimitSpan> = withContext(Dispatchers.IO) {
        if (route.points.size < 2 || route.points.size > 12_000) return@withContext emptyList()
        runCatching {
            val body = buildJsonObject {
                put("encoded_polyline", Polyline.encode(route.points, 6))
                put("costing", "motorcycle")
                put("shape_match", "edge_walk")
                putJsonObject("filters") {
                    put("action", "include")
                    put("attributes", buildJsonArray {
                        add(kotlinx.serialization.json.JsonPrimitive("edge.speed_limit"))
                        add(kotlinx.serialization.json.JsonPrimitive("edge.begin_shape_index"))
                        add(kotlinx.serialization.json.JsonPrimitive("edge.end_shape_index"))
                    })
                }
            }
            val obj: JsonObject = post("$baseUrl/trace_attributes", body)
            val edges = obj["edges"] as? kotlinx.serialization.json.JsonArray ?: return@runCatching emptyList()
            edges.mapNotNull { e ->
                val o = e as? JsonObject ?: return@mapNotNull null
                val limit = o["speed_limit"]?.jsonPrimitive?.intOrNull ?: return@mapNotNull null
                if (limit <= 0) return@mapNotNull null
                val b = o["begin_shape_index"]?.jsonPrimitive?.doubleOrNull?.toInt() ?: return@mapNotNull null
                val en = o["end_shape_index"]?.jsonPrimitive?.doubleOrNull?.toInt() ?: return@mapNotNull null
                SpeedLimitSpan(route.distanceAt(b), route.distanceAt(en), limit)
            }
        }.getOrDefault(emptyList())
    }

    private inline fun <reified T> post(url: String, body: JsonObject): T {
        val req = Request.Builder().url(url)
            .post(body.toString().toRequestBody("application/json".toMediaType()))
            .build()
        http.newCall(req).execute().use { resp ->
            val text = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) {
                val msg = runCatching { json.decodeFromString<JsonObject>(text)["error"]?.jsonPrimitive?.content }.getOrNull()
                throw RoutingException(msg ?: "Routing server error ${resp.code}")
            }
            return json.decodeFromString(text)
        }
    }
}
