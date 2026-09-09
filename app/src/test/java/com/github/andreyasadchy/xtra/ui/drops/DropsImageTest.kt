package com.github.andreyasadchy.xtra.ui.drops

import com.github.andreyasadchy.xtra.model.ui.TwitchDropImageSource
import org.junit.Assert.assertEquals
import org.junit.Test

class DropsImageTest {
    @Test
    fun `original drop artwork keeps the supplied square url`() {
        val url = "https://static-cdn.jtvnw.net/ttv-boxart/reward-160x160.jpg"

        assertEquals(url, dropsImageUrl(url, TwitchDropImageSource.ORIGINAL))
    }

    @Test
    fun `game box art requests a larger rendition`() {
        assertEquals(
            "https://static-cdn.jtvnw.net/ttv-boxart/game-600x800.jpg",
            dropsImageUrl(
                "https://static-cdn.jtvnw.net/ttv-boxart/game-144x192.jpg",
                TwitchDropImageSource.GAME_BOX_ART,
            ),
        )
    }
}
