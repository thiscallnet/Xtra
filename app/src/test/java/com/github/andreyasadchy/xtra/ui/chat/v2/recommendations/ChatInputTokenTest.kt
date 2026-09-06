package com.github.andreyasadchy.xtra.ui.chat.v2.recommendations

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ChatInputTokenTest {
    @Test
    fun `replaces token at the end and appends one space`() {
        assertEquals(
            ChatTokenReplacement("hello Kappa ", 12),
            ChatInputToken.replace("hello kap", 9, "Kappa"),
        )
    }

    @Test
    fun `replaces token at the beginning and keeps following text`() {
        assertEquals(
            ChatTokenReplacement("Kappa wow", 5),
            ChatInputToken.replace("kap wow", 2, "Kappa"),
        )
    }

    @Test
    fun `replaces token in the middle without duplicate spaces`() {
        val result = ChatInputToken.replace("that was so kapp wow", 15, "Kappa")
        assertEquals("that was so Kappa wow", result?.text)
        assertEquals(17, result?.cursor)
    }

    @Test
    fun `punctuation adjacent to prose stays part of the message token`() {
        val result = ChatInputToken.replace("kap!", 3, "Kappa")
        assertEquals("Kappa ", result?.text)
        assertEquals(6, result?.cursor)
    }

    @Test
    fun `punctuation emote is a complete whitespace token`() {
        assertEquals(CurrentChatToken("<3", 6, 8), ChatInputToken.aroundCursor("hello <3", 8))
        assertEquals(
            ChatTokenReplacement("hello <3 ", 9),
            ChatInputToken.replace("hello <", 7, "<3"),
        )
        assertEquals(
            ChatTokenReplacement("say :) now", 6),
            ChatInputToken.replace("say : now", 5, ":)"),
        )
    }

    @Test
    fun `punctuation and alphanumeric tokens in the middle preserve surrounding prose`() {
        assertEquals(CurrentChatToken(":)", 6, 8), ChatInputToken.aroundCursor("hello :) wow", 7))
        assertEquals(
            ChatTokenReplacement("hello Kappa wow", 11),
            ChatInputToken.replace("hello kap wow", 9, "Kappa"),
        )
        assertEquals(
            ChatTokenReplacement("hello <3 wow", 8),
            ChatInputToken.replace("hello < wow", 7, "<3"),
        )
    }

    @Test
    fun `empty and whitespace positions have no token`() {
        assertNull(ChatInputToken.aroundCursor("", 0))
        assertNull(ChatInputToken.aroundCursor("   ", 1))
    }
}
