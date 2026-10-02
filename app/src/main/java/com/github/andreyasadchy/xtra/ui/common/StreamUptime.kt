package com.github.andreyasadchy.xtra.ui.common

import android.text.format.DateUtils
import android.view.View
import android.widget.TextView
import kotlin.time.Instant

/** Renders a snapshot at bind time; owns no timer or background work. */
internal class StreamCardUptimeBinder(private val view: TextView) {
    fun bind(createdAt: String?, enabled: Boolean, nowMs: Long = System.currentTimeMillis()) {
        val startedAtMs = if (enabled) parseStreamStartedAtMs(createdAt) else null
        val text = startedAtMs?.let { formatStreamUptime(it, nowMs) }
        if (text != null) {
            if (view.text.toString() != text) view.text = text
            view.visibility = View.VISIBLE
        } else {
            clear()
        }
    }

    fun clear() {
        view.text = null
        view.visibility = View.GONE
    }
}

/** Parse the server timestamp once during a full holder bind. */
internal fun parseStreamStartedAtMs(createdAt: String?): Long? {
    val instant = createdAt?.let { Instant.parseOrNull(it) } ?: return null
    return instant.toEpochMilliseconds().takeIf { it > 0L }
}

internal fun formatStreamUptime(startedAtMs: Long, nowMs: Long): String? {
    if (nowMs <= startedAtMs) return null
    return DateUtils.formatElapsedTime((nowMs - startedAtMs) / 1000L)
}
