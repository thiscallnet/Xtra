package com.github.andreyasadchy.xtra.ui.player

/**
 * Coordinates alternate Twitch player types for one live ad window.
 *
 * A source is allowed to be tried once per ad window. When a clean playlist is
 * observed, the controller resets so a later ad can get a fresh set of tokens.
 */
class TwitchAdController {

    private var adWindowActive = false
    private val attemptedPlayerTypes = linkedSetOf<String>()

    fun playerTypesForAd(currentPlayerType: String?): List<String> {
        if (!adWindowActive) {
            adWindowActive = true
            attemptedPlayerTypes.clear()
        }
        return PLAYER_TYPES.filter { playerType ->
            playerType != currentPlayerType && attemptedPlayerTypes.add(playerType)
        }
    }

    fun onCleanPlaylist() {
        if (adWindowActive) {
            adWindowActive = false
            attemptedPlayerTypes.clear()
        }
    }

    fun reset() {
        adWindowActive = false
        attemptedPlayerTypes.clear()
    }

    companion object {
        // Match VAFT's maintained Source-tier order. Keep Xtra's existing
        // autoplay path as a last-resort fallback after those candidates.
        val PLAYER_TYPES = listOf("site", "popout", "mobile_web", "embed", "autoplay")
    }
}
