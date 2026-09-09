package com.github.andreyasadchy.xtra.ui.common

import android.view.View
import android.widget.TextView
import com.github.andreyasadchy.xtra.R
import com.github.andreyasadchy.xtra.model.ui.Stream

class StreamDropsBadgeBinder(
    private val badge: TextView,
    private val onClick: (Stream) -> Unit,
) {
    private var boundStream: Stream? = null

    init {
        badge.setOnClickListener { boundStream?.let(onClick) }
    }

    fun bind(stream: Stream?) {
        boundStream = stream

        if (stream == null) {
            clear()
            return
        }

        renderHint(stream, StreamDropsPreviewPolicy.hasDropsTag(stream))
    }

    fun clear() {
        boundStream = null
        badge.visibility = View.GONE
        badge.text = null
        badge.contentDescription = null
    }

    private fun renderHint(stream: Stream, visible: Boolean) {
        if (!visible) {
            badge.visibility = View.GONE
            badge.text = null
            badge.contentDescription = null
            return
        }
        badge.visibility = View.VISIBLE
        badge.text = badge.context.getString(R.string.stream_drops_badge)
        badge.contentDescription = badge.context.getString(
            R.string.stream_drops_badge_content_description,
            stream.channelName ?: badge.context.getString(R.string.channels),
        )
    }
}

internal object StreamDropsPreviewPolicy {
    fun hasDropsTag(stream: Stream): Boolean = stream.tags.orEmpty().any {
        it.equals(DROPS_ENABLED_TAG, ignoreCase = true)
    }

    private const val DROPS_ENABLED_TAG = "DropsEnabled"
}
