package com.github.andreyasadchy.xtra.ui.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class VideoOutputOwnerTest {
    @Test
    fun switchingTargetsLeavesOnlyTheNewestTargetOwned() {
        val player = Any()
        val mainTarget = Any()
        val secondaryTarget = Any()
        val attached = mutableListOf<Pair<Any, Any>>()
        val detached = mutableListOf<Pair<Any, Any>>()
        val owner = VideoOutputOwner<Any, Any>(
            attachTarget = { currentPlayer, target -> attached += currentPlayer to target },
            detachTarget = { currentPlayer, target -> detached += currentPlayer to target },
        )

        owner.attach(player, mainTarget)
        owner.attach(player, secondaryTarget)
        assertEquals(listOf(player to mainTarget), detached)
        assertEquals(listOf(player to mainTarget, player to secondaryTarget), attached)

        owner.attach(player, mainTarget)
        assertEquals(
            listOf(player to mainTarget, player to secondaryTarget),
            detached,
        )
        assertEquals(
            listOf(player to mainTarget, player to secondaryTarget, player to mainTarget),
            attached,
        )

        owner.clear()
        assertEquals(
            listOf(player to mainTarget, player to secondaryTarget, player to mainTarget),
            detached,
        )
        assertTrue(owner.attachedPlayer() == null)
    }
}
