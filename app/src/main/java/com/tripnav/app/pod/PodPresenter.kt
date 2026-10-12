package com.tripnav.app.pod

import android.location.Location
import com.tripnav.app.ble.PodConnection
import com.tripnav.app.ble.PodLinkState
import com.tripnav.app.location.LocationSource
import com.tripnav.app.mirror.MirrorHub
import com.tripnav.app.mirror.MirrorState
import com.tripnav.app.nav.ManeuverMapper
import com.tripnav.app.nav.NavEngine
import com.tripnav.app.nav.NavProgress
import com.tripnav.app.nav.NavState
import com.tripnav.app.nav.SpeedCameraWatch
import com.tripnav.app.settings.AppSettings
import com.tripnav.app.settings.NightMode
import com.tripnav.app.settings.PodIdle
import com.tripnav.app.settings.PodSecondary
import com.tripnav.app.settings.SettingsRepository
import com.tripnav.app.util.Sun
import com.tripnav.protocol.CompassDirection
import com.tripnav.protocol.NavFrame
import com.tripnav.protocol.NavFrameSpec
import com.tripnav.protocol.PodIcon
import com.tripnav.protocol.PodProtocol
import com.tripnav.protocol.SecondaryField
import java.util.Calendar
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/** What the pod is currently being fed; shown in the UI so the rider knows the source. */
enum class PodSource { NONE, TRIPNAV, GOOGLE_MAPS, COMPASS, CLOCK }

/**
 * Decides what the pod displays: TripNav guidance first, then mirrored Google Maps guidance,
 * otherwise the idle compass or clock. Also drives the pod's night mode.
 */
class PodPresenter(
    private val scope: CoroutineScope,
    private val pod: PodConnection,
    private val nav: NavEngine,
    private val mirror: MirrorHub,
    private val location: LocationSource,
    private val settings: SettingsRepository,
    private val cameras: SpeedCameraWatch,
    private val battery: PhoneBattery,
) {
    private var lastBatteryPercent: Int? = null

    @Volatile var source: PodSource = PodSource.NONE
        private set(v) { field = v; pod.guiding = v == PodSource.TRIPNAV || v == PodSource.GOOGLE_MAPS }
    private var lastCompass: CompassDirection = CompassDirection.N

    fun start() {
        scope.launch {
            combine(nav.state, mirror.state, location.location, settings.settings, pod.status) { n, m, l, s, p ->
                Inputs(n, m, l, s, p.state)
            }.collect(::render)
        }
        // Mirror data goes stale silently if Maps is killed; re-evaluate periodically.
        scope.launch {
            while (isActive) {
                delay(5_000)
                checkBattery()
                render(Inputs(nav.state.value, mirror.state.value, location.location.value, settings.settings.value, pod.status.value.state))
            }
        }
        pod.onReady = { scope.launch { render(Inputs(nav.state.value, mirror.state.value, location.location.value, settings.settings.value, PodLinkState.READY)) } }
    }

    private data class Inputs(
        val nav: NavState, val mirror: MirrorState?, val loc: Location?, val s: AppSettings, val link: PodLinkState,
    )

    private fun render(i: Inputs) {
        pod.setNight(isNight(i.s, i.loc))
        if (i.link != PodLinkState.READY) return
        val night = isNight(i.s, i.loc)
        val imperial = i.s.imperial
        when (val n = i.nav) {
            is NavState.Active -> {
                source = PodSource.TRIPNAV
                val p = n.progress
                when {
                    n.rerouting -> pod.show(NavFrame.build(NavFrameSpec(PodIcon.REROUTE, 0, night = night, imperial = imperial)))
                    p == null -> pod.show(PodProtocol.loadingScreen(night))
                    else -> pod.show(NavFrame.build(navSpec(p, i.s, night)))
                }
                return
            }
            is NavState.Routing -> { source = PodSource.TRIPNAV; pod.show(PodProtocol.loadingScreen(night)); return }
            is NavState.Arrived -> {
                source = PodSource.TRIPNAV
                pod.show(NavFrame.build(NavFrameSpec(PodIcon.DESTINATION, 0, night = night, imperial = imperial)))
                return
            }
            else -> Unit
        }
        val m = i.mirror
        if (i.s.mirrorGoogleMaps && m != null && mirror.isFresh()) {
            source = PodSource.GOOGLE_MAPS
            pod.show(NavFrame.build(NavFrameSpec(m.icon, m.distanceM, night = night, imperial = imperial)))
            return
        }
        when (i.s.podIdle) {
            PodIdle.CLOCK -> { if (source != PodSource.CLOCK) pod.showClock(); source = PodSource.CLOCK }
            PodIdle.COMPASS -> {
                source = PodSource.COMPASS
                val l = i.loc
                if (l != null && l.hasBearing() && l.speed > 1.5f) lastCompass = CompassDirection.fromBearing(l.bearing)
                pod.show(PodProtocol.compass(lastCompass, night))
            }
        }
    }

    private fun navSpec(p: NavProgress, s: AppSettings, night: Boolean): NavFrameSpec {
        val rht = nav.rightHand()
        val icon = ManeuverMapper.podIcon(p.upcoming, rht)
        val nextIcon = p.next?.let { ManeuverMapper.podIcon(it, rht) }
        val secondary = when (s.podSecondary) {
            PodSecondary.ARRIVAL_TIME -> Calendar.getInstance().apply { timeInMillis = p.etaEpochMs }
                .let { SecondaryField.ClockTime(it.get(Calendar.HOUR_OF_DAY), it.get(Calendar.MINUTE), amPm = !s.clock24h) }
            PodSecondary.TIME_LEFT -> SecondaryField.Duration(Math.ceil(p.remainingS / 60).toInt())
            PodSecondary.DISTANCE_LEFT -> SecondaryField.Distance(p.remainingM.toInt())
            PodSecondary.NEXT_DISTANCE -> p.next?.let { SecondaryField.Distance(it.lengthM.toInt()) } ?: SecondaryField.None
        }
        // An upcoming speed camera takes over the bottom line with the pod's own camera countdown.
        val camera = cameras.nextCameraM(p.alongM)?.takeIf { s.cameraAlerts }?.let { SecondaryField.SpeedCamera(it.toInt()) }
        return NavFrameSpec(icon, p.distanceToManeuverM.toInt(), nextIcon, camera ?: secondary, night, s.imperial)
    }

    /** Flashes the pod's low-battery screen when the phone drops past 15 %, 10 % and 5 % while discharging. */
    private fun checkBattery() {
        val now = battery.read() ?: return
        val crossed = PhoneBattery.crossed(lastBatteryPercent, now)
        lastBatteryPercent = now.percent
        if (crossed != null && pod.status.value.state == PodLinkState.READY) {
            val night = isNight(settings.settings.value, location.location.value)
            pod.flash(NavFrame.build(NavFrameSpec(PodIcon.LOW_BATTERY, 0, night = night)), BATTERY_FLASH_MS)
        }
    }

    private fun isNight(s: AppSettings, loc: Location?): Boolean = when (s.nightMode) {
        NightMode.ALWAYS_DAY -> false
        NightMode.ALWAYS_NIGHT -> true
        NightMode.AUTO_SUN -> if (loc != null) Sun.isNight(loc.latitude, loc.longitude) else {
            val h = Calendar.getInstance().get(Calendar.HOUR_OF_DAY); h < 6 || h >= 19
        }
    }

    companion object { private const val BATTERY_FLASH_MS = 4_000L }
}
