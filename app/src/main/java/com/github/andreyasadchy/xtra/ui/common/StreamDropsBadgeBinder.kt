package com.github.andreyasadchy.xtra.ui.common

import android.view.View
import android.widget.TextView
import com.github.andreyasadchy.xtra.R
import com.github.andreyasadchy.xtra.model.ui.Stream

class StreamDropsBadgeBinder(
    private val badge: TextView,
    private val touchTarget: View? = null,
    private val onClick: (Stream) -> Unit,
) {
    private var boundStream: Stream? = null

    init {
        (touchTarget ?: badge).setOnClickListener { boundStream?.let(onClick) }
        if (touchTarget != null) {
            touchTarget.isFocusable = true
            touchTarget.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES
            badge.isClickable = false
            badge.isFocusable = false
            badge.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }
    }

    fun bind(stream: Stream?) {
        boundStream = stream

        if (stream == null) {
            clear()
            return
        }

        renderHint(stream, StreamDropsPreviewPolicy.hasVerifiedDrops(stream))
    }

    fun clear() {
        boundStream = null
        badge.visibility = View.GONE
        badge.text = null
        badge.contentDescription = null
        syncTouchTarget()
    }

    private fun renderHint(stream: Stream, visible: Boolean) {
        if (!visible) {
            badge.visibility = View.GONE
            badge.text = null
            badge.contentDescription = null
            syncTouchTarget()
            return
        }
        badge.visibility = View.VISIBLE
        badge.text = badge.context.getString(R.string.stream_drops_badge)
        badge.contentDescription = badge.context.getString(
            R.string.stream_drops_badge_content_description,
            stream.channelName ?: badge.context.getString(R.string.channels),
        )
        syncTouchTarget()
    }

    private fun syncTouchTarget() {
        touchTarget?.apply {
            visibility = badge.visibility
            contentDescription = badge.contentDescription
        }
    }
}

internal object StreamDropsPreviewPolicy {
    fun hasVerifiedDrops(stream: Stream): Boolean = stream.dropsAvailable == true
}
