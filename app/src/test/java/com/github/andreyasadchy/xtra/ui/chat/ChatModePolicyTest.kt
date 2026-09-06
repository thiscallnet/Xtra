package com.github.andreyasadchy.xtra.ui.chat

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatModePolicyTest {
    @Test
    fun validLiveChatAlwaysUsesV2() {
        assertTrue(shouldUseChatV2ForLive(true, "channel-id", "channel-login"))
    }

    @Test
    fun multiviewIsNotASeparateLegacyMode() {
        assertTrue(shouldUseChatV2ForLive(true, "channel-id", "channel-login"))
    }

    @Test
    fun replayAndIncompleteLiveArgumentsDoNotStartLiveV2() {
        assertFalse(shouldUseChatV2ForLive(false, "channel-id", "channel-login"))
        assertFalse(shouldUseChatV2ForLive(true, null, "channel-login"))
        assertFalse(shouldUseChatV2ForLive(true, "channel-id", null))
    }
}
