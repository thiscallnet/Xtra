package com.github.andreyasadchy.xtra.repository.preload

import org.junit.Assert.assertNotEquals
import org.junit.Test

class StreamPlaybackConfigurationTest {
    @Test
    fun lowLatencyIsPartOfThePreloadConfigurationGeneration() {
        assertNotEquals(configuration(lowLatency = false).fingerprint, configuration(lowLatency = true).fingerprint)
    }

    private fun configuration(lowLatency: Boolean) = StreamPlaybackConfiguration(
        networkLibrary = "okhttp",
        gqlHeaders = emptyMap(),
        randomDeviceId = true,
        xDeviceId = "device",
        playerType = "site",
        supportedCodecs = "h264",
        proxyPlaybackAccessToken = false,
        proxyHost = null,
        proxyPort = null,
        proxyUser = null,
        proxyPassword = null,
        enableIntegrity = false,
        lowLatency = lowLatency,
        proxyMultivariantPlaylist = false,
        streamHeaders = emptyMap(),
        customStreamProxyEnabled = false,
        customStreamProxyUrl = null,
    )
}
