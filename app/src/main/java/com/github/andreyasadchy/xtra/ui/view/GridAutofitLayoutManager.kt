@file:Suppress("MemberVisibilityCanBePrivate", "unused")

package com.github.andreyasadchy.xtra.ui.view

import android.content.Context
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import kotlin.math.max

class GridAutofitLayoutManager : GridLayoutManager {

    private var columnWidth = 0
    private var lastTotalSpace = Int.MIN_VALUE

    constructor(context: Context, columnWidth: Int) : super(context, 1) {
        setColumnWidth(columnWidth)
    }

    constructor(context: Context, columnWidth: Int, orientation: Int, reverseLayout: Boolean) : super(context, 1, orientation, reverseLayout) {
        setColumnWidth(columnWidth)
    }

    override fun onLayoutChildren(recycler: RecyclerView.Recycler?, state: RecyclerView.State) {
        if (width > 0 && height > 0) {
            val totalSpace = if (orientation == VERTICAL) {
                width - paddingRight - paddingLeft
            } else {
                height - paddingTop - paddingBottom
            }

            if (totalSpace != lastTotalSpace) {
                val newSpanCount = max(1, totalSpace / columnWidth)
                if (newSpanCount != spanCount) {
                    setSpanCount(newSpanCount)
                }
                lastTotalSpace = totalSpace
            }
        }
        super.onLayoutChildren(recycler, state)
    }

    fun setColumnWidth(width: Int) {
        if (width <= 0) {
            throw IllegalArgumentException("Width should be more than 0. Provided $width")
        }
        if (columnWidth != width) {
            columnWidth = width
            lastTotalSpace = Int.MIN_VALUE
        }
    }

    fun updateWidth() {
        lastTotalSpace = Int.MIN_VALUE
        requestLayout()
    }
}
