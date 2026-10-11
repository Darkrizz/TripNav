package com.tripnav.app.settings

import android.content.Context
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.tripnav.app.BuildConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

private val Context.dataStore by preferencesDataStore(name = "settings")

enum class NightMode { AUTO_SUN, ALWAYS_DAY, ALWAYS_NIGHT }

/** What the pod's secondary field shows while navigating. */
enum class PodSecondary { ARRIVAL_TIME, TIME_LEFT, DISTANCE_LEFT, NEXT_DISTANCE }

/** What the pod shows when connected but not navigating. */
enum class PodIdle { COMPASS, CLOCK }

enum class RoutePreference { FASTEST, AVOID_HIGHWAYS, CURVY }

data class AppSettings(
    val imperial: Boolean = false,
    val clock24h: Boolean = true,
    val nightMode: NightMode = NightMode.AUTO_SUN,
    val voiceEnabled: Boolean = true,
    val voiceStreetNames: Boolean = true,
    val podSecondary: PodSecondary = PodSecondary.ARRIVAL_TIME,
    val podIdle: PodIdle = PodIdle.COMPASS,
    val routePreference: RoutePreference = RoutePreference.FASTEST,
    val avoidTolls: Boolean = false,
    val avoidFerries: Boolean = false,
    val speedWarning: Boolean = true,
    val speedWarningTolerance: Int = 5,
    val mirrorGoogleMaps: Boolean = true,
    val keepScreenOn: Boolean = true,
    val valhallaUrl: String = BuildConfig.DEFAULT_VALHALLA_URL,
    val knownPods: Set<String> = emptySet(),
    val lastPod: String? = null,
    val autoConnect: Boolean = true,
)

class SettingsRepository(private val context: Context, scope: CoroutineScope) {
    private object K {
        val imperial = booleanPreferencesKey("imperial")
        val clock24h = booleanPreferencesKey("clock24h")
        val nightMode = stringPreferencesKey("night_mode")
        val voice = booleanPreferencesKey("voice")
        val voiceStreets = booleanPreferencesKey("voice_streets")
        val podSecondary = stringPreferencesKey("pod_secondary")
        val podIdle = stringPreferencesKey("pod_idle")
        val routePref = stringPreferencesKey("route_pref")
        val avoidTolls = booleanPreferencesKey("avoid_tolls")
        val avoidFerries = booleanPreferencesKey("avoid_ferries")
        val speedWarning = booleanPreferencesKey("speed_warning")
        val speedTolerance = intPreferencesKey("speed_tolerance")
        val mirror = booleanPreferencesKey("mirror_gmaps")
        val keepScreenOn = booleanPreferencesKey("keep_screen_on")
        val valhallaUrl = stringPreferencesKey("valhalla_url")
        val knownPods = stringSetPreferencesKey("known_pods")
        val lastPod = stringPreferencesKey("last_pod")
        val autoConnect = booleanPreferencesKey("auto_connect")
    }

    private inline fun <reified E : Enum<E>> Preferences.enum(key: Preferences.Key<String>, default: E): E =
        this[key]?.let { runCatching { enumValueOf<E>(it) }.getOrNull() } ?: default

    private fun toSettings(p: Preferences): AppSettings {
        val d = AppSettings()
        return AppSettings(
            imperial = p[K.imperial] ?: d.imperial,
            clock24h = p[K.clock24h] ?: d.clock24h,
            nightMode = p.enum(K.nightMode, d.nightMode),
            voiceEnabled = p[K.voice] ?: d.voiceEnabled,
            voiceStreetNames = p[K.voiceStreets] ?: d.voiceStreetNames,
            podSecondary = p.enum(K.podSecondary, d.podSecondary),
            podIdle = p.enum(K.podIdle, d.podIdle),
            routePreference = p.enum(K.routePref, d.routePreference),
            avoidTolls = p[K.avoidTolls] ?: d.avoidTolls,
            avoidFerries = p[K.avoidFerries] ?: d.avoidFerries,
            speedWarning = p[K.speedWarning] ?: d.speedWarning,
            speedWarningTolerance = p[K.speedTolerance] ?: d.speedWarningTolerance,
            mirrorGoogleMaps = p[K.mirror] ?: d.mirrorGoogleMaps,
            keepScreenOn = p[K.keepScreenOn] ?: d.keepScreenOn,
            valhallaUrl = p[K.valhallaUrl]?.takeIf { it.isNotBlank() } ?: d.valhallaUrl,
            knownPods = p[K.knownPods] ?: emptySet(),
            lastPod = p[K.lastPod],
            autoConnect = p[K.autoConnect] ?: d.autoConnect,
        )
    }

    val settings: StateFlow<AppSettings> = context.dataStore.data.map(::toSettings).stateIn(scope, SharingStarted.Eagerly, AppSettings())

    /** Reads the persisted settings, waiting for disk if the flow has not loaded yet. */
    suspend fun load(): AppSettings = toSettings(context.dataStore.data.first())

    private val writeScope = scope

    private fun edit(block: (androidx.datastore.preferences.core.MutablePreferences) -> Unit) {
        writeScope.launch { context.dataStore.edit(block) }
    }

    fun setImperial(v: Boolean) = edit { it[K.imperial] = v }
    fun setClock24h(v: Boolean) = edit { it[K.clock24h] = v }
    fun setNightMode(v: NightMode) = edit { it[K.nightMode] = v.name }
    fun setVoice(v: Boolean) = edit { it[K.voice] = v }
    fun setVoiceStreets(v: Boolean) = edit { it[K.voiceStreets] = v }
    fun setPodSecondary(v: PodSecondary) = edit { it[K.podSecondary] = v.name }
    fun setPodIdle(v: PodIdle) = edit { it[K.podIdle] = v.name }
    fun setRoutePreference(v: RoutePreference) = edit { it[K.routePref] = v.name }
    fun setAvoidTolls(v: Boolean) = edit { it[K.avoidTolls] = v }
    fun setAvoidFerries(v: Boolean) = edit { it[K.avoidFerries] = v }
    fun setSpeedWarning(v: Boolean) = edit { it[K.speedWarning] = v }
    fun setSpeedTolerance(v: Int) = edit { it[K.speedTolerance] = v }
    fun setMirror(v: Boolean) = edit { it[K.mirror] = v }
    fun setKeepScreenOn(v: Boolean) = edit { it[K.keepScreenOn] = v }
    fun setValhallaUrl(v: String) = edit { it[K.valhallaUrl] = v.trim().trimEnd('/') }
    fun setAutoConnect(v: Boolean) = edit { it[K.autoConnect] = v }
    fun setLastPod(mac: String) = edit { it[K.lastPod] = mac }
    fun addKnownPod(mac: String) = edit { it[K.knownPods] = (it[K.knownPods] ?: emptySet()) + mac; it[K.lastPod] = mac }
    fun forgetPod(mac: String) = edit {
        it[K.knownPods] = (it[K.knownPods] ?: emptySet()) - mac
        if (it[K.lastPod] == mac) it.remove(K.lastPod)
    }
}
