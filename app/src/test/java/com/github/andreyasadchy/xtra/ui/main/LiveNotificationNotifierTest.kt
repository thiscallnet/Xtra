package com.github.andreyasadchy.xtra.ui.main

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LiveNotificationNotifierTest {

    @Test
    fun avatarUpdateOnlyAppliesToTheActiveNotificationGeneration() {
        assertTrue(isLiveNotificationGenerationCurrent("channel:100", "channel:100"))
        assertFalse(isLiveNotificationGenerationCurrent("channel:200", "channel:100"))
        assertFalse(isLiveNotificationGenerationCurrent(null, "channel:100"))
    }
}
