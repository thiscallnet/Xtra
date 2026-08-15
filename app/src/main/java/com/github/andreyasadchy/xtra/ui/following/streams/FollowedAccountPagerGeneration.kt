package com.github.andreyasadchy.xtra.ui.following.streams

/** Tracks whether a followed-stream Paging generation belongs to a new account. */
internal class FollowedAccountPagerGeneration {
    private var hasGeneration = false
    private var accountId: String? = null

    fun switchTo(newAccountId: String?): Boolean {
        val changed = hasGeneration && accountId != newAccountId
        hasGeneration = true
        accountId = newAccountId
        return changed
    }
}
