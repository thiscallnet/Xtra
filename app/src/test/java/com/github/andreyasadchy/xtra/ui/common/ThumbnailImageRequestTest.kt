package com.github.andreyasadchy.xtra.ui.common

import com.github.andreyasadchy.xtra.model.ui.Stream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ThumbnailImageRequestTest {

    @Test
    fun previewBucketsUseOneStableDiskEntryButFreshNetworkUrls() {
        val stream = Stream(
            channelId = "channel-42",
            thumbnailURL = "https://static-cdn.jtvnw.net/previews/{width}x{height}.jpg",
        )

        val first = streamThumbnailRequestPlan(stream, bucket = 10L)
        val second = streamThumbnailRequestPlan(stream, bucket = 11L)

        assertEquals(first!!.diskCacheKey, second!!.diskCacheKey)
        assertNotEquals(first.networkUrl, second.networkUrl)
        assertEquals("xtra:stream-thumbnail:channel:channel-42", first.diskCacheKey)
    }

    @Test
    fun missingThumbnailHasNoTwoStageRequest() {
        assertNull(streamThumbnailRequestPlan(Stream(channelId = "channel-42"), bucket = 10L))
    }
}
