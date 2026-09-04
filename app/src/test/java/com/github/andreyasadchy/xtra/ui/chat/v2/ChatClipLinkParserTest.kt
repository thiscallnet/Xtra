package com.github.andreyasadchy.xtra.ui.chat.v2

import com.github.andreyasadchy.xtra.ui.chat.v2.preview.ChatClipPreviewLink
import com.github.andreyasadchy.xtra.ui.chat.v2.preview.formatClipDuration
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatClipLinkParserTest {

    @Test
    fun screenshotClipUrlExtractsSlug() {
        val body = "StreamElements: \"How do fk do u fish??\" " +
            "https://www.twitch.tv/posty/clip/AliveNiceVampirePeteZarolTie-5_jmEx1vlvmZNDAm"
        val links = ChatClipPreviewLink.parse(body)
        assertEquals(1, links.size)
        assertEquals("AliveNiceVampirePeteZarolTie-5_jmEx1vlvmZNDAm", links.single().slug)
        assertEquals("https://www.twitch.tv/posty/clip/AliveNiceVampirePeteZarolTie-5_jmEx1vlvmZNDAm", links.single().url)
    }

    @Test
    fun clipsSubdomainUrlExtractsSlug() {
        val links = ChatClipPreviewLink.parse("https://clips.twitch.tv/AliveNiceVampirePeteZarolTie-5_jmEx1vlvmZNDAm")
        assertEquals(1, links.size)
        assertEquals("AliveNiceVampirePeteZarolTie-5_jmEx1vlvmZNDAm", links.single().slug)
    }

    @Test
    fun trailingPunctuationIsTrimmed() {
        val links = ChatClipPreviewLink.parse("wow https://www.twitch.tv/posty/clip/SomeSlug-abc123_-.")
        assertEquals(1, links.size)
        assertEquals("SomeSlug-abc123_-", links.single().slug)
        assertEquals("https://www.twitch.tv/posty/clip/SomeSlug-abc123_-", links.single().url)
    }

    @Test
    fun duplicatesAreCollapsedCaseInsensitively() {
        val links = ChatClipPreviewLink.parse(
            "https://www.twitch.tv/posty/clip/SomeSlug-abc123 https://clips.twitch.tv/someslug-ABC123",
        )
        assertEquals(1, links.size)
    }

    @Test
    fun nonClipUrlsAreIgnored() {
        assertTrue(ChatClipPreviewLink.parse("https://www.twitch.tv/posty").isEmpty())
        assertTrue(ChatClipPreviewLink.parse("https://www.twitch.tv/videos/1234567890").isEmpty())
        assertTrue(ChatClipPreviewLink.parse("just chatting").isEmpty())
    }

    @Test
    fun isClipUrlMatchesBothHosts() {
        assertTrue(ChatClipPreviewLink.isClipUrl("https://www.twitch.tv/posty/clip/SomeSlug-abc123"))
        assertTrue(ChatClipPreviewLink.isClipUrl("https://clips.twitch.tv/SomeSlug-abc123"))
        assertFalse(ChatClipPreviewLink.isClipUrl("https://www.twitch.tv/posty"))
    }

    @Test
    fun durationFormatsLikeTwitch() {
        assertEquals("0:05", formatClipDuration(5))
        assertEquals("1:05", formatClipDuration(65))
        assertEquals("1:02:05", formatClipDuration(3725))
        assertEquals(null, formatClipDuration(null))
        assertEquals(null, formatClipDuration(-1))
    }
}
