package com.tripnav.app.pod

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager

/** Phone battery level and charging state, read from the sticky battery broadcast (no permission needed). */
class PhoneBattery(private val context: Context) {
    data class Status(val percent: Int, val charging: Boolean)

    fun read(): Status? {
        val i = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED)) ?: return null
        val level = i.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        val scale = i.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
        if (level < 0 || scale <= 0) return null
        val status = i.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
        val charging = status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL
        return Status(level * 100 / scale, charging)
    }

    companion object {
        /** Warn on the pod once when the level drops past each of these. */
        val THRESHOLDS = listOf(15, 10, 5)

        /** The threshold newly crossed going from [previous] to [now], if any (only while discharging). */
        fun crossed(previous: Int?, now: Status): Int? {
            if (now.charging || previous == null) return null
            return THRESHOLDS.firstOrNull { t -> previous > t && now.percent <= t }
        }
    }
}
