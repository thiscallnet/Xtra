package com.github.andreyasadchy.xtra.ui.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class EmotePickerSectionTest {

    @Test
    fun recentEmotesCannotBeAddedToFavoritesWithoutProviderIdentity() {
        assertFalse(EmotePickerSection.RECENTS.supportsFavoriteToggle)
    }

    @Test
    fun previousDefaultPickerTabsGainTheChannelSection() {
        val previousDefault = setOf("FAVORITES", "RECENTS", "EMOJI", "TWITCH", "THIRD_PARTY")

        assertEquals(
            previousDefault + EmotePickerSection.CHANNEL.name,
            migrateDefaultEmotePickerSections(previousDefault),
        )
        assertEquals(
            setOf("FAVORITES", "TWITCH"),
            migrateDefaultEmotePickerSections(setOf("FAVORITES", "TWITCH")),
        )
    }
}
