package com.github.andreyasadchy.xtra.ui.chat

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatComposerVisibilityTest {
    @Test
    fun hiddenChatBarPreferenceStaysHiddenInSlidingPlayerLayout() {
        assertFalse(shouldShowChatComposer(true, true, false))
    }

    @Test
    fun enabledComposerIsVisibleWhenPreferenceAllowsIt() {
        assertTrue(shouldShowChatComposer(true, true, true))
    }
}
