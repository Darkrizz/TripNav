package com.tripnav.app.trips

import android.location.Location
import com.tripnav.app.data.TripDao
import com.tripnav.app.data.TripEntity
import com.tripnav.app.util.Geo
import com.tripnav.app.util.LatLon
import com.tripnav.app.util.Polyline
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class LiveTrip(
    val startedAt: Long = 0,
    val distanceM: Double = 0.0,
    val movingSeconds: Long = 0,
    val maxSpeedKmh: Double = 0.0,
    val avgMovingKmh: Double = 0.0,
    val recording: Boolean = false,
)

/** Records the ride (distance, moving time, max speed, track) while the ride service runs. */
class TripRecorder(private val scope: CoroutineScope, private val dao: TripDao) {
    private val _live = MutableStateFlow(LiveTrip())
    val live: StateFlow<LiveTrip> = _live.asStateFlow()

    private val track = ArrayList<LatLon>()
    private var last: Location? = null
    private var lastStored: LatLon? = null

    fun start() {
        if (_live.value.recording) return
        track.clear(); last = null; lastStored = null
        _live.value = LiveTrip(startedAt = System.currentTimeMillis(), recording = true)
    }

    fun onLocation(loc: Location) {
        val cur = _live.value
        if (!cur.recording) return
        if (loc.hasAccuracy() && loc.accuracy > 35f) return
        val here = LatLon(loc.latitude, loc.longitude)
        val prev = last
        last = loc
        if (prev == null) { store(here); return }
        val d = Geo.distance(prev.latitude, prev.longitude, loc.latitude, loc.longitude)
        val dt = ((loc.time - prev.time) / 1000).coerceIn(0, 30)
        val speedKmh = if (loc.hasSpeed()) loc.speed * 3.6 else if (dt > 0) d / dt * 3.6 else 0.0
        val moving = speedKmh > 4 && d > 1.5
        if (!moving) return
        val dist = cur.distanceM + d
        val movingS = cur.movingSeconds + dt
        _live.value = cur.copy(
            distanceM = dist, movingSeconds = movingS,
            maxSpeedKmh = maxOf(cur.maxSpeedKmh, speedKmh.coerceAtMost(300.0)),
            avgMovingKmh = if (movingS > 0) dist / movingS * 3.6 else 0.0,
        )
        val ls = lastStored
        if (ls == null || Geo.distance(ls, here) > 20) store(here)
    }

    private fun store(p: LatLon) { track += p; lastStored = p }

    /** Stops recording and saves the trip if it is long enough to be meaningful. */
    fun stop() {
        val cur = _live.value
        if (!cur.recording) return
        _live.value = cur.copy(recording = false)
        if (cur.distanceM < 300 || track.size < 2) return
        val points = ArrayList(track)
        val fmt = SimpleDateFormat("EEE d MMM, HH:mm", Locale.getDefault())
        scope.launch {
            dao.insert(
                TripEntity(
                    startedAt = cur.startedAt, endedAt = System.currentTimeMillis(), distanceM = cur.distanceM,
                    movingSeconds = cur.movingSeconds, maxSpeedKmh = cur.maxSpeedKmh,
                    track = Polyline.encode(points, 5), title = "Ride · " + fmt.format(Date(cur.startedAt)),
                ),
            )
        }
    }
}
