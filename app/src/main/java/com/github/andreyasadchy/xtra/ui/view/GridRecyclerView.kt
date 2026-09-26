package com.github.andreyasadchy.xtra.ui.view

import android.content.Context
import android.content.res.Configuration
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.accessibility.AccessibilityManager
import android.widget.Toast
import androidx.core.view.ViewCompat
import androidx.core.view.accessibility.AccessibilityViewCommand
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.paging.LoadStateAdapter
import androidx.recyclerview.widget.ConcatAdapter
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.github.andreyasadchy.xtra.R
import com.github.andreyasadchy.xtra.util.C
import com.github.andreyasadchy.xtra.util.isTelevision
import com.github.andreyasadchy.xtra.util.prefs

class GridRecyclerView : RecyclerView {

    constructor(context: Context) : super(context)
    constructor(context: Context, attrs: AttributeSet) : super(context, attrs)
    constructor(context: Context, attrs: AttributeSet, defStyleAttr: Int) : super(context, attrs, defStyleAttr)

    private val prefs = context.prefs()
    private val gridLayoutManager: GridLayoutManager
    private var gridPage: GridPage? = null
    private var temporarilySingleColumn = false
    private var pinchAllowed: () -> Boolean = { true }
    private var pinchListener: RecyclerView.OnItemTouchListener? = null
    private val preferenceListener = android.content.SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key == null || key == C.PORTRAIT_COLUMN_COUNT || key == C.LANDSCAPE_COLUMN_COUNT ||
            gridPage?.let { key == it.preferenceKey(Configuration.ORIENTATION_PORTRAIT) ||
                key == it.preferenceKey(Configuration.ORIENTATION_LANDSCAPE) } == true
        ) {
            applyColumns(resources.configuration)
        }
    }

    init {
        val columns = getColumnsForConfiguration(resources.configuration)
        gridLayoutManager = GridLayoutManager(context, columns)
        gridLayoutManager.spanSizeLookup = object : GridLayoutManager.SpanSizeLookup() {
            override fun getSpanSize(position: Int): Int {
                val wrappedAdapter = (adapter as? ConcatAdapter)
                    ?.getWrappedAdapterAndPosition(position)
                    ?.first
                return if (wrappedAdapter is LoadStateAdapter<*>) {
                    gridLayoutManager.spanCount
                } else {
                    1
                }
            }
        }
        layoutManager = gridLayoutManager
        addItemDecoration(columns)
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        (pinchListener as? PageGridPinchListener)?.cancelForConfigurationChange()
        super.onConfigurationChanged(newConfig)
        applyColumns(newConfig)
    }

    fun usePageGrid(page: GridPage, allowPinch: () -> Boolean = { true }) {
        val pageChanged = gridPage != page
        gridPage = page
        pinchAllowed = allowPinch
        if (pageChanged) temporarilySingleColumn = false
        applyColumns(resources.configuration)
        if (pinchListener == null && !context.isTelevision()) {
            pinchListener = PageGridPinchListener().also(::addOnItemTouchListener)
        }
    }

    /** Search pages use a one-column recent-search list until actual results are shown. */
    fun setTemporarilySingleColumn(enabled: Boolean) {
        if (temporarilySingleColumn == enabled) return
        temporarilySingleColumn = enabled
        applyColumns(resources.configuration)
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        prefs.registerOnSharedPreferenceChangeListener(preferenceListener)
    }

    override fun onDetachedFromWindow() {
        prefs.unregisterOnSharedPreferenceChangeListener(preferenceListener)
        super.onDetachedFromWindow()
    }

    private fun getColumnsForConfiguration(configuration: Configuration): Int {
        if (temporarilySingleColumn) return 1
        gridPage?.let { return GridColumnPreferences.get(context, it, configuration.orientation) }
        return GridColumnPreferences.getLegacy(context, configuration.orientation)
    }

    private fun applyColumns(configuration: Configuration) {
        val columns = getColumnsForConfiguration(configuration)
        if (gridLayoutManager.spanCount != columns) {
            if (isComputingLayout) {
                post { applyColumns(resources.configuration) }
                return
            }
            gridLayoutManager.spanCount = columns
            addItemDecoration(columns)
        }
        updateGridAccessibilityActions()
    }

    private fun updateGridAccessibilityActions() {
        val orientation = resources.configuration.orientation
        val spanCount = gridLayoutManager.spanCount
        val canAdjustColumns = gridPage != null && !temporarilySingleColumn
        increaseColumnsActionId = updateGridAccessibilityAction(
            increaseColumnsActionId,
            R.string.increase_grid_columns,
            canAdjustColumns && spanCount < maxColumns(orientation),
        ) { adjustPageColumns(1) }
        decreaseColumnsActionId = updateGridAccessibilityAction(
            decreaseColumnsActionId,
            R.string.decrease_grid_columns,
            canAdjustColumns && spanCount > 1,
        ) { adjustPageColumns(-1) }
    }

    private fun updateGridAccessibilityAction(
        actionId: Int?,
        labelId: Int,
        available: Boolean,
        action: () -> Boolean,
    ): Int? {
        if (!available) {
            actionId?.let { ViewCompat.removeAccessibilityAction(this, it) }
            return null
        }
        return actionId ?: ViewCompat.addAccessibilityAction(
            this,
            context.getString(labelId),
            AccessibilityViewCommand { _, _ -> action() },
        )
    }

    private var increaseColumnsActionId: Int? = null
    private var decreaseColumnsActionId: Int? = null

    private fun adjustPageColumns(delta: Int): Boolean {
        if (gridPage == null) return false
        if (temporarilySingleColumn) return false
        val orientation = resources.configuration.orientation
        val next = (gridLayoutManager.spanCount + delta).coerceIn(1, maxColumns(orientation))
        if (!setPageColumns(next, width / 2f, height / 2f)) return false
        showColumnsFeedback(next)
        return true
    }

    private fun setPageColumns(columns: Int, focusX: Float, focusY: Float): Boolean {
        val page = gridPage ?: return false
        val orientation = resources.configuration.orientation
        if (columns == gridLayoutManager.spanCount || columns !in 1..maxColumns(orientation)) return false
        val anchor = findChildViewUnder(focusX, focusY) ?: getChildAt(0)
        val position = anchor?.let(::getChildAdapterPosition)
            ?.takeIf { it != RecyclerView.NO_POSITION }
            ?: (gridLayoutManager as? LinearLayoutManager)?.findFirstVisibleItemPosition()
        val offset = anchor?.top ?: 0
        gridLayoutManager.spanCount = columns
        position?.let { gridLayoutManager.scrollToPositionWithOffset(it, offset) }
        GridColumnPreferences.set(context, page, orientation, columns)
        updateGridAccessibilityActions()
        return true
    }

    private fun showColumnsFeedback(columns: Int) {
        Toast.makeText(context, context.getString(R.string.grid_columns_changed, columns), Toast.LENGTH_SHORT).show()
    }

    private fun isTouchExplorationEnabled(): Boolean =
        (context.getSystemService(Context.ACCESSIBILITY_SERVICE) as? AccessibilityManager)
            ?.isTouchExplorationEnabled == true

    private inner class PageGridPinchListener : OnItemTouchListener {
        private val scaleDetector = ScaleGestureDetector(context, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScaleBegin(detector: ScaleGestureDetector): Boolean {
                return gestureClaimed
            }

            override fun onScale(detector: ScaleGestureDetector): Boolean {
                if (!gestureClaimed || !pinchAllowed() || isTouchExplorationEnabled()) return false
                pendingScale *= detector.scaleFactor
                val orientation = resources.configuration.orientation
                val current = gridLayoutManager.spanCount
                val minimumScale = 1f / SCALE_STEP
                var next = current
                while (pendingScale >= SCALE_STEP && next > 1) {
                    next--
                    pendingScale /= SCALE_STEP
                }
                while (pendingScale <= minimumScale && next < maxColumns(orientation)) {
                    next++
                    pendingScale /= minimumScale
                }
                if (next != current && setPageColumns(next, detector.focusX, detector.focusY)) {
                    lastChangedColumns = next
                }
                if ((next == 1 && pendingScale >= SCALE_STEP) ||
                    (next == maxColumns(orientation) && pendingScale <= minimumScale)
                ) {
                    pendingScale = 1f
                }
                return true
            }

            override fun onScaleEnd(detector: ScaleGestureDetector) {
                // Keep owning the input stream until its final UP or CANCEL.
            }
        })
        private var gestureClaimed = false
        private var pendingScale = 1f

        override fun onInterceptTouchEvent(rv: RecyclerView, event: MotionEvent): Boolean {
            if (gridPage != null && !temporarilySingleColumn) {
                if (!gestureClaimed && event.actionMasked == MotionEvent.ACTION_POINTER_DOWN &&
                    event.pointerCount >= 2 && canStartPinch()
                ) {
                    claimGesture(rv)
                }
                scaleDetector.onTouchEvent(event)
            }
            if (event.actionMasked == MotionEvent.ACTION_UP || event.actionMasked == MotionEvent.ACTION_CANCEL) {
                val intercept = gestureClaimed
                finishGesture(rv)
                return intercept
            }
            return gestureClaimed
        }

        override fun onTouchEvent(rv: RecyclerView, event: MotionEvent) {
            scaleDetector.onTouchEvent(event)
            if (event.actionMasked == MotionEvent.ACTION_UP || event.actionMasked == MotionEvent.ACTION_CANCEL) {
                finishGesture(rv)
            }
        }

        override fun onRequestDisallowInterceptTouchEvent(disallowIntercept: Boolean) = Unit

        private var lastChangedColumns: Int? = null

        private fun canStartPinch(): Boolean =
            gridPage != null && !temporarilySingleColumn && pinchAllowed() && !isTouchExplorationEnabled()

        private fun claimGesture(rv: RecyclerView) {
            gestureClaimed = true
            pendingScale = 1f
            rv.stopScroll()
            rv.parent?.requestDisallowInterceptTouchEvent(true)
        }

        private fun finishGesture(rv: RecyclerView) {
            if (!gestureClaimed) return
            gestureClaimed = false
            pendingScale = 1f
            rv.parent?.requestDisallowInterceptTouchEvent(false)
            lastChangedColumns?.let { columns ->
                showColumnsFeedback(columns)
            }
            lastChangedColumns = null
        }

        fun cancelForConfigurationChange() {
            gestureClaimed = false
            pendingScale = 1f
            lastChangedColumns = null
            parent?.requestDisallowInterceptTouchEvent(false)
        }
    }

    private fun maxColumns(orientation: Int): Int =
        if (orientation == Configuration.ORIENTATION_PORTRAIT) 4 else 6

    private companion object {
        const val SCALE_STEP = 1.2f
    }

    private fun addItemDecoration(columns: Int) {
        // Material 3 cards provide their own separation.
    }
}
