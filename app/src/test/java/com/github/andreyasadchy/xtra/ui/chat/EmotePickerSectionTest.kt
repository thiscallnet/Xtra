package com.github.andreyasadchy.xtra.ui.chat

import org.junit.Assert.assertFalse
import org.junit.Test

class EmotePickerSectionTest {

    @Test
    fun recentEmotesCannotBeAddedToFavoritesWithoutProviderIdentity() {
        assertFalse(EmotePickerSection.RECENTS.supportsFavoriteToggle)
    }
}
