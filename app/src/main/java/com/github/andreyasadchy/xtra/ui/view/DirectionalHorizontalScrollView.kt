package com.github.andreyasadchy.xtra.ui.view

import android.content.Context
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.ViewConfiguration
import android.widget.HorizontalScrollView
import kotlin.math.abs

/** Lets a horizontal child scroll without stealing vertical drags or shelf swipes it cannot consume. */
class DirectionalHorizontalScrollView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = android.R.attr.horizontalScrollViewStyle,
) : HorizontalScrollView(context, attrs, defStyleAttr) {
    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop
    private var downX = 0f
    private var downY = 0f

    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = event.x
                downY = event.y
                parent?.requestDisallowInterceptTouchEvent(true)
            }

            MotionEvent.ACTION_MOVE -> {
                val dx = downX - event.x
                val dy = downY - event.y
                if (abs(dx) > touchSlop || abs(dy) > touchSlop) {
                    val horizontalGesture = abs(dx) > abs(dy)
                    val canScrollWithGesture = horizontalGesture &&
                        canScrollHorizontally(if (dx > 0f) 1 else -1)
                    parent?.requestDisallowInterceptTouchEvent(canScrollWithGesture)
                }
            }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                parent?.requestDisallowInterceptTouchEvent(false)
            }
        }
        return super.dispatchTouchEvent(event)
    }
}
