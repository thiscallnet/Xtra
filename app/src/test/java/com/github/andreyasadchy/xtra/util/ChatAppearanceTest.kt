package com.github.andreyasadchy.xtra.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ChatAppearanceTest {

    @Test
    fun `badge size accepts finite values in the supported range`() {
        assertEquals(4f, parseChatBadgeSize("4"))
        assertEquals(18.5f, parseChatBadgeSize("18.5"))
        assertEquals(18.5f, parseChatBadgeSize("18,5"))
        assertEquals(64f, parseChatBadgeSize("64"))
    }

    @Test
    fun `badge size rejects invalid and unsafe values`() {
        listOf("abc", "NaN", "Infinity", "-Infinity", "3", "65", "9999999999999").forEach {
            assertNull(it, parseChatBadgeSize(it))
        }
    }
}
