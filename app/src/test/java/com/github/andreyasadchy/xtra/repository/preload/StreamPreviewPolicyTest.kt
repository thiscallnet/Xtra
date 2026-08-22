package com.github.andreyasadchy.xtra.repository.preload

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class StreamPreviewPolicyTest {
    @Test
    fun previewIsOptInAndUsesConservativeDefaults() {
        assertEquals(StreamPreviewMode.OFF, StreamPreviewMode.fromPreference(null))
        assertEquals(StreamPreviewQuality.P360, StreamPreviewQuality.fromPreference(null))
        assertEquals(StreamPreviewDelay.NORMAL, StreamPreviewDelay.fromPreference(null))
        assertEquals(1_250L, StreamPreviewDelay.NORMAL.delayMs)
    }

    @Test
    fun previewModesKeepWifiAndMobileSeparate() {
        assertEquals(StreamPreviewMode.WIFI_ONLY, StreamPreviewMode.fromPreference("wifi"))
        assertEquals(StreamPreviewMode.WIFI_AND_MOBILE, StreamPreviewMode.fromPreference("all"))
        assertEquals(StreamPreviewMode.OFF, StreamPreviewMode.fromPreference("unexpected"))
    }

    @Test
    fun previewQualityChoicesAreBounded() {
        assertEquals(StreamPreviewQuality.P360, StreamPreviewQuality.fromPreference("360"))
        assertEquals(StreamPreviewQuality.P480, StreamPreviewQuality.fromPreference("480"))
        assertEquals(StreamPreviewQuality.AUTO, StreamPreviewQuality.fromPreference("auto"))
        assertTrue(StreamPreviewPolicy.MIN_VISIBLE_FRACTION >= 0.65f)
    }

    @Test
    fun customProxyBuildsDirectUriWithoutTokenResolution() {
        assertEquals(
            "https://proxy.example/live/foo.m3u8",
            StreamPreloadPolicy.customStreamProxyUrl("https://proxy.example/live/\$channel.m3u8", "Foo"),
        )
    }

    @Test
    fun fullscreenPlaybackBlocksNewPreviewSelectionButAllowsHandoffStateToBeCheckedSeparately() {
        assertTrue(!StreamPreviewPolicy.canStartPreview(isPlayerFullscreen = true, networkAllowed = true, handoffPending = false))
        assertTrue(!StreamPreviewPolicy.canStartPreview(isPlayerFullscreen = false, networkAllowed = false, handoffPending = false))
        assertTrue(!StreamPreviewPolicy.canStartPreview(isPlayerFullscreen = false, networkAllowed = true, handoffPending = true))
        assertTrue(StreamPreviewPolicy.canStartPreview(isPlayerFullscreen = false, networkAllowed = true, handoffPending = false))
    }
}
