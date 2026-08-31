package com.github.andreyasadchy.xtra.util

import org.junit.Assert.assertEquals
import org.junit.Test

class PlaybackBackendTest {

    @Test
    fun `fresh defaults use legacy ExoPlayer`() {
        assertEquals(
            PlaybackBackend.LEGACY_EXOPLAYER,
            resolvePlaybackBackend(
                playerPreference = null,
                useLegacyCustomPlaybackService = true,
            ),
        )
    }

    @Test
    fun `ExoPlayer with legacy service disabled uses modern Media3`() {
        assertEquals(
            PlaybackBackend.MEDIA3,
            resolvePlaybackBackend(C.EXOPLAYER, false),
        )
    }

    @Test
    fun `legacy custom service selects legacy ExoPlayer`() {
        assertEquals(
            PlaybackBackend.LEGACY_EXOPLAYER,
            resolvePlaybackBackend(C.EXOPLAYER, true),
        )
    }

    @Test
    fun `MediaPlayer selection wins over legacy service flag`() {
        assertEquals(
            PlaybackBackend.ANDROID_MEDIA_PLAYER,
            resolvePlaybackBackend(C.MEDIA_PLAYER, false),
        )
        assertEquals(
            PlaybackBackend.ANDROID_MEDIA_PLAYER,
            resolvePlaybackBackend(C.MEDIA_PLAYER, true),
        )
    }
}
