package com.github.andreyasadchy.xtra.ui.player.clip

import java.io.File
import java.util.Locale
import kotlin.math.ceil

internal object LocalHlsPlaylistWriter {
    fun write(
        directory: File,
        segments: List<PreparedClipSegment>,
        outputName: String = PLAYLIST_NAME,
    ): File {
        require(segments.isNotEmpty())
        val targetDurationSeconds = segments.maxOf { it.durationUs }
            .toDouble()
            .div(1_000_000.0)
            .let(::ceil)
            .toInt()
            .coerceAtLeast(1)
        val playlist = File(directory, outputName)
        val output = buildString {
            appendLine("#EXTM3U")
            appendLine("#EXT-X-VERSION:7")
            appendLine("#EXT-X-PLAYLIST-TYPE:VOD")
            appendLine("#EXT-X-TARGETDURATION:$targetDurationSeconds")
            appendLine("#EXT-X-MEDIA-SEQUENCE:${segments.first().mediaSequence}")

            var previousKey: String? = null
            var previousIv: String? = null
            var previousMap: String? = null
            var previousDiscontinuity: Int? = null
            segments.forEach { segment ->
                if (previousDiscontinuity != null && previousDiscontinuity != segment.discontinuitySequence) {
                    appendLine("#EXT-X-DISCONTINUITY")
                }
                previousDiscontinuity = segment.discontinuitySequence

                val key = segment.keyFile
                val iv = segment.encryptionIv
                if (key != previousKey || iv != previousIv) {
                    if (key == null) {
                        if (previousKey != null) appendLine("#EXT-X-KEY:METHOD=NONE")
                    } else {
                        append("#EXT-X-KEY:METHOD=AES-128,URI=\"")
                        append(key)
                        append('"')
                        iv?.let { append(",IV=").append(it) }
                        appendLine()
                    }
                    previousKey = key
                    previousIv = iv
                }

                val init = segment.initFile
                if (init != null && init != previousMap) {
                    appendLine("#EXT-X-MAP:URI=\"$init\"")
                    previousMap = init
                }

                appendLine(
                    String.format(
                        Locale.US,
                        "#EXTINF:%.3f,",
                        segment.durationUs.toDouble() / 1_000_000.0,
                    )
                )
                appendLine(segment.segmentFile)
            }
            appendLine("#EXT-X-ENDLIST")
        }
        playlist.writeText(output)
        return playlist
    }

    const val PLAYLIST_NAME = "clip.m3u8"
}
