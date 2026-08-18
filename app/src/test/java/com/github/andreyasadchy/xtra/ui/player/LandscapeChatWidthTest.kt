package com.github.andreyasadchy.xtra.ui.player

import org.junit.Assert.assertEquals
import org.junit.Test

class LandscapeChatWidthTest {

    @Test
    fun `configured chat width is respected across common landscape aspect ratios`() {
        val dimensions = listOf(
            "16:9" to 1600,
            "19.5:9" to 1950,
            "20:9" to 2000,
            "21:9" to 2100,
        )

        dimensions.forEach { (ratio, availableWidth) ->
            listOf(5, 10, 15).forEach { percentage ->
                val configuredWidth = availableWidth * percentage / 100
                assertEquals(
                    "$ratio at $percentage%",
                    configuredWidth,
                    clampLandscapeChatWidth(configuredWidth, availableWidth),
                )
            }
        }
    }

    @Test
    fun `chat width is capped when it exceeds the available width`() {
        assertEquals(800, clampLandscapeChatWidth(1000, 800))
    }
}
