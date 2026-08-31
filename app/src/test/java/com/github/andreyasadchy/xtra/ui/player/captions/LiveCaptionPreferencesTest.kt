package com.github.andreyasadchy.xtra.ui.player.captions

import org.junit.Assert.assertEquals
import org.junit.Test

class LiveCaptionPreferencesTest {
    @Test
    fun `accepts ListPreference string value`() {
        assertEquals(1_000, parseLiveCaptionPartialInterval("1000"))
    }

    @Test
    fun `accepts legacy integer value`() {
        assertEquals(500, parseLiveCaptionPartialInterval(500))
    }

    @Test
    fun `invalid value uses safe default`() {
        assertEquals(1_000, parseLiveCaptionPartialInterval("not-a-duration"))
    }
}
