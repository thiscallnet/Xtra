package com.github.andreyasadchy.xtra.ui.saved.clips

import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import androidx.recyclerview.widget.ConcatAdapter
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.core.view.children
import kotlin.math.roundToInt

/** Scroll the existing header with the clips when fixed chrome would consume the list viewport. */
internal class ClipsViewportLayout(
    savedState: Bundle?,
    private val root: LinearLayout,
    private val content: View,
    private val recycler: RecyclerView,
    private val clipsAdapter: RecyclerView.Adapter<*>,
    private val headerViews: List<View>,
    private val onHeaderVisible: (Boolean) -> Unit,
) : AutoCloseable {
    private val originalContentParams = LinearLayout.LayoutParams(content.layoutParams as LinearLayout.LayoutParams)
    private val header = LinearLayout(root.context).apply {
        orientation = LinearLayout.VERTICAL
        layoutParams = RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
    }
    private val headerAdapter = object : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
        override fun getItemCount() = 1
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) = object : RecyclerView.ViewHolder(header) {}
        override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) = Unit
        override fun onViewAttachedToWindow(holder: RecyclerView.ViewHolder) { if (!closed) onHeaderVisible(true) }
        override fun onViewDetachedFromWindow(holder: RecyclerView.ViewHolder) { if (!closed) onHeaderVisible(false) }
    }
    private var hasItems = savedState?.getBoolean(STATE_SCROLLING) == true
    private var scrolling = false
    private var closed = false
    private var posted = false
    private val layoutListener = View.OnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> refresh() }
    private val dataObserver = object : RecyclerView.AdapterDataObserver() {
        override fun onItemRangeInserted(positionStart: Int, itemCount: Int) = refresh()
        override fun onItemRangeRemoved(positionStart: Int, itemCount: Int) = refresh()
    }

    init {
        root.addOnLayoutChangeListener(layoutListener)
        headerViews.forEach { it.addOnLayoutChangeListener(layoutListener) }
        clipsAdapter.registerAdapterDataObserver(dataObserver)
        if (hasItems) setScrolling(true)
    }

    fun setHasItems(value: Boolean) {
        hasItems = value
        refresh()
    }

    private fun refresh() {
        if (closed || posted) return
        posted = true
        root.post {
            posted = false
            if (closed || root.height <= 0) return@post
            val headerHeight = headerViews.sumOf {
                if (it.visibility == View.GONE) 0 else {
                    val params = it.layoutParams as ViewGroup.MarginLayoutParams
                    it.measuredHeight + params.topMargin + params.bottomMargin
                }
            }
            val rowSpace = (96 * root.resources.displayMetrics.density).roundToInt()
            val fixedSpace = root.children.filter { it !== content && it !in headerViews && it.visibility != View.GONE }
                .sumOf { it.measuredHeight }
            if (hasItems && clipsAdapter.itemCount == 0) return@post
            val next = hasItems && headerHeight + rowSpace + fixedSpace > root.height - root.paddingTop - root.paddingBottom
            if (next != scrolling) setScrolling(next)
        }
    }

    private fun setScrolling(value: Boolean) {
        val manager = recycler.layoutManager as LinearLayoutManager
        val first = manager.findFirstVisibleItemPosition().coerceAtLeast(0)
        val offset = if (scrolling && first == 0) 0 else manager.findViewByPosition(first)?.top ?: 0
        val clipPosition = (first - if (scrolling) 1 else 0).coerceAtLeast(0)
        recycler.adapter = null
        scrolling = value
        if (value) {
            headerViews.forEach { (it.parent as ViewGroup).removeView(it); header.addView(it) }
            content.layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
            recycler.adapter = ConcatAdapter(headerAdapter, clipsAdapter)
            // Start with usable clip rows. Scrolling upward reveals every original preview/header action.
            manager.scrollToPositionWithOffset(clipPosition + 1, offset.coerceAtLeast(0))
            onHeaderVisible(false)
        } else {
            headerViews.forEachIndexed { index, view ->
                (view.parent as ViewGroup).removeView(view)
                root.addView(view, index)
            }
            content.layoutParams = LinearLayout.LayoutParams(originalContentParams)
            recycler.adapter = clipsAdapter
            manager.scrollToPositionWithOffset(clipPosition, offset)
            onHeaderVisible(true)
        }
    }

    fun showPreview() {
        if (scrolling) recycler.smoothScrollToPosition(0)
    }

    fun save(outState: Bundle) {
        outState.putBoolean(STATE_SCROLLING, scrolling)
    }

    override fun close() {
        closed = true
        root.removeOnLayoutChangeListener(layoutListener)
        clipsAdapter.unregisterAdapterDataObserver(dataObserver)
        headerViews.forEach { it.removeOnLayoutChangeListener(layoutListener) }
    }

    private companion object {
        const val STATE_SCROLLING = "clips_scrolling_header"
    }
}
