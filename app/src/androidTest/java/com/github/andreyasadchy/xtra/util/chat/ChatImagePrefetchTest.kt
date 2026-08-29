package com.github.andreyasadchy.xtra.util.chat

import com.bumptech.glide.load.model.GlideUrl
import com.github.andreyasadchy.xtra.BuildConfig
import com.github.andreyasadchy.xtra.model.chat.Image
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatImagePrefetchTest {

    @Test
    fun thirdPartyLocalDataRemainsBinaryGlideModel() {
        val bytes = byteArrayOf(1, 2, 3)
        val image = Image(localData = bytes, thirdParty = true, start = 0, end = 1)

        val model = ChatAdapterUtils.glideImageModel(image, bytes)

        assertSame(bytes, model)
        assertTrue(model !is GlideUrl)
    }

    @Test
    fun thirdPartyRemoteDataUsesXtraUserAgent() {
        val image = Image(thirdParty = true, start = 0, end = 1)

        val model = ChatAdapterUtils.glideImageModel(image, "https://example.com/emote.png") as GlideUrl

        assertEquals("https://example.com/emote.png", model.toString())
        assertEquals("Xtra/${BuildConfig.VERSION_NAME}", model.headers["User-Agent"])
    }

    @Test
    fun failedPrefetchCanBeRetriedAfterCrossCallDeduplication() {
        val tracker = ChatAdapterUtils.ChatImagePrefetchTracker(maxEntries = 2)
        val firstToken = tracker.tryStart("same-request")

        assertNotNull(firstToken)
        assertNull(tracker.tryStart("same-request"))
        tracker.markFailed("same-request", firstToken!!)
        assertNotNull(tracker.tryStart("same-request"))
    }
}
