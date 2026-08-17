package com.github.andreyasadchy.xtra.ui.player

import androidx.media3.exoplayer.hls.playlist.HlsMediaPlaylist
import androidx.media3.exoplayer.hls.playlist.HlsMultivariantPlaylist
import androidx.media3.exoplayer.hls.playlist.HlsPlaylist
import androidx.media3.exoplayer.hls.playlist.HlsPlaylistParserFactory
import androidx.media3.exoplayer.upstream.ParsingLoadable
import com.github.andreyasadchy.xtra.player.lowlatency.HlsPlaylistParser

/**
 * The playlist parser used by all Twitch HLS playback paths.
 *
 * Keeping this outside a service lets the canonical MediaSession service and
 * the remaining legacy/multiview paths share the exact same Twitch parser
 * behavior during the migration.
 */
class TwitchHlsPlaylistParserFactory : HlsPlaylistParserFactory {
    override fun createPlaylistParser(): ParsingLoadable.Parser<HlsPlaylist> = HlsPlaylistParser()

    override fun createPlaylistParser(
        multivariantPlaylist: HlsMultivariantPlaylist,
        previousMediaPlaylist: HlsMediaPlaylist?
    ): ParsingLoadable.Parser<HlsPlaylist> =
        HlsPlaylistParser(multivariantPlaylist, previousMediaPlaylist)
}
