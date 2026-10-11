package com.tripnav.app.nav

import com.tripnav.protocol.PodIcon

/** Maps Valhalla maneuver types (see Valhalla `DirectionsLeg_Maneuver_Type`) to pod icon IDs. */
object ManeuverMapper {
    fun podIcon(m: Maneuver, rightHandTraffic: Boolean): Int = when (m.type) {
        1, 2, 3 -> PodIcon.DEPART
        4 -> PodIcon.DESTINATION
        5 -> PodIcon.DESTINATION_RIGHT
        6 -> PodIcon.DESTINATION_LEFT
        7, 8, 17, 22, 27, 29 -> PodIcon.STRAIGHT
        9 -> PodIcon.SLIGHT_RIGHT
        10 -> PodIcon.TURN_RIGHT
        11 -> PodIcon.SHARP_RIGHT
        12 -> PodIcon.U_TURN_RIGHT
        13 -> PodIcon.U_TURN_LEFT
        14 -> PodIcon.SHARP_LEFT
        15 -> PodIcon.TURN_LEFT
        16 -> PodIcon.SLIGHT_LEFT
        18 -> PodIcon.ON_RAMP_RIGHT
        19 -> PodIcon.ON_RAMP_LEFT
        20 -> PodIcon.OFF_RAMP_RIGHT
        21 -> PodIcon.OFF_RAMP_LEFT
        23 -> PodIcon.KEEP_RIGHT
        24 -> PodIcon.KEEP_LEFT
        25 -> PodIcon.MERGE
        26 -> PodIcon.roundabout(m.roundaboutExit ?: 1, rightHandTraffic)
        28 -> if (m.instruction.contains("train", ignoreCase = true)) PodIcon.FERRY_TRAIN else PodIcon.FERRY
        37 -> PodIcon.MERGE_RIGHT
        38 -> PodIcon.MERGE_LEFT
        else -> PodIcon.STRAIGHT
    }

    /** Maneuvers that are mere continuations and should not be announced or shown as "next". */
    fun isPassive(m: Maneuver) = m.type in setOf(7, 8, 22, 27, 29)
}

/** ISO 3166 countries that drive on the left. */
object TrafficSide {
    private val leftHand = setOf(
        "AG", "AI", "AU", "BB", "BD", "BM", "BN", "BS", "BT", "BW", "CY", "DM", "FJ", "FK", "GB", "GD", "GG", "GY",
        "HK", "ID", "IE", "IM", "IN", "JE", "JM", "JP", "KE", "KI", "KN", "KY", "LC", "LK", "LS", "MO", "MS", "MT",
        "MU", "MV", "MW", "MY", "MZ", "NA", "NP", "NR", "NZ", "PG", "PK", "SB", "SC", "SG", "SH", "SR", "SZ", "TC",
        "TH", "TL", "TO", "TT", "TV", "TZ", "UG", "VC", "VG", "VI", "WS", "ZA", "ZM", "ZW",
    )

    fun isRightHand(countryIso: String?): Boolean = countryIso?.uppercase() !in leftHand
}
