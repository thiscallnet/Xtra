package com.github.andreyasadchy.xtra.ui.player

/**
 * Coordinates alternate Twitch player types for one live ad window.
 *
 * Failed candidates become eligible again after a cooldown within the same break.
 */
class TwitchAdController(private val clockMs: () -> Long = { System.nanoTime() / 1_000_000 }) {

    private var adWindowActive = false
    private val attemptedPlayerTypes = linkedMapOf<String, Long>()

    @Synchronized
    fun playerTypesForAd(currentPlayerType: String?, limit: Int = PLAYER_TYPES.size): List<String> {
        if (!adWindowActive) {
            adWindowActive = true
            attemptedPlayerTypes.clear()
        }
        val now = clockMs()
        return PLAYER_TYPES.filter { playerType ->
            playerType != currentPlayerType &&
                attemptedPlayerTypes[playerType]?.let { now - it >= RETRY_COOLDOWN_MS } != false
        }.sortedBy { attemptedPlayerTypes[it] ?: Long.MIN_VALUE }.take(limit)
    }

    @Synchronized
    fun onPlayerTypeAttemptStarted(playerType: String) {
        attemptedPlayerTypes[playerType] = clockMs()
    }

    @Synchronized
    fun onCleanPlaylist() {
        if (adWindowActive) {
            adWindowActive = false
            attemptedPlayerTypes.clear()
        }
    }

    @Synchronized
    fun reset() {
        adWindowActive = false
        attemptedPlayerTypes.clear()
    }

    companion object {
        const val RETRY_COOLDOWN_MS = 5_000L
        // Match VAFT's maintained Source-tier order. Keep Xtra's existing
        // autoplay path as a last-resort fallback after those candidates.
        val PLAYER_TYPES = listOf("site", "popout", "mobile_web", "embed", "autoplay")
    }
}
