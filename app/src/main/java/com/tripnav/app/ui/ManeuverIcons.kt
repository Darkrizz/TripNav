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
import androidx.compose.ui.graphics.vector.ImageVector
import com.tripnav.protocol.PodIcon

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
