package com.github.andreyasadchy.xtra.ui.view

import android.content.Context
import android.util.AttributeSet
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout

/**
 * Picture-in-picture chat only has room for the message list. These containers lay out just their
 * first child while [firstChildOnly] is set, so composer, pickers and overlays inside them need no
 * per-view visibility handling and come back untouched when the flag is cleared.
 */
private fun ViewGroup.layoutFirstChildOnly(left: Int, top: Int, right: Int, bottom: Int) {
    for (i in 0 until childCount) {
        val child = getChildAt(i)
        if (i == 0) child.layout(0, 0, right - left, bottom - top) else child.layout(0, 0, 0, 0)
    }
}

private fun ViewGroup.measureFirstChildOnly(widthMeasureSpec: Int, heightMeasureSpec: Int): Pair<Int, Int> {
    val width = View.resolveSize(View.MeasureSpec.getSize(widthMeasureSpec), widthMeasureSpec)
    val height = View.resolveSize(View.MeasureSpec.getSize(heightMeasureSpec), heightMeasureSpec)
    getChildAt(0)?.measure(
        View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
        View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY),
    )
    return width to height
}

class FirstChildOnlyLinearLayout : LinearLayout {
    constructor(context: Context) : super(context)
    constructor(context: Context, attrs: AttributeSet) : super(context, attrs)
    constructor(context: Context, attrs: AttributeSet, defStyleAttr: Int) : super(context, attrs, defStyleAttr)

    var firstChildOnly = false
        set(value) {
            if (field == value) return
            field = value
            requestLayout()
        }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        if (!firstChildOnly) return super.onMeasure(widthMeasureSpec, heightMeasureSpec)
        val (width, height) = measureFirstChildOnly(widthMeasureSpec, heightMeasureSpec)
        setMeasuredDimension(width, height)
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        if (!firstChildOnly) return super.onLayout(changed, l, t, r, b)
        layoutFirstChildOnly(l, t, r, b)
    }
}

class FirstChildOnlyFrameLayout : FrameLayout {
    constructor(context: Context) : super(context)
    constructor(context: Context, attrs: AttributeSet) : super(context, attrs)
    constructor(context: Context, attrs: AttributeSet, defStyleAttr: Int) : super(context, attrs, defStyleAttr)

    var firstChildOnly = false
        set(value) {
            if (field == value) return
            field = value
            requestLayout()
        }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        if (!firstChildOnly) return super.onMeasure(widthMeasureSpec, heightMeasureSpec)
        val (width, height) = measureFirstChildOnly(widthMeasureSpec, heightMeasureSpec)
        setMeasuredDimension(width, height)
    }

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        if (!firstChildOnly) return super.onLayout(changed, left, top, right, bottom)
        layoutFirstChildOnly(left, top, right, bottom)
    }
}
