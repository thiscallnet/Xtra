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
import androidx.appcompat.widget.Toolbar
import androidx.navigation.Navigation
import com.github.andreyasadchy.xtra.R
import com.github.andreyasadchy.xtra.XtraApp
import com.github.andreyasadchy.xtra.model.twitchinbox.NotificationUnreadSummary
import com.github.andreyasadchy.xtra.util.C
import com.github.andreyasadchy.xtra.util.tokenPrefs
import java.util.WeakHashMap
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
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
    private var summaryGeneration = 0L
    private var summaryRefreshJob: Job? = null
    private var summaryRefreshAccount: String? = null
    private var summaryRefreshToken: Any? = null
    private val boundToolbars = WeakHashMap<Toolbar, Unit>()

    fun invalidateSummary() {
        // A request started before a read/dismiss action must not restore its stale unread badge.
        summaryGeneration++
        lastSummaryRefreshAt = 0L
        cachedNotificationSummary = null
        cachedWhisperSummary = null
        hasCachedSummary = false
        cancelSummaryRefreshes()
        applyCachedBadges()
    }

    fun bind(toolbar: Toolbar, activity: MainActivity) {
        val accountId = activity.tokenPrefs().getString(C.USER_ID, null)
        val loggedIn = !accountId.isNullOrBlank()
        val notificationItem = toolbar.menu.findItem(R.id.twitchNotifications)
        val whispersItem = toolbar.menu.findItem(R.id.whispers)
        if (notificationItem == null && whispersItem == null) return
        boundToolbars[toolbar] = Unit
        notificationItem?.isVisible = loggedIn
        whispersItem?.isVisible = loggedIn
        if (!loggedIn) {
            lastSummaryAccount = null
            invalidateSummary()
            boundToolbars.keys.forEach { boundToolbar ->
                boundToolbar.menu.findItem(R.id.twitchNotifications)?.isVisible = false
                boundToolbar.menu.findItem(R.id.whispers)?.isVisible = false
            }
            return
        }
        if ((lastSummaryAccount != null && lastSummaryAccount != accountId) ||
            (summaryRefreshAccount != null && summaryRefreshAccount != accountId)
        ) {
            invalidateSummary()
        }

        notificationItem?.let { bindItem(toolbar.context, it, R.drawable.ic_twitch_notifications, activity.getString(R.string.notifications)) {
            Navigation.findNavController(activity, R.id.navHostFragment).navigate(R.id.action_global_twitchNotificationsFragment)
        } }
        whispersItem?.let { bindItem(toolbar.context, it, R.drawable.ic_twitch_whispers, activity.getString(R.string.whispers)) {
            Navigation.findNavController(activity, R.id.navHostFragment).navigate(R.id.action_global_whispersFragment)
        } }
        applyCachedBadges()
        refreshBadges(activity)
    }

    private fun applyCachedBadges() {
        boundToolbars.keys.forEach(::applyCachedBadges)
    }

    private fun cancelSummaryRefreshes() {
        summaryRefreshToken = null
        summaryRefreshAccount = null
        summaryRefreshJob?.cancel()
        summaryRefreshJob = null
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

    private fun refreshBadges(activity: MainActivity) {
        val accountId = activity.tokenPrefs().getString(C.USER_ID, null) ?: return
        if (lastSummaryAccount == accountId && System.currentTimeMillis() - lastSummaryRefreshAt < SUMMARY_STALE_AFTER_MILLIS) return
        val currentJob = summaryRefreshJob
        if (currentJob?.isActive == true) return
        val generation = summaryGeneration
        val app = activity.application as XtraApp
        val appContext = app.applicationContext
        val module = app.xtraModule
        val refreshToken = Any()
        val job = app.applicationScope.launch(start = CoroutineStart.LAZY) {
            val (notificationSummary, whisperSummary) = withContext(Dispatchers.IO) {
                suspend fun <T> request(block: suspend () -> T): T? = try {
                    block()
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    null
                }
                request { module.twitchNotificationsRepository.getUnreadSummary() } to
                    request { module.whispersRepository.getUnreadSummary() }
            }
            withContext(Dispatchers.Main.immediate) {
                if (summaryRefreshToken !== refreshToken) return@withContext
                // The generation check also covers requests whose underlying transport ignores cancellation.
                if (appContext.tokenPrefs().getString(C.USER_ID, null) != accountId || generation != summaryGeneration) {
                    summaryRefreshJob = null
                    summaryRefreshAccount = null
                    summaryRefreshToken = null
                    return@withContext
                }
                notificationSummary?.let { cachedNotificationSummary = it }
                whisperSummary?.let { cachedWhisperSummary = it }
                hasCachedSummary = cachedNotificationSummary != null || cachedWhisperSummary != null
                if (notificationSummary != null || whisperSummary != null) {
                    lastSummaryAccount = accountId
                    lastSummaryRefreshAt = System.currentTimeMillis()
                }
                applyCachedBadges()
                summaryRefreshJob = null
                summaryRefreshAccount = null
                summaryRefreshToken = null
            }
        }
        summaryRefreshJob = job
        summaryRefreshAccount = accountId
        summaryRefreshToken = refreshToken
        job.start()
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
