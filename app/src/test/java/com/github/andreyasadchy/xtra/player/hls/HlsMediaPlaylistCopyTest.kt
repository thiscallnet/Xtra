package com.github.andreyasadchy.xtra.player.hls

import androidx.media3.common.C
import androidx.media3.exoplayer.hls.playlist.HlsMediaPlaylist
import org.junit.Assert.assertEquals
import org.junit.Test

class HlsMediaPlaylistCopyTest {

    @Test
    fun copyWithInterstitialsPreservesLivePlaylistFlags() {
        val playlist = playlist(
            preciseStart = true,
            hasIndependentSegments = true,
            hasEndTag = false,
            hasProgramDateTime = true,
        )

        val copy = playlist.copyWithInterstitials(emptyList())

        assertPreserved(playlist, copy)
    }

    @Test
    fun copyWithInterstitialsPreservesFinitePlaylistFlags() {
        val playlist = playlist(
            preciseStart = false,
            hasIndependentSegments = false,
            hasEndTag = true,
            hasProgramDateTime = false,
        )

        val copy = playlist.copyWithInterstitials(emptyList())

        assertPreserved(playlist, copy)
    }

    private fun assertPreserved(expected: HlsMediaPlaylist, actual: HlsMediaPlaylist) {
        assertEquals(expected.preciseStart, actual.preciseStart)
        assertEquals(expected.hasIndependentSegments, actual.hasIndependentSegments)
        assertEquals(expected.hasEndTag, actual.hasEndTag)
        assertEquals(expected.hasProgramDateTime, actual.hasProgramDateTime)
        assertEquals(expected.hasPositiveStartOffset, actual.hasPositiveStartOffset)
        assertEquals(expected.startTimeUs, actual.startTimeUs)
        assertEquals(expected.mediaSequence, actual.mediaSequence)
        assertEquals(expected.durationUs, actual.durationUs)
        assertEquals(expected.targetDurationUs, actual.targetDurationUs)
        assertEquals(expected.partTargetDurationUs, actual.partTargetDurationUs)
        assertEquals(expected.interstitials, actual.interstitials)
    }

    private fun playlist(
        preciseStart: Boolean,
        hasIndependentSegments: Boolean,
        hasEndTag: Boolean,
        hasProgramDateTime: Boolean,
    ) = HlsMediaPlaylist(
        HlsMediaPlaylist.PLAYLIST_TYPE_UNKNOWN,
        "https://example.invalid/live.m3u8",
        listOf("#EXTM3U"),
        2_000_000L,
        preciseStart,
        123_000_000L,
        true,
        4,
        120L,
        7,
        6_000_000L,
        1_000_000L,
        hasIndependentSegments,
        hasEndTag,
        hasProgramDateTime,
        null,
        emptyList(),
        emptyList(),
        HlsMediaPlaylist.ServerControl(
            C.TIME_UNSET,
            false,
            C.TIME_UNSET,
            C.TIME_UNSET,
            false,
        ),
        emptyMap(),
        emptyList(),
        null,
    )
}
