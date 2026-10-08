package com.github.andreyasadchy.xtra.player.hls

import java.io.ByteArrayOutputStream

/** Checks clear AAC-in-TS boundaries before keeping the existing HLS extractor and audio decoder. */
internal object TransportStreamAudio {
    data class Signature(val pid: Int, val firstPts: Long, val lastPts: Long, val config: Int, val frames: Int, val hasVideo: Boolean) {
        // PES packet grouping differs between renditions; the final PES PTS is not the final AAC frame PTS.
        fun alignedWith(other: Signature): Boolean = pid == other.pid && firstPts == other.firstPts &&
            config == other.config && frames == other.frames
    }

    fun inspect(bytes: ByteArray): Signature? {
        if (bytes.isEmpty() || bytes.size % 188 != 0) return null
        var audioPid = -1
        var firstPts = -1L
        var lastPts = -1L
        var hasVideo = false
        val audio = ByteArrayOutputStream()
        for (packet in bytes.indices step 188) {
            fun byte(offset: Int) = bytes[packet + offset].toInt() and 255
            if (byte(0) != 0x47 || byte(1) and 0x80 != 0 || byte(3) and 0xc0 != 0) return null
            if (byte(3) and 0x10 == 0) continue
            var payload = 4 + if (byte(3) and 0x20 != 0) 1 + byte(4) else 0
            if (payload >= 188) continue
            val pid = ((byte(1) and 0x1f) shl 8) or byte(2)
            if (byte(1) and 0x40 != 0 && payload + 9 <= 188 &&
                byte(payload) == 0 && byte(payload + 1) == 0 && byte(payload + 2) == 1
            ) {
                val kind = byte(payload + 3)
                if (kind in 0xe0..0xef) hasVideo = true
                if (kind !in 0xc0..0xdf) continue
                if (audioPid != -1 && audioPid != pid) return null
                audioPid = pid
                if (byte(payload + 7) and 0x80 == 0 || byte(payload + 8) < 5 || payload + 14 > 188) return null
                val p = payload + 9
                if (byte(p) and 1 != 1 || byte(p + 2) and 1 != 1 || byte(p + 4) and 1 != 1) return null
                val pts = ((byte(p).toLong() shr 1 and 7) shl 30) or (byte(p + 1).toLong() shl 22) or
                    ((byte(p + 2).toLong() shr 1) shl 15) or (byte(p + 3).toLong() shl 7) or (byte(p + 4).toLong() shr 1)
                if (firstPts == -1L) firstPts = pts
                lastPts = pts
                payload += 9 + byte(payload + 8)
            }
            if (pid == audioPid && payload < 188) audio.write(bytes, packet + payload, 188 - payload)
        }
        val frames = audio.toByteArray()
        var offset = 0
        var count = 0
        var config = -1
        while (offset + 7 <= frames.size) {
            fun byte(index: Int) = frames[offset + index].toInt() and 255
            if (byte(0) != 0xff || byte(1) and 0xf6 != 0xf0) return null
            val nextConfig = ((byte(2) and 0xfd) shl 2) or (byte(3) shr 6)
            if (config != -1 && config != nextConfig) return null
            config = nextConfig
            val length = ((byte(3) and 3) shl 11) or (byte(4) shl 3) or (byte(5) shr 5)
            if (length < 7 || offset + length > frames.size || byte(6) and 3 != 0) return null
            offset += length
            count++
        }
        return if (offset == frames.size && count > 0 && firstPts >= 0) Signature(audioPid, firstPts, lastPts, config, count, hasVideo) else null
    }
}
