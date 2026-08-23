package com.github.andreyasadchy.xtra.repository.preload

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class StreamPreviewPolicyTest {
    @Test
    fun missingPreviewModeEnablesAllNetworksAndImmediateIsAvailable() {
        assertEquals(StreamPreviewMode.WIFI_AND_MOBILE, StreamPreviewMode.fromPreference(null))
        assertEquals(StreamPreviewQuality.P360, StreamPreviewQuality.fromPreference(null))
        assertEquals(StreamPreviewDelay.IMMEDIATE, StreamPreviewDelay.fromPreference(null))
        assertEquals(0L, StreamPreviewDelay.IMMEDIATE.delayMs)
        assertEquals(StreamPreviewDelay.FAST, StreamPreviewDelay.fromPreference("fast"))
    }

    @Test
    fun previewModesKeepWifiAndMobileSeparate() {
        assertEquals(StreamPreviewMode.OFF, StreamPreviewMode.fromPreference("off"))
        assertEquals(StreamPreviewMode.WIFI_ONLY, StreamPreviewMode.fromPreference("wifi"))
        assertEquals(StreamPreviewMode.WIFI_AND_MOBILE, StreamPreviewMode.fromPreference("all"))
        assertEquals(StreamPreviewMode.OFF, StreamPreviewMode.fromPreference("unexpected"))
    }

    @Test
    fun previewQualityChoicesAreBounded() {
        assertEquals(StreamPreviewQuality.P360, StreamPreviewQuality.fromPreference("360"))
        assertEquals(StreamPreviewQuality.P480, StreamPreviewQuality.fromPreference("480"))
        assertEquals(StreamPreviewQuality.AUTO, StreamPreviewQuality.fromPreference("auto"))
        assertTrue(StreamPreviewSelectionPolicy.START_VISIBLE_FRACTION in 0.30f..0.35f)
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
