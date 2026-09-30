package com.github.andreyasadchy.xtra.ui.player

import org.junit.Assert.assertEquals
import org.junit.Test

class TwitchVaftControllerTest {

    @Test
    fun vaftWindowSchedulesFairBatchesAndRetriesAfterCooldown() {
        var now = 0L
        val controller = TwitchVaftController { now }

        val firstPass = controller.playerTypesForVaft("site", limit = 2)
        assertEquals(listOf("popout", "mobile_web"), firstPass)
        firstPass.forEach(controller::onPlayerTypeAttemptStarted)
        val secondPass = controller.playerTypesForVaft("site", limit = 2)
        assertEquals(listOf("embed", "autoplay"), secondPass)
        secondPass.forEach(controller::onPlayerTypeAttemptStarted)
        assertEquals(emptyList<String>(), controller.playerTypesForVaft("site"))
        now = TwitchVaftController.RETRY_COOLDOWN_MS - 1
        assertEquals(emptyList<String>(), controller.playerTypesForVaft("site"))
        now++
        assertEquals(listOf("popout", "mobile_web"), controller.playerTypesForVaft("site", limit = 2))
    }

    @Test
    fun cleanPlaylistAllowsAlternatesOnTheNextVaftWindow() {
        val controller = TwitchVaftController()

        controller.playerTypesForVaft("site").forEach(controller::onPlayerTypeAttemptStarted)
        controller.onCleanPlaylist()

        assertEquals(
            listOf("popout", "mobile_web", "embed", "autoplay"),
            controller.playerTypesForVaft("site"),
        )
    }

    @Test
    fun resetAllowsAlternatesOnTheNextVaftWindow() {
        val controller = TwitchVaftController()

        controller.playerTypesForVaft("site").forEach(controller::onPlayerTypeAttemptStarted)
        controller.reset()

        assertEquals(
            listOf("popout", "mobile_web", "embed", "autoplay"),
            controller.playerTypesForVaft("site"),
        )
    }
}
