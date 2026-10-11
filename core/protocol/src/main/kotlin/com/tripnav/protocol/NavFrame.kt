package com.tripnav.protocol

/** Icon IDs understood by the pod (byte 2 / byte 7 of a navigate frame). */
object PodIcon {
    const val DESTINATION = 0
    const val DESTINATION_LEFT = 1
    const val DESTINATION_RIGHT = 2
    const val MERGE_RIGHT = 3
    const val MERGE_LEFT = 4
    const val FORK_RIGHT = 5
    const val FORK_LEFT = 6
    const val OFF_RAMP_RIGHT = 7
    const val OFF_RAMP_LEFT = 8
    const val STRAIGHT = 9
    const val ROUNDABOUT_CW_BASE = 10
    const val TURN_LEFT = 20
    const val TURN_RIGHT = 21
    const val SHARP_LEFT = 22
    const val SHARP_RIGHT = 23
    const val SLIGHT_LEFT = 24
    const val SLIGHT_RIGHT = 25
    const val U_TURN_RIGHT = 26
    const val MERGE = 27
    const val REROUTE = 28
    const val ON_RAMP_RIGHT = 29
    const val ON_RAMP_LEFT = 30
    const val ON_RAMP_SLIGHT_RIGHT = 31
    const val ON_RAMP_SLIGHT_LEFT = 32
    const val ON_RAMP_SHARP_RIGHT = 33
    const val ON_RAMP_SHARP_LEFT = 34
    const val KEEP_LEFT = 43
    const val KEEP_RIGHT = 44
    const val OFF_RAMP_SLIGHT_RIGHT = 45
    const val OFF_RAMP_SLIGHT_LEFT = 46
    const val OFF_RAMP_SHARP_RIGHT = 47
    const val OFF_RAMP_SHARP_LEFT = 48
    const val ROUNDABOUT_CCW_BASE = 49
    const val DEPART = 60
    const val U_TURN_LEFT = 61
    const val FERRY = 62
    const val FERRY_TRAIN = 63
    const val COMPASS = 65
    const val NO_DATA = 66
    const val LOW_BATTERY = 68
    /** Byte 7 value telling the pod that bytes 11..13 hold a speed-camera distance. */
    const val SPEED_CAMERA_MARKER = 60

    /** Roundabout icon for exit [exit] (1..9). Counter-clockwise for right-hand traffic. */
    fun roundabout(exit: Int, rightHandTraffic: Boolean): Int {
        val e = exit.coerceIn(1, 9)
        return (if (rightHandTraffic) ROUNDABOUT_CCW_BASE else ROUNDABOUT_CW_BASE) + e
    }

    val names: Map<Int, String> = linkedMapOf(
        DESTINATION to "Destination", DESTINATION_LEFT to "Destination left", DESTINATION_RIGHT to "Destination right",
        MERGE_RIGHT to "Merge right", MERGE_LEFT to "Merge left", FORK_RIGHT to "Fork right", FORK_LEFT to "Fork left",
        OFF_RAMP_RIGHT to "Exit right", OFF_RAMP_LEFT to "Exit left", STRAIGHT to "Straight",
        TURN_LEFT to "Turn left", TURN_RIGHT to "Turn right", SHARP_LEFT to "Sharp left", SHARP_RIGHT to "Sharp right",
        SLIGHT_LEFT to "Slight left", SLIGHT_RIGHT to "Slight right", U_TURN_RIGHT to "U-turn (right)",
        MERGE to "Merge", REROUTE to "Rerouting", ON_RAMP_RIGHT to "Ramp right", ON_RAMP_LEFT to "Ramp left",
        ON_RAMP_SLIGHT_RIGHT to "Ramp slight right", ON_RAMP_SLIGHT_LEFT to "Ramp slight left",
        ON_RAMP_SHARP_RIGHT to "Ramp sharp right", ON_RAMP_SHARP_LEFT to "Ramp sharp left",
        KEEP_LEFT to "Keep left", KEEP_RIGHT to "Keep right",
        OFF_RAMP_SLIGHT_RIGHT to "Exit slight right", OFF_RAMP_SLIGHT_LEFT to "Exit slight left",
        OFF_RAMP_SHARP_RIGHT to "Exit sharp right", OFF_RAMP_SHARP_LEFT to "Exit sharp left",
        DEPART to "Depart", U_TURN_LEFT to "U-turn (left)", FERRY to "Ferry", FERRY_TRAIN to "Car train",
        COMPASS to "Compass", NO_DATA to "No data", LOW_BATTERY to "Low battery",
    ) + (1..9).associate { ROUNDABOUT_CW_BASE + it to "Roundabout CW exit $it" } +
        (1..9).associate { ROUNDABOUT_CCW_BASE + it to "Roundabout CCW exit $it" }
}

enum class DistanceUnit(val code: Int) { METERS(1), HECTOMETERS(2), TENTH_MILES(3), FEET(4) }

/** A distance as the pod shows it: a 16-bit value plus its unit code. */
data class PodDistance(val value: Int, val unit: DistanceUnit) {
    companion object {
        /**
         * Metric: metres up to 999 m, then tenths of a km. Imperial: feet up to 550 ft, then tenths of a mile.
         * [round] snaps to 10 m below 100 m and to 50 m above, which keeps the display from flickering.
         */
        fun encode(meters: Int, imperial: Boolean, round: Boolean = false): PodDistance {
            var m = meters.coerceAtLeast(0)
            if (round) m = if (m <= 100) ((m + 5) / 10) * 10 else ((m + 25) / 50) * 50
            val (v, u) = if (imperial) {
                val feet = (m * 3.281).toInt()
                if (feet <= 550) feet to DistanceUnit.FEET else ((m * 10) / 1609.344).toInt() to DistanceUnit.TENTH_MILES
            } else {
                if (m <= 999) m to DistanceUnit.METERS else (m / 100) to DistanceUnit.HECTOMETERS
            }
            return PodDistance(v.coerceIn(0, 0xFFFF), u)
        }
    }
}

/** What the secondary field (bytes 11..13) of a navigate frame shows. */
sealed interface SecondaryField {
    data object None : SecondaryField
    data class Distance(val meters: Int) : SecondaryField
    data class Duration(val minutes: Int) : SecondaryField
    data class ClockTime(val hour24: Int, val minute: Int, val amPm: Boolean) : SecondaryField
    data class SpeedCamera(val meters: Int) : SecondaryField
}

data class NavFrameSpec(
    val icon: Int,
    val distanceMeters: Int,
    val nextIcon: Int? = null,
    val secondary: SecondaryField = SecondaryField.None,
    val night: Boolean = false,
    val imperial: Boolean = false,
)

object NavFrame {
    /** Proximity bar shown under the arrow; fills as the maneuver approaches. */
    fun proximity(distanceMeters: Int): Int = when {
        distanceMeters <= 10 -> 80
        distanceMeters <= 20 -> 64
        distanceMeters <= 45 -> 48
        distanceMeters <= 70 -> 32
        distanceMeters <= 95 -> 16
        else -> 0
    }

    fun build(spec: NavFrameSpec): ByteArray {
        val f = ByteArray(PodProtocol.FRAME_SIZE)
        f[0] = PodProtocol.OP_NAVIGATE.toByte()
        f[1] = 0x11
        f[2] = spec.icon.toByte()
        val d = PodDistance.encode(spec.distanceMeters, spec.imperial)
        f[3] = (d.value ushr 8).toByte(); f[4] = d.value.toByte(); f[5] = d.unit.code.toByte()
        f[6] = (proximity(spec.distanceMeters) or (if (spec.night) 1 else 0)).toByte()
        val secondary = spec.secondary
        f[7] = when {
            secondary is SecondaryField.SpeedCamera -> PodIcon.SPEED_CAMERA_MARKER
            spec.nextIcon != null -> spec.nextIcon
            else -> 0xFF
        }.toByte()
        f[8] = 0xFF.toByte(); f[9] = 0xFF.toByte()
        f[10] = 0x41
        when (secondary) {
            SecondaryField.None -> { f[11] = 0xFF.toByte(); f[12] = 0xFF.toByte(); f[13] = 0xFF.toByte() }
            is SecondaryField.Distance -> putDistance(f, secondary.meters, spec.imperial)
            is SecondaryField.SpeedCamera -> putDistance(f, secondary.meters, spec.imperial)
            is SecondaryField.Duration -> {
                val mins = secondary.minutes.coerceAtLeast(1)
                f[11] = (mins / 60).coerceIn(0, 23).toByte(); f[12] = (mins % 60).toByte(); f[13] = 0
            }
            is SecondaryField.ClockTime -> {
                val hourByte = if (secondary.amPm) {
                    val h12 = secondary.hour24 % 12
                    if (secondary.hour24 >= 12) h12 + 128 else h12 + 64
                } else secondary.hour24.coerceIn(0, 23)
                f[11] = hourByte.toByte(); f[12] = secondary.minute.coerceIn(0, 59).toByte(); f[13] = 0
            }
        }
        return PodProtocol.seal(f)
    }

    private fun putDistance(f: ByteArray, meters: Int, imperial: Boolean) {
        val d = PodDistance.encode(meters, imperial)
        f[11] = (d.value ushr 8).toByte(); f[12] = d.value.toByte(); f[13] = d.unit.code.toByte()
    }
}
