package com.github.andreyasadchy.xtra.ui.common

import android.text.format.DateUtils
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.RecyclerView
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.time.Instant

/**
 * Implemented by stream ViewHolders that display a live uptime badge.
 *
 * A RecyclerView-level ticker calls this only for currently attached holders.
 */
internal interface StreamUptimeViewHolder {
    fun updateUptime(nowMs: Long)
}

/** One ticker for one attached stream RecyclerView. */
internal class VisibleStreamUptimeTicker(
    private val fragment: Fragment,
) {
    private var job: Job? = null

    fun attach(recyclerView: RecyclerView) {
        detach()

        job = fragment.viewLifecycleOwner.lifecycleScope.launch {
            fragment.viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                while (isActive) {
                    val nowMs = System.currentTimeMillis()
                    for (index in 0 until recyclerView.childCount) {
                        val child = recyclerView.getChildAt(index)
                        val holder = recyclerView.getChildViewHolder(child)
                        (holder as? StreamUptimeViewHolder)?.updateUptime(nowMs)
                    }

                    // Keep updates aligned to wall-clock second boundaries.
                    val afterUpdateMs = System.currentTimeMillis()
                    val delayMs = 1000L - (afterUpdateMs % 1000L)
                    delay(delayMs.coerceIn(1L, 1000L))
                }
            }
        }
    }

    fun detach() {
        job?.cancel()
        job = null
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
