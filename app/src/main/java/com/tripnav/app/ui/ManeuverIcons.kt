package com.tripnav.app.ui

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DirectionsBoat
import androidx.compose.material.icons.filled.Explore
import androidx.compose.material.icons.filled.ForkLeft
import androidx.compose.material.icons.filled.ForkRight
import androidx.compose.material.icons.filled.Merge
import androidx.compose.material.icons.filled.NearMe
import androidx.compose.material.icons.filled.Place
import androidx.compose.material.icons.filled.RampLeft
import androidx.compose.material.icons.filled.RampRight
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.RoundaboutLeft
import androidx.compose.material.icons.filled.RoundaboutRight
import androidx.compose.material.icons.filled.SignalCellularOff
import androidx.compose.material.icons.filled.Straight
import androidx.compose.material.icons.filled.Train
import androidx.compose.material.icons.filled.TurnLeft
import androidx.compose.material.icons.filled.TurnRight
import androidx.compose.material.icons.filled.TurnSharpLeft
import androidx.compose.material.icons.filled.TurnSharpRight
import androidx.compose.material.icons.filled.TurnSlightLeft
import androidx.compose.material.icons.filled.TurnSlightRight
import androidx.compose.material.icons.filled.UTurnLeft
import androidx.compose.material.icons.filled.UTurnRight
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.sp
import com.tripnav.app.nav.Maneuver
import com.tripnav.protocol.PodIcon
import kotlin.math.cos
import kotlin.math.sin

/** Phone-side rendering of the same icon IDs the pod gets, so phone and pod always agree. */
fun maneuverVector(icon: Int): ImageVector = when (icon) {
    PodIcon.DESTINATION, PodIcon.DESTINATION_LEFT, PodIcon.DESTINATION_RIGHT -> Icons.Filled.Place
    PodIcon.TURN_LEFT -> Icons.Filled.TurnLeft
    PodIcon.TURN_RIGHT -> Icons.Filled.TurnRight
    PodIcon.SHARP_LEFT -> Icons.Filled.TurnSharpLeft
    PodIcon.SHARP_RIGHT -> Icons.Filled.TurnSharpRight
    PodIcon.SLIGHT_LEFT, PodIcon.KEEP_LEFT -> Icons.Filled.TurnSlightLeft
    PodIcon.SLIGHT_RIGHT, PodIcon.KEEP_RIGHT -> Icons.Filled.TurnSlightRight
    PodIcon.U_TURN_LEFT -> Icons.Filled.UTurnLeft
    PodIcon.U_TURN_RIGHT -> Icons.Filled.UTurnRight
    PodIcon.FORK_LEFT -> Icons.Filled.ForkLeft
    PodIcon.FORK_RIGHT -> Icons.Filled.ForkRight
    PodIcon.MERGE, PodIcon.MERGE_LEFT, PodIcon.MERGE_RIGHT -> Icons.Filled.Merge
    PodIcon.ON_RAMP_LEFT, PodIcon.ON_RAMP_SLIGHT_LEFT, PodIcon.ON_RAMP_SHARP_LEFT,
    PodIcon.OFF_RAMP_LEFT, PodIcon.OFF_RAMP_SLIGHT_LEFT, PodIcon.OFF_RAMP_SHARP_LEFT -> Icons.Filled.RampLeft
    PodIcon.ON_RAMP_RIGHT, PodIcon.ON_RAMP_SLIGHT_RIGHT, PodIcon.ON_RAMP_SHARP_RIGHT,
    PodIcon.OFF_RAMP_RIGHT, PodIcon.OFF_RAMP_SLIGHT_RIGHT, PodIcon.OFF_RAMP_SHARP_RIGHT -> Icons.Filled.RampRight
    in (PodIcon.ROUNDABOUT_CW_BASE + 1)..(PodIcon.ROUNDABOUT_CW_BASE + 9) -> Icons.Filled.RoundaboutLeft
    in (PodIcon.ROUNDABOUT_CCW_BASE + 1)..(PodIcon.ROUNDABOUT_CCW_BASE + 9) -> Icons.Filled.RoundaboutRight
    PodIcon.FERRY -> Icons.Filled.DirectionsBoat
    PodIcon.FERRY_TRAIN -> Icons.Filled.Train
    PodIcon.REROUTE -> Icons.Filled.Refresh
    PodIcon.DEPART -> Icons.Filled.NearMe
    PodIcon.COMPASS -> Icons.Filled.Explore
    PodIcon.NO_DATA -> Icons.Filled.SignalCellularOff
    else -> Icons.Filled.Straight
}

/** Roundabout exit number for an icon, or null if it is not a roundabout. */
fun roundaboutExit(icon: Int): Int? = when (icon) {
    in (PodIcon.ROUNDABOUT_CW_BASE + 1)..(PodIcon.ROUNDABOUT_CW_BASE + 9) -> icon - PodIcon.ROUNDABOUT_CW_BASE
    in (PodIcon.ROUNDABOUT_CCW_BASE + 1)..(PodIcon.ROUNDABOUT_CCW_BASE + 9) -> icon - PodIcon.ROUNDABOUT_CCW_BASE
    else -> null
}

/**
 * The maneuver arrow. Roundabouts with a known exit angle are drawn as they really are: the way in from below,
 * the path around in the direction of traffic, and the exit arm where it actually leaves, with the exit number
 * in the middle. Everything else uses the matching vector icon.
 */
@Composable
fun ManeuverGlyph(m: Maneuver, icon: Int, tint: Color, size: Dp, showExitNumber: Boolean = true) {
    val angle = m.exitAngle
    val exit = roundaboutExit(icon)
    if (exit == null || angle == null) {
        Icon(maneuverVector(icon), null, tint = tint, modifier = Modifier.size(size))
        return
    }
    val clockwise = icon in (PodIcon.ROUNDABOUT_CW_BASE + 1)..(PodIcon.ROUNDABOUT_CW_BASE + 9)
    Box(Modifier.size(size), contentAlignment = Alignment.Center) {
        Canvas(Modifier.size(size)) { drawRoundabout(angle, clockwise, exit, tint) }
        if (showExitNumber) {
            Text("$exit", color = tint, style = TextStyle(fontWeight = FontWeight.Bold, fontSize = (size.value * 0.2f).sp))
        }
    }
}

/**
 * Sweep in degrees from the entry (bottom of the ring) to an exit leaving at [angle] (0 ahead, +90 right).
 * Counter-clockwise rings reach right-hand exits first, clockwise ones left-hand exits.
 */
fun roundaboutSweep(angle: Int, clockwise: Boolean, exit: Int): Float {
    val a = ((angle % 360) + 540) % 360 - 180 // -180..179
    var sweep = if (clockwise) a + 180 else 180 - a
    // An exit right beside the way in is a U-turn when other exits come first.
    if (sweep < 30) sweep = if (exit >= 2) MAX_SWEEP else 30
    // Keep a U-turn's exit arm clear of the way in.
    if (sweep > MAX_SWEEP) sweep = MAX_SWEEP
    return sweep.toFloat()
}

private const val MAX_SWEEP = 315

private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawRoundabout(angle: Int, clockwise: Boolean, exit: Int, tint: Color) {
    val s = size.minDimension
    val c = Offset(size.width / 2, size.height / 2)
    val r = s * 0.17f
    val w = s * 0.08f
    val armEnd = s * 0.44f
    val sweep = roundaboutSweep(angle, clockwise, exit)
    // Heading of the exit on the ring, clockwise from "up"; the entry sits at 180 (bottom).
    val exitHeading = if (clockwise) 180f + sweep else 180f - sweep
    fun onCircle(heading: Float, radius: Float): Offset {
        val rad = Math.toRadians(heading.toDouble())
        return Offset(c.x + radius * sin(rad).toFloat(), c.y - radius * cos(rad).toFloat())
    }
    // The whole ring, faint, so the rider sees the roundabout shape.
    drawCircle(tint.copy(alpha = 0.32f), r, c, style = Stroke(w * 0.6f))
    val path = Path().apply {
        val entryStart = onCircle(180f, armEnd)
        moveTo(entryStart.x, entryStart.y)
        val entry = onCircle(180f, r)
        lineTo(entry.x, entry.y)
        // Compose arcs start at 3 o'clock and sweep clockwise on screen; heading 180 (bottom) is 90 there.
        arcTo(Rect(c, r), 90f, if (clockwise) sweep else -sweep, false)
        val tip = onCircle(exitHeading, armEnd * 0.86f)
        lineTo(tip.x, tip.y)
    }
    drawPath(path, tint, style = Stroke(w, cap = StrokeCap.Round, join = StrokeJoin.Round))
    // Arrow head on the exit arm.
    val rad = Math.toRadians(exitHeading.toDouble())
    val dir = Offset(sin(rad).toFloat(), -cos(rad).toFloat())
    val normal = Offset(-dir.y, dir.x)
    val tip = onCircle(exitHeading, armEnd + w * 0.4f)
    val base = tip - dir * (w * 2.6f)
    val head = Path().apply {
        moveTo(tip.x, tip.y)
        lineTo(base.x + normal.x * w * 1.6f, base.y + normal.y * w * 1.6f)
        lineTo(base.x - normal.x * w * 1.6f, base.y - normal.y * w * 1.6f)
        close()
    }
    drawPath(head, tint)
}
