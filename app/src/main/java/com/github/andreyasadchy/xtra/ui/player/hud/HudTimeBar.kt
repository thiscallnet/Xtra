package com.github.andreyasadchy.xtra.ui.player.hud

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.AttributeSet
import androidx.media3.ui.DefaultTimeBar
import androidx.media3.ui.TimeBar
import kotlin.math.max
import kotlin.math.min

/**
 * Media3's time bar keeps the painted track inside the scrubber's radius.
 * That is useful for a conventional inset controller, but it leaves a
 * visible gap at both edges of the compact player. Keep DefaultTimeBar's
 * input, keyboard, accessibility, and ad-marker behavior while painting the
 * player chrome as one edge-to-edge boundary line.
 */
class HudTimeBar @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : DefaultTimeBar(context, attrs, defStyleAttr) {
    private val density = resources.displayMetrics.density
    private val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private var edgePlayedColor = 0xFFB388FF.toInt()
    private var edgeBufferedColor = 0x66FFFFFF
    private var edgeUnplayedColor = 0x66FFFFFF
    private var edgeDurationMs = -1L
    private var edgePositionMs = 0L
    private var edgeBufferedPositionMs = 0L
    private var initialized = false
    private var edgeMarkerListener: (() -> Unit)? = null

    private val scrubStateListener = object : TimeBar.OnScrubListener {
        override fun onScrubStart(timeBar: TimeBar, position: Long) {
            edgePositionMs = position
            notifyEdgeMarkerChanged()
        }

        override fun onScrubMove(timeBar: TimeBar, position: Long) {
            edgePositionMs = position
            notifyEdgeMarkerChanged()
        }

        override fun onScrubStop(timeBar: TimeBar, position: Long, canceled: Boolean) {
            edgePositionMs = position
            notifyEdgeMarkerChanged()
        }
    }

    init {
        // The superclass remains the interaction/accessibility implementation.
        // Its inset visuals are transparent so they cannot be painted on top
        // of the edge-to-edge treatment below.
        super.setPlayedColor(Color.TRANSPARENT)
        super.setScrubberColor(Color.TRANSPARENT)
        super.setBufferedColor(Color.TRANSPARENT)
        super.setUnplayedColor(Color.TRANSPARENT)
        addListener(scrubStateListener)
        initialized = true
    }

    override fun setPlayedColor(color: Int) {
        edgePlayedColor = color
        super.setPlayedColor(Color.TRANSPARENT)
        notifyEdgeMarkerChanged()
    }

    override fun setScrubberColor(color: Int) {
        edgePlayedColor = color
        super.setScrubberColor(Color.TRANSPARENT)
        notifyEdgeMarkerChanged()
    }

    override fun setBufferedColor(color: Int) {
        edgeBufferedColor = color
        super.setBufferedColor(Color.TRANSPARENT)
        notifyEdgeMarkerChanged()
    }

    override fun setUnplayedColor(color: Int) {
        edgeUnplayedColor = color
        super.setUnplayedColor(Color.TRANSPARENT)
        notifyEdgeMarkerChanged()
    }

    override fun setDuration(duration: Long) {
        edgeDurationMs = duration
        super.setDuration(duration)
        notifyEdgeMarkerChanged()
    }

    override fun setPosition(position: Long) {
        edgePositionMs = position
        super.setPosition(position)
        notifyEdgeMarkerChanged()
    }

    override fun setBufferedPosition(bufferedPosition: Long) {
        edgeBufferedPositionMs = bufferedPosition
        super.setBufferedPosition(bufferedPosition)
        notifyEdgeMarkerChanged()
    }

    /** Installs the visual marker that is allowed to straddle the video edge. */
    fun setEdgeMarkerListener(listener: (() -> Unit)?) {
        edgeMarkerListener = listener
        if (initialized) listener?.invoke()
    }

    fun edgeMarkerFraction(): Float? =
        edgeDurationMs.takeIf { it > 0L }?.let(::normalizedFraction)

    fun edgeMarkerRadiusPx(): Float = markerRadiusPx(max(height, measuredHeight).toFloat())

    /** The center of the painted edge-to-edge track, in this view's coordinates. */
    fun edgeMarkerLineCenterY(): Float {
        val heightPx = max(height, measuredHeight).toFloat()
        val lineHeight = min(heightPx, max(1f, 3f * density))
        return heightPx - lineHeight / 2f
    }

    fun edgeMarkerCenterX(fraction: Float): Float = markerCenterX(
        fraction = fraction,
        widthPx = max(width, measuredWidth).toFloat(),
        radius = edgeMarkerRadiusPx(),
    )

    fun edgeMarkerColor(): Int = edgePlayedColor

    /** True when x falls inside the handle's expanded 48dp touch target. */
    fun isWithinHandleTouchTarget(x: Float): Boolean {
        val duration = edgeDurationMs
        if (duration <= 0L || width <= 0) return false
        val fraction = normalizedFraction(duration)
        val radius = markerRadiusPx(height.toFloat())
        val center = markerCenterX(fraction, width.toFloat(), radius)
        val halfTarget = max(24f * density, radius)
        return x in center - halfTarget..center + halfTarget
    }

    override fun onDraw(canvas: Canvas) {
        val widthPx = width.toFloat()
        val heightPx = height.toFloat()
        if (widthPx <= 0f || heightPx <= 0f) {
            super.onDraw(canvas)
            return
        }

        val lineHeight = min(heightPx, max(1f, 3f * density))
        val lineTop = heightPx - lineHeight
        trackPaint.isAntiAlias = false
        trackPaint.color = edgeUnplayedColor
        canvas.drawRect(0f, lineTop, widthPx, heightPx, trackPaint)

        val duration = edgeDurationMs
        if (duration > 0L) {
            drawFraction(canvas, lineTop, heightPx, widthPx, edgeBufferedPositionMs.toFloat() / duration, edgeBufferedColor)
            drawFraction(canvas, lineTop, heightPx, widthPx, edgePositionMs.toFloat() / duration, edgePlayedColor)

        }

        // Preserve ad markers and any other semantics implemented by Media3.
        // Main bar/scrubber colors are transparent, so this cannot reintroduce
        // the inset line.
        super.onDraw(canvas)
    }

    private fun drawFraction(
        canvas: Canvas,
        lineTop: Float,
        lineBottom: Float,
        widthPx: Float,
        rawFraction: Float,
        color: Int,
    ) {
        val fraction = rawFraction.takeIf { it.isFinite() }?.coerceIn(0f, 1f) ?: return
        if (fraction <= 0f) return
        trackPaint.color = color
        canvas.drawRect(0f, lineTop, widthPx * fraction, lineBottom, trackPaint)
    }

    private fun normalizedFraction(duration: Long): Float =
        (edgePositionMs.toFloat() / duration).takeIf { it.isFinite() }?.coerceIn(0f, 1f) ?: 0f

    private fun markerRadiusPx(heightPx: Float): Float = min(7f * density, heightPx / 2f)

    private fun markerCenterX(fraction: Float, widthPx: Float, radius: Float): Float {
        val travel = (widthPx - 2f * radius).coerceAtLeast(0f)
        return if (travel > 0f) radius + travel * fraction else widthPx / 2f
    }

    private fun notifyEdgeMarkerChanged() {
        edgeMarkerListener?.invoke()
        if (initialized) invalidate()
    }
}
