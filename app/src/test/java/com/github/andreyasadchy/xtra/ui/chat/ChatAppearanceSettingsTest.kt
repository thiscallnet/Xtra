package com.github.andreyasadchy.xtra.ui.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatAppearanceSettingsTest {

    private val surface = 0xFF202124.toInt()
    private val messageDefault = 0xFFE6E0E9.toInt()
    private val metadataDefault = 0xFFC9C3D0.toInt()

    @Test
    fun absentOverridesKeepThemeColorsAndSurfaceRows() {
        val appearance = resolveChatAppearance(
            ChatAppearancePreferenceValues(),
            surface,
            messageDefault,
            metadataDefault,
        )

        assertEquals(messageDefault, appearance.messageTextColor)
        assertEquals(metadataDefault, appearance.metadataTextColor)
        assertEquals(surface, appearance.rowBackgroundColor)
        assertFalse(appearance.hasCustomBackground)
        assertFalse(shouldRenderChatBackground(appearance))
    }

    @Test
    fun customColorsAndBackgroundAreResolvedTogether() {
        val appearance = resolveChatAppearance(
            ChatAppearancePreferenceValues(
                backgroundVisibility = 140,
                messageTextColor = "#FFD166",
                metadataTextColor = "#B8F2E6",
            ),
            surface,
            messageDefault,
            metadataDefault,
        )

        assertEquals(0xFFFFD166.toInt(), appearance.messageTextColor)
        assertEquals(0xFFB8F2E6.toInt(), appearance.metadataTextColor)
        assertEquals(100, appearance.backgroundVisibility)
        assertEquals(surface, appearance.rowBackgroundColor)
        assertFalse(appearance.hasCustomBackground)
    }

    @Test
    fun backgroundRenderingRequiresAnEnabledUsableUri() {
        assertTrue(shouldRenderChatBackground(true, true, 37))
        assertFalse(shouldRenderChatBackground(false, true, 37))
        assertFalse(shouldRenderChatBackground(true, false, 37))
    }

    @Test
    fun zeroVisibilityKeepsBackgroundSettingWithoutRenderingIt() {
        assertFalse(shouldRenderChatBackground(true, true, 0))
    }

    @Test
    fun malformedOverridesFallBackToThemeColors() {
        val appearance = resolveChatAppearance(
            ChatAppearancePreferenceValues(
                messageTextColor = "#GGGGGG",
                metadataTextColor = "#11223344",
            ),
            surface,
            messageDefault,
            metadataDefault,
        )

        assertEquals(messageDefault, appearance.messageTextColor)
        assertEquals(metadataDefault, appearance.metadataTextColor)
    }

    @Test
    fun removingOverridesRestoresThemeDefaults() {
        val customized = ChatAppearancePreferenceValues(
            messageTextColor = "#FFD166",
            metadataTextColor = "#B8F2E6",
        )
        val restored = resolveChatAppearance(
            customized.copy(messageTextColor = null, metadataTextColor = null),
            surface,
            messageDefault,
            metadataDefault,
        )

        assertEquals(messageDefault, restored.messageTextColor)
        assertEquals(metadataDefault, restored.metadataTextColor)
    }
}
