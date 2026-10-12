package com.tripnav.app.offline

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import com.tripnav.app.nav.HttpValhalla
import com.tripnav.app.nav.RoutingException
import com.tripnav.app.nav.ValhallaTransport
import com.tripnav.app.search.Geocoder
import com.tripnav.app.search.Place
import com.tripnav.app.settings.DataMode
import com.tripnav.app.settings.SettingsRepository
import com.tripnav.app.util.LatLon
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient

/** What the map should load. [key] changes whenever the style must be reloaded. */
data class MapStyleSpec(val key: String, val uri: String? = null, val json: String? = null)

/**
 * The one place that decides online vs offline, per [DataMode]:
 * - OFFLINE: never touches the network; everything comes from installed zones.
 * - ONLINE: always uses the online services (zones are ignored).
 * - AUTO: uses an installed zone whenever it covers the job (fast, free, works without signal),
 *   otherwise the online services; falls back to offline when the network fails.
 */
class DataPolicy(
    private val context: Context,
    private val http: OkHttpClient,
    private val settings: SettingsRepository,
    private val packs: PackStore,
    private val router: OfflineRouter,
    private val offlineSearch: OfflineSearch,
    private val geocoder: Geocoder,
) {
    private val mode get() = settings.settings.value.dataMode
    val offlineOnly get() = mode == DataMode.OFFLINE

    fun isOnline(): Boolean {
        if (offlineOnly) return false
        val cm = context.getSystemService(ConnectivityManager::class.java) ?: return false
        val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
            caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
    }

    private fun local(zone: InstalledZone) = ValhallaTransport { endpoint, body ->
        when (endpoint) {
            "route" -> router.route(zone, body)
            "trace_attributes" -> router.traceAttributes(zone, body)
            else -> throw RoutingException("Unsupported offline request $endpoint")
        }
    }

    private fun online() = HttpValhalla(http, settings.settings.value.valhallaUrl)

    /** Picks the routing backend for a trip through [points]. */
    fun routing(points: List<LatLon>): ValhallaTransport {
        val zone = packs.zoneCovering(points)
        return when (mode) {
            DataMode.OFFLINE -> zone?.let(::local) ?: throw RoutingException(
                if (packs.installed.value.isEmpty()) "Offline mode: download a map zone first (Settings → Offline maps)"
                else "This trip leaves your downloaded zones. Download the zones it passes through, or allow online mode.",
            )
            DataMode.ONLINE -> online()
            DataMode.AUTO -> if (zone != null) local(zone) else {
                if (!isOnline()) throw RoutingException("No internet and no downloaded zone covers this trip")
                online()
            }
        }
    }

    suspend fun search(query: String, near: LatLon?): List<Place> = withContext(Dispatchers.IO) {
        Geocoder.parseCoordinates(query)?.let { return@withContext listOf(Place("Dropped pin", "%.5f, %.5f".format(it.lat, it.lon), it)) }
        val zones = packs.installed.value
        when (mode) {
            DataMode.OFFLINE -> offlineSearch.search(zones, query, near)
            DataMode.ONLINE -> geocoder.search(query, near)
            DataMode.AUTO -> {
                val preferLocal = zones.isNotEmpty() && (near == null || zones.any { it.zone.contains(near) } || !isOnline())
                val localHits = if (preferLocal) offlineSearch.search(zones, query, near) else emptyList()
                if (localHits.size >= 3 || !isOnline()) localHits
                else (localHits + runCatching { geocoder.search(query, near) }.getOrDefault(emptyList()))
                    .distinctBy { "${it.name.lowercase()}|${Math.round(it.point.lat * 200)}|${Math.round(it.point.lon * 200)}" }
            }
        }
    }

    suspend fun reverse(p: LatLon): Place = withContext(Dispatchers.IO) {
        val zones = packs.installed.value
        val local = if (mode != DataMode.ONLINE) offlineSearch.reverse(zones, p) else null
        local ?: if (isOnline()) runCatching { geocoder.reverse(p) }.getOrNull() ?: pin(p) else pin(p)
    }

    /** Shared text/links: coordinates work offline; short links need the network to unwrap. */
    suspend fun resolveShared(text: String, near: LatLon?): Place? = withContext(Dispatchers.IO) {
        Geocoder.parseCoordinates(text)?.let { return@withContext reverse(it) }
        if (isOnline()) geocoder.resolveShared(text, near)
        else {
            val query = text.replace(Regex("https?://\\S+"), " ").trim()
            if (query.isBlank()) null else search(query, near).firstOrNull()
        }
    }

    /** Traffic side from the zone at [p]; null means "unknown, ask the online geocoder if allowed". */
    fun rightHandTrafficAt(p: LatLon): Boolean? = packs.zoneAt(p)?.zone?.rightHandTraffic

    fun countryLookupAllowed() = mode != DataMode.OFFLINE && isOnline()

    fun mapStyle(night: Boolean, location: LatLon?, onlineDay: String, onlineNight: String): MapStyleSpec {
        val zones = packs.installed.value
        val useOffline = when (mode) {
            DataMode.OFFLINE -> true
            DataMode.ONLINE -> false
            DataMode.AUTO -> zones.isNotEmpty() && (location?.let { p -> zones.any { it.zone.contains(p) } } ?: true || !isOnline())
        }
        if (useOffline && zones.isNotEmpty()) {
            val key = "offline:$night:" + zones.joinToString(",") { it.zone.id + it.zone.files.firstOrNull()?.sha256?.take(8) }
            return MapStyleSpec(key, json = OfflineStyle.build(context, zones, night))
        }
        if (useOffline) return MapStyleSpec("offline-empty", json = EMPTY_STYLE)
        val uri = if (night) onlineNight else onlineDay
        return MapStyleSpec("online:$uri", uri = uri)
    }

    private fun pin(p: LatLon) = Place("Dropped pin", "%.5f, %.5f".format(p.lat, p.lon), p)

    companion object {
        private const val EMPTY_STYLE =
            """{"version":8,"sources":{},"layers":[{"id":"bg","type":"background","paint":{"background-color":"#1b2027"}}]}"""
    }
}
