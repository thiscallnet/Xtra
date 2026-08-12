package com.github.andreyasadchy.xtra.ui.multiview.ui

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.view.GestureDetector
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.widget.FrameLayout
import androidx.core.view.ViewCompat
import androidx.core.view.isVisible
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import com.github.andreyasadchy.xtra.R
import com.github.andreyasadchy.xtra.databinding.MultiviewSlotBinding
import com.github.andreyasadchy.xtra.model.ui.Stream
import com.github.andreyasadchy.xtra.ui.multiview.playback.MultiviewPlaybackSnapshot
import com.github.andreyasadchy.xtra.ui.multiview.playback.MultiviewSlotStatus
import com.google.android.material.color.MaterialColors

/** A stable tile shell. The coordinator owns the player; this view only owns presentation and gestures. */
class MultiviewSlotView(context: Context) : FrameLayout(context) {
    private val binding = MultiviewSlotBinding.inflate(LayoutInflater.from(context), this, true)
    private val gestureDetector = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
        override fun onDown(event: MotionEvent): Boolean = true

        override fun onSingleTapConfirmed(event: MotionEvent): Boolean {
            onTap?.invoke()
            return true
        }

        override fun onDoubleTap(event: MotionEvent): Boolean {
            onDoubleTap?.invoke()
            return true
        }

        override fun onLongPress(event: MotionEvent) {
            onLongPress?.invoke()
        }
    })

    val playerView: PlayerView
        get() = binding.playerView

    val actionsAnchor: View
        get() = binding.overflowButton

    var identity: String = ""
        private set
    var stream: Stream? = null
        private set

    var onTap: (() -> Unit)? = null
    var onDoubleTap: (() -> Unit)? = null
    var onLongPress: (() -> Unit)? = null
    var onOverflow: (() -> Unit)? = null
    var onRetry: (() -> Unit)? = null

    init {
        isClickable = true
        isFocusable = true
        setOnClickListener { onTap?.invoke() }
        binding.playerView.setOnTouchListener { _, event ->
            gestureDetector.onTouchEvent(event)
            true
        }
        binding.overflowButton.setOnClickListener { onOverflow?.invoke() }
        binding.statusMessage.setOnClickListener { onRetry?.invoke() }
        setControlsVisible(false)
    }

    fun bind(
        identity: String,
        stream: Stream,
        snapshot: MultiviewPlaybackSnapshot?,
        audioActive: Boolean,
        focused: Boolean,
        fillVideo: Boolean,
    ) {
        this.identity = identity
        this.stream = stream
        val name = displayName(stream)
        binding.channelName.text = name
        binding.qualityBadge.text = snapshot?.qualityLabel.orEmpty()
        binding.qualityBadge.isVisible = !snapshot?.qualityLabel.isNullOrBlank()
        binding.playerView.resizeMode = if (fillVideo) {
            AspectRatioFrameLayout.RESIZE_MODE_ZOOM
        } else {
            AspectRatioFrameLayout.RESIZE_MODE_FIT
        }
        binding.audioIcon.isVisible = audioActive
        binding.audioIcon.setImageResource(
            if (audioActive) R.drawable.baseline_volume_up_black_24 else R.drawable.baseline_volume_off_black_24,
        )
        binding.audioIcon.contentDescription = context.getString(
            if (audioActive) R.string.multiview_audio_active_short else R.string.multiview_audio_muted_short,
        )
        isSelected = audioActive
        contentDescription = when {
            focused -> context.getString(R.string.multiview_tile_focused_description, name)
            audioActive -> context.getString(R.string.multiview_tile_audio_active_description, name)
            else -> context.getString(R.string.multiview_tile_description, name)
        }
        ViewCompat.setStateDescription(
            this,
            context.getString(
                if (audioActive) R.string.multiview_audio_active_short else R.string.multiview_audio_muted_short,
            ),
        )
        updateBorder(audioActive)
        updateStatus(snapshot)
        if (focused) {
            binding.channelName.setTypeface(binding.channelName.typeface, android.graphics.Typeface.BOLD)
        } else {
            binding.channelName.setTypeface(binding.channelName.typeface, android.graphics.Typeface.NORMAL)
        }
    }

    fun setControlsVisible(visible: Boolean) {
        binding.overflowButton.isVisible = visible
    }

    private fun updateBorder(active: Boolean) {
        val border = GradientDrawable().apply {
            setColor(Color.TRANSPARENT)
            if (active) {
                setStroke(dp(2), MaterialColors.getColor(this@MultiviewSlotView, androidx.appcompat.R.attr.colorPrimary))
            }
        }
        foreground = border
    }

    private fun updateStatus(snapshot: MultiviewPlaybackSnapshot?) {
        val status = snapshot?.status ?: MultiviewSlotStatus.LOADING
        val message = when (status) {
            MultiviewSlotStatus.LOADING -> context.getString(R.string.multiview_loading)
            MultiviewSlotStatus.BUFFERING -> context.getString(R.string.multiview_buffering)
            MultiviewSlotStatus.RECONNECTING -> context.getString(R.string.multiview_reconnecting)
            MultiviewSlotStatus.OFFLINE -> context.getString(R.string.multiview_offline)
            MultiviewSlotStatus.PLAYBACK_UNAVAILABLE -> context.getString(R.string.multiview_playback_unavailable)
            MultiviewSlotStatus.LIVE -> null
        }
        binding.statusMessage.text = message
        binding.statusMessage.isVisible = message != null
        binding.statusMessage.isClickable = snapshot?.retryAvailable == true
        binding.statusMessage.isFocusable = snapshot?.retryAvailable == true
        binding.statusMessage.contentDescription = message?.let { text ->
            if (snapshot?.retryAvailable == true) {
                context.getString(R.string.multiview_retry_description, text)
            } else {
                text
            }
        }
    }

    private fun displayName(stream: Stream): String {
        return stream.channelName?.takeIf { it.isNotBlank() }
            ?: stream.channelLogin?.takeIf { it.isNotBlank() }
            ?: stream.id.orEmpty()
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
}
