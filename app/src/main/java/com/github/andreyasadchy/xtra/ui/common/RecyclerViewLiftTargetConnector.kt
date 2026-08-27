package com.github.andreyasadchy.xtra.ui.common

import android.view.View
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.appbar.AppBarLayout

/** Keeps exactly one RecyclerView connected to an AppBarLayout's lift state. */
class RecyclerViewLiftTargetConnector(
    private val appBar: AppBarLayout,
) {
    private var target: RecyclerView? = null
    private var scrollListener: RecyclerView.OnScrollListener? = null
    private var layoutListener: View.OnLayoutChangeListener? = null
    private var lastLifted: Boolean? = null

    fun connect(recyclerView: RecyclerView) {
        if (target === recyclerView) {
            update()
            return
        }
        disconnect()
        val onScroll = object : RecyclerView.OnScrollListener() {
            override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) = update()
        }
        val onLayout = View.OnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> update() }
        recyclerView.addOnScrollListener(onScroll)
        recyclerView.addOnLayoutChangeListener(onLayout)
        appBar.setLiftOnScrollTargetView(recyclerView)
        target = recyclerView
        scrollListener = onScroll
        layoutListener = onLayout
        update()
    }

    fun disconnect() {
        target?.let { recyclerView ->
            scrollListener?.let(recyclerView::removeOnScrollListener)
            layoutListener?.let(recyclerView::removeOnLayoutChangeListener)
        }
        target = null
        scrollListener = null
        layoutListener = null
        lastLifted = null
        appBar.setLiftOnScrollTargetView(null)
        appBar.isLifted = false
    }

    private fun update() {
        val lifted = target?.canScrollVertically(-1) == true
        if (lastLifted == lifted) return
        lastLifted = lifted
        appBar.isLifted = lifted
    }
}
