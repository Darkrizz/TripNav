package com.tripnav.app.offline

import android.content.Context
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Builds a MapLibre style for the installed zones from the bundled offline style (assets/offline, generated
 * by tools/make_offline_style.py). Every zone gets its own PMTiles source; each style layer is repeated per
 * zone so neighbouring zones render seamlessly with labels still drawn above all fills.
 */
object OfflineStyle {
    private const val PLACEHOLDER = "openmaptiles"
    // Required by the OpenMapTiles schema licence and OSM's ODbL.
    private const val ATTRIBUTION = "© OpenMapTiles © OpenStreetMap contributors"

    fun build(context: Context, zones: List<InstalledZone>, night: Boolean): String = build(
        context.assets.open(if (night) "offline/night.json" else "offline/day.json").bufferedReader().use { it.readText() },
        zones.map { it.zone.id to it.mapFile.absolutePath },
    )

    /** @param zones (zone id, absolute .pmtiles path) pairs. */
    fun build(baseStyleJson: String, zones: List<Pair<String, String>>): String {
        val base = Json.parseToJsonElement(baseStyleJson).jsonObject
        val sources = zones.associate { (id, path) ->
            "omt-$id" to JsonObject(mapOf(
                "type" to JsonPrimitive("vector"),
                "url" to JsonPrimitive("pmtiles://file://$path"),
                "attribution" to JsonPrimitive(ATTRIBUTION),
            ))
        }
        val layers = base["layers"]!!.jsonArray.flatMap { el ->
            val layer = el.jsonObject
            if (layer["source"]?.jsonPrimitive?.content != PLACEHOLDER) listOf(layer)
            else zones.map { (id, _) ->
                JsonObject(layer + mapOf(
                    "id" to JsonPrimitive("${layer["id"]!!.jsonPrimitive.content}@$id"),
                    "source" to JsonPrimitive("omt-$id"),
                ))
            }
        }
        return JsonObject(base + mapOf("sources" to JsonObject(sources), "layers" to JsonArray(layers))).toString()
    }
}
