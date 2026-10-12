package com.tripnav.app

import com.tripnav.app.offline.OfflineStyle
import com.tripnav.app.offline.PackManifest
import com.tripnav.app.offline.Zone
import com.tripnav.app.util.LatLon
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

class OfflineTest {
    private val western = Zone("in-western", "India · Western", bbox = listOf(67.6746, 14.3893, 80.9136, 24.7176), rightHandTraffic = false)

    @Test fun zoneContainment() {
        assertTrue(western.contains(LatLon(18.52, 73.85)))   // Pune
        assertTrue(western.contains(LatLon(15.49, 73.82)))   // Panaji
        assertFalse(western.contains(LatLon(26.91, 70.92)))  // Jaisalmer (northern zone)
    }

    @Test fun manifestParsesWorkflowOutput() {
        val json = """{"schema":1,"generated":"2026-10-12","zones":[{"id":"in-western","name":"India · Western",
            "country":"IN","rightHandTraffic":false,"bbox":[67.6,14.3,80.9,24.7],
            "files":[{"kind":"routing","name":"in-western-routing.tar","size":10,"sha256":"ab"}]}]}"""
        val m = Json { ignoreUnknownKeys = true }.decodeFromString<PackManifest>(json)
        assertEquals("in-western", m.zones.single().id)
        assertEquals(10, m.zones.single().totalBytes)
        assertFalse(m.zones.single().rightHandTraffic)
    }

    @Test fun offlineStyleRepeatsLayersPerZone() {
        val base = File("src/main/assets/offline/day.json").readText()
        val out = Json.parseToJsonElement(OfflineStyle.build(base, listOf("a" to "/x/a.pmtiles", "b" to "/x/b.pmtiles"))).jsonObject
        val sources = out["sources"]!!.jsonObject
        assertEquals(setOf("omt-a", "omt-b"), sources.keys)
        assertEquals("pmtiles://file:///x/a.pmtiles", sources["omt-a"]!!.jsonObject["url"]!!.jsonPrimitive.content)
        val layers = out["layers"]!!.jsonArray.map { it.jsonObject }
        val baseLayers = Json.parseToJsonElement(base).jsonObject["layers"]!!.jsonArray.map { it.jsonObject }
        val vector = baseLayers.count { it["source"] != null }
        assertEquals(baseLayers.size + vector, layers.size)
        assertTrue(layers.none { it["source"]?.jsonPrimitive?.content == "openmaptiles" })
        // No label may still reference non-Latin names (MapLibre can't shape Indic scripts).
        assertFalse(base.contains("name:nonlatin"))
        assertTrue(base.contains("asset://offline/glyphs/"))
    }
}
