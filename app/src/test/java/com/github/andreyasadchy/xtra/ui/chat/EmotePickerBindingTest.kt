package com.github.andreyasadchy.xtra.ui.chat

import com.github.andreyasadchy.xtra.model.chat.Emote
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EmotePickerBindingTest {
    @Test
    fun refreshedAssetWithSameNameIdAndProviderCountsAsChanged() {
        val old = Emote(name = "posty5head", id = "emote-id", url1x = "https://example.test/a.png")
        val refreshed = Emote(name = "posty5head", id = "emote-id", url1x = "https://example.test/b.png")

        // Emote.equals only compares names, so assert the premise first.
        assertTrue(old == refreshed)
        assertFalse(old.hasSamePickerBinding(refreshed))
        assertFalse(listOf(old).hasSamePickerBinding(listOf(refreshed)))
    }

    @Test
    fun identicalBindingContentCountsAsUnchanged() {
        val first = Emote(
            name = "party",
            id = "7tv-id",
            url1x = "https://example.test/1x.png",
            url4x = "https://example.test/4x.png",
            format = "webp",
            source = Emote.CHANNEL_STV,
        )
        val second = Emote(
            name = "party",
            id = "7tv-id",
            url1x = "https://example.test/1x.png",
            url4x = "https://example.test/4x.png",
            format = "webp",
            source = Emote.CHANNEL_STV,
        )

        assertTrue(first.hasSamePickerBinding(second))
        assertTrue(listOf(first).hasSamePickerBinding(listOf(second)))
    }

    @Test
    fun changedIdentityMetadataOrOrderCountsAsChanged() {
        val base = Emote(name = "party", id = "7tv-id", url1x = "https://example.test/1x.png", source = Emote.CHANNEL_STV)
        fun variant(
            name: String? = "party",
            id: String? = "7tv-id",
            url1x: String? = "https://example.test/1x.png",
            format: String? = null,
            source: Int? = Emote.CHANNEL_STV,
        ) = Emote(name = name, id = id, url1x = url1x, format = format, source = source)

        assertFalse(base.hasSamePickerBinding(variant(name = "other")))
        assertFalse(base.hasSamePickerBinding(variant(id = "other-id")))
        assertFalse(base.hasSamePickerBinding(variant(source = Emote.GLOBAL_STV)))
        assertFalse(base.hasSamePickerBinding(variant(format = "webp")))
        assertFalse(listOf(base).hasSamePickerBinding(listOf(base, base)))
        assertFalse(
            listOf(base, variant(name = "other", id = "other-id")).hasSamePickerBinding(
                listOf(variant(name = "other", id = "other-id"), base),
            ),
        )
    }
}
