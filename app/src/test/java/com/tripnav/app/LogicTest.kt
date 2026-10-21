package com.tripnav.app

import com.tripnav.app.mirror.MirrorParser
import com.tripnav.app.nav.Destination
import com.tripnav.app.nav.Maneuver
import com.tripnav.app.nav.ManeuverMapper
import com.tripnav.app.nav.Route
import com.tripnav.app.nav.RouteFollower
import com.tripnav.app.nav.TrafficSide
import com.tripnav.app.nav.Roundabouts
import com.tripnav.app.search.Geocoder
import com.tripnav.app.search.Place
import com.tripnav.app.search.SharedPlace
import com.tripnav.app.search.SharedText
import com.tripnav.app.ui.roundaboutSweep
import com.tripnav.app.trips.Gpx
import com.tripnav.app.util.Geo
import com.tripnav.app.util.LatLon
import com.tripnav.app.util.Polyline
import com.tripnav.app.util.Sun
import com.tripnav.protocol.PodIcon
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class LogicTest {
    @Test fun polylineRoundTrip() {
        val pts = listOf(LatLon(-23.55052, -46.633308), LatLon(-23.551, -46.634), LatLon(51.5007, -0.1246))
        val back = Polyline.decode(Polyline.encode(pts, 6), 6)
        pts.zip(back).forEach { (a, b) -> assertTrue(Geo.distance(a, b) < 0.5) }
    }

    @Test fun polylineKnownVector() {
        // Google's reference example, precision 5.
        val pts = Polyline.decode("_p~iF~ps|U_ulLnnqC_mqNvxq`@", 5)
        assertEquals(3, pts.size)
        assertEquals(38.5, pts[0].lat, 1e-6); assertEquals(-120.2, pts[0].lon, 1e-6)
        assertEquals(43.252, pts[2].lat, 1e-6)
    }

    @Test fun coordinateParsing() {
        assertEquals(LatLon(12.9716, 77.5946), Geocoder.parseCoordinates("12.9716, 77.5946"))
        assertNotNull(Geocoder.parseCoordinates("https://www.google.com/maps/place/X/@48.858370,2.294481,17z"))
        assertEquals(LatLon(48.8583701, 2.2944813),
            Geocoder.parseCoordinates("https://www.google.com/maps/place/Eiffel/data=!3d48.8583701!4d2.2944813"))
        assertEquals(null, Geocoder.parseCoordinates("Pizza near me"))
    }

    @Test fun mapsNotificationParsing() {
        val t = MirrorParser.parse("300 m", "Turn left onto Main Street")!!
        assertEquals(300, t.distanceM); assertEquals(PodIcon.TURN_LEFT, t.icon)
        val r = MirrorParser.parse("1.2 km", "At the roundabout, take the 2nd exit onto A4")!!
        assertEquals(1200, r.distanceM); assertEquals(PodIcon.roundabout(2, true), r.icon)
        assertEquals(PodIcon.SLIGHT_RIGHT, MirrorParser.parse("500 ft", "Slight right toward I-95")!!.icon)
        assertEquals(PodIcon.TURN_RIGHT, MirrorParser.parse("150 m", "Vire à direita na Rua Augusta")!!.icon)
        assertEquals(PodIcon.roundabout(3, false), MirrorParser.parse("200 m", "At the roundabout, take the third exit", rightHand = false)!!.icon)
        val near = MirrorParser.parse("Turn right", "onto MG Road")!!
        assertEquals(null, near.distanceM); assertEquals(PodIcon.TURN_RIGHT, near.icon)
        assertEquals(null, MirrorParser.parse("", ""))
    }

    @Test fun valhallaManeuverMapping() {
        fun m(type: Int, exit: Int? = null) = Maneuver(type, "", null, null, null, 0.0, 0.0, 0, 0, exit)
        assertEquals(PodIcon.TURN_RIGHT, ManeuverMapper.podIcon(m(10), true))
        assertEquals(PodIcon.TURN_LEFT, ManeuverMapper.podIcon(m(15), true))
        assertEquals(PodIcon.ROUNDABOUT_CCW_BASE + 3, ManeuverMapper.podIcon(m(26, 3), true))
        assertEquals(PodIcon.ROUNDABOUT_CW_BASE + 3, ManeuverMapper.podIcon(m(26, 3), false))
        assertEquals(PodIcon.DESTINATION, ManeuverMapper.podIcon(m(4), true))
        assertTrue(TrafficSide.isRightHand("BR")); assertTrue(!TrafficSide.isRightHand("in"))
    }

    @Test fun followerTracksProgress() {
        // A straight 1 km road heading east along the equator.
        val pts = (0..10).map { LatLon(0.0, it * 0.0009) }
        val route = Route(pts, emptyList(), 1000.0, 100.0, listOf(Destination("end", pts.last())))
        val f = RouteFollower(route)
        val s1 = f.snap(LatLon(0.0001, 0.0045), 90f)
        assertEquals(500.0, s1.alongM, 5.0)
        assertTrue(s1.offsetM in 9.0..13.0)
        val s2 = f.snap(LatLon(0.0, 0.0081), 90f)
        assertEquals(900.0, s2.alongM, 5.0)
    }

    @Test fun gpxSampling() {
        val pts = (0 until 100).map { LatLon(it.toDouble(), 0.0) }
        val v = Gpx.sampleVias(pts, 10)
        assertEquals(10, v.size); assertEquals(pts.first(), v.first()); assertEquals(pts.last(), v.last())
    }

    @Test fun sunNightDetection() {
        // London, 2026-06-21: noon is day, 01:00 UTC is night.
        assertTrue(!Sun.isNight(51.5, -0.12, 1_782_043_200_000L))
        assertTrue(Sun.isNight(51.5, -0.12, 1_782_003_600_000L))
    }

    @Test
    fun sunDayNight() {
        fun ist(h: Int, m: Int = 0) = java.time.ZonedDateTime.of(2026, 10, 12, h, m, 0, 0, java.time.ZoneId.of("Asia/Kolkata")).toInstant().toEpochMilli()
        val (lat, lon) = 12.97 to 77.59 // Bengaluru: sunrise ≈ 06:08, sunset ≈ 18:02 IST
        assertEquals(true, com.tripnav.app.util.Sun.isNight(lat, lon, ist(5, 30)))
        assertEquals(false, com.tripnav.app.util.Sun.isNight(lat, lon, ist(6, 30)))
        assertEquals(false, com.tripnav.app.util.Sun.isNight(lat, lon, ist(10)))
        assertEquals(false, com.tripnav.app.util.Sun.isNight(lat, lon, ist(14)))
        assertEquals(false, com.tripnav.app.util.Sun.isNight(lat, lon, ist(17, 40)))
        assertEquals(true, com.tripnav.app.util.Sun.isNight(lat, lon, ist(18, 30)))
        assertEquals(true, com.tripnav.app.util.Sun.isNight(lat, lon, ist(23, 50)))
        assertEquals(true, com.tripnav.app.util.Sun.isNight(lat, lon, ist(0, 30)))
        // West of Greenwich too (New York, 12 Oct: sunrise ≈ 07:08, sunset ≈ 18:22 EDT).
        val ny = { h: Int -> java.time.ZonedDateTime.of(2026, 10, 12, h, 0, 0, 0, java.time.ZoneId.of("America/New_York")).toInstant().toEpochMilli() }
        assertEquals(false, com.tripnav.app.util.Sun.isNight(40.71, -74.0, ny(12)))
        assertEquals(true, com.tripnav.app.util.Sun.isNight(40.71, -74.0, ny(21)))
        assertEquals(true, com.tripnav.app.util.Sun.isNight(40.71, -74.0, ny(5)))
    }

    @Test
    fun speedCamerasAlongRoute() {
        // Straight 2 km road heading north; cameras on it, beside it, and far off it.
        val pts = (0..20).map { com.tripnav.app.util.LatLon(19.0 + it * 0.0009, 72.8) }
        val route = Route(pts, emptyList(), 2000.0, 120.0, emptyList())
        val onRoad = com.tripnav.app.util.LatLon(19.0 + 5 * 0.0009, 72.8)        // ~500 m along
        val beside = com.tripnav.app.util.LatLon(19.0 + 15 * 0.0009, 72.80015)   // ~16 m off, ~1500 m along
        val far = com.tripnav.app.util.LatLon(19.0 + 10 * 0.0009, 72.81)         // ~1 km off
        val along = com.tripnav.app.nav.CameraMatcher.along(route, listOf(beside, far, onRoad))
        assertEquals(2, along.size)
        assertEquals(500.0, along[0], 5.0)
        assertEquals(1500.0, along[1], 5.0)
        assertEquals(300.0, com.tripnav.app.nav.CameraMatcher.nextAhead(along, 200.0)!!, 5.0)
        assertEquals(null, com.tripnav.app.nav.CameraMatcher.nextAhead(along, 0.0, rangeM = 400.0))
        assertEquals(null, com.tripnav.app.nav.CameraMatcher.nextAhead(along, 1600.0))
    }

    @Test
    fun batteryThresholds() {
        val b = com.tripnav.app.pod.PhoneBattery
        assertEquals(15, b.crossed(16, com.tripnav.app.pod.PhoneBattery.Status(15, false)))
        assertEquals(null, b.crossed(15, com.tripnav.app.pod.PhoneBattery.Status(14, false)))
        assertEquals(10, b.crossed(11, com.tripnav.app.pod.PhoneBattery.Status(9, false)))
        assertEquals(null, b.crossed(16, com.tripnav.app.pod.PhoneBattery.Status(15, true)))
        assertEquals(null, b.crossed(null, com.tripnav.app.pod.PhoneBattery.Status(4, false)))
    }

    @Test fun sharedMapsLinks() {
        // What maps.app.goo.gl links from the Maps app unwrap to: a name, no coordinates.
        val app = "https://maps.google.com/?q=Casa+Viva+Tarragona,+Carrer+de+la+Uni%C3%B3,+34,+36,+43001+Tarragona&ftid=0x12a3fcd5457390ed:0x811dd55ed844569f&entry=gps&g_st=ic"
        assertEquals(null, SharedPlace.coordinates(app))
        assertEquals("Casa Viva Tarragona, Carrer de la Unió, 34, 36, 43001 Tarragona", SharedPlace.query(app))
        val pin = "https://maps.google.com/?q=3.0943406,106.1475836&entry=gps"
        assertEquals(LatLon(3.0943406, 106.1475836), SharedPlace.coordinates(pin))
        assertEquals(null, SharedPlace.query(pin))
        assertEquals(LatLon(5.811698, -55.118891), SharedPlace.coordinates("https://www.google.com/maps/search/5.811698,+-55.118891?entry=tts"))
        val web = "https://www.google.com/maps/place/Eulennest+Vinothek+%26+Weinbar/@48.1979108,16.3658069,17z/data=!3m1!4b1!8m2!3d48.1979108!4d16.3658069"
        assertEquals(LatLon(48.1979108, 16.3658069), SharedPlace.coordinates(web))
        assertEquals("Eulennest Vinothek & Weinbar", SharedPlace.query(web))
        val consent = "https://consent.google.com/ml?continue=" + java.net.URLEncoder.encode(app, "UTF-8")
        assertEquals("Casa Viva Tarragona, Carrer de la Unió, 34, 36, 43001 Tarragona", SharedPlace.query(consent))

        val share = SharedText.parse("Gateway of India\nApollo Bandar, Colaba, Mumbai, Maharashtra 400001\nhttps://maps.app.goo.gl/AbC123?g_st=aw")
        assertEquals("https://maps.app.goo.gl/AbC123?g_st=aw", share.url)
        assertEquals("Gateway of India", share.label)
        assertEquals(
            listOf("Gateway of India, Apollo Bandar, Colaba, Mumbai, Maharashtra", "Gateway of India, Mumbai", "Gateway of India, Maharashtra", "Gateway of India",
                "Apollo Bandar, Colaba, Mumbai, Maharashtra"),
            SharedPlace.queries(share.lines.joinToString(", ")),
        )
        assertEquals(null, SharedText.parse("https://maps.app.goo.gl/AbC123").label)
    }

    @Test fun sharedPlaceRanking() {
        val hits = listOf(
            Place("Colaba Bus Depot", "Mumbai", LatLon(18.91, 72.82)),
            Place("Gateway of India - गेटवे ऑफ इंडिया", "Mumbai", LatLon(18.922, 72.8347)),
        )
        val best = SharedPlace.best(hits, "Gateway of India", requireMatch = true)!!
        assertEquals(LatLon(18.922, 72.8347), best.point); assertEquals("Gateway of India", best.name)
        assertEquals(null, SharedPlace.best(hits.take(1), "Gateway of India", requireMatch = true))
        assertEquals(LatLon(18.91, 72.82), SharedPlace.best(hits.take(1), "Gateway of India", requireMatch = false)!!.point)
    }

    @Test fun mapsTripLine() {
        val now = java.util.Calendar.getInstance().apply { set(2026, 9, 21, 10, 0, 0); set(java.util.Calendar.MILLISECOND, 0) }.timeInMillis
        val a = MirrorParser.trip("12 min · 5.3 km · 10:42 ETA", now)!!
        assertEquals(12, a.remainingMin); assertEquals(5300, a.remainingM)
        assertEquals(now + 42 * 60_000L, a.arrivalEpochMs)
        val b = MirrorParser.trip("1 hr 5 min (63 km)", now)!!
        assertEquals(65, b.remainingMin); assertEquals(63_000, b.remainingM); assertEquals(now + 65 * 60_000L, b.arrivalEpochMs)
        val c = MirrorParser.trip("Arrive 6:05\u202Fpm", now)!!
        assertEquals(now + (8 * 60 + 5) * 60_000L, c.arrivalEpochMs); assertEquals(8 * 60 + 5, c.remainingMin)
        // Past midnight.
        assertEquals(now + 14 * 3_600_000L, MirrorParser.trip("00:00", now)!!.arrivalEpochMs)
        assertEquals(null, MirrorParser.trip("Towards MG Road"))
        assertEquals(PodIcon.roundabout(2, false), MirrorParser.parse("200 m", "At the roundabout, continue straight", rightHand = false)!!.icon)
        assertEquals(PodIcon.roundabout(1, false), MirrorParser.parse("200 m", "At the roundabout, turn left onto NH 48", rightHand = false)!!.icon)
    }

    @Test fun roundaboutExitGeometry() {
        // Approach heading north, exit heading east: a right turn. ~0.0003 deg ≈ 33 m.
        val d = 0.0003
        val pts = listOf(LatLon(0.0, 0.0), LatLon(d, 0.0), LatLon(d * 1.3, d * 0.3), LatLon(d * 1.3, d * 1.3))
        assertTrue(Roundabouts.exitAngle(pts, 1, 2)!! in 87..93)
        val left = listOf(LatLon(0.0, 0.0), LatLon(d, 0.0), LatLon(d * 1.3, -d * 0.3), LatLon(d * 1.3, -d * 1.3))
        assertTrue(Roundabouts.exitAngle(left, 1, 2)!! in -93..-87)
        assertEquals(null, Roundabouts.exitAngle(pts, 0, 0))
        // Clockwise (left-hand traffic) rings reach left exits first; counter-clockwise ones right exits first.
        assertEquals(90f, roundaboutSweep(-90, clockwise = true, exit = 1))
        assertEquals(270f, roundaboutSweep(90, clockwise = true, exit = 3))
        assertEquals(90f, roundaboutSweep(90, clockwise = false, exit = 1))
        assertEquals(180f, roundaboutSweep(0, clockwise = false, exit = 2))
        assertEquals(315f, roundaboutSweep(179, clockwise = true, exit = 4))
    }
}
