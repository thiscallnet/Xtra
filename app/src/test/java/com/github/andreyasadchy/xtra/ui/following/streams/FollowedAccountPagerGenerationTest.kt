package com.github.andreyasadchy.xtra.ui.following.streams

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FollowedAccountPagerGenerationTest {

    @Test
    fun accountSwitchStartsASeparateGenerationWithoutRepeatingForTheSameAccount() {
        val generation = FollowedAccountPagerGeneration()

        assertFalse(generation.switchTo("account-a"))
        assertTrue(generation.switchTo("account-b"))
        assertFalse(generation.switchTo("account-b"))
        assertTrue(generation.switchTo(null))
        assertFalse(generation.switchTo(null))
    }
}
