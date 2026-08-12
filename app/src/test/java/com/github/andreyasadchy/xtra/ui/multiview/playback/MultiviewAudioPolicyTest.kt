package com.github.andreyasadchy.xtra.ui.multiview.playback

import org.junit.Assert.assertEquals
import org.junit.Test

class MultiviewAudioPolicyTest {
    @Test
    fun hiddenAdSlotStaysMutedAcrossActiveStreamChangesAndRenders() {
        fun render(activeIdentity: String): Map<String, Float> {
            return mapOf(
                "a" to MultiviewAudioPolicy.volumeFor(
                    identity = "a",
                    activeIdentity = activeIdentity,
                    hiddenForAd = true,
                    activeVolume = 1f,
                ),
                "b" to MultiviewAudioPolicy.volumeFor(
                    identity = "b",
                    activeIdentity = activeIdentity,
                    hiddenForAd = false,
                    activeVolume = 1f,
                ),
            )
        }

        assertEquals(mapOf("a" to 0f, "b" to 1f), render("b"))
        assertEquals(mapOf("a" to 0f, "b" to 0f), render("a"))
        assertEquals(0f, MultiviewAudioPolicy.volumeFor("a", "a", true, 1f), 0f)
    }
}
