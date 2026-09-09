package com.github.andreyasadchy.xtra.ui.common

import com.github.andreyasadchy.xtra.model.ui.Stream
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StreamDropsPreviewPolicyTest {
    @Test
    fun onlyDropsEnabledStreamsReceiveThePreviewBadge() {
        assertTrue(StreamDropsPreviewPolicy.hasDropsTag(Stream(tags = listOf("DropsEnabled"))))
        assertTrue(StreamDropsPreviewPolicy.hasDropsTag(Stream(tags = listOf("dropsenabled"))))
        assertFalse(StreamDropsPreviewPolicy.hasDropsTag(Stream(tags = listOf("English", "Gaming"))))
        assertFalse(StreamDropsPreviewPolicy.hasDropsTag(Stream()))
    }
}
