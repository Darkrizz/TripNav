package com.tripnav.protocol

/** A decoded notification coming back from the pod. */
sealed interface PodResponse {
    val raw: ByteArray

    class Nack(override val raw: ByteArray) : PodResponse
    class NavAck(override val raw: ByteArray) : PodResponse
    class TimeAck(override val raw: ByteArray) : PodResponse
    class Session(override val raw: ByteArray) : PodResponse
    class PinResult(val accepted: Boolean, override val raw: ByteArray) : PodResponse
    /** Firmware is BCD-ish: `0x12 0x05` -> "12.5". */
    class Firmware(val major: Int, val minor: Int, override val raw: ByteArray) : PodResponse {
        val version get() = "$major.$minor"
    }
    class Serial(val serial: String, override val raw: ByteArray) : PodResponse
    class Unknown(override val raw: ByteArray) : PodResponse

    companion object {
        fun parse(bytes: ByteArray): PodResponse {
            if (bytes.isEmpty()) return Unknown(bytes)
            return when (bytes[0].toInt() and 0xFF) {
                PodProtocol.OP_NACK -> Nack(bytes)
                PodProtocol.OP_NAVIGATE -> NavAck(bytes)
                PodProtocol.OP_SET_TIME -> TimeAck(bytes)
                PodProtocol.OP_SESSION -> Session(bytes)
                PodProtocol.OP_PIN -> PinResult(bytes.size > 1 && bytes[1].toInt() == 1, bytes)
                PodProtocol.OP_PING_FW -> if (bytes.size >= 3) {
                    val a = bytes[1].toInt() and 0xFF
                    val b = bytes[2].toInt() and 0xFF
                    Firmware((a shr 4) * 10 + (a and 0x0F), (b shr 4) * 10 + (b and 0x0F), bytes)
                } else Unknown(bytes)
                PodProtocol.OP_PING_WP -> {
                    val sb = StringBuilder()
                    for (i in 1 until minOf(8, bytes.size)) {
                        val c = bytes[i].toInt() and 0xFF
                        if (c == 0) break
                        if (c in 32..126) sb.append(c.toChar())
                    }
                    Serial(sb.toString(), bytes)
                }
                else -> Unknown(bytes)
            }
        }
    }
}
