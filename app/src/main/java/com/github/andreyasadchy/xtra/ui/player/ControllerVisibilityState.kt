package com.github.andreyasadchy.xtra.ui.player

internal class ControllerVisibilityState(
    initiallyAutoHideEnabled: Boolean = true,
) {
    var targetVisible = false
        private set

    private var autoHideEnabled = initiallyAutoHideEnabled
    private var scrubbing = false

    fun setAutoHideEnabled(enabled: Boolean) {
        autoHideEnabled = enabled
    }

    fun toggle(hideOnTouch: Boolean): Boolean {
        targetVisible = if (hideOnTouch) !targetVisible else true
        return targetVisible
    }

    fun show() {
        targetVisible = true
    }

    fun hide() {
        targetVisible = false
    }

    fun onScrubStart() {
        scrubbing = true
    }

    fun onScrubStop() {
        scrubbing = false
    }

    fun shouldScheduleHide(
        hideOnTouch: Boolean,
        interactionLocked: Boolean,
        progressPressed: Boolean,
        rootVisible: Boolean,
    ): Boolean = targetVisible &&
        autoHideEnabled &&
        !scrubbing &&
        hideOnTouch &&
        !interactionLocked &&
        !progressPressed &&
        rootVisible
}
