package com.tripnav.protocol

import java.util.UUID

/** Wire-level constants and frame builders for the TripperPod (`RE_DISP`) display. See docs/tripper-protocol.md. */
object PodProtocol {
    const val DEVICE_NAME = "RE_DISP"
    val SERVICE_UUID: UUID = UUID.fromString("01FF0100-BA5E-F4EE-5CA1-EB1E5E4B1CE0")
    val CHAR_UUID: UUID = UUID.fromString("01FF0101-BA5E-F4EE-5CA1-EB1E5E4B1CE0")
    val CCCD_UUID: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

    const val FRAME_SIZE = 20
    /** Minimum gap between two writes; the pod drops frames written faster than this. */
    const val WRITE_GAP_MS = 80L
    const val KEEPALIVE_MS = 1_000L

    const val OP_NACK = 0x02
    const val OP_PING_FW = 0x03
    const val OP_NAVIGATE = 0x10
    const val OP_PIN = 0x20
    const val OP_SESSION = 0x21
    const val OP_PING_WP = 0x30
    const val OP_CONTROL = 0x40
    const val OP_SET_TIME = 0x50

    /** CRC-16/CCITT-FALSE: poly 0x1021, init 0xFFFF, no reflection, no final xor. */
    fun crc16(data: ByteArray, length: Int = data.size): Int {
        var crc = 0xFFFF
        for (i in 0 until length) {
            crc = crc xor ((data[i].toInt() and 0xFF) shl 8)
            repeat(8) {
                crc = if (crc and 0x8000 != 0) (crc shl 1) xor 0x1021 else crc shl 1
                crc = crc and 0xFFFF
            }
        }
        return crc
    }

    /** Writes the CRC of bytes 0..17 into bytes 18..19 (big-endian) and returns [frame]. */
    fun seal(frame: ByteArray): ByteArray {
        require(frame.size == FRAME_SIZE) { "frame must be $FRAME_SIZE bytes" }
        val crc = crc16(frame, 18)
        frame[18] = (crc ushr 8).toByte()
        frame[19] = crc.toByte()
        return frame
    }

    fun isValid(frame: ByteArray): Boolean =
        frame.size == FRAME_SIZE &&
            crc16(frame, 18) == (((frame[18].toInt() and 0xFF) shl 8) or (frame[19].toInt() and 0xFF))

    private fun frame(vararg head: Int): ByteArray {
        val f = ByteArray(FRAME_SIZE)
        head.forEachIndexed { i, v -> f[i] = v.toByte() }
        return seal(f)
    }

    fun showPin() = frame(OP_SESSION, 0x01)
    fun closeSession() = frame(OP_SESSION, 0x00)
    fun pingFirmware() = frame(OP_PING_FW)
    fun pingSerial() = frame(OP_PING_WP)
    fun navStarted() = frame(OP_CONTROL, 0x01)
    fun navStopped() = frame(OP_CONTROL, 0x05)
    /** The original app shows the incoming-call screen with the same `40 05` control frame. */
    fun callIcon() = frame(OP_CONTROL, 0x05)

    fun pin(code: String): ByteArray {
        require(code.length == 6 && code.all { it.isDigit() }) { "PIN must be 6 digits" }
        val f = ByteArray(FRAME_SIZE)
        f[0] = OP_PIN.toByte()
        code.toByteArray(Charsets.US_ASCII).copyInto(f, 1)
        return seal(f)
    }

    fun setTime(hour24: Int, minute: Int, use24h: Boolean): ByteArray {
        require(hour24 in 0..23 && minute in 0..59)
        val hourByte = if (use24h) hour24 else {
            val period = if (hour24 < 12) 1 else 2
            val h12 = (hour24 % 12).let { if (it == 0) 12 else it }
            (period shl 6) or h12
        }
        return frame(OP_SET_TIME, hourByte, minute)
    }

    /** Nav "stop" screen as sent by the original app (icon REROUTE with no distance). */
    fun stopNav(): ByteArray = hex("10111C00000100FF00000000000000000000F782")

    /** Idle screen (DEPART icon) shown while connected with no active guidance. */
    fun navIdle(): ByteArray = hex("10113C0000044015000041000403000000001050")

    fun loadingScreen(night: Boolean): ByteArray {
        val f = ByteArray(FRAME_SIZE)
        f[0] = OP_NAVIGATE.toByte(); f[1] = 0x11; f[2] = PodIcon.REROUTE.toByte()
        if (night) f[6] = 1
        return seal(f)
    }

    fun noDataScreen(): ByteArray {
        val f = ByteArray(FRAME_SIZE) { 0xFF.toByte() }
        f[0] = OP_NAVIGATE.toByte(); f[1] = 0x11; f[2] = PodIcon.NO_DATA.toByte()
        return seal(f)
    }

    fun compass(direction: CompassDirection, night: Boolean): ByteArray {
        val f = ByteArray(FRAME_SIZE)
        f[0] = OP_NAVIGATE.toByte(); f[1] = 0x11; f[2] = PodIcon.COMPASS.toByte()
        f[6] = if (night) 1 else 0
        f[7] = 0xFF.toByte(); f[11] = 0xFF.toByte(); f[12] = 0xFF.toByte(); f[13] = 0xFF.toByte()
        f[14] = direction.code.toByte()
        return seal(f)
    }

    /** Returns a copy of a navigate frame with the night bit (byte 6, bit 0) set or cleared. */
    fun withNight(frame: ByteArray, night: Boolean): ByteArray {
        if (frame.size != FRAME_SIZE || frame[0].toInt() != OP_NAVIGATE) return frame
        val f = frame.copyOf()
        val b = f[6].toInt() and 0xFF
        f[6] = (if (night) b or 1 else b and 0xFE).toByte()
        return seal(f)
    }

    fun hex(s: String): ByteArray {
        val clean = s.replace(" ", "").replace(":", "")
        require(clean.length % 2 == 0) { "hex must have even length" }
        return ByteArray(clean.length / 2) { clean.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
    }

    fun toHex(bytes: ByteArray): String = bytes.joinToString(" ") { "%02X".format(it.toInt() and 0xFF) }
}

enum class CompassDirection(val code: Int) {
    N(0x10), NE(0x50), E(0x20), SE(0x70), S(0x40), SW(0x80), W(0x30), NW(0x60);

    companion object {
        fun fromBearing(bearing: Float): CompassDirection {
            val norm = ((bearing % 360f) + 360f) % 360f
            return entries[(((norm + 22.5f) % 360f) / 45f).toInt()]
        }
    }
}
