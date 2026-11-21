package com.github.andreyasadchy.xtra.player.hls

import android.net.Uri
import android.os.SystemClock
import android.util.Log
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UriUtil
import androidx.media3.exoplayer.hls.playlist.DefaultHlsPlaylistParserFactory
import androidx.media3.exoplayer.hls.playlist.HlsMediaPlaylist
import androidx.media3.exoplayer.hls.playlist.HlsMultivariantPlaylist
import androidx.media3.exoplayer.hls.playlist.HlsPlaylist
import androidx.media3.exoplayer.hls.playlist.HlsPlaylistParserFactory
import androidx.media3.exoplayer.upstream.ParsingLoadable
import com.github.andreyasadchy.xtra.BuildConfig
import com.github.andreyasadchy.xtra.util.m3u8.TwitchVaftDetector
import java.io.ByteArrayInputStream
import java.nio.charset.StandardCharsets
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

@androidx.media3.common.util.UnstableApi
fun interface TwitchHlsDiagnosticsSink {
    fun onPlaylistParsed(
        diagnostics: TwitchHlsPlaylistDiagnostics,
        parsed: HlsPlaylist,
    )
}

@androidx.media3.common.util.UnstableApi
internal fun HlsMediaPlaylist.copyWithInterstitials(
    interstitials: List<HlsMediaPlaylist.Interstitial>,
): HlsMediaPlaylist = HlsMediaPlaylist(
    playlistType,
    baseUri,
    tags,
    startOffsetUs,
    preciseStart,
    startTimeUs,
    hasDiscontinuitySequence,
    discontinuitySequence,
    mediaSequence,
    version,
    targetDurationUs,
    partTargetDurationUs,
    hasIndependentSegments,
    hasEndTag,
    hasProgramDateTime,
    protectionSchemes,
    segments,
    trailingParts,
    serverControl,
    renditionReports,
    interstitials,
    lastSeenInitSegment,
)

@androidx.media3.common.util.UnstableApi
private fun HlsMediaPlaylist.copyWithStartTimeUs(startTimeUs: Long): HlsMediaPlaylist = HlsMediaPlaylist(
    playlistType,
    baseUri,
    tags,
    startOffsetUs,
    preciseStart,
    startTimeUs,
    hasDiscontinuitySequence,
    discontinuitySequence,
    mediaSequence,
    version,
    targetDurationUs,
    partTargetDurationUs,
    hasIndependentSegments,
    hasEndTag,
    hasProgramDateTime,
    protectionSchemes,
    segments,
    trailingParts,
    serverControl,
    renditionReports,
    interstitials,
    lastSeenInitSegment,
)

@androidx.media3.common.util.UnstableApi
private data class ProgramDateTimeContinuityAdjustment(
    val playlist: HlsMediaPlaylist,
    val shiftMs: Long,
    val source: String,
)

@androidx.media3.common.util.UnstableApi
internal data class CachedMediaPlaylist(
    val playlist: HlsMediaPlaylist,
    val parsedAtElapsedRealtimeMs: Long,
)

@androidx.media3.common.util.UnstableApi
class TwitchHlsPlaylistContinuityStore {
    private val recentMediaPlaylists = ConcurrentHashMap<String, CachedMediaPlaylist>()

    @Volatile
    private var delegate: TwitchHlsPlaylistContinuityStore? = null

    internal fun previous(cacheKey: String): CachedMediaPlaylist? {
        delegate?.let { return it.previous(cacheKey) }
        return recentMediaPlaylists[cacheKey]
            ?: recentMediaPlaylists.values.maxByOrNull { it.parsedAtElapsedRealtimeMs }
    }

    @Synchronized
    internal fun record(cacheKey: String, cached: CachedMediaPlaylist) {
        delegate?.let {
            it.record(cacheKey, cached)
            return
        }
        recentMediaPlaylists[cacheKey] = cached
        if (recentMediaPlaylists.size > MAX_CACHED_MEDIA_PLAYLISTS) {
            recentMediaPlaylists.entries
                .minByOrNull { it.value.parsedAtElapsedRealtimeMs }
                ?.let { recentMediaPlaylists.remove(it.key, it.value) }
        }
    }

    /** Preserve a parser's cache while joining it to the active live-playback session. */
    internal fun forwardTo(shared: TwitchHlsPlaylistContinuityStore) {
        if (shared === this) return
        delegate?.let {
            it.forwardTo(shared)
            return
        }
        synchronized(this) {
            delegate?.let {
                it.forwardTo(shared)
                return
            }
            recentMediaPlaylists.forEach { (key, cached) -> shared.record(key, cached) }
            delegate = shared
        }
    }

    private companion object {
        const val MAX_CACHED_MEDIA_PLAYLISTS = 8
    }
}

@androidx.media3.common.util.UnstableApi
class TwitchHlsPlaylistParserFactory(
    private val lowLatencyEnabled: Boolean,
    private val diagnostics: TwitchHlsDiagnosticsSink? = null,
    private val transform: (Uri, HlsPlaylist) -> HlsPlaylist = { _, playlist -> playlist },
    private val continuityStore: TwitchHlsPlaylistContinuityStore = TwitchHlsPlaylistContinuityStore(),
) : HlsPlaylistParserFactory {

    private val delegate = DefaultHlsPlaylistParserFactory()
    private val parseCount = AtomicInteger()

    override fun createPlaylistParser(): ParsingLoadable.Parser<HlsPlaylist> =
        wrap(delegate.createPlaylistParser(), previousMediaPlaylist = null)

    override fun createPlaylistParser(
        multivariantPlaylist: HlsMultivariantPlaylist,
        previousMediaPlaylist: HlsMediaPlaylist?,
    ): ParsingLoadable.Parser<HlsPlaylist> = wrap(
        delegate.createPlaylistParser(multivariantPlaylist, previousMediaPlaylist),
        previousMediaPlaylist,
    )

    private fun wrap(
        parser: ParsingLoadable.Parser<HlsPlaylist>,
        previousMediaPlaylist: HlsMediaPlaylist?,
    ): ParsingLoadable.Parser<HlsPlaylist> =
        ParsingLoadable.Parser { uri, input ->
            val raw = input.readBytes().toString(StandardCharsets.UTF_8)
            VaftPlaylistCapture.record(uri.toString(), raw, "player_parse")
            val adaptation = TwitchLowLatencyPlaylistAdapter.adapt(
                raw = raw,
                enabled = lowLatencyEnabled,
            )
            var finalAdaptation: TwitchPlaylistAdaptation
            var parsed: HlsPlaylist
            try {
                finalAdaptation = adaptation
                parsed = parser.parse(
                    uri,
                    ByteArrayInputStream(adaptation.playlistText.toByteArray(StandardCharsets.UTF_8)),
                )
            } catch (_: Exception) {
                // Most playlists parse successfully. Build the fallback only when it is used.
                finalAdaptation = if (lowLatencyEnabled) {
                    TwitchLowLatencyPlaylistAdapter.adapt(raw = raw, enabled = false)
                } else {
                    adaptation
                }
                parsed = parser.parse(
                    uri,
                    ByteArrayInputStream(finalAdaptation.playlistText.toByteArray(StandardCharsets.UTF_8)),
                )
            }
            if (adaptation.diagnostics.twitchPrefetchTranslated &&
                parsed is HlsMediaPlaylist &&
                TwitchVaftDetector.requiresVaft(parsed)
            ) {
                finalAdaptation = TwitchLowLatencyPlaylistAdapter.adapt(
                    raw = raw,
                    enabled = lowLatencyEnabled,
                    suppressTranslation = true,
                )
                parsed = parser.parse(
                    uri,
                    ByteArrayInputStream(finalAdaptation.playlistText.toByteArray(StandardCharsets.UTF_8)),
                )
            }
            val cacheKey = playlistCacheKey(uri)
            val cachedPrevious = continuityStore.previous(cacheKey)
            val continuityPrevious = previousMediaPlaylist ?: cachedPrevious?.playlist
            val continuityPreviousParsedAtMs = cachedPrevious
                ?.takeIf { cached ->
                    continuityPrevious?.mediaSequence == cached.playlist.mediaSequence &&
                        continuityPrevious.startTimeUs == cached.playlist.startTimeUs
                }
                ?.parsedAtElapsedRealtimeMs
            val parsedAtElapsedRealtimeMs = SystemClock.elapsedRealtime()
            val playlistBeforeInterstitialRestore = parsed as? HlsMediaPlaylist
            if (playlistBeforeInterstitialRestore != null) {
                parsed = restoreTwitchVaftInterstitials(
                    parser = parser,
                    playlistUri = uri,
                    playlist = playlistBeforeInterstitialRestore,
                    playlistText = finalAdaptation.playlistText,
                )
            }
            val continuityAdjustment = preserveProgramDateTimeContinuity(
                previousPlaylist = continuityPrevious,
                updatedPlaylist = parsed as? HlsMediaPlaylist,
                previousParsedAtElapsedRealtimeMs = continuityPreviousParsedAtMs,
                parsedAtElapsedRealtimeMs = parsedAtElapsedRealtimeMs,
            )
            if (continuityAdjustment != null) parsed = continuityAdjustment.playlist
            val programDateTimeShiftMs = continuityAdjustment?.shiftMs
            val compatible = transform(uri, TwitchMultivariantPlaylistCompatibility.apply(
                parsed = parsed,
                rawPlaylist = finalAdaptation.playlistText,
                playlistUri = uri,
            ))
            val compatibleMediaPlaylist = compatible as? HlsMediaPlaylist
            val collectExpandedDiagnostics = BuildConfig.DEBUG || BuildConfig.PERF_DIAGNOSTICS
            val finalDiagnostics = finalAdaptation.diagnostics.copy(
                twitchPrefetchActive = finalAdaptation.diagnostics.twitchPrefetchTranslated,
                partTargetDurationMs = compatibleMediaPlaylist
                    ?.partTargetDurationUs
                    ?.takeIf { it != C.TIME_UNSET }
                    ?.div(1_000L),
                parseIndex = if (collectExpandedDiagnostics) parseCount.incrementAndGet() else 0,
                rawHasEndTag = collectExpandedDiagnostics && raw.lineSequence()
                    .any { it.trim().equals("#EXT-X-ENDLIST", ignoreCase = true) },
                adaptedHasEndTag = collectExpandedDiagnostics && finalAdaptation.playlistText.lineSequence()
                    .any { it.trim().equals("#EXT-X-ENDLIST", ignoreCase = true) },
                parsedBeforeInterstitialRestoreHasEndTag = playlistBeforeInterstitialRestore
                    ?.hasEndTag
                    ?.takeIf { collectExpandedDiagnostics },
                restoredInterstitialCount = if (collectExpandedDiagnostics) {
                    ((compatibleMediaPlaylist?.interstitials?.size ?: 0) -
                        (playlistBeforeInterstitialRestore?.interstitials?.size ?: 0)).coerceAtLeast(0)
                } else {
                    0
                },
                parsedHasEndTag = compatibleMediaPlaylist?.hasEndTag?.takeIf { collectExpandedDiagnostics },
                parsedHasIndependentSegments = compatibleMediaPlaylist
                    ?.hasIndependentSegments
                    ?.takeIf { collectExpandedDiagnostics },
                parsedHasProgramDateTime = compatibleMediaPlaylist
                    ?.hasProgramDateTime
                    ?.takeIf { collectExpandedDiagnostics },
                parsedPreciseStart = compatibleMediaPlaylist?.preciseStart?.takeIf { collectExpandedDiagnostics },
                parsedPlaylistType = compatibleMediaPlaylist?.playlistType?.takeIf { collectExpandedDiagnostics },
                mediaSequence = compatibleMediaPlaylist?.mediaSequence?.takeIf { collectExpandedDiagnostics },
                playlistDurationMs = compatibleMediaPlaylist?.durationUs
                    ?.takeIf { it != C.TIME_UNSET }
                    ?.div(1_000L)
                    ?.takeIf { collectExpandedDiagnostics },
                segmentCount = compatibleMediaPlaylist?.segments?.size?.takeIf { collectExpandedDiagnostics },
                programDateTimeShiftMs = programDateTimeShiftMs,
                previousMediaSequence = continuityPrevious
                    ?.mediaSequence
                    ?.takeIf { collectExpandedDiagnostics },
                programDateTimeSequenceOffset = continuityPrevious
                    ?.let { previous -> (playlistBeforeInterstitialRestore?.mediaSequence ?: 0L) - previous.mediaSequence }
                    ?.takeIf { collectExpandedDiagnostics },
                programDateTimeShiftSource = continuityAdjustment?.source,
            )
            if (compatibleMediaPlaylist?.hasProgramDateTime == true) {
                continuityStore.record(cacheKey, CachedMediaPlaylist(
                    compatibleMediaPlaylist,
                    parsedAtElapsedRealtimeMs,
                ))
            }
            diagnostics?.onPlaylistParsed(finalDiagnostics, compatible)
            logDiagnostics(finalDiagnostics, compatible)
            compatible
        }

    private fun restoreTwitchVaftInterstitials(
        parser: ParsingLoadable.Parser<HlsPlaylist>,
        playlistUri: Uri,
        playlist: HlsMediaPlaylist,
        playlistText: String,
    ): HlsMediaPlaylist {
        if (!playlistText.contains("X-ASSET-URI=\"\"")) return playlist

        val parserCompatibleText = TwitchLowLatencyPlaylistAdapter
            .parserCompatibleWithEmptyAssetUris(playlistText)
        val compatibilityPlaylist = runCatching {
            parser.parse(
                playlistUri,
                ByteArrayInputStream(parserCompatibleText.toByteArray(StandardCharsets.UTF_8)),
            ) as? HlsMediaPlaylist
        }.getOrNull() ?: return playlist

        val existingIds = playlist.interstitials.mapTo(HashSet()) { it.id }
        val restored = compatibilityPlaylist.interstitials
            .asSequence()
            .filter { TwitchLowLatencyPlaylistAdapter.isEmptyAssetUriPlaceholder(it.assetUri?.toString()) }
            .filterNot { it.id in existingIds }
            .map { it.copyWithAssetUri(Uri.EMPTY) }
            .toList()
        if (restored.isEmpty()) return playlist

        return playlist.copyWithInterstitials(playlist.interstitials + restored)
    }

    private fun HlsMediaPlaylist.Interstitial.copyWithAssetUri(
        assetUri: Uri,
    ): HlsMediaPlaylist.Interstitial = HlsMediaPlaylist.Interstitial(
        id,
        assetUri,
        assetListUri,
        startDateUnixUs,
        endDateUnixUs,
        durationUs,
        plannedDurationUs,
        cue,
        endOnNext,
        resumeOffsetUs,
        playoutLimitUs,
        snapTypes,
        restrictions,
        clientDefinedAttributes,
        contentMayVary,
        timelineOccupies,
        timelineStyle,
        skipControlOffsetUs,
        skipControlDurationUs,
        skipControlLabelId,
    )

    private fun logDiagnostics(diagnostics: TwitchHlsPlaylistDiagnostics, parsed: HlsPlaylist) {
        if (!BuildConfig.DEBUG && !BuildConfig.PERF_DIAGNOSTICS) return
        val mediaPlaylist = parsed as? HlsMediaPlaylist
        Log.d(
            "TwitchLL",
            "targetMs=${diagnostics.declaredTargetDurationMs ?: -1L} " +
                "effectiveTargetMs=${diagnostics.effectiveReloadTargetDurationMs ?: -1L} " +
                "avgSegmentMs=${diagnostics.averageSegmentDurationMs ?: -1L} " +
                "prefetchCount=${diagnostics.twitchPrefetchCount} " +
                "translated=${diagnostics.twitchPrefetchTranslated} " +
                "container=${diagnostics.container ?: "Unknown"} " +
                "parseIndex=${diagnostics.parseIndex} " +
                "rawEndTag=${diagnostics.rawHasEndTag} " +
                "adaptedEndTag=${diagnostics.adaptedHasEndTag} " +
                "preRestoreEndTag=${diagnostics.parsedBeforeInterstitialRestoreHasEndTag ?: false} " +
                "restoredInterstitials=${diagnostics.restoredInterstitialCount} " +
                "parsedEndTag=${diagnostics.parsedHasEndTag ?: false} " +
                "independentSegments=${diagnostics.parsedHasIndependentSegments ?: false} " +
                "programDateTime=${diagnostics.parsedHasProgramDateTime ?: false} " +
                "preciseStart=${diagnostics.parsedPreciseStart ?: false} " +
                "playlistType=${diagnostics.parsedPlaylistType ?: mediaPlaylist?.playlistType ?: -1} " +
                "mediaSequence=${diagnostics.mediaSequence ?: -1L} " +
                "playlistDurationMs=${diagnostics.playlistDurationMs ?: -1L} " +
                "pdtShiftMs=${diagnostics.programDateTimeShiftMs ?: -1L} " +
                "previousMediaSequence=${diagnostics.previousMediaSequence ?: -1L} " +
                "sequenceOffset=${diagnostics.programDateTimeSequenceOffset ?: -1L} " +
                "pdtShiftSource=${diagnostics.programDateTimeShiftSource ?: "none"} " +
                "segments=${diagnostics.segmentCount ?: mediaPlaylist?.segments?.size ?: 0} " +
                "trailingParts=${mediaPlaylist?.trailingParts?.size ?: 0}",
        )
    }

    /** Keep an overlapping segment on its existing timeline when its server PDT has shifted. */
    private fun preserveProgramDateTimeContinuity(
        previousPlaylist: HlsMediaPlaylist?,
        updatedPlaylist: HlsMediaPlaylist?,
        previousParsedAtElapsedRealtimeMs: Long?,
        parsedAtElapsedRealtimeMs: Long,
    ): ProgramDateTimeContinuityAdjustment? {
        if (previousPlaylist == null || updatedPlaylist?.hasProgramDateTime != true) return null
        val sequenceOffset = updatedPlaylist.mediaSequence - previousPlaylist.mediaSequence
        if (sequenceOffset < 0L) return null

        val continuousStartTimeUs: Long
        val source: String
        if (sequenceOffset < previousPlaylist.segments.size) {
            val overlap = previousPlaylist.segments[sequenceOffset.toInt()]
            continuousStartTimeUs = previousPlaylist.startTimeUs + overlap.relativeStartTimeUs
            source = "overlap_continuity"
        } else {
            val missingSequenceCount = sequenceOffset - previousPlaylist.segments.size
            if (missingSequenceCount > MAX_MISSING_SEQUENCE_COUNT) return null
            val averageSegmentDurationUs = previousPlaylist.segments
                .takeLast(5)
                .map { it.durationUs }
                .filter { it > 0L }
                .takeIf { it.isNotEmpty() }
                ?.average()
                ?.toLong()
                ?.takeIf { it > 0L }
                ?: return null
            val previousParsedAtMs = previousParsedAtElapsedRealtimeMs ?: return null
            val expectedAdvanceUs = sequenceOffset * averageSegmentDurationUs
            val actualAdvanceUs = (parsedAtElapsedRealtimeMs - previousParsedAtMs)
                .coerceAtLeast(0L) * 1_000L
            val previousWindowDurationUs = previousPlaylist.durationUs
                .takeIf { it != C.TIME_UNSET }
                ?: previousPlaylist.segments.sumOf { it.durationUs }
            val updatedWindowDurationUs = updatedPlaylist.durationUs
                .takeIf { it != C.TIME_UNSET }
                ?: updatedPlaylist.segments.sumOf { it.durationUs }
            val expectedElapsedUs = (expectedAdvanceUs - previousWindowDurationUs + updatedWindowDurationUs)
                .coerceAtLeast(0L)
            val toleranceUs = maxOf(15_000_000L, expectedElapsedUs / 4L)
            if (kotlin.math.abs(actualAdvanceUs - expectedElapsedUs) > toleranceUs) return null
            val estimatedMissingDurationUs = missingSequenceCount * averageSegmentDurationUs
            val estimatedShiftUs = updatedPlaylist.startTimeUs -
                (previousPlaylist.endTimeUs + estimatedMissingDurationUs)
            if (kotlin.math.abs(estimatedShiftUs) < maxOf(2_000_000L, averageSegmentDurationUs)) return null
            continuousStartTimeUs = previousPlaylist.endTimeUs + estimatedMissingDurationUs
            source = "sequence_gap_continuity"
        }
        val shiftUs = updatedPlaylist.startTimeUs - continuousStartTimeUs
        if (shiftUs == 0L) return null
        return ProgramDateTimeContinuityAdjustment(
            playlist = updatedPlaylist.copyWithStartTimeUs(continuousStartTimeUs),
            shiftMs = shiftUs / 1_000L,
            source = source,
        )
    }

    private fun playlistCacheKey(uri: Uri): String = uri.buildUpon().clearQuery().build().toString()

    private companion object {
        const val MAX_MISSING_SEQUENCE_COUNT = 300L
    }
}

@androidx.media3.common.util.UnstableApi
private object TwitchMultivariantPlaylistCompatibility {

    private data class RawVariant(
        val uri: Uri,
        val label: String?,
    )

    private data class UnavailableVariant(
        val name: String,
        val bitrate: Int,
        val codecs: String?,
        val width: Int,
        val height: Int,
        val frameRate: Float,
        val stableVariantId: String,
    )

    private const val IVS_NAME = "IVS-NAME"
    private const val STREAM_INF = "#EXT-X-STREAM-INF:"
    private const val SESSION_DATA = "#EXT-X-SESSION-DATA:"
    private const val UNAVAILABLE_MEDIA = "com.amazon.ivs.unavailable-media"

    fun apply(
        parsed: HlsPlaylist,
        rawPlaylist: String,
        playlistUri: Uri,
    ): HlsPlaylist {
        val multivariant = parsed as? HlsMultivariantPlaylist ?: return parsed
        val rawVariants = parseRawVariants(rawPlaylist, playlistUri)
        val unavailable = parseUnavailableVariants(rawPlaylist)
        if (rawVariants.isEmpty() || (rawVariants.all { it.label == null } && unavailable.isEmpty())) {
            return withCea608Fallback(multivariant)
        }

        val rawByUri = rawVariants.groupBy { it.uri }
            .mapValues { (_, values) -> ArrayDeque(values) }
        val variants = ArrayList<HlsMultivariantPlaylist.Variant>(
            multivariant.variants.size + unavailable.size,
        )
        val template = rawVariants.firstOrNull {
            !it.label.isNullOrBlank() && it.uri.toString().contains("/index-")
        }
        val syntheticStableIds = HashSet<String>()
        val syntheticUris = HashSet<Uri>()
        if (template != null) {
            unavailable.forEach { candidate ->
                val replacement = if (variants.isEmpty() &&
                    !candidate.stableVariantId.equals("audio_only", true)
                ) {
                    "chunked"
                } else {
                    candidate.stableVariantId
                }
                val candidateUri = Uri.parse(
                    template.uri.toString().replace(
                        "${template.label}/index-",
                        "$replacement/index-",
                    )
                )
                if (candidateUri == template.uri ||
                    !syntheticStableIds.add(candidate.stableVariantId) ||
                    !syntheticUris.add(candidateUri)
                ) {
                    return@forEach
                }
                variants += HlsMultivariantPlaylist.Variant(
                    candidateUri,
                    Format.Builder()
                        .setId(variants.size)
                        .setLabel(candidate.name)
                        .setContainerMimeType(MimeTypes.APPLICATION_M3U8)
                        .setCodecs(candidate.codecs)
                        .setAverageBitrate(Format.NO_VALUE)
                        .setPeakBitrate(candidate.bitrate)
                        .setWidth(candidate.width)
                        .setHeight(candidate.height)
                        .setFrameRate(candidate.frameRate)
                        .build(),
                    null,
                    null,
                    null,
                    null,
                    null,
                    candidate.stableVariantId,
                )
            }
        }
        multivariant.variants.forEach { variant ->
            val rawVariant = rawByUri[variant.url]?.removeFirstOrNull()
            if (rawVariant == null) {
                variants += variant.copyWithFormat(variant.format.buildUpon().setId(variants.size).build())
                return@forEach
            }
            val format = variant.format
            // Unavailable IVS renditions are inserted before the original ones.
            // Reassign every id so the inserted and original tracks cannot collide.
            val labeledFormat = format.buildUpon().setId(variants.size).apply {
                rawVariant.label?.takeIf { it.isNotBlank() }?.let(::setLabel)
            }.build()
            variants += variant.copyWithFormat(labeledFormat)
        }

        return withCea608Fallback(
            HlsMultivariantPlaylist(
                multivariant.baseUri,
                multivariant.tags,
                variants,
                multivariant.videos,
                multivariant.audios,
                multivariant.subtitles,
                multivariant.closedCaptions,
                multivariant.muxedAudioFormat,
                multivariant.muxedCaptionFormats,
                multivariant.hasIndependentSegments,
                multivariant.variableDefinitions,
                multivariant.sessionKeyDrmInitData,
                multivariant.contentSteeringInfo,
            )
        )
    }

    private fun withCea608Fallback(
        multivariant: HlsMultivariantPlaylist,
    ): HlsMultivariantPlaylist {
        val muxedCaptionFormats = multivariant.muxedCaptionFormats.orEmpty()
        if (muxedCaptionFormats.any {
                it.sampleMimeType == MimeTypes.APPLICATION_CEA608
            }
        ) {
            return multivariant
        }
        return HlsMultivariantPlaylist(
            multivariant.baseUri,
            multivariant.tags,
            multivariant.variants,
            multivariant.videos,
            multivariant.audios,
            multivariant.subtitles,
            multivariant.closedCaptions,
            multivariant.muxedAudioFormat,
            muxedCaptionFormats + Format.Builder()
                .setSampleMimeType(MimeTypes.APPLICATION_CEA608)
                .build(),
            multivariant.hasIndependentSegments,
            multivariant.variableDefinitions,
            multivariant.sessionKeyDrmInitData,
            multivariant.contentSteeringInfo,
        )
    }

    private fun parseRawVariants(raw: String, playlistUri: Uri): List<RawVariant> {
        val lines = raw.lineSequence().map(String::trim).toList()
        val result = ArrayList<RawVariant>()
        lines.forEachIndexed { index, line ->
            if (!line.startsWith(STREAM_INF)) return@forEachIndexed
            val uriLine = lines.drop(index + 1).firstOrNull { it.isNotBlank() } ?: return@forEachIndexed
            if (uriLine.startsWith("#")) return@forEachIndexed
            result += RawVariant(
                uri = UriUtil.resolveToUri(playlistUri.toString(), uriLine),
                label = quotedAttribute(line, IVS_NAME),
            )
        }
        return result
    }

    private fun parseUnavailableVariants(raw: String): List<UnavailableVariant> {
        val encoded = raw.lineSequence()
            .map(String::trim)
            .filter { it.startsWith(SESSION_DATA) }
            .filter { quotedAttribute(it, "DATA-ID") == UNAVAILABLE_MEDIA }
            .mapNotNull { quotedAttribute(it, "VALUE") }
            .toList()
        return encoded.flatMap { value ->
            runCatching {
                val json = java.util.Base64.getDecoder().decode(value).toString(StandardCharsets.UTF_8)
                val array = org.json.JSONArray(json)
                (0 until array.length()).mapNotNull { index ->
                    val objectValue = array.optJSONObject(index) ?: return@mapNotNull null
                    val filters = objectValue.optJSONArray("FILTER_REASONS")
                    val codecFiltered = (0 until (filters?.length() ?: 0)).any { filterIndex ->
                        filters?.optString(filterIndex) == "FR_CODEC_NOT_REQUESTED"
                    }
                    if (codecFiltered) return@mapNotNull null
                    val resolution = objectValue.optString("RESOLUTION").split('x')
                    UnavailableVariant(
                        name = objectValue.optString("IVS_NAME"),
                        bitrate = objectValue.optInt("BANDWIDTH", Format.NO_VALUE),
                        codecs = objectValue.optString("CODECS").takeIf { it.isNotBlank() },
                        width = resolution.getOrNull(0)?.toIntOrNull()?.takeIf { it > 0 } ?: Format.NO_VALUE,
                        height = resolution.getOrNull(1)?.toIntOrNull()?.takeIf { it > 0 } ?: Format.NO_VALUE,
                        frameRate = objectValue.optDouble("FRAME-RATE", Format.NO_VALUE.toDouble()).toFloat(),
                        stableVariantId = objectValue.optString("STABLE-VARIANT-ID"),
                    )
                }
            }.getOrDefault(emptyList())
        }.filter { it.name.isNotBlank() && it.stableVariantId.isNotBlank() }
    }

    private fun quotedAttribute(line: String, name: String): String? =
        Regex("(?:^|[:,])${Regex.escape(name)}=\"([^\"]*)\"").find(line)?.groupValues?.get(1)
}
