package com.github.andreyasadchy.xtra.ui.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class EmojiPickerItemTest {
    @Test
    fun `aliases replace next to punctuation and expose their ranges`() {
        val matches = EmojiPickerCatalog.findAliasMatches("hello (:smile:), :satisfied:!")

        assertEquals(listOf(":smile:", ":satisfied:"), matches.map { it.matchedAlias })
        assertEquals(
            "hello (😄), 😆!",
            EmojiPickerCatalog.replaceAliases("hello (:smile:), :satisfied:!"),
        )
    }

    @Test
    fun `aliases do not attach to letters from any script`() {
        val text = "a:smile: я:smile: a\u0301:smile: (:smile:)"

        assertEquals("a:smile: я:smile: a\u0301:smile: (😄)", EmojiPickerCatalog.replaceAliases(text))
        assertTrue(EmojiPickerCatalog.findAliasMatches(text).all { it.matchedAlias == ":smile:" })
    }

    @Test
    fun `escaped aliases and URLs stay literal when sending`() {
        assertEquals(
            "say :heart: https://example.com/:heart: example.com/:heart: example.com:8080/:heart: localhost:3000/:heart: 😄",
            EmojiPickerCatalog.replaceAliases("say \\:heart: https://example.com/:heart: example.com/:heart: example.com:8080/:heart: localhost:3000/:heart: :smile:"),
        )
        assertTrue(EmojiPickerCatalog.findAliasMatches("say \\:heart: https://example.com/:heart: example.com/:heart: example.com:8080/:heart: localhost:3000/:heart:").isEmpty())
        assertNull(EmojiPickerCatalog.findAliasPrefixAtCursor("https://x/:hea", "https://x/:hea".length))
        assertNull(EmojiPickerCatalog.findAliasPrefixAtCursor("\\:hea", "\\:hea".length))
        assertNull(EmojiPickerCatalog.findAliasPrefixAtCursor("foo:hea", "foo:hea".length))
    }

    @Test
    fun `autocomplete tokenizer preserves closed emoji aliases`() {
        val tokenizer = ChatFragment.SpaceTokenizer()

        assertEquals("Kappa ", tokenizer.terminateToken(":Kappa"))
        assertEquals(":heart: ", tokenizer.terminateToken(":heart:"))
    }

    @Test
    fun `twemoji filenames preserve selectors required by zwj sequences`() {
        assertTrue(Twemoji.url("🏳️‍🌈").endsWith("/1f3f3-fe0f-200d-1f308.png"))
        assertTrue(Twemoji.url("🏃‍♀️").endsWith("/1f3c3-200d-2640-fe0f.png"))
        assertTrue(Twemoji.url("👁️‍🗨️").endsWith("/1f441-200d-1f5e8.png"))
        assertTrue(Twemoji.url("❤️").endsWith("/2764.png"))
    }
}
