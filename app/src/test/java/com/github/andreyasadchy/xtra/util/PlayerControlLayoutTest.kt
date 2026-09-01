package com.github.andreyasadchy.xtra.util

import org.junit.Assert.assertEquals
import org.junit.Test

class PlayerControlLayoutTest {

    @Test
    fun `anchored entries preserve their group and anchor`() {
        val placements = PlayerControlLayout.controlPlacements(
            "minimize:quick:top_start,quality:menu:top_end",
            "minimize:hidden,quality:hidden",
        )

        assertEquals(
            PlayerControlLayout.ControlPlacement(
                "minimize",
                PlayerControlLayout.GROUP_QUICK,
                PlayerControlLayout.ANCHOR_TOP_START,
            ),
            placements.first { it.action == "minimize" },
        )
        assertEquals(
            PlayerControlLayout.ControlPlacement(
                "quality",
                PlayerControlLayout.GROUP_MENU,
                PlayerControlLayout.ANCHOR_TOP_END,
            ),
            placements.first { it.action == "quality" },
        )
    }

    @Test
    fun `unsupported quick actions stay in the More menu`() {
        val placements = PlayerControlLayout.controlPlacements(
            "bookmark:quick:top_center,viewers:quick:top_center",
            "bookmark:menu,viewers:menu",
        )

        assertEquals(PlayerControlLayout.GROUP_MENU, placements.first { it.action == "bookmark" }.group)
        assertEquals(PlayerControlLayout.GROUP_MENU, placements.first { it.action == "viewers" }.group)
        assertEquals(
            "bookmark:menu:top_center,viewers:menu:top_center",
            PlayerControlLayout.serializeControlLayout(placements),
        )
    }

    @Test
    fun `TV primary action policy excludes secondary phone controls`() {
        assertEquals(true, PlayerControlLayout.isTvPrimaryAction("quality"))
        assertEquals(true, PlayerControlLayout.isTvPrimaryAction("chat"))
        assertEquals(true, PlayerControlLayout.isTvPrimaryAction("volume"))
        assertEquals(false, PlayerControlLayout.isTvPrimaryAction("speed"))
        assertEquals(false, PlayerControlLayout.isTvPrimaryAction("clip"))
        assertEquals(false, PlayerControlLayout.isTvPrimaryAction("fullscreen"))
    }
}
