package com.github.andreyasadchy.xtra.ui.inbox

import android.text.format.DateUtils
import com.github.andreyasadchy.xtra.R
import com.github.andreyasadchy.xtra.model.twitchinbox.TwitchInboxError
import java.time.Instant

fun relativeTime(instant: Instant?): String = instant?.let {
    DateUtils.getRelativeTimeSpanString(it.toEpochMilli(), System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS).toString()
}.orEmpty()

fun TwitchInboxError.messageRes(): Int = when (this) {
    TwitchInboxError.SignedOut -> R.string.reconnect_twitch
    TwitchInboxError.RequiresReauth -> R.string.session_needs_refresh
    is TwitchInboxError.RateLimited, TwitchInboxError.Network, TwitchInboxError.TwitchServerError,
    is TwitchInboxError.GraphQl, is TwitchInboxError.PrivateApiChanged, TwitchInboxError.Unknown -> R.string.connection_error
}
