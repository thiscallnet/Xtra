package com.github.andreyasadchy.xtra.ui.player.captions

import org.junit.Assert.assertEquals
import org.junit.Test

class LiveCaptionTimingTest {

    @Test
    fun parsesSignedSecondsAndClampsToSafeRange() {
        assertEquals(500, parseCaptionTextOffsetMs("0.5"))
        assertEquals(-500, parseCaptionTextOffsetMs("-0.5"))
        assertEquals(MAX_CAPTION_TEXT_OFFSET_MS, parseCaptionTextOffsetMs("3"))
        assertEquals(-MAX_CAPTION_TEXT_OFFSET_MS, parseCaptionTextOffsetMs("-3"))
    }

    @Test
    fun invalidValueUsesNeutralOffset() {
        assertEquals(DEFAULT_CAPTION_TEXT_OFFSET_MS, parseCaptionTextOffsetMs("not-a-number"))
        assertEquals("0.00 s", formatCaptionTextOffset(null))
    }
}
