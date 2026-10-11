package com.tripnav.app.service

import android.Manifest
import android.app.Notification
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.media.AudioManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import com.tripnav.app.MainActivity
import com.tripnav.app.R
import com.tripnav.app.TripNavApp
import com.tripnav.app.ble.PodLinkState
import com.tripnav.app.nav.NavEngine
import com.tripnav.app.nav.NavState
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Foreground service that keeps the pod link, GPS, guidance and trip recording alive
 * while the screen is off or another app (e.g. Google Maps) is in front.
 */
class RideService : LifecycleService() {
    private val c get() = (application as TripNavApp).container
    private var started = false

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        when (intent?.action) {
            ACTION_STOP -> { shutdown(); return START_NOT_STICKY }
            else -> {
                ensureForeground()
                intent?.getStringExtra(EXTRA_ADDRESS)?.let { c.pod.connect(it) }
            }
        }
        return START_STICKY
    }

    private fun ensureForeground() {
        if (started) return
        started = true
        val hasLocation = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        val type = if (Build.VERSION.SDK_INT >= 29) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE or (if (hasLocation) ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION else 0)
        } else 0
        ServiceCompat.startForeground(this, NOTIF_ID, buildNotification("Starting…", null), type)
        if (hasLocation) c.location.acquire(HOLDER)
        c.recorder.start()

        lifecycleScope.launch {
            combine(c.pod.status, c.nav.state, c.settings.settings) { p, n, s -> Triple(p, n, s) }.collect { (p, n, s) ->
                val podText = when (p.state) {
                    PodLinkState.READY -> "Pod connected"
                    PodLinkState.AWAITING_PIN -> "Enter the PIN shown on the pod"
                    PodLinkState.CONNECTING, PodLinkState.HANDSHAKING -> "Connecting to pod…"
                    PodLinkState.RECONNECTING -> "Reconnecting to pod…"
                    PodLinkState.IDLE -> "Pod not connected"
                }
                val navText = when (n) {
                    is NavState.Active -> n.progress?.let {
                        "${NavEngine.formatDistance(it.distanceToManeuverM, s.imperial)} · ${it.upcoming.instruction}"
                    } ?: "Starting guidance…"
                    is NavState.Arrived -> "Arrived"
                    else -> null
                }
                if (androidx.core.app.ActivityCompat.checkSelfPermission(this@RideService, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED || Build.VERSION.SDK_INT < 33) {
                    NotificationManagerCompat.from(this@RideService).notify(NOTIF_ID, buildNotification(podText, navText))
                }
            }
        }
        // Show the call screen on the pod while a phone/VoIP call is in progress (no phone-state permission needed).
        lifecycleScope.launch {
            val audio = getSystemService(AudioManager::class.java)
            while (isActive) {
                val inCall = audio.mode == AudioManager.MODE_IN_CALL || audio.mode == AudioManager.MODE_IN_COMMUNICATION
                c.pod.setCallActive(inCall)
                delay(1_500)
            }
        }
    }

    private fun buildNotification(podText: String, navText: String?): Notification {
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE,
        )
        val stop = PendingIntent.getService(
            this, 1, Intent(this, RideService::class.java).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, TripNavApp.RIDE_CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_ride)
            .setContentTitle(navText ?: getString(R.string.notif_ride_title))
            .setContentText(podText)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_NAVIGATION)
            .setContentIntent(open)
            .addAction(0, "Stop", stop)
            .build()
    }

    private fun shutdown() {
        c.nav.stop()
        c.pod.disconnect()
        c.recorder.stop()
        c.location.release(HOLDER)
        c.location.stopSimulation()
        started = false
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        if (started) { c.recorder.stop(); c.location.release(HOLDER) }
        super.onDestroy()
    }

    companion object {
        private const val NOTIF_ID = 42
        private const val HOLDER = "ride-service"
        const val ACTION_STOP = "com.tripnav.app.STOP"
        const val EXTRA_ADDRESS = "address"

        fun start(context: Context, connectTo: String? = null) {
            val i = Intent(context, RideService::class.java)
            connectTo?.let { i.putExtra(EXTRA_ADDRESS, it) }
            ContextCompat.startForegroundService(context, i)
        }

        fun stop(context: Context) {
            context.startService(Intent(context, RideService::class.java).setAction(ACTION_STOP))
        }
    }
}
