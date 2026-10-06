package com.github.andreyasadchy.xtra.player.hls

/** Presentation clocks identify a segment across independently encoded TS renditions. */
internal object TransportStreamClock {
    fun signature(bytes: ByteArray): String? {
        if (bytes.size < 188 || bytes[0].toInt() and 255 != 0x47) return null
        val clocks = mutableMapOf<String, Long>()
        var packet = 0
        while (packet + 188 <= bytes.size) {
            fun byte(offset: Int) = bytes[packet + offset].toInt() and 255
            if (byte(0) != 0x47) return null
            val control = byte(3)
            // Only inspect complete, clear PES starts. Do not guess through damaged headers.
            if (byte(1) and 0xc0 == 0x40 && control and 0xc0 == 0 && control and 0x10 != 0) {
                val payload = 4 + if (control and 0x20 != 0) 1 + byte(4) else 0
                if (payload + 14 <= 188 && byte(payload) == 0 && byte(payload + 1) == 0 && byte(payload + 2) == 1 &&
                    byte(payload + 6) and 0xc0 == 0x80 && byte(payload + 7) and 0x80 != 0 && byte(payload + 8) >= 5
                ) {
                    val kind = when (byte(payload + 3)) {
                        in 0xc0..0xdf -> "soun"
                        in 0xe0..0xef -> "vide"
                        else -> null
                    }
                    val pts = payload + 9
                    val prefix = byte(pts) shr 4
                    if (kind != null && prefix in 2..3 && byte(pts) and 1 == 1 &&
                        byte(pts + 2) and 1 == 1 && byte(pts + 4) and 1 == 1
                    ) {
                        val ticks = ((byte(pts).toLong() shr 1 and 7) shl 30) or
                            (byte(pts + 1).toLong() shl 22) or
                            ((byte(pts + 2).toLong() shr 1) shl 15) or
                            (byte(pts + 3).toLong() shl 7) or (byte(pts + 4).toLong() shr 1)
                        clocks.putIfAbsent(kind, ticks)
                    }
                }
            }
            packet += 188
        }
        // Segment boundaries follow video frames. Audio packet interleaving can
        // differ between renditions even when their pictures begin at the same PTS.
        return clocks["vide"]?.let { "ts:vide=$it" } ?: clocks["soun"]?.let { "ts:soun=$it" }
    }
}
