package com.tripnav.app.offline

import android.app.DownloadManager
import android.content.Context
import android.net.Uri
import androidx.core.content.edit
import androidx.documentfile.provider.DocumentFile
import com.tripnav.app.util.LatLon
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.IOException
import java.security.MessageDigest

@Serializable
data class PackFile(val kind: String, val name: String, val size: Long, val sha256: String)

@Serializable
data class Zone(
    val id: String,
    val name: String,
    val description: String = "",
    val country: String? = null,
    val rightHandTraffic: Boolean = true,
    val bbox: List<Double> = emptyList(),
    val files: List<PackFile> = emptyList(),
) {
    val totalBytes get() = files.sumOf { it.size }

    fun contains(p: LatLon): Boolean =
        bbox.size == 4 && p.lon >= bbox[0] && p.lat >= bbox[1] && p.lon <= bbox[2] && p.lat <= bbox[3]
}

@Serializable
data class PackManifest(val schema: Int = 1, val generated: String = "", val zones: List<Zone> = emptyList())

/** A zone whose routing, map and search files are all present and verified on the device. */
data class InstalledZone(val zone: Zone, val dir: File) {
    val routingTar get() = File(dir, ROUTING)
    val mapFile get() = File(dir, MAP)
    val searchDb get() = File(dir, SEARCH)

    companion object {
        const val ROUTING = "routing.tar"
        const val MAP = "map.pmtiles"
        const val SEARCH = "search.sqlite"
        fun localName(kind: String) = when (kind) { "routing" -> ROUTING; "map" -> MAP; else -> SEARCH }
    }
}

sealed interface ZoneJob {
    data class Downloading(val bytes: Long, val total: Long) : ZoneJob
    data object Verifying : ZoneJob
    data class Failed(val message: String) : ZoneJob
}

/**
 * Offline zone packs: lists what is available (packs.json), downloads through the system DownloadManager
 * (survives the app being closed, resumes, shows progress in the notification shade), verifies SHA-256,
 * and also installs packs copied over USB from a folder.
 */
class PackStore(
    private val context: Context,
    private val http: OkHttpClient,
    private val scope: CoroutineScope,
) {
    private val json = Json { ignoreUnknownKeys = true; prettyPrint = true }
    private val root: File = (context.getExternalFilesDir("packs") ?: File(context.filesDir, "packs")).apply { mkdirs() }
    private val prefs = context.getSharedPreferences("packs", Context.MODE_PRIVATE)
    private val dm = context.getSystemService(DownloadManager::class.java)

    private val _installed = MutableStateFlow<List<InstalledZone>>(emptyList())
    val installed: StateFlow<List<InstalledZone>> = _installed.asStateFlow()
    private val _available = MutableStateFlow<PackManifest?>(null)
    val available: StateFlow<PackManifest?> = _available.asStateFlow()
    private val _jobs = MutableStateFlow<Map<String, ZoneJob>>(emptyMap())
    val jobs: StateFlow<Map<String, ZoneJob>> = _jobs.asStateFlow()
    private var poller: Job? = null

    init {
        scanInstalled()
        if (prefs.all.keys.any { it.startsWith("dl:") }) watchDownloads()
    }

    fun zoneAt(p: LatLon): InstalledZone? = _installed.value.firstOrNull { it.zone.contains(p) }

    fun zoneCovering(points: List<LatLon>): InstalledZone? =
        _installed.value.firstOrNull { z -> points.all { z.zone.contains(it) } }

    private fun scanInstalled() {
        _installed.value = root.listFiles().orEmpty().filter { it.isDirectory }.mapNotNull { dir ->
            val meta = File(dir, "zone.json").takeIf { it.exists() } ?: return@mapNotNull null
            val zone = runCatching { json.decodeFromString<Zone>(meta.readText()) }.getOrNull() ?: return@mapNotNull null
            InstalledZone(zone, dir).takeIf { it.routingTar.exists() && it.mapFile.exists() && it.searchDb.exists() }
        }.sortedBy { it.zone.name }
    }

    /** Fetches the list of downloadable zones. */
    suspend fun refreshAvailable(manifestUrl: String): Result<PackManifest> = withContext(Dispatchers.IO) {
        runCatching {
            http.newCall(Request.Builder().url(manifestUrl).build()).execute().use { r ->
                if (!r.isSuccessful) throw IOException("Pack list unavailable (HTTP ${r.code})")
                json.decodeFromString<PackManifest>(r.body!!.string())
            }
        }.onSuccess { _available.value = it }
    }

    fun isUpToDate(zone: Zone): Boolean {
        val local = _installed.value.firstOrNull { it.zone.id == zone.id }?.zone ?: return false
        return local.files.map { it.sha256 }.toSet() == zone.files.map { it.sha256 }.toSet()
    }

    // ---- download ----

    fun download(zone: Zone, manifestUrl: String) {
        if (_jobs.value[zone.id] is ZoneJob.Downloading) return
        val base = manifestUrl.substringBeforeLast('/') + "/"
        val staging = File(root, ".staging-${zone.id}").apply { mkdirs() }
        File(staging, "zone.json").writeText(json.encodeToString(Zone.serializer(), zone))
        var enqueued = 0
        prefs.edit {
            zone.files.forEach { f ->
                val target = File(staging, InstalledZone.localName(f.kind))
                if (target.exists() && target.length() == f.size) return@forEach // already fetched earlier
                if (reusable(zone, f) != null) return@forEach // unchanged since the installed version
                enqueued++
                target.delete()
                val req = DownloadManager.Request(Uri.parse(base + f.name))
                    .setTitle("TripNav · ${zone.name}")
                    .setDescription(f.kind)
                    .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE)
                    .setDestinationUri(Uri.fromFile(target))
                    .setAllowedOverMetered(true)
                putLong("dl:${zone.id}:${f.kind}", dm.enqueue(req))
            }
        }
        if (enqueued == 0) {
            _jobs.update { it + (zone.id to ZoneJob.Verifying) }
            scope.launch(Dispatchers.IO) {
                finishInstall(zone, staging).onFailure { e -> _jobs.update { it + (zone.id to ZoneJob.Failed(e.message ?: "Install failed")) } }
                    .onSuccess { _jobs.update { it - zone.id } }
            }
            return
        }
        _jobs.update { it + (zone.id to ZoneJob.Downloading(0, bytesToFetch(zone))) }
        watchDownloads()
    }

    /** The installed copy of [f], if this zone is installed and that file has not changed. */
    private fun reusable(zone: Zone, f: PackFile): File? {
        val iz = _installed.value.firstOrNull { it.zone.id == zone.id } ?: return null
        val old = iz.zone.files.firstOrNull { it.kind == f.kind } ?: return null
        val file = File(iz.dir, InstalledZone.localName(f.kind))
        return file.takeIf { old.sha256.equals(f.sha256, ignoreCase = true) && it.exists() && it.length() == f.size }
    }

    private fun bytesToFetch(zone: Zone) = zone.files.filter { reusable(zone, it) == null }.sumOf { it.size }

    fun cancel(zoneId: String) {
        val keys = prefs.all.keys.filter { it.startsWith("dl:$zoneId:") }
        keys.forEach { k -> dm.remove(prefs.getLong(k, -1)) }
        prefs.edit { keys.forEach { remove(it) } }
        File(root, ".staging-$zoneId").deleteRecursively()
        _jobs.update { it - zoneId }
    }

    private fun watchDownloads() {
        if (poller?.isActive == true) return
        poller = scope.launch(Dispatchers.IO) {
            while (isActive) {
                val pending = prefs.all.keys.filter { it.startsWith("dl:") }
                if (pending.isEmpty()) break
                pending.groupBy { it.split(":")[1] }.forEach { (zoneId, keys) -> pollZone(zoneId, keys) }
                delay(1_000)
            }
        }
    }

    private suspend fun pollZone(zoneId: String, keys: List<String>) {
        val staging = File(root, ".staging-$zoneId")
        val zone = runCatching { json.decodeFromString<Zone>(File(staging, "zone.json").readText()) }.getOrNull()
        if (zone == null) { cancel(zoneId); return }
        var done = 0L; var failed: String? = null; var allDone = true
        keys.forEach { k ->
            val id = prefs.getLong(k, -1)
            dm.query(DownloadManager.Query().setFilterById(id))?.use { c ->
                if (!c.moveToFirst()) { failed = "Download was removed"; return@use }
                val status = c.getInt(c.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS))
                done += c.getLong(c.getColumnIndexOrThrow(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR)).coerceAtLeast(0)
                when (status) {
                    DownloadManager.STATUS_SUCCESSFUL -> Unit
                    DownloadManager.STATUS_FAILED -> {
                        failed = "Download failed (code ${c.getInt(c.getColumnIndexOrThrow(DownloadManager.COLUMN_REASON))})"
                    }
                    else -> allDone = false
                }
            }
        }
        // Files fetched in an earlier attempt are not in the DownloadManager any more.
        val already = zone.files.filter { f -> keys.none { it.endsWith(":${f.kind}") } && reusable(zone, f) == null }
            .sumOf { File(staging, InstalledZone.localName(it.kind)).length() }
        failed?.let { msg ->
            prefs.edit { keys.forEach { remove(it) } }
            _jobs.update { it + (zoneId to ZoneJob.Failed(msg)) }
            return
        }
        if (!allDone) { _jobs.update { it + (zoneId to ZoneJob.Downloading(done + already, bytesToFetch(zone))) }; return }
        prefs.edit { keys.forEach { remove(it) } }
        _jobs.update { it + (zoneId to ZoneJob.Verifying) }
        finishInstall(zone, staging).onFailure { e -> _jobs.update { it + (zoneId to ZoneJob.Failed(e.message ?: "Verification failed")) } }
            .onSuccess { _jobs.update { it - zoneId } }
    }

    /** Verifies every file against the manifest, then atomically swaps the zone into place. */
    private fun finishInstall(zone: Zone, staging: File): Result<Unit> = runCatching {
        val reused = zone.files.mapNotNull { f ->
            val target = File(staging, InstalledZone.localName(f.kind))
            if (target.exists()) null else reusable(zone, f)?.let { f to it }
        }
        zone.files.filter { f -> reused.none { it.first == f } }.forEach { f ->
            val file = File(staging, InstalledZone.localName(f.kind))
            if (!file.exists() || file.length() != f.size) throw IOException("${f.kind}: incomplete file")
            if (!sha256(file).equals(f.sha256, ignoreCase = true)) {
                file.delete(); throw IOException("${f.kind}: checksum mismatch, please download again")
            }
        }
        // Unchanged files move over from the installed copy (verified when it was installed).
        reused.forEach { (f, src) ->
            if (!src.renameTo(File(staging, InstalledZone.localName(f.kind)))) throw IOException("${f.kind}: could not reuse installed file")
        }
        File(staging, "zone.json").writeText(json.encodeToString(Zone.serializer(), zone))
        val dest = File(root, zone.id)
        val old = File(root, ".old-${zone.id}")
        old.deleteRecursively()
        if (dest.exists() && !dest.renameTo(old)) throw IOException("Could not replace the installed zone")
        if (!staging.renameTo(dest)) { old.renameTo(dest); throw IOException("Could not install zone") }
        old.deleteRecursively()
        scanInstalled()
    }

    /**
     * Installs packs copied to the phone (e.g. over USB): a folder containing packs.json plus the
     * `<zone>-routing.tar`, `<zone>-map.pmtiles` and `<zone>-search.sqlite` files.
     */
    suspend fun importFolder(tree: Uri): Result<List<String>> = withContext(Dispatchers.IO) {
        runCatching {
            val dir = DocumentFile.fromTreeUri(context, tree) ?: throw IOException("Can't open folder")
            val manifestDoc = dir.findFile("packs.json") ?: throw IOException("packs.json not found in that folder")
            val manifest = context.contentResolver.openInputStream(manifestDoc.uri)!!.use {
                json.decodeFromString<PackManifest>(it.readBytes().decodeToString())
            }
            val imported = ArrayList<String>()
            manifest.zones.forEach { zone ->
                val docs = zone.files.associateWith { dir.findFile(it.name) }
                if (docs.values.any { it == null }) return@forEach
                val staging = File(root, ".staging-${zone.id}").apply { deleteRecursively(); mkdirs() }
                _jobs.update { it + (zone.id to ZoneJob.Verifying) }
                docs.forEach { (f, doc) ->
                    context.contentResolver.openInputStream(doc!!.uri)!!.use { input ->
                        File(staging, InstalledZone.localName(f.kind)).outputStream().use { input.copyTo(it, 1 shl 20) }
                    }
                }
                finishInstall(zone, staging).getOrThrow()
                _jobs.update { it - zone.id }
                imported += zone.name
            }
            if (imported.isEmpty()) throw IOException("No complete zone found in that folder")
            imported
        }
    }

    fun delete(zoneId: String) {
        File(root, zoneId).deleteRecursively()
        scanInstalled()
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
