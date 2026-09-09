package com.github.andreyasadchy.xtra.ui.common

import android.view.View
import android.widget.TextView
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.github.andreyasadchy.xtra.R
import com.github.andreyasadchy.xtra.XtraApp
import com.github.andreyasadchy.xtra.model.ui.Stream
import com.github.andreyasadchy.xtra.model.ui.TwitchChannelDropCampaign
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

class StreamDropsBadgeBinder(
    private val fragment: Fragment,
    private val badge: TextView,
    private val onClick: (Stream) -> Unit,
) {
    private var requestJob: Job? = null
    private var boundIdentity: String? = null
    private var boundStream: Stream? = null

    init {
        badge.setOnClickListener { boundStream?.let(onClick) }
    }

    fun bind(stream: Stream?) {
        requestJob?.cancel()
        requestJob = null
        boundStream = stream
        boundIdentity = stream?.streamIdentity()

        if (stream == null) {
            clear()
            return
        }

        val hasTagHint = stream.tags.orEmpty().any { it.equals(DROPS_ENABLED_TAG, ignoreCase = true) }
        renderHint(stream, hasTagHint)

        val channelId = stream.channelId?.takeIf { it.isNotBlank() } ?: return
        requestJob = fragment.viewLifecycleOwner.lifecycleScope.launch {
            val campaigns = (fragment.requireContext().applicationContext as XtraApp)
                .xtraModule.dropsRepository
                .refreshChannelDropCatalog(channelId)
            if (boundIdentity != stream.streamIdentity()) return@launch

            when {
                campaigns == null -> renderHint(stream, hasTagHint)
                campaigns.isEmpty() -> clear()
                else -> renderCatalog(stream, campaigns)
            }
        }
    }

    fun clear() {
        requestJob?.cancel()
        requestJob = null
        boundIdentity = null
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

    private fun renderCatalog(
        stream: Stream,
        campaigns: List<TwitchChannelDropCampaign>,
    ) {
        val drops = campaigns.flatMap { it.drops }
        badge.visibility = View.VISIBLE
        badge.text = if (drops.isEmpty()) {
            badge.context.getString(R.string.stream_drops_badge)
        } else {
            badge.resources.getQuantityString(R.plurals.stream_drops_count, drops.size, drops.size)
        }
        badge.contentDescription = badge.context.getString(
            R.string.stream_drops_badge_content_description,
            stream.channelName ?: badge.context.getString(R.string.channels),
        )
    }

    private companion object {
        const val DROPS_ENABLED_TAG = "DropsEnabled"
    }
}
