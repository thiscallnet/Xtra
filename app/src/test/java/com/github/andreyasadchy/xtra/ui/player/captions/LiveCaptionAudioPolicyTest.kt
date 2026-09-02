package com.github.andreyasadchy.xtra.ui.player.captions

import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LiveCaptionAudioPolicyTest {
    @Test fun captionsRequireDecodedPcm() {
        assertTrue(liveCaptionsRequirePcm(true))
        assertFalse(liveCaptionsRequirePcm(false))
        assertEquals(LiveCaptionAudioOutputMode.PCM, liveCaptionOutputMode(true))
        assertEquals(LiveCaptionAudioOutputMode.DIRECT, liveCaptionOutputMode(false))
}
}
