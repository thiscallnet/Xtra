package com.github.andreyasadchy.xtra.ui.chat

import com.github.andreyasadchy.xtra.model.chat.ChatMessage
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MessageClickedChatAdapterTest {
    @Test
    fun nullIdLegacyRowsUseObjectIdentity() {
        val selected = ChatMessage(message = "selected")
        val other = ChatMessage(message = "other")

        assertTrue(isChatPopupMessageSelected(selected, null, selected))
        assertFalse(isChatPopupMessageSelected(other, null, selected))
    }

    @Test
    fun v2RowsUseTheirStableId() {
        val selected = ChatMessage(id = "message-1")
        val replacement = ChatMessage(id = "message-1")
        val other = ChatMessage(id = "message-2")

        assertTrue(isChatPopupMessageSelected(replacement, "message-1", selected))
        assertFalse(isChatPopupMessageSelected(other, "message-1", selected))
    }
}
