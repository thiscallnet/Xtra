package com.github.andreyasadchy.xtra.ui.player.clip

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ClipTimeTest {
    @Test
    fun parsesHoursMinutesAndSeconds() {
        assertEquals(6_133_000L, ClipTime.parseMs("01:42:13"))
        assertEquals(90_000L, ClipTime.parseMs("01:30"))
        assertEquals("04:30:00", ClipTime.formatMs(16_200_000L))
    }

    @Test
    fun rejectsInvalidMinuteAndSecondValues() {
        assertNull(ClipTime.parseMs("01:60:00"))
        assertNull(ClipTime.parseMs("01:00:60"))
        assertNull(ClipTime.parseMs("not-a-time"))
    }
}
