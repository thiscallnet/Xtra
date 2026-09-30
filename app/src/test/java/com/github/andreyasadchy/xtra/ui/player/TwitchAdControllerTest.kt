package com.github.andreyasadchy.xtra.ui.player

import org.junit.Assert.assertEquals
import org.junit.Test

class TwitchAdControllerTest {

    @Test
    fun adWindowSchedulesFairBatchesAndRetriesAfterCooldown() {
        var now = 0L
        val controller = TwitchAdController { now }

        val firstPass = controller.playerTypesForAd("site", limit = 2)
        assertEquals(listOf("popout", "mobile_web"), firstPass)
        firstPass.forEach(controller::onPlayerTypeAttemptStarted)
        val secondPass = controller.playerTypesForAd("site", limit = 2)
        assertEquals(listOf("embed", "autoplay"), secondPass)
        secondPass.forEach(controller::onPlayerTypeAttemptStarted)
        assertEquals(emptyList<String>(), controller.playerTypesForAd("site"))
        now = TwitchAdController.RETRY_COOLDOWN_MS - 1
        assertEquals(emptyList<String>(), controller.playerTypesForAd("site"))
        now++
        assertEquals(listOf("popout", "mobile_web"), controller.playerTypesForAd("site", limit = 2))
    }

    @Test
    fun cleanPlaylistAllowsAlternatesOnTheNextAdWindow() {
        val controller = TwitchAdController()

        controller.playerTypesForAd("site").forEach(controller::onPlayerTypeAttemptStarted)
        controller.onCleanPlaylist()

        assertEquals(
            listOf("popout", "mobile_web", "embed", "autoplay"),
            controller.playerTypesForAd("site"),
        )
    }

    @Test
    fun resetAllowsAlternatesOnTheNextAdWindow() {
        val controller = TwitchAdController()

        controller.playerTypesForAd("site").forEach(controller::onPlayerTypeAttemptStarted)
        controller.reset()

        assertEquals(
            listOf("popout", "mobile_web", "embed", "autoplay"),
            controller.playerTypesForAd("site"),
        )
    }
}
