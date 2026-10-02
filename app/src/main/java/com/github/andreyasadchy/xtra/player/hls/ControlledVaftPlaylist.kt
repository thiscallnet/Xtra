package com.github.andreyasadchy.xtra.player.hls

import android.content.Context
import android.net.Uri
import android.os.SystemClock
import android.util.Log
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.util.Util
import androidx.media3.common.util.UriUtil
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.exoplayer.hls.playlist.HlsMediaPlaylist
import androidx.media3.exoplayer.hls.playlist.HlsMultivariantPlaylist
import androidx.media3.exoplayer.hls.playlist.HlsPlaylist
import com.github.andreyasadchy.xtra.BuildConfig
import com.github.andreyasadchy.xtra.XtraModule
import com.github.andreyasadchy.xtra.ui.player.TwitchVaftController
import com.github.andreyasadchy.xtra.util.C as Settings
import com.github.andreyasadchy.xtra.util.TwitchApiHelper
import com.github.andreyasadchy.xtra.util.httpProxyHost
import com.github.andreyasadchy.xtra.util.httpProxyPort
import com.github.andreyasadchy.xtra.util.m3u8.TwitchVaftDetector
import com.github.andreyasadchy.xtra.util.prefs
import com.github.andreyasadchy.xtra.util.isVaftEnabled
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withTimeoutOrNull
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.util.UUID
import java.time.Instant
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.abs

/** Owns the sequence that the HLS loader sees, before any media is queued. */
@androidx.media3.common.util.UnstableApi
class ControlledVaftPlaylist(
    private val context: Context,
    private val module: XtraModule,
    private val network: DataSource.Factory,
    private val channel: String,
    private val formats: ConcurrentHashMap<String, Format>,
    private val lowLatency: Boolean,
) {
    class WaitingForVerifiedMediaException : IOException("Verified live media is being resolved")
    private data class Published(
        val identity: String,
        val epochUs: Long,
        val sequence: Long,
        val discontinuity: Int,
        val sourceDiscontinuity: Int,
        val playerType: String,
        val segment: HlsMediaPlaylist.Segment,
        val format: Format? = null,
        val availableFormats: List<Format>? = null,
    )

    private data class Candidate(val playerType: String, val uri: Uri, val playlist: HlsMediaPlaylist, val format: Format? = null)
    private class Feed(val fingerprints: ConcurrentHashMap<String, String> = ConcurrentHashMap()) {
        val published = mutableListOf<Published>()
        var candidate: Candidate? = null
        var lastSearchMs = Long.MIN_VALUE
        var resolving: Job? = null
        var lastProgressMs = SystemClock.elapsedRealtime()
        val createdMs = SystemClock.elapsedRealtime()
        var recoveryEpochUs: Long? = null
        var resolved: Resolution? = null
        var generation = 0L
        @Volatile var visible: List<Published> = emptyList()
    }

    private data class Resolution(val candidate: Candidate?, val anchor: Pair<Int, Published>?, val primary: Boolean)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    @Volatile private var closed = false

    fun close() {
        closed = true
        scope.cancel()
        feeds.clear()
        candidateMasters.clear()
        replacementCatalogs.clear()
        initializationClocks.clear()
    }

    fun consumeRecovery(epochUs: Long): Boolean = currentFeed?.let { feed -> synchronized(feed) {
        val recovery = feed.recoveryEpochUs
        if (recovery != null && epochUs >= recovery) {
            feed.recoveryEpochUs = null
            true
        } else false
    } } ?: false

    private val primaryType = context.prefs().getString(Settings.TOKEN_PLAYER_TYPE, "site") ?: "site"
    private val deviceId = UUID.randomUUID().toString().replace("-", "")
    private val subtitleRoutes = ConcurrentHashMap.newKeySet<String>()
    private val feeds = ConcurrentHashMap<String, Feed>()
    private val candidateMasters = ConcurrentHashMap<String, HlsMultivariantPlaylist>()
    private val replacementCatalogs = ConcurrentHashMap<String, List<Format>>()
    private val initializationClocks = ConcurrentHashMap<String, Map<Long, FragmentedMp4Clock.Track>>()
    @Volatile private var currentFeed: Feed? = null
    @Volatile private var lastSelectedFormat: Format? = null

    fun selectFormat(format: Format?) {
        if (format == null) return
        val sameKind = feeds.entries.filter { (formats[it.key]?.height?.let { height -> height > 0 }) == (format.height > 0) }
        val matching = sameKind.filter { formats[it.key]?.let { value ->
            value.height == format.height && value.frameRate == format.frameRate &&
                Util.getCodecsOfType(value.codecs, if (format.height > 0) C.TRACK_TYPE_VIDEO else C.TRACK_TYPE_AUDIO) ==
                Util.getCodecsOfType(format.codecs, if (format.height > 0) C.TRACK_TYPE_VIDEO else C.TRACK_TYPE_AUDIO)
        } == true }
        // The extractor can report replacement dimensions and codec level under the original manifest ID.
        val exact = sameKind.firstOrNull { formats[it.key] == format }
            ?: sameKind.singleOrNull { format.id != null && formats[it.key]?.id == format.id }
            ?: matching.singleOrNull()
        exact?.value?.let { currentFeed = it }
        if (BuildConfig.DEBUG && lastSelectedFormat != format) {
            Log.d("XtraVaftFeed", "event=active_rendition channel=$channel height=${format.height} idToken=${format.id?.hashCode()} " +
                "known=${exact != null} routes=${sameKind.map { formats[it.key]?.let { value -> "${value.height}:${value.id?.hashCode()}" } }}")
        }
        lastSelectedFormat = format
    }

    fun availableFormatsAt(epochUs: Long): List<Format>? {
        val item = currentFeed?.visible?.lastOrNull {
            epochUs >= it.epochUs && epochUs < it.epochUs + it.segment.durationUs
        } ?: return null
        if (item.playerType == primaryType) return null
        return item.availableFormats
    }

    fun formatAt(epochUs: Long): Format? = currentFeed?.visible?.lastOrNull {
        epochUs >= it.epochUs && epochUs < it.epochUs + it.segment.durationUs
    }?.takeIf { it.playerType != primaryType }?.format

    fun isAlternateAt(epochUs: Long): Boolean = currentFeed?.visible?.any {
        it.playerType != primaryType && epochUs >= it.epochUs && epochUs < it.epochUs + it.segment.durationUs
    } == true

    fun nextChangeAfter(epochUs: Long): Long? = currentFeed?.visible?.zipWithNext()?.firstOrNull { (a, b) ->
        b.epochUs > epochUs && ((a.playerType != primaryType) != (b.playerType != primaryType) || a.format != b.format)
    }?.second?.epochUs

    fun transform(uri: Uri, playlist: HlsPlaylist): HlsPlaylist {
        if (playlist is HlsMultivariantPlaylist) {
            playlist.variants.forEach { formats[route(it.url)] = it.format }
            playlist.audios.forEach { rendition -> rendition.url?.let { formats[route(it)] = rendition.format } }
            playlist.subtitles.forEach { rendition -> rendition.url?.let { subtitleRoutes += route(it) } }
            return playlist
        }
        if (playlist !is HlsMediaPlaylist) return playlist
        if (route(uri) in subtitleRoutes) return playlist
        val format = formats[route(uri)] ?: throw IOException("Controlled live rendition is missing its format")
        val feed = feeds.getOrPut(route(uri)) { Feed() }
        return synchronized(feed) {
            if (currentFeed == null && format.height > 0) currentFeed = feed
            val blocked = if (context.prefs().isVaftEnabled()) marked(playlist) else playlist.segments.map { false }
            val tailBlocked = blocked.lastOrNull() == true
            val before = feed.published.lastOrNull()?.sequence
            val resolved = feed.resolved.also { feed.resolved = null }
            val blockedAhead = if (resolved?.primary == true) {
                // Revalidate against this response, rather than publishing an older primary response.
                val anchorIdentity = resolved.anchor?.second?.identity
                val source = resolved.candidate?.playlist
                val anchorIndex = resolved.anchor?.first
                val anchor = if (source != null && anchorIndex != null) playlist.segments.indexOfFirst {
                    identity(playlist, it) == identity(source, source.segments[anchorIndex])
                }.takeIf { it >= 0 }?.let { index ->
                    feed.published.lastOrNull { it.identity == anchorIdentity }?.let { index to it }
                } else null
                append(feed, playlist, primaryType, blocked, anchor, format)
            } else append(feed, playlist, primaryType, blocked, format = format)
            val primaryExtended = feed.published.lastOrNull()?.let { it.sequence != before && it.playerType == primaryType } == true
            if (primaryExtended && !tailBlocked) feed.candidate = null
            if (resolved != null && !resolved.primary && !primaryExtended) {
                feed.candidate = resolved.candidate
                resolved.candidate?.let { append(feed, it.playlist, it.playerType, marked(it.playlist), resolved.anchor, it.format)
                    recoverExpiredWindow(feed, it.playlist, marked(it.playlist), SystemClock.elapsedRealtime(), it.playerType, it.format)
                    if (feed.published.lastOrNull()?.playerType == it.playerType) feed.candidate = it
                }
            }
            val now = SystemClock.elapsedRealtime()
            if (feed.published.lastOrNull()?.sequence != before) feed.lastProgressMs = now
            recoverExpiredWindow(feed, playlist, blocked, now, format = format)
            // append inspects the same aligned timeline used for publication.
            val last = feed.published.lastOrNull()
            val needsResolution = tailBlocked || blockedAhead ||
                (!primaryExtended && feed.candidate != null) || (last != null && last.playerType != primaryType)
            if (!needsResolution && primaryExtended) {
                feed.generation++
                feed.resolving?.cancel()
                feed.resolved = null
            }
            if (feed.published.isEmpty()) {
                if (needsResolution) resolveAsync(feed, format, uri, playlist, blocked, now)
                if (now - feed.createdMs < 20_000L) throw WaitingForVerifiedMediaException()
                throw IOException("No verified live media is available yet")
            }
            while (feed.published.size > 30) feed.published.removeAt(0)
            if (feed.fingerprints.size > 120) feed.fingerprints.clear()
            feed.visible = feed.published.toList()
            val first = feed.published.first()
            val publishedLast = feed.published.last()
            val result = snapshot(playlist, feed.published, feed.candidate)
            if (needsResolution) resolveAsync(feed, format, uri, playlist, blocked, now)
            if (VaftPlaylistCapture.isEnabled) {
                VaftPlaylistCapture.record(uri.toString(), buildString {
                    appendLine("#EXTM3U")
                    appendLine("#EXT-X-MEDIA-SEQUENCE:${first.sequence}")
                    appendLine("#EXT-X-PROGRAM-DATE-TIME:${Instant.ofEpochMilli(first.epochUs / 1_000L)}")
                    feed.published.forEach {
                        appendLine("#XTRA-DISCONTINUITY:${it.discontinuity}")
                        appendLine("#XTRA-PLAYER-TYPE:${it.playerType}")
                        appendLine("#EXTINF:${it.segment.durationUs / 1_000_000.0},")
                        appendLine(it.segment.url)
                    }
                    result.trailingParts.forEach {
                        appendLine("#EXT-X-PART:DURATION=${it.durationUs / 1_000_000.0},URI=\"${it.url}\"")
                    }
                }, "controlled_publish")
            }
            if (BuildConfig.DEBUG) {
                Log.d("XtraVaftFeed", "event=publish channel=$channel height=${format.height} sequence=${first.sequence} " +
                    "count=${feed.published.size} appended=${before?.let { publishedLast.sequence - it } ?: feed.published.size} " +
                    "withheld=${blocked.count { it }} alternate=${publishedLast.playerType != primaryType} " +
                    "startUs=${first.epochUs} endUs=${publishedLast.epochUs + publishedLast.segment.durationUs}")
            }
            result
        }
    }

    private fun resolveAsync(feed: Feed, format: Format, uri: Uri, primary: HlsMediaPlaylist,
                             blocked: List<Boolean>, now: Long) {
        if (closed || feed.resolving?.isActive == true ||
            (feed.lastSearchMs != Long.MIN_VALUE && now - feed.lastSearchMs < 500L)) return
        feed.lastSearchMs = now
        val probe = Feed(feed.fingerprints).also {
            it.published.addAll(feed.published)
            it.lastProgressMs = feed.lastProgressMs
        }
        val known = feed.candidate
        val generation = feed.generation
        // Retain committed segments, but never publish cached prefetch while its refresh is pending.
        feed.candidate = null
        feed.resolving = scope.launch {
            val result = withTimeoutOrNull(8_000L) {
                val primaryAnchor = contentAnchor(probe, primary, blocked)
                val before = probe.published.lastOrNull()?.sequence
                append(probe, primary, primaryType, blocked, primaryAnchor, format)
                if (probe.published.lastOrNull()?.sequence != before) {
                    Resolution(Candidate(primaryType, uri, primary), primaryAnchor, true)
                } else {
                    resolveReplacement(format, probe, known) ?: Resolution(null, null, false)
                }
            }
            synchronized(feed) {
                if (!closed && generation == feed.generation) feed.resolved = result ?: Resolution(null, null, false)
            }
            if (BuildConfig.DEBUG) Log.d("XtraVaftFeed", "event=resolve_complete channel=$channel elapsedMs=${SystemClock.elapsedRealtime() - now} ready=${result?.candidate != null}")
        }
    }

    private suspend fun resolveReplacement(format: Format, feed: Feed, known: Candidate?): Resolution? = coroutineScope {
        val results = Channel<Resolution?>(Channel.UNLIMITED)
        val jobs = mutableListOf<Job>()
        if (known != null) jobs += launch {
            val result = withTimeoutOrNull(2_000L) {
                try {
                    (fetch(known.uri) as? HlsMediaPlaylist)?.let {
                        usableReplacement(feed, Candidate(known.playerType, known.uri, it, known.format), allowCurrentTail = true)
                    }
                } catch (error: CancellationException) { throw error } catch (_: IOException) { null }
            }
            results.send(result)
        }
        jobs += launch {
            // A healthy cached route gets a short head start. A slow or stale route
            // must not hold up the other player types for its whole timeout.
            if (known != null) delay(150L)
            results.send(findCandidate(format, feed)?.let { usableReplacement(feed, it) })
        }
        try {
            repeat(jobs.size) {
                results.receive()?.let { return@coroutineScope it }
            }
            null
        } finally {
            jobs.forEach { it.cancel() }
            results.close()
        }
    }

    private suspend fun usableReplacement(feed: Feed, candidate: Candidate, allowCurrentTail: Boolean = false): Resolution? {
        val blocked = marked(candidate.playlist)
        if (blocked.lastOrNull() != false) return null
        val anchor = contentAnchor(feed, candidate.playlist, blocked)
        val probe = Feed(feed.fingerprints).also {
            it.published.addAll(feed.published)
            it.lastProgressMs = feed.lastProgressMs
        }
        val before = probe.published.lastOrNull()?.sequence
        append(probe, candidate.playlist, candidate.playerType, blocked, anchor, candidate.format)
        recoverExpiredWindow(probe, candidate.playlist, blocked, SystemClock.elapsedRealtime(), candidate.playerType, candidate.format)
        val currentTail = feed.published.lastOrNull()
        // A fresh response may still end at the current tail while the next segment
        // is being produced. Keep that healthy route and its verified trailing parts.
        val refreshedTail = allowCurrentTail && currentTail != null && currentTail.playerType == candidate.playerType &&
            candidate.playlist.segments.lastOrNull()?.let { identity(candidate.playlist, it) } == currentTail.identity
        return if (probe.published.lastOrNull()?.sequence != before || refreshedTail) Resolution(candidate, anchor, false) else null
    }

    private fun recoverExpiredWindow(feed: Feed, raw: HlsMediaPlaylist, blocked: List<Boolean>, now: Long,
                                     type: String = primaryType, format: Format? = null) {
        val previous = feed.published.lastOrNull() ?: return
        if (!raw.hasProgramDateTime || now - feed.lastProgressMs < 8_000L) return
        val first = raw.segments.firstOrNull() ?: return
        if (raw.startTimeUs + first.relativeStartTimeUs <= previous.epochUs + previous.segment.durationUs + 2_000L) return
        val index = maxOf(blocked.indexOfLast { it } + 1, raw.segments.size - 3)
        if (index >= raw.segments.size) return
        feed.published.clear()
        feed.generation++
        feed.resolving?.cancel()
        feed.candidate = null
        feed.resolved = null
        append(feed, raw, type, blocked, format = format)
        if (feed.published.isEmpty()) return
        val fresh = feed.published.dropWhile { it.epochUs < raw.startTimeUs + raw.segments[index].relativeStartTimeUs }
        feed.published.clear()
        feed.published.addAll(fresh.mapIndexed { offset, item -> item.copy(
            sequence = previous.sequence + offset + 1, discontinuity = previous.discontinuity + 1,
        ) })
        feed.recoveryEpochUs = feed.published.first().epochUs
        feed.lastProgressMs = now
        if (BuildConfig.DEBUG) Log.d("XtraVaftFeed", "event=expired_window_recovery channel=$channel")
    }

    private fun route(uri: Uri): String = uri.buildUpon().clearQuery().build().toString()

    private fun marked(playlist: HlsMediaPlaylist): List<Boolean> {
        val ranges = TwitchVaftDetector.visibleBoundaries(playlist).filter { it.basis == "date_range" }
        return playlist.segments.map { segment ->
            val start = playlist.startTimeUs + segment.relativeStartTimeUs
            TwitchVaftDetector.isVaftTitle(segment.title) || (playlist.hasProgramDateTime && ranges.any {
                it.epochStartTimeUs != null && start + segment.durationUs > it.epochStartTimeUs &&
                    (it.epochEndTimeUs == null || start < it.epochEndTimeUs)
            })
        }
    }

    private fun identity(playlist: HlsMediaPlaylist, segment: HlsMediaPlaylist.Segment): String {
        val path = UriUtil.resolveToUri(playlist.baseUri, segment.url).path
        return "$path:${segment.byteRangeOffset}:${segment.byteRangeLength}:${segment.durationUs}"
    }

    private fun append(feed: Feed, playlist: HlsMediaPlaylist, type: String, blocked: List<Boolean>,
                       contentAnchor: Pair<Int, Published>? = null, format: Format? = null): Boolean {
        val input = playlist.segments
        if (input.isEmpty()) return false
        val known = feed.published.associateBy { it.identity }
        val overlapIndex = contentAnchor?.first ?: input.indexOfLast { identity(playlist, it) in known }
        val overlap = contentAnchor?.second ?: input.getOrNull(overlapIndex)?.let { known[identity(playlist, it)] }
        var firstIndex = if (overlap != null) overlapIndex + 1 else 0
        if (feed.published.isEmpty()) firstIndex = blocked.indexOfLast { it } + 1
        val origin = overlap?.let { it.epochUs - input[overlapIndex].relativeStartTimeUs }
            ?: playlist.startTimeUs
        for (index in firstIndex until input.size) {
            val segment = input[index]
            val key = identity(playlist, segment)
            if (key in known || feed.published.any { it.identity == key }) continue
            val previous = feed.published.lastOrNull()
            val proposed = origin + segment.relativeStartTimeUs
            val nextEpoch = previous?.let { it.epochUs + it.segment.durationUs } ?: proposed
            if (blocked[index] && (previous == null || proposed + segment.durationUs > nextEpoch + 2_000L)) return true
            if (previous != null && abs(proposed - nextEpoch) > 2_000L) {
                if (proposed < nextEpoch) continue
                if (BuildConfig.DEBUG) Log.d("XtraVaftFeed", "event=wait_for_continuity channel=$channel deltaUs=${proposed - nextEpoch}")
                break
            }
            val sourceDiscontinuity = playlist.discontinuitySequence + segment.relativeDiscontinuitySequence
            val discontinuity = (previous?.discontinuity ?: 0) + if (previous != null &&
                ((previous.playerType == type && previous.sourceDiscontinuity != sourceDiscontinuity) ||
                    (previous.playerType != type && overlap == null) ||
                    (previous.format != null && format != null && previous.format.height != format.height))) 1 else 0
            feed.published += Published(key, nextEpoch, previous?.sequence?.plus(1) ?: playlist.mediaSequence + index,
                discontinuity, sourceDiscontinuity, type, absolute(playlist.baseUri, segment), format,
                if (type != primaryType) replacementCatalogs[type] else null)
        }
        return false
    }

    /** Signed paths can differ for the same transport bytes. Only inspect a switch boundary. */
    private suspend fun contentAnchor(feed: Feed, playlist: HlsMediaPlaylist, blocked: List<Boolean>): Pair<Int, Published>? {
        if (feed.published.isEmpty() || playlist.segments.any { input ->
            feed.published.any { it.identity == identity(playlist, input) }
        }) return null
        val inputs = playlist.segments.indices.filter { !blocked[it] }.takeLast(3)
        if (inputs.isEmpty()) return null
        val outputs = feed.published.takeLast(3)
        return withTimeoutOrNull(1_500L) {
            coroutineScope {
                val incoming = inputs.map { index -> async {
                    index to fingerprint(feed, absolute(playlist.baseUri, playlist.segments[index]))
                } }
                val previous = outputs.map { item -> async { item to fingerprint(feed, item.segment) } }
                val incomingValues = incoming.awaitAll()
                val previousValues = previous.awaitAll()
                incomingValues.asReversed().firstNotNullOfOrNull { (index, digest) ->
                    digest?.let { value -> previousValues.lastOrNull { (item, known) ->
                        known == value && item.segment.durationUs == playlist.segments[index].durationUs
                    }?.first?.let { index to it } }
                }?.also {
                    if (BuildConfig.DEBUG) Log.d("XtraVaftFeed", "event=content_overlap channel=$channel sequence=${it.second.sequence}")
                }
            }
        }
    }

    private suspend fun fingerprint(feed: Feed, segment: HlsMediaPlaylist.Segment): String? {
        if (segment.hasGapTag || segment.fullSegmentEncryptionKeyUri != null) return null
        val key = "${segment.url}:${segment.byteRangeOffset}:${segment.byteRangeLength}"
        feed.fingerprints[key]?.let { return it }
        return try {
            runInterruptible(Dispatchers.IO) {
                val source = network.createDataSource()
                try {
                    val limit = minOf(65_536L, segment.byteRangeLength.takeIf { it > 0 } ?: 65_536L)
                    source.open(DataSpec.Builder().setUri(segment.url).setPosition(segment.byteRangeOffset).setLength(limit).build())
                    val bytes = ByteArrayOutputStream()
                    val buffer = ByteArray(8 * 1024)
                    var remaining = limit
                    var count = 0L
                    while (remaining > 0 && !Thread.currentThread().isInterrupted) {
                        val size = source.read(buffer, 0, minOf(buffer.size.toLong(), remaining).toInt())
                        if (size == C.RESULT_END_OF_INPUT) break
                        bytes.write(buffer, 0, size)
                        remaining -= size
                        count += size
                    }
                    if (count < 188L) null else {
                        val prefix = bytes.toByteArray()
                        val clocks = segment.initializationSegment?.let { init ->
                            val initKey = "${init.url}:${init.byteRangeOffset}:${init.byteRangeLength}"
                            if (initializationClocks.size > 120) initializationClocks.clear()
                            initializationClocks[initKey] ?: readInitializationClocks(init).also { initializationClocks[initKey] = it }
                        }.orEmpty()
                        FragmentedMp4Clock.signature(prefix, clocks) ?: "$count:" + MessageDigest.getInstance("SHA-256")
                            .digest(prefix).joinToString("") { "%02x".format(it) }
                    }
                } finally { runCatching { source.close() } }
            }?.also { feed.fingerprints[key] = it }
        } catch (error: CancellationException) { throw error } catch (_: IOException) { null }
    }

    private fun readInitializationClocks(segment: HlsMediaPlaylist.Segment): Map<Long, FragmentedMp4Clock.Track> {
        val source = network.createDataSource()
        return try {
            source.open(DataSpec.Builder().setUri(segment.url).setPosition(segment.byteRangeOffset)
                .setLength(segment.byteRangeLength.takeIf { it > 0 } ?: C.LENGTH_UNSET.toLong()).build())
            val bytes = ByteArrayOutputStream()
            val buffer = ByteArray(4 * 1024)
            while (bytes.size() < 65_536) {
                val size = source.read(buffer, 0, minOf(buffer.size, 65_536 - bytes.size()))
                if (size == C.RESULT_END_OF_INPUT) break
                bytes.write(buffer, 0, size)
            }
            FragmentedMp4Clock.tracks(bytes.toByteArray())
        } finally { runCatching { source.close() } }
    }

    private fun absolute(base: String, segment: HlsMediaPlaylist.Segment): HlsMediaPlaylist.Segment =
        HlsMediaPlaylist.Segment(
            UriUtil.resolve(base, segment.url), segment.initializationSegment?.let { absolute(base, it) },
            segment.title, segment.durationUs, segment.relativeDiscontinuitySequence, segment.relativeStartTimeUs,
            segment.drmInitData, segment.fullSegmentEncryptionKeyUri?.let { UriUtil.resolve(base, it) },
            segment.encryptionIV, segment.byteRangeOffset, segment.byteRangeLength, segment.hasGapTag, emptyList(),
        )

    private fun snapshot(raw: HlsMediaPlaylist, published: List<Published>, candidate: Candidate?): HlsMediaPlaylist {
        val first = published.first()
        val last = published.last()
        val tail = if (last.playerType == primaryType) raw else candidate?.playlist
        // Parts retain their source sequence. Only expose the next part after the exact published tail.
        val parts = tail?.takeIf {
            lowLatency && !it.hasEndTag && it.segments.lastOrNull()?.let { segment -> identity(it, segment) } == last.identity &&
                marked(it).lastOrNull() == false
        }?.let { source ->
            val sourceLast = source.segments.last()
            val origin = last.epochUs - sourceLast.relativeStartTimeUs
            val ranges = TwitchVaftDetector.visibleBoundaries(source).filter { it.basis == "date_range" }
            source.trailingParts.takeWhile { part ->
                val start = source.startTimeUs + part.relativeStartTimeUs
                !part.hasGapTag && (!source.hasProgramDateTime || ranges.none {
                    it.epochStartTimeUs != null && start + part.durationUs > it.epochStartTimeUs &&
                        (it.epochEndTimeUs == null || start < it.epochEndTimeUs)
                })
            }.map { part ->
                HlsMediaPlaylist.Part(
                    UriUtil.resolve(source.baseUri, part.url), part.initializationSegment?.let { absolute(source.baseUri, it) },
                    part.durationUs, last.discontinuity - first.discontinuity +
                        part.relativeDiscontinuitySequence - sourceLast.relativeDiscontinuitySequence,
                    origin + part.relativeStartTimeUs - first.epochUs, part.drmInitData,
                    part.fullSegmentEncryptionKeyUri?.let { UriUtil.resolve(source.baseUri, it) }, part.encryptionIV,
                    part.byteRangeOffset, part.byteRangeLength, part.hasGapTag, part.isIndependent, part.isPreload,
                )
            }
        }.orEmpty()
        if (BuildConfig.DEBUG && parts.isNotEmpty()) Log.d("XtraVaftFeed", "event=prefetch_publish channel=$channel parts=${parts.size}")
        return HlsMediaPlaylist(
            raw.playlistType, raw.baseUri, emptyList(), C.TIME_UNSET, false, first.epochUs,
            true, first.discontinuity, first.sequence, raw.version,
            ((published.maxOf { it.segment.durationUs } + 999_999L) / 1_000_000L) * 1_000_000L,
            tail?.partTargetDurationUs?.takeIf { parts.isNotEmpty() } ?: C.TIME_UNSET,
            raw.hasIndependentSegments,
            raw.hasEndTag && published.last().identity == raw.segments.lastOrNull()?.let { identity(raw, it) },
            raw.hasProgramDateTime, raw.protectionSchemes,
            published.map { it.segment.copyWith(it.epochUs - first.epochUs, it.discontinuity - first.discontinuity) },
            parts, HlsMediaPlaylist.ServerControl(C.TIME_UNSET, false, C.TIME_UNSET, C.TIME_UNSET, false),
            emptyMap(), emptyList(), raw.lastSeenInitSegment,
        )
    }

    private suspend fun findCandidate(wanted: Format, feed: Feed): Candidate? = withTimeoutOrNull(8_000L) {
        coroutineScope {
            val results = Channel<Candidate?>(Channel.UNLIMITED)
            val types = TwitchVaftController.PLAYER_TYPES.filter { it != primaryType }
            val jobs = types.map { type -> launch {
                val candidate = try {
                    val prefs = context.prefs()
                    val master = candidateMasters[type] ?: run {
                    val url = module.playerRepository.loadStreamPlaylistUrl(
                        context, prefs.getString(Settings.NETWORK_LIBRARY, Settings.OKHTTP),
                        TwitchApiHelper.getGQLHeaders(context, prefs.getBoolean(Settings.TOKEN_INCLUDE_TOKEN_STREAM, true)),
                        channel, false, deviceId, type, prefs.getString(Settings.TOKEN_SUPPORTED_CODECS, "av1,h265,h264"),
                        prefs.getBoolean(Settings.PROXY_PLAYBACK_ACCESS_TOKEN, false), prefs.httpProxyHost(), prefs.httpProxyPort(),
                        prefs.getString(Settings.PROXY_USER, null), prefs.getString(Settings.PROXY_PASSWORD, null), lowLatency = lowLatency,
                    )
                    (fetch(Uri.parse(url)) as? HlsMultivariantPlaylist)?.also { candidateMasters[type] = it }
                    }
                    val variants = master?.variants?.filter {
                        // Keep codec compatibility and media kind; a lower temporary rung is usable.
                        (wanted.height <= 0 && it.format.height <= 0 || wanted.height > 0 && it.format.height in 1..wanted.height) &&
                            it.format.codecs?.substringBefore(',')?.take(4) == wanted.codecs?.substringBefore(',')?.take(4)
                    }?.sortedWith(compareBy(
                        { abs(it.format.height - wanted.height) },
                        { abs(it.format.frameRate - wanted.frameRate) },
                        { abs(it.format.bitrate.toLong() - wanted.bitrate) },
                    ))
                    var selectedCandidate: Candidate? = null
                    for (selected in variants.orEmpty()) {
                        val source = withTimeoutOrNull(1_500L) { fetch(selected.url) as? HlsMediaPlaylist }?.takeIf {
                            it.segments.isNotEmpty() && marked(it).lastOrNull() == false
                        }
                        if (source != null) {
                            val blocked = marked(source)
                            val probe = Feed(feed.fingerprints).also {
                                it.published.addAll(feed.published)
                                it.lastProgressMs = feed.lastProgressMs
                            }
                            val before = probe.published.lastOrNull()?.sequence
                            append(probe, source, type, blocked, contentAnchor(feed, source, blocked))
                            recoverExpiredWindow(probe, source, blocked, SystemClock.elapsedRealtime(), type, selected.format)
                            if (probe.published.lastOrNull()?.sequence != before) {
                                replacementCatalogs[type] = master?.variants.orEmpty().map { it.format }.distinct()
                                selectedCandidate = Candidate(type, selected.url, source, selected.format)
                                break
                            }
                        }
                    }
                    selectedCandidate
                } catch (error: CancellationException) { throw error } catch (_: Exception) { candidateMasters.remove(type); null }
                results.trySend(candidate)
            } }
            try {
                repeat(types.size) {
                    results.receive()?.let { candidate ->
                        if (BuildConfig.DEBUG) Log.d("XtraVaftFeed", "event=candidate_ready channel=$channel playerType=${candidate.playerType} height=${candidate.format?.height}")
                        return@coroutineScope candidate
                    }
                }
                null
            } finally { jobs.forEach { it.cancel() }; results.close() }
        }
    }

    private suspend fun fetch(uri: Uri): HlsPlaylist = runInterruptible(Dispatchers.IO) {
        val source = network.createDataSource()
        try {
            source.open(DataSpec(uri))
            val bytes = ByteArrayOutputStream()
            val buffer = ByteArray(16 * 1024)
            while (!Thread.currentThread().isInterrupted) {
                val size = source.read(buffer, 0, buffer.size)
                if (size == C.RESULT_END_OF_INPUT) break
                bytes.write(buffer, 0, size)
                if (bytes.size() > 512 * 1024) throw IOException("Live playlist exceeds the capture limit")
            }
            TwitchHlsPlaylistParserFactory(lowLatencyEnabled = lowLatency).createPlaylistParser()
                .parse(source.uri ?: uri, ByteArrayInputStream(bytes.toByteArray()))
        } finally { runCatching { source.close() } }
    }
}
