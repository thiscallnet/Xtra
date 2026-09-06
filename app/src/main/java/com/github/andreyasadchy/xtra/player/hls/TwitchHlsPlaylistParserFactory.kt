package com.github.andreyasadchy.xtra.player.hls

import android.net.Uri
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
import com.github.andreyasadchy.xtra.util.m3u8.TwitchAdDetector
import java.io.ByteArrayInputStream
import java.nio.charset.StandardCharsets

fun interface TwitchHlsDiagnosticsSink {
    fun onPlaylistParsed(
        diagnostics: TwitchHlsPlaylistDiagnostics,
        parsed: HlsPlaylist,
    )
}

@androidx.media3.common.util.UnstableApi
class TwitchHlsPlaylistParserFactory(
    private val lowLatencyEnabled: Boolean,
    private val diagnostics: TwitchHlsDiagnosticsSink? = null,
) : HlsPlaylistParserFactory {

    private val delegate = DefaultHlsPlaylistParserFactory()

    override fun createPlaylistParser(): ParsingLoadable.Parser<HlsPlaylist> =
        wrap(delegate.createPlaylistParser())

    override fun createPlaylistParser(
        multivariantPlaylist: HlsMultivariantPlaylist,
        previousMediaPlaylist: HlsMediaPlaylist?,
    ): ParsingLoadable.Parser<HlsPlaylist> = wrap(
        delegate.createPlaylistParser(multivariantPlaylist, previousMediaPlaylist),
    )

    private fun wrap(parser: ParsingLoadable.Parser<HlsPlaylist>): ParsingLoadable.Parser<HlsPlaylist> =
        ParsingLoadable.Parser { uri, input ->
            val raw = input.readBytes().toString(StandardCharsets.UTF_8)
            val adaptation = TwitchLowLatencyPlaylistAdapter.adapt(
                raw = raw,
                enabled = lowLatencyEnabled,
            )
            val fallbackAdaptation = TwitchLowLatencyPlaylistAdapter.adapt(
                raw = raw,
                enabled = false,
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
                finalAdaptation = fallbackAdaptation
                parsed = parser.parse(
                    uri,
                    ByteArrayInputStream(fallbackAdaptation.playlistText.toByteArray(StandardCharsets.UTF_8)),
                )
            }
            if (adaptation.diagnostics.twitchPrefetchTranslated &&
                parsed is HlsMediaPlaylist &&
                TwitchAdDetector.isAd(parsed)
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
            if (parsed is HlsMediaPlaylist) {
                parsed = restoreTwitchAdInterstitials(
                    parser = parser,
                    playlistUri = uri,
                    playlist = parsed,
                    playlistText = finalAdaptation.playlistText,
                )
            }
            val compatible = TwitchMultivariantPlaylistCompatibility.apply(
                parsed = parsed,
                rawPlaylist = finalAdaptation.playlistText,
                playlistUri = uri,
            )
            val finalDiagnostics = finalAdaptation.diagnostics.copy(
                twitchPrefetchActive = finalAdaptation.diagnostics.twitchPrefetchTranslated,
                partTargetDurationMs = (compatible as? HlsMediaPlaylist)
                    ?.partTargetDurationUs
                    ?.takeIf { it != C.TIME_UNSET }
                    ?.div(1_000L),
            )
            diagnostics?.onPlaylistParsed(finalDiagnostics, compatible)
            logDiagnostics(finalDiagnostics)
            compatible
        }

    private fun restoreTwitchAdInterstitials(
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

        return HlsMediaPlaylist(
            playlist.playlistType,
            playlist.baseUri,
            playlist.tags,
            playlist.startOffsetUs,
            playlist.hasPositiveStartOffset,
            playlist.startTimeUs,
            playlist.hasDiscontinuitySequence,
            playlist.discontinuitySequence,
            playlist.mediaSequence,
            playlist.version,
            playlist.targetDurationUs,
            playlist.partTargetDurationUs,
            playlist.hasEndTag,
            playlist.hasProgramDateTime,
            playlist.preciseStart,
            playlist.protectionSchemes,
            playlist.segments,
            playlist.trailingParts,
            playlist.serverControl,
            playlist.renditionReports,
            playlist.interstitials + restored,
            playlist.lastSeenInitSegment,
        )
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

    private fun logDiagnostics(diagnostics: TwitchHlsPlaylistDiagnostics) {
        if (!BuildConfig.DEBUG && !BuildConfig.PERF_DIAGNOSTICS) return
        Log.d(
            "TwitchLL",
            "targetMs=${diagnostics.declaredTargetDurationMs ?: -1L} " +
                "effectiveTargetMs=${diagnostics.effectiveReloadTargetDurationMs ?: -1L} " +
                "avgSegmentMs=${diagnostics.averageSegmentDurationMs ?: -1L} " +
                "prefetchCount=${diagnostics.twitchPrefetchCount} " +
                "translated=${diagnostics.twitchPrefetchTranslated} " +
                "container=${diagnostics.container ?: "Unknown"}",
        )
    }
}

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
                variants += variant
                return@forEach
            }
            val format = variant.format
            val labeledFormat = rawVariant.label?.takeIf { it.isNotBlank() }?.let {
                format.buildUpon().setLabel(it).build()
            } ?: format
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
