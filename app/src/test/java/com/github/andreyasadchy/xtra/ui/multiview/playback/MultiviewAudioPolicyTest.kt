package com.github.andreyasadchy.xtra.ui.multiview.playback

import org.junit.Assert.assertEquals
import org.junit.Test

class MultiviewAudioPolicyTest {
    @Test
    fun configuredVolumesRemainIndependentAcrossSlots() {
        val volumes = mapOf("a" to 0.25f, "b" to 0.75f)

        assertEquals(
            0.25f,
            MultiviewAudioPolicy.volumeFor("a", volumes, hiddenForVaft = false),
            0f,
        )
        assertEquals(
            0.75f,
            MultiviewAudioPolicy.volumeFor("b", volumes, hiddenForVaft = false),
            0f,
        )
        assertEquals(
            0f,
            MultiviewAudioPolicy.volumeFor("b", volumes, hiddenForVaft = true),
            0f,
        )
    }

    @Test
    fun hiddenVaftSlotStaysMutedAcrossActiveStreamChangesAndRenders() {
        fun render(activeIdentity: String): Map<String, Float> {
            return mapOf(
                "a" to MultiviewAudioPolicy.volumeFor(
                    identity = "a",
                    activeIdentity = activeIdentity,
                    hiddenForVaft = true,
                    activeVolume = 1f,
                ),
                "b" to MultiviewAudioPolicy.volumeFor(
                    identity = "b",
                    activeIdentity = activeIdentity,
                    hiddenForVaft = false,
                    activeVolume = 1f,
                ),
            )
        }

        assertEquals(mapOf("a" to 0f, "b" to 1f), render("b"))
        assertEquals(mapOf("a" to 0f, "b" to 0f), render("a"))
        assertEquals(0f, MultiviewAudioPolicy.volumeFor("a", "a", true, 1f), 0f)
    }
}
