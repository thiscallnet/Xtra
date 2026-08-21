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

        now = 101L
        assertNull(cache.take("two", "config"))
    }

    @Test
    fun changingConfigurationInvalidatesEntries() {
        val cache = StreamPreloadUrlCache(elapsedRealtimeMs = { 0L })

        cache.put("creator", "url", "old-config")

        assertNull(cache.get("creator", "new-config"))
    }
}
