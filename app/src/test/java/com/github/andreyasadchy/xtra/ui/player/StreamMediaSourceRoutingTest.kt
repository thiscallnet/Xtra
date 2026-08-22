package com.github.andreyasadchy.xtra.ui.player

import androidx.media3.common.MimeTypes
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StreamMediaSourceRoutingTest {
    @Test
    fun liveHlsItemsUseTheCustomHlsFactory() {
        assertTrue(StreamMediaSourceRouting.isHls(MimeTypes.APPLICATION_M3U8, "/live"))
        assertTrue(StreamMediaSourceRouting.isHls(null, "/live.m3u8"))
    }

    @Test
    fun offlineProgressiveItemsUseTheDefaultFactoryPath() {
        assertFalse(StreamMediaSourceRouting.isHls(null, "/offline.mp4"))
    }
}
