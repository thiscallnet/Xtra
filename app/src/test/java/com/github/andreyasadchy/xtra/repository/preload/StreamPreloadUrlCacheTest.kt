package com.github.andreyasadchy.xtra.repository.preload

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class StreamPreloadUrlCacheTest {
    @Test
    fun cacheIsBoundedAndExpiresEntries() {
        var now = 0L
        val cache = StreamPreloadUrlCache(maxEntries = 2, ttlMs = 100, elapsedRealtimeMs = { now })

        cache.put("one", "url-one", "config")
        cache.put("two", "url-two", "config")
        cache.put("three", "url-three", "config")
        assertNull(cache.get("one", "config"))
        assertEquals("url-two", cache.get("two", "config"))
        assertEquals(2, cache.size())

        now = 101L
        assertNull(cache.take("two", "config"))
        assertEquals(1, cache.size())
    }

    @Test
    fun boundedCacheSupportsVODPreviewUrlsAndConfigurationInvalidation() {
        var now = 0L
        val cache = StreamPreloadUrlCache(maxEntries = 8, ttlMs = 100, elapsedRealtimeMs = { now })

        repeat(9) { id -> cache.put("vod-$id", "signed-url-$id", "old-config") }

        assertNull(cache.get("vod-0", "old-config"))
        assertEquals("signed-url-8", cache.get("vod-8", "old-config"))
        assertNull(cache.get("vod-8", "new-config"))
    }

    @Test
    fun changingConfigurationInvalidatesEntries() {
        val cache = StreamPreloadUrlCache(elapsedRealtimeMs = { 0L })

        cache.put("creator", "url", "old-config")

        assertNull(cache.get("creator", "new-config"))
    }

    @Test
    fun defaultCacheRetainsRecentEntriesAcrossFeedChanges() {
        val cache = StreamPreloadUrlCache(elapsedRealtimeMs = { 0L })

        repeat(StreamPreloadPolicy.MAX_CACHED_STREAM_URLS + 1) { index ->
            cache.put("creator-$index", "signed-url-$index", "config")
        }

        assertNull(cache.get("creator-0", "config"))
        assertEquals(
            "signed-url-${StreamPreloadPolicy.MAX_CACHED_STREAM_URLS}",
            cache.get("creator-${StreamPreloadPolicy.MAX_CACHED_STREAM_URLS}", "config"),
        )
        assertEquals(StreamPreloadPolicy.MAX_CACHED_STREAM_URLS, cache.size())
    }

    @Test
    fun previewPeekPreservesTheExactUrlForFullscreenPlayback() {
        val cache = StreamPreloadUrlCache(elapsedRealtimeMs = { 0L })
        cache.put("foo", "signed-url-a", "config")

        val previewUrl = cache.get("foo", "config")
        val playbackUrl = cache.take("foo", "config")

        assertEquals("signed-url-a", previewUrl)
        assertEquals("signed-url-a", playbackUrl)
    }
}
