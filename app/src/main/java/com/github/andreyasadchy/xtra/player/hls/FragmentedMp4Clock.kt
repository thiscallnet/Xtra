package com.github.andreyasadchy.xtra.player.hls

/** Decode clocks allow matching independently encoded renditions without comparing picture bytes. */
internal object FragmentedMp4Clock {
    data class Track(val kind: String, val timescale: Long)
    private data class Box(val kind: String, val body: Int, val end: Int)

    fun tracks(bytes: ByteArray): Map<Long, Track> {
        val moov = boxes(bytes, 0, bytes.size).firstOrNull { it.kind == "moov" } ?: return emptyMap()
        return boxes(bytes, moov.body, moov.end).filter { it.kind == "trak" }.mapNotNull { trak ->
            val children = boxes(bytes, trak.body, trak.end)
            val tkhd = children.firstOrNull { it.kind == "tkhd" } ?: return@mapNotNull null
            val mdia = children.firstOrNull { it.kind == "mdia" } ?: return@mapNotNull null
            val media = boxes(bytes, mdia.body, mdia.end)
            val mdhd = media.firstOrNull { it.kind == "mdhd" } ?: return@mapNotNull null
            val hdlr = media.firstOrNull { it.kind == "hdlr" } ?: return@mapNotNull null
            val idOffset = tkhd.body + if (bytes[tkhd.body].toInt() == 1) 20 else 12
            val scaleOffset = mdhd.body + if (bytes[mdhd.body].toInt() == 1) 20 else 12
            if (idOffset + 4 > tkhd.end || scaleOffset + 4 > mdhd.end || hdlr.body + 12 > hdlr.end) return@mapNotNull null
            val scale = number(bytes, scaleOffset, 4)
            val kind = String(bytes, hdlr.body + 8, 4, Charsets.US_ASCII)
            if (scale <= 0 || kind !in listOf("vide", "soun")) null
            else number(bytes, idOffset, 4) to Track(kind, scale)
        }.toMap()
    }

    fun signature(bytes: ByteArray, tracks: Map<Long, Track>): String? {
        if (tracks.isEmpty() || tracks.values.map { it.kind }.distinct().size != tracks.size) return null
        val clocks = mutableMapOf<String, Long>()
        for (moof in boxes(bytes, 0, bytes.size).filter { it.kind == "moof" }) {
            for (traf in boxes(bytes, moof.body, moof.end).filter { it.kind == "traf" }) {
                val children = boxes(bytes, traf.body, traf.end)
                val tfhd = children.firstOrNull { it.kind == "tfhd" } ?: continue
                val tfdt = children.firstOrNull { it.kind == "tfdt" } ?: continue
                if (tfhd.body + 8 > tfhd.end || tfdt.body + 8 > tfdt.end) continue
                val track = tracks[number(bytes, tfhd.body + 4, 4)] ?: continue
                val size = if (bytes[tfdt.body].toInt() == 1) 8 else 4
                if (tfdt.body + 4 + size > tfdt.end) continue
                val ticks = number(bytes, tfdt.body + 4, size)
                if (ticks < 0 || ticks / track.timescale > Long.MAX_VALUE / 1_000_000L) return null
                val timeUs = ticks / track.timescale * 1_000_000L + ticks % track.timescale * 1_000_000L / track.timescale
                clocks.putIfAbsent(track.kind, timeUs)
            }
        }
        if (clocks.size != tracks.size) return null
        return "mp4:" + clocks.toSortedMap().entries.joinToString { "${it.key}=${it.value}" }
    }

    private fun boxes(bytes: ByteArray, start: Int, end: Int): List<Box> {
        val result = mutableListOf<Box>()
        var position = start
        while (position + 8 <= end) {
            var size = number(bytes, position, 4)
            var header = 8
            if (size == 1L) {
                if (position + 16 > end) break
                size = number(bytes, position + 8, 8)
                header = 16
            }
            if (size == 0L) size = (end - position).toLong()
            if (size < header || size > end - position) break
            val body = position + header
            // Full boxes used above need at least their version/flags field.
            if (body + 4 <= position + size) result += Box(String(bytes, position + 4, 4, Charsets.US_ASCII), body, position + size.toInt())
            position += size.toInt()
        }
        return result
    }

    private fun number(bytes: ByteArray, start: Int, size: Int): Long {
        var value = 0L
        repeat(size) { value = (value shl 8) or (bytes[start + it].toLong() and 255) }
        return value
    }
}
