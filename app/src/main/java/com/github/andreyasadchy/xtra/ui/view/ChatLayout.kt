package com.github.andreyasadchy.xtra.ui.view

import android.content.Context
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import android.widget.RelativeLayout
import com.github.andreyasadchy.xtra.ui.player.hud.HudTimeBar
import kotlin.math.roundToInt

class ChatLayout : RelativeLayout {

    constructor(context: Context) : super(context)
    constructor(context: Context, attrs: AttributeSet) : super(context, attrs)
    constructor(context: Context, attrs: AttributeSet, defStyleAttr: Int) : super(context, attrs, defStyleAttr)

    var isPortrait = false

    /**
     * The player timeline is painted on the boundary immediately above this
     * view in portrait mode (or immediately to its left in landscape mode).
     * Keep the small scrubber overdraw above chat visually and forward a tap on
     * that overdraw to the same Media3 time bar instead of making the first
     * chat pixels a dead, clipped part of the timeline.
     */
    var boundaryTimeBar: View? = null

    private var boundaryGestureActive = false
    private val chatLocation = IntArray(2)
    private val timeBarLocation = IntArray(2)

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        super.onMeasure(widthMeasureSpec, heightMeasureSpec)
        if (isPortrait) {
            val playerHeight = (measuredWidth / (16f / 9f)).toInt()
            val availableHeight = (measuredHeight - playerHeight).coerceAtLeast(0)
            super.onMeasure(
                widthMeasureSpec,
                MeasureSpec.makeMeasureSpec(availableHeight, MeasureSpec.EXACTLY)
            )
        }
    }

    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        val timeBar = boundaryTimeBar?.takeIf { it.isShown && it.isAttachedToWindow }
        if (timeBar != null) {
            if (event.actionMasked == MotionEvent.ACTION_DOWN) {
                boundaryGestureActive = isOnTimelineBoundary(event, timeBar)
            }
            if (boundaryGestureActive) {
                val handled = dispatchToTimeBar(timeBar, event)
                if (event.actionMasked == MotionEvent.ACTION_UP ||
                    event.actionMasked == MotionEvent.ACTION_CANCEL
                ) {
                    boundaryGestureActive = false
                }
                return handled || true
            }
        } else if (event.actionMasked == MotionEvent.ACTION_CANCEL) {
            boundaryGestureActive = false
        }
        return super.dispatchTouchEvent(event)
    }

    private fun isOnTimelineBoundary(event: MotionEvent, timeBar: View): Boolean {
        if (!isPortrait) return false
        val coordinates = timeBarCoordinates(event, timeBar)
        val hudTimeBar = timeBar as? HudTimeBar ?: return false
        val density = resources.displayMetrics.density
        val boundaryBand = (12f * density).roundToInt().toFloat()
        return hudTimeBar.isWithinHandleTouchTarget(coordinates.first) &&
            coordinates.second in timeBar.height.toFloat()..timeBar.height + boundaryBand
    }

    private fun dispatchToTimeBar(timeBar: View, event: MotionEvent): Boolean {
        val coordinates = timeBarCoordinates(event, timeBar)
        val x = coordinates.first.coerceIn(0f, timeBar.width.toFloat())
        val y = coordinates.second.coerceIn(0f, (timeBar.height - 1).coerceAtLeast(0).toFloat())
        val copy = MotionEvent.obtain(event)
        copy.offsetLocation(x - event.x, y - event.y)
        val handled = timeBar.dispatchTouchEvent(copy)
        copy.recycle()
        return handled
    }

    private fun timeBarCoordinates(event: MotionEvent, timeBar: View): Pair<Float, Float> {
        getLocationOnScreen(chatLocation)
        timeBar.getLocationOnScreen(timeBarLocation)
        return (
            event.x + chatLocation[0] - timeBarLocation[0]
        ) to (
            event.y + chatLocation[1] - timeBarLocation[1]
        )
    }
}
