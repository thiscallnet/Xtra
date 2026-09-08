package com.github.andreyasadchy.xtra.ui.player

/**
 * Keeps a quality selection while a player source is being replaced.
 *
 * A reverse transition can begin after the first transition has already
 * cleared the live quality object. In that case a null capture must not erase
 * the selection that the reverse transition needs to restore.
 */
internal class SourceSwitchQualityState {
    private var pendingName: String? = null

    fun capture(currentName: String?) {
        if (currentName != null) {
            pendingName = currentName
        }
    }

    fun consume(): String? = pendingName.also { pendingName = null }

    fun clear() {
        pendingName = null
    }
}
