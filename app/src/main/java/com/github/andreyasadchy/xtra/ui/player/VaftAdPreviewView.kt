package com.github.andreyasadchy.xtra.ui.player

import android.content.Context
import android.graphics.Outline
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import android.view.ViewOutlineProvider
import android.widget.FrameLayout
import android.widget.TextView
import androidx.annotation.OptIn
import androidx.core.content.edit
import androidx.core.net.toUri
import androidx.media3.common.C as Media3C
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionParameters
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import com.github.andreyasadchy.xtra.R
import com.github.andreyasadchy.xtra.repository.preload.StreamMedia3Runtime
import com.github.andreyasadchy.xtra.ui.view.PlayerLayout
import com.github.andreyasadchy.xtra.util.C
import com.github.andreyasadchy.xtra.util.LivePlaybackPolicies
import com.github.andreyasadchy.xtra.util.prefs
import java.util.UUID
import kotlin.math.abs

/**
 * A tiny, draggable, muted view of the original stream (the one carrying the ad) while VAFT is
 * playing a replacement. It owns a separate player that never goes through the controlled VAFT
 * feed, so it shows exactly what the original stream contains.
 */
@OptIn(UnstableApi::class)
class VaftAdPreviewView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : FrameLayout(context, attrs, defStyleAttr) {
    private val playerView: PlayerView
    private val label: TextView
    private val marginPx = (MARGIN_DP * resources.displayMetrics.density).toInt()
    private val touchSlop = TOUCH_SLOP_DP * resources.displayMetrics.density
    private var runtime: StreamMedia3Runtime? = null
    private var player: ExoPlayer? = null
    private var mediaId: String? = null
    private var sourceUri: String? = null
    private var dragging = false
    private var downRawX = 0f
    private var downRawY = 0f
    private var downTranslationX = 0f
    private var downTranslationY = 0f

    private val delayedRelease = Runnable { release() }

    private val playerListener = object : Player.Listener {
        override fun onPlayerError(error: PlaybackException) {
            label.setText(R.string.vaft_ad_preview_unavailable)
        }

        override fun onRenderedFirstFrame() {
            label.setText(R.string.vaft_ad_preview_label)
        }
    }

    init {
        inflate(context, R.layout.view_vaft_ad_preview, this)
        playerView = findViewById(R.id.vaftAdPreviewPlayer)
        label = findViewById(R.id.vaftAdPreviewLabel)
        setBackgroundResource(R.drawable.vaft_ad_preview_background)
        val radius = 6 * resources.displayMetrics.density
        outlineProvider = object : ViewOutlineProvider() {
            override fun getOutline(view: View, outline: Outline) {
                outline.setRoundRect(0, 0, view.width, view.height, radius)
            }
        }
        clipToOutline = true
        visibility = GONE
        contentDescription = context.getString(R.string.vaft_ad_preview_label)
    }

    /** Starts the original stream at [uri]. Calling it again for the same source is a no-op. */
    fun show(runtime: StreamMedia3Runtime, uri: String) {
        removeCallbacks(delayedRelease)
        if (player != null && sourceUri == uri) return
        release()
        this.runtime = runtime
        sourceUri = uri
        val id = "$MEDIA_ID_PREFIX${UUID.randomUUID()}"
        mediaId = id
        label.setText(R.string.vaft_ad_preview_label)
        val preview = runtime.buildPreviewPlayer(
            context,
            TrackSelectionParameters.Builder(context)
                .setTrackTypeDisabled(Media3C.TRACK_TYPE_AUDIO, true)
                .setMaxVideoSize(PREVIEW_MAX_WIDTH, PREVIEW_MAX_HEIGHT)
                .build(),
        )
        preview.addListener(playerListener)
        playerView.player = preview
        player = preview
        preview.setMediaItem(
            MediaItem.Builder()
                .setMediaId(id)
                .setUri(uri.toUri())
                .setMimeType(MimeTypes.APPLICATION_M3U8)
                .setLiveConfiguration(
                    MediaItem.LiveConfiguration.Builder()
                        .setTargetOffsetMs(LivePlaybackPolicies.forLowLatency(false).targetOffsetMs)
                        .build(),
                )
                .build(),
        )
        preview.volume = 0f
        preview.prepare()
        preview.playWhenReady = true
        visibility = VISIBLE
        bringToFront()
        post { applySavedPosition() }
    }

    /** Releases after a short grace period so brief ad-state flips do not restart the stream. */
    fun releaseSoon() {
        if (player == null) return
        removeCallbacks(delayedRelease)
        postDelayed(delayedRelease, RELEASE_GRACE_MS)
    }

    /** Stops the preview and frees its player and decoder. */
    fun release() {
        removeCallbacks(delayedRelease)
        player?.let { preview ->
            preview.removeListener(playerListener)
            playerView.player = null
            runCatching { preview.stop() }
            runCatching { preview.release() }
        }
        player = null
        mediaId?.let { id -> runtime?.releaseTransientMediaItem(id) }
        mediaId = null
        sourceUri = null
        runtime = null
        visibility = GONE
    }

    override fun onDetachedFromWindow() {
        release()
        super.onDetachedFromWindow()
    }

    override fun onSizeChanged(width: Int, height: Int, oldWidth: Int, oldHeight: Int) {
        super.onSizeChanged(width, height, oldWidth, oldHeight)
        invalidateOutline()
        applySavedPosition()
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if ((parent as? PlayerLayout)?.interactionLocked == true) return false
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                dragging = false
                downRawX = event.rawX
                downRawY = event.rawY
                downTranslationX = translationX
                downTranslationY = translationY
                parent?.requestDisallowInterceptTouchEvent(true)
            }
            MotionEvent.ACTION_MOVE -> {
                if (!dragging && (abs(event.rawX - downRawX) > touchSlop || abs(event.rawY - downRawY) > touchSlop)) {
                    dragging = true
                }
                if (dragging) {
                    val (x, y) = clamp(
                        downTranslationX + event.rawX - downRawX,
                        downTranslationY + event.rawY - downRawY,
                    )
                    translationX = x
                    translationY = y
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                parent?.requestDisallowInterceptTouchEvent(false)
                if (dragging) savePosition()
                dragging = false
            }
        }
        return true
    }

    /** Keeps the view inside the parent, inset by the edge margin. */
    private fun clamp(x: Float, y: Float): Pair<Float, Float> {
        val host = parent as? View ?: return x to y
        val maxX = (host.width - width - marginPx).toFloat().coerceAtLeast(marginPx.toFloat())
        val maxY = (host.height - height - marginPx).toFloat().coerceAtLeast(marginPx.toFloat())
        return x.coerceIn(marginPx.toFloat(), maxX) to y.coerceIn(marginPx.toFloat(), maxY)
    }

    private fun savePosition() {
        val host = parent as? View ?: return
        val rangeX = (host.width - width - 2 * marginPx).coerceAtLeast(1)
        val rangeY = (host.height - height - 2 * marginPx).coerceAtLeast(1)
        context.prefs().edit {
            putFloat(C.PLAYER_VAFT_AD_PREVIEW_X, ((translationX - marginPx) / rangeX).coerceIn(0f, 1f))
            putFloat(C.PLAYER_VAFT_AD_PREVIEW_Y, ((translationY - marginPx) / rangeY).coerceIn(0f, 1f))
        }
    }

    /** Positions are stored as fractions of the free range so they survive rotation and resizing. */
    private fun applySavedPosition() {
        val host = parent as? View ?: return
        if (host.width == 0 || width == 0) return
        val prefs = context.prefs()
        val fractionX = prefs.getFloat(C.PLAYER_VAFT_AD_PREVIEW_X, DEFAULT_FRACTION_X)
        val fractionY = prefs.getFloat(C.PLAYER_VAFT_AD_PREVIEW_Y, DEFAULT_FRACTION_Y)
        val rangeX = (host.width - width - 2 * marginPx).coerceAtLeast(0)
        val rangeY = (host.height - height - 2 * marginPx).coerceAtLeast(0)
        translationX = marginPx + fractionX * rangeX
        translationY = marginPx + fractionY * rangeY
    }

    private companion object {
        const val MEDIA_ID_PREFIX = "vaft-ad-preview:"
        const val RELEASE_GRACE_MS = 2_000L
        const val MARGIN_DP = 8
        const val TOUCH_SLOP_DP = 6
        const val PREVIEW_MAX_WIDTH = 426
        const val PREVIEW_MAX_HEIGHT = 240
        const val DEFAULT_FRACTION_X = 1f
        const val DEFAULT_FRACTION_Y = 0f
    }
}
