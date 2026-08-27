package com.github.andreyasadchy.xtra.ui.view

import android.content.Context
import android.graphics.Rect
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import android.widget.FrameLayout

class PlayerLayout : FrameLayout {

    constructor(context: Context) : super(context)
    constructor(context: Context, attrs: AttributeSet) : super(context, attrs)
    constructor(context: Context, attrs: AttributeSet, defStyleAttr: Int) : super(context, attrs, defStyleAttr)

    var isPortrait = false

    var interactionLocked = false

    /** The only descendant that may receive touch input while interactionLocked. */
    var interactionUnlockView: View? = null

    private val unlockHitRect = Rect()
    private var unlockGesture = false

    override fun onInterceptTouchEvent(event: MotionEvent): Boolean {
        if (!interactionLocked) {
            unlockGesture = false
            return super.onInterceptTouchEvent(event)
        }

        // Once the controller has auto-hidden, let the player receive a tap so it
        // can reveal the unlock button again. While the button is visible, keep
        // every other control behind the lock.
        if (interactionUnlockView?.isShown != true) {
            unlockGesture = false
            return false
        }

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                unlockGesture = isInsideUnlockView(event)
            }
            MotionEvent.ACTION_POINTER_DOWN -> {
                unlockGesture = false
            }
        }

        return !unlockGesture
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        return if (interactionLocked) {
            true
        } else {
            super.onTouchEvent(event)
        }
    }

    private fun isInsideUnlockView(event: MotionEvent): Boolean {
        val unlockView = interactionUnlockView
            ?.takeIf { it.isShown }
            ?: return false

        unlockView.getDrawingRect(unlockHitRect)
        offsetDescendantRectToMyCoords(unlockView, unlockHitRect)

        return unlockHitRect.contains(
            event.x.toInt(),
            event.y.toInt(),
        )
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        super.onMeasure(widthMeasureSpec, heightMeasureSpec)
        if (isPortrait) {
            val playerHeight = (measuredWidth / (16f / 9f)).toInt()
            super.onMeasure(
                widthMeasureSpec,
                MeasureSpec.makeMeasureSpec(playerHeight, MeasureSpec.EXACTLY)
            )
        }
    }
}
