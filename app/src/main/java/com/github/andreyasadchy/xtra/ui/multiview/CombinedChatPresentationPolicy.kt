package com.github.andreyasadchy.xtra.ui.multiview

/** Small, platform-free decisions used by the combined chat surface. */
object CombinedChatPresentationPolicy {
    fun shouldAutoScroll(wasAtBottom: Boolean, explicitRefresh: Boolean = false): Boolean {
        return wasAtBottom || explicitRefresh
    }

    fun nextRenderGeneration(current: Long): Long = current + 1L
}
