package com.github.andreyasadchy.xtra.ui.player.captions

import org.junit.Assert.assertEquals
import org.junit.Test

class LiveCaptionPositionTest {
    @Test
    fun `center maps to zero translation`() {
        assertEquals(
            0f,
            LiveCaptionPosition.translationForCenter(0.5f, 1000, 100, 800),
            0.001f,
        )
    }

    @Test
    fun `translation round trips to normalized center`() {
        val translation = LiveCaptionPosition.translationForCenter(0.2f, 1000, 100, 800)
        assertEquals(
            0.2f,
            LiveCaptionPosition.normalizedCenterForTranslation(translation, 1000, 100, 800),
            0.001f,
        )
    }

    @Test
    fun `same normalized center has same relative placement at different sizes`() {
        val first = LiveCaptionPosition.translationForCenter(0.8f, 1000, 100, 800)
        val second = LiveCaptionPosition.translationForCenter(0.8f, 2000, 200, 1600)
        assertEquals(first / 1000f, second / 2000f, 0.001f)
    }
}
