package com.github.andreyasadchy.xtra.ui.common

import com.github.andreyasadchy.xtra.model.ui.Stream
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StreamDropsPreviewPolicyTest {
    @Test
    fun onlyStreams_with_verified_channel_drops_receive_the_preview_badge() {
        assertTrue(StreamDropsPreviewPolicy.hasVerifiedDrops(Stream(dropsAvailable = true)))
        assertFalse(StreamDropsPreviewPolicy.hasVerifiedDrops(Stream(tags = listOf("DropsEnabled"))))
        assertFalse(StreamDropsPreviewPolicy.hasVerifiedDrops(Stream(dropsAvailable = false)))
        assertFalse(StreamDropsPreviewPolicy.hasVerifiedDrops(Stream()))
    }
}
