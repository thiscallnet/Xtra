package com.github.andreyasadchy.xtra.player.hls

import android.net.Uri
import android.os.SystemClock
import android.util.Log
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.util.UriUtil
import androidx.media3.common.util.Util
import androidx.media3.datasource.ByteArrayDataSource
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.TransferListener
import androidx.media3.exoplayer.hls.playlist.HlsMediaPlaylist
import androidx.media3.exoplayer.hls.playlist.HlsMultivariantPlaylist
import androidx.media3.exoplayer.hls.playlist.HlsPlaylist
import com.github.andreyasadchy.xtra.BuildConfig
import com.github.andreyasadchy.xtra.util.m3u8.TwitchVaftDetector
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runInterruptible
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap

/** Substitutes already downloaded, aligned audio TS segments without changing the HLS track. */
@androidx.media3.common.util.UnstableApi
class HiddenStreamAudioPlaylist(private val network: DataSource.Factory) {
    private data class Audio(val bytes: ByteArray, val signature: TransportStreamAudio.Signature)
    private data class Video(val playlist: HlsMediaPlaylist, val codec: String)
    private data class Manifest(
        val bytes: ByteArray, val discontinuity: Int, val endTimeUs: Long,
        val sequences: Map<Long, Long>, val capturedAtMs: Long,
    )
    private data class Boundary(val uri: String, val epochUs: Long, val durationUs: Long, val discontinuity: Int)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val videos = ConcurrentHashMap<String, Video>()
    private val formats = ConcurrentHashMap<String, Format>()
    private val replacements = ConcurrentHashMap<String, Audio>()
    private val replacementTimes = ConcurrentHashMap<String, Long>()
    private val videoSegments = ConcurrentHashMap.newKeySet<String>()
    private val verified = ConcurrentHashMap<String, TransportStreamAudio.Signature>()
    private val incompatible = ConcurrentHashMap.newKeySet<String>()
    @Volatile private var audioUri: Uri? = null
    @Volatile private var audioCodec: String? = null
    @Volatile private var audioManifest: Manifest? = null
    @Volatile private var closed = false
    @Volatile var hidden = false
        private set
    // Once a gap has entered this source's video queue, video must not gate its audio clock.
    @Volatile var videoMayBeAbsent = false
        private set
    private var worker: Job? = null

    @Synchronized
    fun setHidden(value: Boolean) {
        if (closed || value == hidden) return
        hidden = value
        worker?.cancel()
        worker = if (value) scope.launch {
            val cached = LinkedHashMap<String, Audio>()
            while (isActive) {
                try { refresh(cached) }
                catch (error: CancellationException) { throw error }
                catch (_: Exception) { log("audio_unavailable"); delay(4_000L) }
                delay(1_000L)
            }
        } else null
        log(if (value) "hidden" else "restore_video")
    }

    fun observe(uri: Uri, playlist: HlsPlaylist) {
        if (playlist is HlsMultivariantPlaylist) {
            playlist.variants.forEach { formats[it.url.toString()] = it.format }
            val audio = playlist.variants.firstOrNull {
                it.format.height <= 0 && Util.getCodecsOfType(it.format.codecs, C.TRACK_TYPE_VIDEO) == null &&
                    Util.getCodecsOfType(it.format.codecs, C.TRACK_TYPE_AUDIO) != null
            }
            audioUri = audio?.url
            audioCodec = audio?.format?.let { Util.getCodecsOfType(it.codecs, C.TRACK_TYPE_AUDIO) }
        } else if (playlist is HlsMediaPlaylist) {
            val format = formats[uri.toString()] ?: return
            if (format.height <= 0 || !playlist.hasProgramDateTime) return
            val codec = Util.getCodecsOfType(format.codecs, C.TRACK_TYPE_AUDIO) ?: return
            videos[uri.toString()] = Video(playlist, codec)
            if (codec == audioCodec && !TwitchVaftDetector.requiresVaft(playlist)) {
                rememberVideoSegments(uri.toString(), playlist)
            }
        }
    }

    fun transform(playlist: HlsPlaylist): HlsPlaylist {
        if (!hidden || playlist !is HlsMediaPlaylist || videoSegments.isEmpty()) return playlist
        var changed = false
        val segments = playlist.segments.map { segment ->
            val uri = UriUtil.resolve(playlist.baseUri, segment.url)
            if (uri in videoSegments) {
                changed = true
                HlsMediaPlaylist.Segment(
                    Uri.parse(uri).buildUpon().fragment(AUDIO_FRAGMENT).build().toString(),
                    null, segment.title, segment.durationUs, segment.relativeDiscontinuitySequence,
                    segment.relativeStartTimeUs, null, null, null, 0, C.LENGTH_UNSET.toLong(),
                    false, segment.parts,
                )
            } else segment
        }
        if (BuildConfig.DEBUG) Log.d("HiddenStreamAudio", "event=playlist cached=${replacements.size} matched=${segments.count { isAudioSegment(Uri.parse(it.url)) }} total=${segments.size}")
        if (!changed) return playlist
        // Keep the selected rendition's live edge and parts. An audio manifest can lag behind it;
        // dropping those parts would drain a low-latency player's buffer during the handoff.
        return HlsMediaPlaylist(
            playlist.playlistType, playlist.baseUri, playlist.tags, playlist.startOffsetUs,
            playlist.preciseStart, playlist.startTimeUs, playlist.hasDiscontinuitySequence,
            playlist.discontinuitySequence, playlist.mediaSequence, playlist.version,
            playlist.targetDurationUs, playlist.partTargetDurationUs, playlist.hasIndependentSegments, playlist.hasEndTag,
            playlist.hasProgramDateTime, playlist.protectionSchemes, segments, playlist.trailingParts,
            playlist.serverControl, playlist.renditionReports, playlist.interstitials, playlist.lastSeenInitSegment,
        )
    }

    private suspend fun refresh(cached: LinkedHashMap<String, Audio>) {
        val uri = audioUri ?: return
        val manifestBytes = read(uri, 512 * 1024)
        val playlist = TwitchHlsPlaylistParserFactory(lowLatencyEnabled = false).createPlaylistParser()
            .parse(uri, ByteArrayInputStream(manifestBytes)) as? HlsMediaPlaylist ?: return
        if (!playlist.hasProgramDateTime || playlist.hasEndTag || TwitchVaftDetector.requiresVaft(playlist)) return
        val audioBoundaries = boundaries(playlist)
        val available = HashMap<Boundary, Audio>()
        // Audio is small, but keep both network work and memory bounded. Never wait on it in the player loader.
        for (boundary in audioBoundaries.takeLast(8).asReversed()) {
            val audio = cached[boundary.uri] ?: run {
                val bytes = read(Uri.parse(boundary.uri), 256 * 1024)
                val signature = TransportStreamAudio.inspect(bytes) ?: continue
                if (signature.hasVideo) continue
                Audio(bytes, signature).also { cached[boundary.uri] = it }
            }
            available[boundary] = audio
        }
        while (cached.size > 16) cached.remove(cached.keys.first())
        val next = HashMap<String, Audio>()
        for ((route, video) in videos) {
            if (video.codec != audioCodec || TwitchVaftDetector.requiresVaft(video.playlist)) continue
            val matches = boundaries(video.playlist).mapNotNull { boundary ->
                available.entries.firstOrNull { (audio, _) ->
                    audio.epochUs == boundary.epochUs && audio.durationUs == boundary.durationUs &&
                        audio.discontinuity == boundary.discontinuity
                }?.let { boundary to it.value }
            }
            if (matches.isEmpty()) continue
            val proofKey = "$route:${matches.last().first.discontinuity}"
            if (proofKey in incompatible) continue
            val proof = verified[proofKey] ?: run {
                val (boundary, audio) = matches.last()
                val signature = TransportStreamAudio.inspect(read(Uri.parse(boundary.uri), 4 * 1024 * 1024))
                if (signature == null || !signature.hasVideo || !signature.alignedWith(audio.signature)) {
                    incompatible += proofKey
                    log("incompatible_audio")
                    continue
                }
                verified[proofKey] = signature
                log("audio_verified")
                signature
            }
            matches.forEach { (boundary, audio) ->
                if (proof.pid == audio.signature.pid && proof.config == audio.signature.config) {
                    next[boundary.uri] = audio
                    replacementTimes[boundary.uri] = boundary.epochUs
                }
            }
            available.forEach { (boundary, audio) ->
                if (proof.pid == audio.signature.pid && proof.config == audio.signature.config) {
                    next[boundary.uri] = audio
                    replacementTimes[boundary.uri] = boundary.epochUs
                }
            }
            rememberVideoSegments(route, video.playlist)
        }
        if (hidden && !closed) {
            replacements.putAll(next)
            audioManifest = if (audioBoundaries.size == playlist.segments.size && audioBoundaries.isNotEmpty() &&
                playlist.segments.all { Uri.parse(it.url).isAbsolute } && playlist.trailingParts.isEmpty()) {
                Manifest(manifestBytes, audioBoundaries.last().discontinuity, playlist.endTimeUs,
                    audioBoundaries.mapIndexed { index, boundary -> boundary.epochUs to playlist.mediaSequence + index }.toMap(),
                    SystemClock.elapsedRealtime())
            } else null
            // Retain bytes for already published/queued audio markers until the source is released.
            replacementTimes.entries.sortedByDescending { it.value }.drop(64).forEach {
                replacements.remove(it.key)
                replacementTimes.remove(it.key)
            }
            val current = videos.values.flatMap { mediaSegments(it.playlist) }.mapTo(HashSet()) { it }
            videoSegments.retainAll(current + replacements.keys)
        }
    }

    private fun mediaSegments(playlist: HlsMediaPlaylist): List<String> =
        (playlist.segments + playlist.segments.flatMap { it.parts } + playlist.trailingParts)
            .filter { clearSegment(it) }.map { UriUtil.resolve(playlist.baseUri, it.url) }

    private fun rememberVideoSegments(route: String, playlist: HlsMediaPlaylist) {
        (playlist.segments + playlist.segments.flatMap { it.parts } + playlist.trailingParts).forEach { segment ->
            if (clearSegment(segment) && verified.containsKey("$route:${playlist.discontinuitySequence + segment.relativeDiscontinuitySequence}")) {
                videoSegments += UriUtil.resolve(playlist.baseUri, segment.url)
            }
        }
    }

    private fun clearSegment(segment: HlsMediaPlaylist.SegmentBase): Boolean =
        segment.initializationSegment == null && segment.drmInitData == null &&
            segment.fullSegmentEncryptionKeyUri == null && !segment.hasGapTag &&
            segment.byteRangeOffset == 0L && segment.byteRangeLength == C.LENGTH_UNSET.toLong()

    private fun boundaries(playlist: HlsMediaPlaylist): List<Boundary> = playlist.segments.mapNotNull { segment ->
        if (segment.initializationSegment != null || segment.drmInitData != null ||
            segment.fullSegmentEncryptionKeyUri != null || segment.hasGapTag ||
            segment.byteRangeOffset != 0L || segment.byteRangeLength != C.LENGTH_UNSET.toLong()
        ) return@mapNotNull null
        Boundary(UriUtil.resolve(playlist.baseUri, segment.url), playlist.startTimeUs + segment.relativeStartTimeUs,
            segment.durationUs, playlist.discontinuitySequence + segment.relativeDiscontinuitySequence)
    }

    private suspend fun read(uri: Uri, limit: Int): ByteArray = runInterruptible {
        val source = network.createDataSource()
        try {
            source.open(DataSpec(uri))
            val bytes = ByteArrayOutputStream()
            val buffer = ByteArray(16 * 1024)
            while (true) {
                val count = source.read(buffer, 0, buffer.size)
                if (count == C.RESULT_END_OF_INPUT) break
                if (bytes.size() + count > limit) throw IOException("Background audio probe exceeds limit")
                bytes.write(buffer, 0, count)
            }
            bytes.toByteArray()
        } finally { runCatching { source.close() } }
    }

    fun dataSource(upstream: DataSource): DataSource = object : DataSource {
        private var active: DataSource? = null
        private var openedUri: Uri? = null
        private var openedAudio: Audio? = null
        private var filtering = false
        private val input = ByteArray(64 * 1024)
        private var inputPosition = 0
        private var inputLength = 0
        private var skipPrefix = 0
        private val packet = ByteArray(188)
        private var packetPosition = 188
        private var packetLength = 188
        private val videoPids = HashSet<Int>()
        private val listeners = mutableListOf<TransferListener>()
        override fun addTransferListener(listener: TransferListener) {
            listeners += listener
            upstream.addTransferListener(listener)
        }
        override fun open(dataSpec: DataSpec): Long {
            val marked = isAudioSegment(dataSpec.uri)
            val original = if (marked) dataSpec.uri.buildUpon().fragment(null).build() else dataSpec.uri
            val retry = dataSpec.position > 0 && original == openedUri
            val selected = videos[original.toString()]?.playlist
            val manifest = audioManifest?.takeIf { candidate ->
                hidden && selected != null && !TwitchVaftDetector.requiresVaft(selected) &&
                    verified.containsKey("$original:${candidate.discontinuity}") &&
                    SystemClock.elapsedRealtime() - candidate.capturedAtMs < 3_000L &&
                    // Never replace a playlist with an older live edge or a different sequence timeline.
                    // Full audio segments must already cover even the selected rendition's live parts.
                    candidate.endTimeUs > selected.endTimeUs &&
                    selected.segments.withIndex().any { (index, segment) ->
                        candidate.sequences[selected.startTimeUs + segment.relativeStartTimeUs] == selected.mediaSequence + index
                    }
            }
            // A partial retry must use the same bytes even if visibility changed meanwhile.
            val audio = if (retry) openedAudio
                else if (hidden) replacements[original.toString()] else null
            openedUri = original
            openedAudio = audio
            filtering = if (retry) filtering else audio == null && hidden && original.toString() in videoSegments
            packetPosition = 188
            packetLength = 188
            inputPosition = 0
            inputLength = 0
            skipPrefix = if (filtering) (dataSpec.position % 188).toInt() else 0
            if (!retry) videoPids.clear()
            if (filtering) {
                videoMayBeAbsent = true
                log("filter_video")
            }
            val source = if (manifest != null) ByteArrayDataSource(manifest.bytes).also { memory ->
                listeners.forEach(memory::addTransferListener)
                videoMayBeAbsent = true
                log("route_audio_manifest", manifest.bytes.size)
            } else if (audio != null) ByteArrayDataSource(audio.bytes).also { memory ->
                listeners.forEach(memory::addTransferListener)
                videoMayBeAbsent = true
                log("route_audio", audio.bytes.size)
            } else upstream
            active = source
            val request = dataSpec.buildUpon().setUri(original)
                .setPosition(dataSpec.position - skipPrefix)
                .setLength(if (dataSpec.length == C.LENGTH_UNSET.toLong()) dataSpec.length else dataSpec.length + skipPrefix)
                .build()
            val size = source.open(request)
            return if (size == C.LENGTH_UNSET.toLong()) size else size - skipPrefix
        }
        override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
            val source = checkNotNull(active)
            if (!filtering || length == 0) return source.read(buffer, offset, length)
            var written = 0
            while (written < length) {
                if (packetPosition == packetLength) {
                    packetLength = 0
                    packetPosition = 0
                    while (packetLength < packet.size) {
                        if (inputPosition == inputLength) {
                            inputLength = source.read(input, 0, input.size)
                            inputPosition = 0
                            if (inputLength == C.RESULT_END_OF_INPUT) { inputLength = 0; break }
                        }
                        val count = minOf(packet.size - packetLength, inputLength - inputPosition)
                        input.copyInto(packet, packetLength, inputPosition, inputPosition + count)
                        inputPosition += count
                        packetLength += count
                    }
                    if (packetLength == 0) return if (written == 0) C.RESULT_END_OF_INPUT else written
                    packetPosition = skipPrefix.coerceAtMost(packetLength)
                    skipPrefix = 0
                    if (packetLength == 188 && packet[0] == 0x47.toByte()) {
                        fun byte(i: Int) = packet[i].toInt() and 255
                        val pid = ((byte(1) and 31) shl 8) or byte(2)
                        val payload = 4 + if (byte(3) and 32 != 0) 1 + byte(4) else 0
                        if (byte(3) and 16 != 0 && byte(1) and 64 != 0 && payload + 4 <= 188 &&
                            byte(payload) == 0 && byte(payload + 1) == 0 && byte(payload + 2) == 1 &&
                            byte(payload + 3) in 0xe0..0xef) videoPids += pid
                        if (pid in videoPids) {
                            // Null TS packets keep byte offsets and all AAC timestamps/PAT/PMT data unchanged.
                            packet.fill(0xff.toByte())
                            packet[0] = 0x47
                            packet[1] = 0x1f
                            packet[2] = 0xff.toByte()
                            packet[3] = 0x10
                        }
                    }
                }
                val count = minOf(length - written, packetLength - packetPosition)
                packet.copyInto(buffer, offset + written, packetPosition, packetPosition + count)
                packetPosition += count
                written += count
            }
            return written
        }
        override fun getUri(): Uri? = active?.uri
        override fun getResponseHeaders(): Map<String, List<String>> = active?.responseHeaders.orEmpty()
        override fun close() { try { active?.close() } finally { active = null } }
    }

    @Synchronized
    fun close() {
        closed = true
        hidden = false
        scope.cancel()
        replacements.clear()
        replacementTimes.clear()
        videoSegments.clear()
        audioManifest = null
        videos.clear()
        verified.clear()
        incompatible.clear()
    }

    private fun log(event: String, bytes: Int = 0) {
        if (BuildConfig.DEBUG) Log.d("HiddenStreamAudio", "event=$event bytes=$bytes elapsedMs=${SystemClock.elapsedRealtime()}")
    }

    companion object {
        private const val AUDIO_FRAGMENT = "xtra-background-audio"
        fun isAudioSegment(uri: Uri): Boolean = uri.fragment == AUDIO_FRAGMENT
    }
}
