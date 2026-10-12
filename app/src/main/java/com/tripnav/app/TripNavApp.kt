package com.tripnav.app

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.telephony.TelephonyManager
import com.tripnav.app.ble.PodConnection
import com.tripnav.app.ble.PodScanner
import com.tripnav.app.data.TripNavDatabase
import com.tripnav.app.location.LocationSource
import com.tripnav.app.mirror.MirrorHub
import com.tripnav.app.nav.NavEngine
import com.tripnav.app.nav.TrafficSide
import com.tripnav.app.nav.ValhallaClient
import com.tripnav.app.nav.VoiceGuide
import com.tripnav.app.offline.DataPolicy
import com.tripnav.app.offline.OfflineRouter
import com.tripnav.app.offline.OfflineSearch
import com.tripnav.app.offline.PackStore
import com.tripnav.app.pod.PodPresenter
import com.tripnav.app.search.Geocoder
import com.tripnav.app.settings.SettingsRepository
import com.tripnav.app.trips.TripRecorder
import com.tripnav.app.util.Geo
import com.tripnav.app.util.LatLon
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import java.util.Locale
import java.util.concurrent.TimeUnit

class TripNavApp : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(RIDE_CHANNEL, getString(R.string.channel_ride), NotificationManager.IMPORTANCE_LOW),
        )
        container.start()
    }

    companion object {
        const val RIDE_CHANNEL = "ride"
    }
}

/** Manual dependency graph; everything here lives for the whole process. */
class AppContainer(private val context: Context) {
    /** Main-thread scope: state machines that touch UI-observed flows run here. */
    val mainScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    val ioScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    val http: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .addInterceptor { chain ->
            chain.proceed(chain.request().newBuilder().header("User-Agent", Geocoder.UA).build())
        }
        .build()

    val settings = SettingsRepository(context, ioScope)
    val db = TripNavDatabase.create(context)
    val location = LocationSource(context, mainScope)
    val scanner = PodScanner(context)
    val pod = PodConnection(context, settings)
    val geocoder = Geocoder(http)
    val valhalla = ValhallaClient()
    val packs = PackStore(context, http, ioScope)
    val offlineRouter = OfflineRouter(context)
    val offlineSearch = OfflineSearch()
    val data = DataPolicy(context, http, settings, packs, offlineRouter, offlineSearch, geocoder)
    val mirror = MirrorHub(ioScope, db.learnedIcons())
    val recorder = TripRecorder(ioScope, db.trips())

    @Volatile private var countryIso: String? = initialCountry()
    private var countryCheckedAt: LatLon? = null
    fun rightHandTraffic(): Boolean {
        val here = location.location.value?.let { LatLon(it.latitude, it.longitude) }
        return here?.let { data.rightHandTrafficAt(it) } ?: TrafficSide.isRightHand(countryIso)
    }

    val nav = NavEngine(mainScope, valhalla, settings, location, data::routing, ::rightHandTraffic)

    /** Deletes a zone after releasing the routing engine and search database that may hold it open. */
    fun deleteZone(id: String) {
        offlineRouter.close()
        offlineSearch.close(id)
        packs.delete(id)
    }
    val voice by lazy { VoiceGuide(context, settings) }
    val presenter = PodPresenter(mainScope, pod, nav, mirror, location, settings)

    fun start() {
        nav.announcer = com.tripnav.app.nav.Announcer { text, urgent -> voice.announce(text, urgent) }
        presenter.start()
        // A zone was installed, updated or deleted: drop cached search handles so the new files are used.
        mainScope.launch { packs.installed.collect { offlineSearch.close() } }
        // Offline-only mode: tell MapLibre not to attempt any network requests.
        mainScope.launch {
            settings.settings.collect { s ->
                org.maplibre.android.MapLibre.getInstance(context)
                org.maplibre.android.MapLibre.setConnected(if (s.dataMode == com.tripnav.app.settings.DataMode.OFFLINE) false else null)
            }
        }
        // Keep the traffic side right when riding across borders (only re-checked every 50 km).
        mainScope.launch {
            location.location.filterNotNull().collect { loc ->
                val here = LatLon(loc.latitude, loc.longitude)
                val last = countryCheckedAt
                if ((last == null || Geo.distance(last, here) > 50_000) && data.rightHandTrafficAt(here) == null && data.countryLookupAllowed()) {
                    countryCheckedAt = here
                    ioScope.launch { geocoder.countryCode(here)?.let { countryIso = it } }
                }
                recorder.onLocation(loc)
            }
        }
    }

    private fun initialCountry(): String? {
        val tm = context.getSystemService(TelephonyManager::class.java)
        return tm?.networkCountryIso?.takeIf { it.isNotBlank() } ?: Locale.getDefault().country.takeIf { it.isNotBlank() }
    }
}
