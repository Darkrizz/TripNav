package com.tripnav.protocol

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PodProtocolTest {
    private fun hex(s: String) = PodProtocol.hex(s)

    // Fixed frames lifted verbatim from the reference app; they pin down CRC and layout.
    @Test fun fixedFramesMatchReference() {
        assertContentEquals(hex("21010000000000000000000000000000000050A7"), PodProtocol.showPin())
        assertContentEquals(hex("2100000000000000000000000000000000004045"), PodProtocol.closeSession())
        assertContentEquals(hex("03000000000000000000000000000000000045D9"), PodProtocol.pingFirmware())
        assertContentEquals(hex("300000000000000000000000000000000000428B"), PodProtocol.pingSerial())
    }

    @Test fun referenceNavFramesHaveValidCrc() {
        assertTrue(PodProtocol.isValid(PodProtocol.stopNav()))
        assertTrue(PodProtocol.isValid(PodProtocol.navIdle()))
    }

    @Test fun pinFrameCarriesAsciiDigits() {
        val f = PodProtocol.pin("123456")
        assertEquals(0x20, f[0].toInt())
        assertEquals("123456", String(f, 1, 6, Charsets.US_ASCII))
        assertTrue(PodProtocol.isValid(f))
    }

    @Test fun setTimeEncodesAmPm() {
        assertEquals(14, PodProtocol.setTime(14, 5, true)[1].toInt())
        assertEquals((2 shl 6) or 2, PodProtocol.setTime(14, 5, false)[1].toInt() and 0xFF)
        assertEquals((1 shl 6) or 12, PodProtocol.setTime(0, 0, false)[1].toInt() and 0xFF)
    }

    @Test fun distanceEncoding() {
        assertEquals(PodDistance(250, DistanceUnit.METERS), PodDistance.encode(250, imperial = false))
        assertEquals(PodDistance(12, DistanceUnit.HECTOMETERS), PodDistance.encode(1_250, imperial = false))
        assertEquals(PodDistance(492, DistanceUnit.FEET), PodDistance.encode(150, imperial = true))
        assertEquals(PodDistance(6, DistanceUnit.TENTH_MILES), PodDistance.encode(1_000, imperial = true))
        assertEquals(PodDistance(250, DistanceUnit.METERS), PodDistance.encode(240, imperial = false, round = true))
    }

    @Test fun navFrameLayout() {
        val f = NavFrame.build(
            NavFrameSpec(
                icon = PodIcon.TURN_RIGHT, distanceMeters = 40, nextIcon = PodIcon.TURN_LEFT,
                secondary = SecondaryField.ClockTime(15, 30, amPm = true), night = true,
            ),
        )
        assertEquals(listOf(0x10, 0x11, 21, 0, 40, 1, 48 or 1, 20, 0xFF, 0xFF, 0x41, 3 + 128, 30, 0),
            f.take(14).map { it.toInt() and 0xFF })
        assertTrue(PodProtocol.isValid(f))
    }

    @Test fun speedCameraUsesMarker() {
        val f = NavFrame.build(NavFrameSpec(PodIcon.STRAIGHT, 500, PodIcon.TURN_LEFT, SecondaryField.SpeedCamera(300)))
        assertEquals(PodIcon.SPEED_CAMERA_MARKER, f[7].toInt())
        assertEquals(300, ((f[11].toInt() and 0xFF) shl 8) or (f[12].toInt() and 0xFF))
    }

    @Test fun nightToggleResealsFrame() {
        val day = PodProtocol.navIdle()
        val night = PodProtocol.withNight(day, true)
        assertEquals(1, night[6].toInt() and 1)
        assertTrue(PodProtocol.isValid(night))
        assertContentEquals(day, PodProtocol.withNight(night, false))
    }

    @Test fun compassBearings() {
        assertEquals(CompassDirection.N, CompassDirection.fromBearing(350f))
        assertEquals(CompassDirection.E, CompassDirection.fromBearing(95f))
        assertEquals(CompassDirection.SW, CompassDirection.fromBearing(-135f))
    }

    @Test fun responseParsing() {
        val fw = PodResponse.parse(hex("031205")) as PodResponse.Firmware
        assertEquals("12.5", fw.version)
        assertTrue((PodResponse.parse(hex("2001")) as PodResponse.PinResult).accepted)
        assertEquals("AB12", (PodResponse.parse(hex("3041423132000000")) as PodResponse.Serial).serial)
    }
}
