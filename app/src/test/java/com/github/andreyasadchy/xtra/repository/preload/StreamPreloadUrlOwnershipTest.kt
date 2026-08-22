package com.github.andreyasadchy.xtra.repository.preload

import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class StreamPreloadUrlOwnershipTest {
    @Test
    fun previewKeepsTheWarmUrlForPlaybackAndExactMediaHandoff() {
        val repositoryCalls = AtomicInteger()
        val cache = StreamPreloadUrlCache(elapsedRealtimeMs = { 0L })
        val ownership = StreamPreloadUrlOwnership(cache)
        val configuration = "config"

        val resolvedUrl = "signed-url-a".also {
            repositoryCalls.incrementAndGet()
            cache.put("foo", it, configuration)
        }
        val previewUrl = ownership.forPreview("foo", configuration)
        val playbackUrl = ownership.forPlayback("foo", configuration)
        val handoffEntry = MediaPreloadPlanEntry(
            channelLogin = "foo",
            url = resolvedUrl,
            rank = 0,
            samplesLoadedAtMs = 0L,
        )

        assertEquals("signed-url-a", previewUrl)
        assertEquals("signed-url-a", playbackUrl)
        assertEquals(1, repositoryCalls.get())
        assertTrue(StreamMediaPreloadHandoff.isUsable(handoffEntry, "foo", playbackUrl!!, true, 1_000L, 4_500L))
    }
}
