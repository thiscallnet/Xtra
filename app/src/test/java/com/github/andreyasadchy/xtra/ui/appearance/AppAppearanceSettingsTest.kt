package com.github.andreyasadchy.xtra.ui.appearance

import org.junit.Assert.assertEquals
import org.junit.Test

class AppAppearanceSettingsTest {
    private val app = BackgroundConfiguration(
        enabled = true,
        visibility = 65,
    )
    private val player = BackgroundConfiguration(
        enabled = true,
        visibility = 35,
    )

    @Test
    fun inheritUsesTheAppBackground() {
        assertEquals(app, resolvePlayerBackground(app, player, PlayerBackgroundMode.INHERIT_APP))
    }

    @Test
    fun customUsesOnlyThePlayerBackground() {
        assertEquals(player, resolvePlayerBackground(app, player, PlayerBackgroundMode.CUSTOM))
    }

    @Test
    fun offReturnsTheThemeFallback() {
        assertEquals(BackgroundConfiguration(), resolvePlayerBackground(app, player, PlayerBackgroundMode.OFF))
    }
}
