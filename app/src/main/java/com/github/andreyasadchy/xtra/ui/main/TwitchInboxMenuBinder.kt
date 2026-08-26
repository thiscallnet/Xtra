package com.github.andreyasadchy.xtra.ui.main

import android.content.Context
import android.graphics.Color
import android.view.Gravity
import android.view.MenuItem
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextView
import androidx.core.view.setPadding
import androidx.lifecycle.lifecycleScope
import androidx.appcompat.widget.Toolbar
import androidx.navigation.Navigation
import com.github.andreyasadchy.xtra.R
import com.github.andreyasadchy.xtra.XtraApp
import com.github.andreyasadchy.xtra.model.twitchinbox.NotificationUnreadSummary
import com.github.andreyasadchy.xtra.util.C
import com.github.andreyasadchy.xtra.util.tokenPrefs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

object TwitchInboxMenuBinder {
    private const val SUMMARY_STALE_AFTER_MILLIS = 45_000L
    private var lastSummaryAccount: String? = null
    private var lastSummaryRefreshAt = 0L
    private var cachedNotificationSummary: NotificationUnreadSummary? = null
    private var cachedWhisperSummary: com.github.andreyasadchy.xtra.repository.WhisperUnreadSummary? = null
    private var hasCachedSummary = false

    fun invalidateSummary() {
        lastSummaryRefreshAt = 0L
    }

    fun bind(toolbar: Toolbar, activity: MainActivity) {
        val accountId = activity.tokenPrefs().getString(C.USER_ID, null)
        val loggedIn = !accountId.isNullOrBlank()
        val notificationItem = toolbar.menu.findItem(R.id.twitchNotifications)
        val whispersItem = toolbar.menu.findItem(R.id.whispers)
        if (notificationItem == null && whispersItem == null) return
        notificationItem?.isVisible = loggedIn
        whispersItem?.isVisible = loggedIn
        if (!loggedIn) {
            lastSummaryAccount = null
            lastSummaryRefreshAt = 0L
            cachedNotificationSummary = null
            cachedWhisperSummary = null
            hasCachedSummary = false
            return
        }
        if (lastSummaryAccount != null && lastSummaryAccount != accountId) {
            cachedNotificationSummary = null
            cachedWhisperSummary = null
            hasCachedSummary = false
            lastSummaryRefreshAt = 0L
        }

        notificationItem?.let { bindItem(toolbar.context, it, R.drawable.ic_twitch_notifications, activity.getString(R.string.notifications)) {
            Navigation.findNavController(activity, R.id.navHostFragment).navigate(R.id.action_global_twitchNotificationsFragment)
        } }
        whispersItem?.let { bindItem(toolbar.context, it, R.drawable.ic_twitch_whispers, activity.getString(R.string.whispers)) {
            Navigation.findNavController(activity, R.id.navHostFragment).navigate(R.id.action_global_whispersFragment)
        } }
        applyCachedBadges(toolbar)
        refreshBadges(toolbar, activity)
    }

    private fun applyCachedBadges(toolbar: Toolbar) {
        val notificationBadge = toolbar.menu.findItem(R.id.twitchNotifications)?.actionView as? BadgeActionView
        val whisperBadge = toolbar.menu.findItem(R.id.whispers)?.actionView as? BadgeActionView
        if (!hasCachedSummary) {
            notificationBadge?.setBadge(null, false)
            whisperBadge?.setBadge(null, false)
            return
        }
        cachedNotificationSummary?.let { summary ->
            val displayCount = summary.count?.takeIf { it > 0 }
            notificationBadge?.setBadge(displayCount, summary.hasUnread && displayCount == null)
        } ?: notificationBadge?.setBadge(null, false)
        cachedWhisperSummary?.let { summary ->
            val displayCount = summary.count?.takeIf { it > 0 }
            whisperBadge?.setBadge(displayCount, summary.hasUnread && displayCount == null)
        } ?: whisperBadge?.setBadge(null, false)
    }

    private fun bindItem(context: Context, item: MenuItem, icon: Int, description: String, onClick: () -> Unit) {
        val actionView = item.actionView as? BadgeActionView ?: BadgeActionView(context, icon, description).also { item.actionView = it }
        actionView.setOnClickListener { onClick() }
        item.setOnMenuItemClickListener { onClick(); true }
        item.title = description
        item.contentDescription = description
    }

    private fun refreshBadges(toolbar: Toolbar, activity: MainActivity) {
        val accountId = activity.tokenPrefs().getString(C.USER_ID, null) ?: return
        if (lastSummaryAccount == accountId && System.currentTimeMillis() - lastSummaryRefreshAt < SUMMARY_STALE_AFTER_MILLIS) return
        val currentJob = toolbar.getTag(R.id.twitch_inbox_refresh_job) as? Job
        if (currentJob?.isActive == true) return
        val job = activity.lifecycleScope.launch {
            val module = (activity.application as XtraApp).xtraModule
            lastSummaryAccount = accountId
            lastSummaryRefreshAt = System.currentTimeMillis()
            val (notificationSummary, whisperSummary) = withContext(Dispatchers.IO) {
                runCatching { module.twitchNotificationsRepository.getUnreadSummary() }.getOrNull() to
                    runCatching { module.whispersRepository.getUnreadSummary() }.getOrNull()
            }
            if (activity.tokenPrefs().getString(C.USER_ID, null) != accountId) return@launch
            notificationSummary?.let { cachedNotificationSummary = it }
            whisperSummary?.let { cachedWhisperSummary = it }
            hasCachedSummary = cachedNotificationSummary != null || cachedWhisperSummary != null
            applyCachedBadges(toolbar)
        }
        toolbar.setTag(R.id.twitch_inbox_refresh_job, job)
    }

    private class BadgeActionView(context: Context, icon: Int, description: String) : FrameLayout(context) {
        private val badge = TextView(context).apply {
            setTextColor(Color.WHITE)
            setTextSize(10f)
            gravity = Gravity.CENTER
            setBackgroundResource(R.drawable.bg_twitch_badge)
            visibility = View.GONE
            contentDescription = description
        }

        init {
            minimumWidth = dp(48)
            minimumHeight = dp(48)
            isClickable = true
            isFocusable = true
            addView(ImageView(context).apply {
                setImageResource(icon)
                contentDescription = description
                layoutParams = LayoutParams(dp(24), dp(24), Gravity.CENTER)
            })
            addView(badge, LayoutParams(dp(18), dp(18), Gravity.TOP or Gravity.END).apply {
                topMargin = dp(5)
                rightMargin = dp(3)
            })
            setPadding(0)
        }

        fun setBadge(count: Int?, dot: Boolean) {
            if (count == null && !dot || count == 0) {
                badge.visibility = View.GONE
                return
            }
            badge.visibility = View.VISIBLE
            badge.text = if (dot || count == null) "" else if (count > 99) "99+" else count.toString()
        }

        private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
    }
}
