package com.tripnav.app.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.tripnav.app.TripNavApp
import com.tripnav.app.data.PlaceEntity
import com.tripnav.app.nav.Destination
import com.tripnav.app.nav.Route
import com.tripnav.app.search.Place
import com.tripnav.app.service.RideService
import com.tripnav.app.trips.Gpx
import com.tripnav.app.trips.GpxData
import com.tripnav.app.util.LatLon
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class PreviewState(
    val stops: List<Destination> = emptyList(),
    val route: Route? = null,
    val planning: Boolean = false,
    val error: String? = null,
)

class RideViewModel(app: Application) : AndroidViewModel(app) {
    val c = (app as TripNavApp).container

    private val _query = MutableStateFlow("")
    val query: StateFlow<String> = _query.asStateFlow()
    private val _results = MutableStateFlow<List<Place>>(emptyList())
    val results: StateFlow<List<Place>> = _results.asStateFlow()
    private val _searching = MutableStateFlow(false)
    val searching: StateFlow<Boolean> = _searching.asStateFlow()
    private val _preview = MutableStateFlow(PreviewState())
    val preview: StateFlow<PreviewState> = _preview.asStateFlow()
    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()

    val favorites = c.db.places().favorites().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val recents = c.db.places().recents().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private var searchJob: Job? = null
    private var planJob: Job? = null

    private fun here(): LatLon? = c.location.location.value?.let { LatLon(it.latitude, it.longitude) }

    fun setQuery(q: String) {
        _query.value = q
        searchJob?.cancel()
        if (q.isBlank()) { _results.value = emptyList(); _searching.value = false; return }
        searchJob = viewModelScope.launch {
            delay(350)
            _searching.value = true
            _results.value = runCatching { c.data.search(q, here()) }.getOrDefault(emptyList())
            _searching.value = false
        }
    }

    fun clearSearch() { setQuery("") }

    /** Picks a place: replaces the destination, or appends it as an extra stop when [asStop]. */
    fun choose(place: Place, asStop: Boolean = false) {
        val dest = Destination(place.name, place.point)
        val stops = if (asStop) _preview.value.stops + dest else listOf(dest)
        clearSearch()
        rememberPlace(place)
        plan(stops)
    }

    fun choose(p: PlaceEntity, asStop: Boolean = false) = choose(Place(p.name, p.address, LatLon(p.lat, p.lon)), asStop)

    fun dropPin(p: LatLon) {
        viewModelScope.launch {
            val place = runCatching { c.data.reverse(p) }.getOrElse { Place("Dropped pin", "", p) }
            choose(place, asStop = _preview.value.stops.isNotEmpty())
        }
    }

    fun removeStop(index: Int) {
        val stops = _preview.value.stops.toMutableList().apply { if (index in indices) removeAt(index) }
        if (stops.isEmpty()) cancelPreview() else plan(stops)
    }

    fun replan() = plan(_preview.value.stops)

    private fun plan(stops: List<Destination>) {
        if (stops.isEmpty()) return
        _preview.value = PreviewState(stops = stops, planning = true)
        // A newer plan (stop added or removed meanwhile) supersedes this one.
        planJob?.cancel()
        planJob = viewModelScope.launch {
            c.nav.plan(stops)
                .onSuccess { r -> _preview.update { it.copy(route = r, planning = false) } }
                .onFailure { e -> _preview.update { it.copy(planning = false, error = e.message ?: "Routing failed") } }
        }
    }

    fun cancelPreview() { planJob?.cancel(); _preview.value = PreviewState() }

    fun startNavigation() {
        val route = _preview.value.route ?: return
        RideService.start(getApplication())
        c.nav.start(route)
        _preview.value = PreviewState()
    }

    fun stopNavigation() = c.nav.stop()

    fun simulateCurrentRoute(speedKmh: Double = 60.0) {
        val route = (c.nav.state.value as? com.tripnav.app.nav.NavState.Active)?.route ?: _preview.value.route ?: return
        c.location.simulate(route.points, speedKmh)
    }

    fun toggleFavorite(place: Place) = viewModelScope.launch {
        val dao = c.db.places()
        val existing = dao.near(place.point.lat, place.point.lon)
        dao.upsert(
            (existing ?: PlaceEntity(name = place.name, address = place.address, lat = place.point.lat, lon = place.point.lon,
                favorite = false, lastUsed = System.currentTimeMillis())).let { it.copy(favorite = !it.favorite) },
        )
    }

    fun deletePlace(p: PlaceEntity) = viewModelScope.launch { c.db.places().delete(p.id) }

    private fun rememberPlace(place: Place) = viewModelScope.launch {
        val dao = c.db.places()
        val existing = dao.near(place.point.lat, place.point.lon)
        dao.upsert(
            existing?.copy(lastUsed = System.currentTimeMillis())
                ?: PlaceEntity(name = place.name, address = place.address, lat = place.point.lat, lon = place.point.lon,
                    favorite = false, lastUsed = System.currentTimeMillis()),
        )
    }

    /** Text or a link shared from another app. */
    fun handleShared(text: String) = viewModelScope.launch {
        _message.value = "Finding shared place…"
        val place = runCatching { c.data.resolveShared(text, here()) }.getOrNull()
        _message.value = if (place == null) "Couldn't find that place" else null
        place?.let { choose(it) }
    }

    fun handleGeo(uri: String) {
        val q = Regex("[?&]q=([^&]+)").find(uri)?.groupValues?.get(1)?.let { java.net.URLDecoder.decode(it, "UTF-8") }
        if (q != null && com.tripnav.app.search.Geocoder.parseCoordinates(q) == null) {
            viewModelScope.launch {
                runCatching { c.data.search(q, here()) }.getOrNull()?.firstOrNull()?.let { choose(it) }
                    ?: run { _message.value = "No match for \"$q\"" }
            }
        } else handleShared(uri)
    }

    fun routeGpx(data: GpxData) {
        val stops = if (data.track.size >= 2) {
            val vias = Gpx.sampleVias(data.track).drop(1)
            vias.mapIndexed { i, p -> Destination(if (i == vias.lastIndex) data.name ?: "End of GPX route" else "", p) }
        } else data.waypoints.map { Destination(it.first, it.second) }
        if (stops.isEmpty()) { _message.value = "GPX file has no points"; return }
        plan(stops)
    }

    fun consumeMessage() { _message.value = null }
}
