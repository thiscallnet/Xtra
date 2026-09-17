package com.github.andreyasadchy.xtra.ui.player.hud

import android.content.Context
import android.graphics.Rect
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import kotlin.math.roundToInt

/**
 * Keeps the visible edge marker interactive when its lower half overlaps the
 * content below the player. The overlay is transparent and only consumes a
 * gesture that starts inside the marker; every other touch falls through to
 * the normal player/chat hierarchy.
 */
class HudEdgeMarkerTouchOverlay @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {
    private var hud: PlayerHudLayout? = null
    private var bar: HudTimeBar? = null
    private var markerGesture = false

    init {
        isClickable = false
        isFocusable = false
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
    }

    fun bind(hud: PlayerHudLayout, bar: HudTimeBar) {
        this.hud = hud
        this.bar = bar
        invalidate()
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        val marker = markerBoundsOnScreen()
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                if (marker == null || !marker.contains(eventGlobalX(event), eventGlobalY(event))) {
                    markerGesture = false
                    return false
                }
                markerGesture = dispatchToBar(event)
                return markerGesture
            }

            MotionEvent.ACTION_MOVE,
            MotionEvent.ACTION_UP,
            MotionEvent.ACTION_CANCEL,
            -> if (markerGesture) {
                val handled = dispatchToBar(event)
                if (event.actionMasked == MotionEvent.ACTION_UP || event.actionMasked == MotionEvent.ACTION_CANCEL) {
                    markerGesture = false
                }
                return handled || event.actionMasked != MotionEvent.ACTION_DOWN
            }
        }
        return markerGesture
    }

    private fun markerBoundsOnScreen(): Rect? {
        val hud = hud ?: return null
        val bar = bar ?: return null
        if (!hud.isShown || hud.alpha <= 0.01f || hud.interactionLocked ||
            !bar.isShown || bar.width <= 0 || bar.height <= 0
        ) return null
        val fraction = bar.edgeMarkerFraction() ?: return null
        val barRect = Rect()
        if (!bar.getGlobalVisibleRect(barRect)) return null
        val radius = bar.edgeMarkerRadiusPx()
        if (radius <= 0f) return null
        val centerX = barRect.left + bar.edgeMarkerCenterX(fraction)
        val centerY = barRect.top + bar.edgeMarkerLineCenterY()
        val left = (centerX - radius).roundToInt()
        val top = (centerY - radius).roundToInt()
        val right = (centerX + radius).roundToInt()
        val bottom = (centerY + radius).roundToInt()
        return Rect(left, top, right, bottom)
    }

    private fun dispatchToBar(event: MotionEvent): Boolean {
        val bar = bar ?: return false
        val overlayLocation = IntArray(2)
        val barLocation = IntArray(2)
        getLocationOnScreen(overlayLocation)
        bar.getLocationOnScreen(barLocation)

        val forwarded = MotionEvent.obtain(event)
        try {
            forwarded.offsetLocation(
                (overlayLocation[0] - barLocation[0]).toFloat(),
                (overlayLocation[1] - barLocation[1]).toFloat(),
            )
            // The marker can extend below the bar's 48dp host. Keep the
            // forwarded gesture inside the host while preserving its x path.
            val clampedY = forwarded.y.coerceIn(0f, (bar.height - 1).coerceAtLeast(0).toFloat())
            forwarded.offsetLocation(0f, clampedY - forwarded.y)
            return bar.dispatchTouchEvent(forwarded)
        } finally {
            forwarded.recycle()
        }
    }

    private fun eventGlobalX(event: MotionEvent): Int =
        (event.x + locationOnScreenX()).roundToInt()

    private fun eventGlobalY(event: MotionEvent): Int =
        (event.y + locationOnScreenY()).roundToInt()

    private fun locationOnScreenX(): Int = IntArray(2).also(::getLocationOnScreen)[0]

    private fun locationOnScreenY(): Int = IntArray(2).also(::getLocationOnScreen)[1]
}
