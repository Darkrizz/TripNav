package com.tripnav.app.offline

import android.content.Context
import com.valhalla.valhalla.Valhalla
import com.valhalla.valhalla.config.ValhallaConfigFactory
import com.valhalla.valhalla.config.ValhallaConfigManager
import com.valhalla.valhalla.files.ValhallaFile
import java.io.IOException

/**
 * On-device Valhalla (valhalla-mobile, the same engine the original app embedded). One engine is open at a
 * time; opening mmaps the zone's tile extract, so it is kept and reused until another zone is needed.
 */
class OfflineRouter(private val context: Context) {
    private var engine: Valhalla? = null
    private var engineZone: String? = null

    @Synchronized
    private fun engineFor(zone: InstalledZone): Valhalla {
        // Keyed by file identity too, so an updated zone is picked up without restarting the app.
        val key = "${zone.zone.id}:${zone.routingTar.length()}:${zone.routingTar.lastModified()}"
        if (engineZone == key) engine?.let { return it }
        close()
        val config = ValhallaConfigFactory.usingTileExtract(zone.routingTar.absolutePath)
        val manager = ValhallaConfigManager(context, ValhallaFile(context, "valhalla-${zone.zone.id}.json"))
        return Valhalla(context, config, manager).also { engine = it; engineZone = key }
    }

    fun route(zone: InstalledZone, requestJson: String): String = call { engineFor(zone).routeRaw(requestJson) }

    fun traceAttributes(zone: InstalledZone, requestJson: String): String =
        call { engineFor(zone).traceAttributesRaw(requestJson) }

    /** Valhalla reports problems (no road nearby, no path) as exceptions; surface them like server errors. */
    private inline fun call(block: () -> String): String = try {
        synchronized(this) { block() }
    } catch (e: Exception) {
        throw IOException(friendly(e.message ?: e.toString()), e)
    }

    private fun friendly(msg: String): String = when {
        "No suitable edges" in msg || "171" in msg -> "No road found near the start or destination"
        "No path could be found" in msg || "442" in msg -> "No route found between these points"
        else -> "Offline routing failed: ${msg.take(120)}"
    }

    /** Must be called before a zone's files are deleted or replaced. */
    @Synchronized
    fun close() {
        runCatching { engine?.close() }
        engine = null; engineZone = null
    }
}
