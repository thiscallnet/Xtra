package com.github.andreyasadchy.xtra.ui.common

import android.graphics.Rect
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.widget.TextView
import androidx.viewpager2.widget.ViewPager2
import com.google.android.material.appbar.AppBarLayout
import com.google.android.material.tabs.TabLayout
import kotlin.math.max
import kotlin.math.roundToInt

/** Keeps pager tabs clear of the minimized player, and preserves readable labels in a narrow safe area. */
class PlayerOverlayTabAvoidance(
    private val appBar: AppBarLayout,
    private val tabLayout: TabLayout,
    private val viewPager: ViewPager2,
    private val playerContainer: View,
) : AutoCloseable {

    private val baseMargins = (tabLayout.layoutParams as ViewGroup.MarginLayoutParams).let {
        it.marginStart to it.marginEnd
    }
    private val originalTabMode = tabLayout.tabMode
    private val originalTabGravity = tabLayout.tabGravity
    private val appBarBounds = Rect()
    private val tabBounds = Rect()
    private val playerBounds = Rect()
    private val gapPx = (8f * tabLayout.resources.displayMetrics.density).roundToInt()

    private var cachedFixedModeRequiredWidth: Float? = null
    private var reflowGeneration = 0L
    private var pendingSelectedRevealGeneration: Long? = null
    private var lastSafeGeometry: SafeGeometry? = null
    private var observer: ViewTreeObserver? = null
    private var hasPreDrawListener = false
    private var isClosed = false

    private data class SafeGeometry(
        val exclusionActive: Boolean,
        val availableWidth: Int,
        val marginStart: Int,
        val marginEnd: Int,
    )

    private val preDrawListener = ViewTreeObserver.OnPreDrawListener {
        updateLayout()
        true
    }
    private val attachListener = object : View.OnAttachStateChangeListener {
        override fun onViewAttachedToWindow(view: View) {
            attachToTreeObserver()
        }

        override fun onViewDetachedFromWindow(view: View) {
            detachFromTreeObserver()
        }
    }

    init {
        tabLayout.addOnAttachStateChangeListener(attachListener)
        if (tabLayout.isAttachedToWindow) attachToTreeObserver()
    }

    private fun attachToTreeObserver() {
        val nextObserver = tabLayout.viewTreeObserver
        if (!nextObserver.isAlive) return
        if (observer !== nextObserver) {
            detachFromTreeObserver()
            observer = nextObserver
        }
        if (!hasPreDrawListener) {
            nextObserver.addOnPreDrawListener(preDrawListener)
            hasPreDrawListener = true
        }
        updateLayout()
    }

    private fun detachFromTreeObserver() {
        observer?.let {
            if (hasPreDrawListener && it.isAlive) it.removeOnPreDrawListener(preDrawListener)
        }
        observer = null
        hasPreDrawListener = false
    }

    private fun updateLayout() {
        if (isClosed) return
        val params = tabLayout.layoutParams as? ViewGroup.MarginLayoutParams ?: return
        var desiredStartMargin = baseMargins.first
        var desiredEndMargin = baseMargins.second
        var exclusionActive = false
        val isRtl = tabLayout.layoutDirection == View.LAYOUT_DIRECTION_RTL
        val baseLeftMargin = if (isRtl) baseMargins.second else baseMargins.first
        val baseRightMargin = if (isRtl) baseMargins.first else baseMargins.second
        var safeWidth = (appBar.width - baseLeftMargin - baseRightMargin).coerceAtLeast(0)

        if (tabLayout.isShown && playerContainer.isShown) {
            val overlay = playerContainer.findViewById<View>(com.github.andreyasadchy.xtra.R.id.playerLayout)
            if (overlay?.isShown == true &&
                appBar.getGlobalVisibleRect(appBarBounds) &&
                tabLayout.getGlobalVisibleRect(tabBounds) &&
                overlay.getGlobalVisibleRect(playerBounds)
            ) {
                var scaledOverlay = false
                var ancestor: View? = overlay
                while (ancestor != null) {
                    if (ancestor.scaleX < 0.99f || ancestor.scaleY < 0.99f) {
                        scaledOverlay = true
                        break
                    }
                    ancestor = ancestor.parent as? View
                }

                val overlapsVertically = playerBounds.top < tabBounds.bottom &&
                    playerBounds.bottom > tabBounds.top
                val baseLeft = appBarBounds.left + baseLeftMargin
                val baseRight = appBarBounds.right - baseRightMargin
                safeWidth = (baseRight - baseLeft).coerceAtLeast(0)
                val overlapsHorizontally = playerBounds.left < baseRight && playerBounds.right > baseLeft

                if (scaledOverlay && overlapsVertically && overlapsHorizontally) {
                    val availableLeft = (playerBounds.left - gapPx - baseLeft).coerceAtLeast(0)
                    val availableRight = (baseRight - playerBounds.right - gapPx).coerceAtLeast(0)
                    if (availableLeft >= availableRight) {
                        val physicalRightMargin = (appBarBounds.right - playerBounds.left + gapPx)
                            .coerceAtLeast(baseRightMargin)
                        if (isRtl) desiredStartMargin = physicalRightMargin else desiredEndMargin = physicalRightMargin
                        safeWidth = availableLeft
                    } else {
                        val physicalLeftMargin = (playerBounds.right + gapPx - appBarBounds.left)
                            .coerceAtLeast(baseLeftMargin)
                        if (isRtl) desiredEndMargin = physicalLeftMargin else desiredStartMargin = physicalLeftMargin
                        safeWidth = availableRight
                    }
                    exclusionActive = true
                }
            }
        }

        val marginsChanged = params.marginStart != desiredStartMargin || params.marginEnd != desiredEndMargin
        if (marginsChanged) {
            params.marginStart = desiredStartMargin
            params.marginEnd = desiredEndMargin
            tabLayout.layoutParams = params
        }

        val shouldUseScrollableTabs = originalTabMode == TabLayout.MODE_FIXED &&
            fixedModeRequiredWidth()?.let { safeWidth < it } == true
        val desiredMode = if (shouldUseScrollableTabs) TabLayout.MODE_SCROLLABLE else originalTabMode
        val desiredGravity = if (shouldUseScrollableTabs) TabLayout.GRAVITY_START else originalTabGravity
        val modeChanged = tabLayout.tabMode != desiredMode
        val gravityChanged = tabLayout.tabGravity != desiredGravity
        if (shouldUseScrollableTabs && modeChanged) {
            if (gravityChanged) tabLayout.tabGravity = desiredGravity
            tabLayout.tabMode = desiredMode
        } else if (modeChanged) {
            tabLayout.tabMode = desiredMode
            if (gravityChanged) tabLayout.tabGravity = desiredGravity
            if (originalTabMode == TabLayout.MODE_FIXED) tabLayout.scrollTo(0, 0)
        } else if (gravityChanged) {
            tabLayout.tabGravity = desiredGravity
        }

        val safeGeometry = SafeGeometry(
            exclusionActive = exclusionActive,
            availableWidth = safeWidth,
            marginStart = desiredStartMargin,
            marginEnd = desiredEndMargin,
        )
        val safeGeometryChanged = safeGeometry != lastSafeGeometry
        lastSafeGeometry = safeGeometry
        val layoutChanged = marginsChanged || modeChanged || gravityChanged || safeGeometryChanged
        if (layoutChanged) {
            reflowGeneration++
            pendingSelectedRevealGeneration = desiredMode
                .takeIf { it == TabLayout.MODE_SCROLLABLE }
                ?.let { reflowGeneration }
            return
        }

        maybeRevealSelectedTab(desiredMode, desiredStartMargin, desiredEndMargin)
    }

    private fun fixedModeRequiredWidth(): Float? {
        cachedFixedModeRequiredWidth?.let { return it }
        val indicator = tabLayout.getChildAt(0) as? ViewGroup ?: return null
        val tabCount = tabLayout.tabCount
        if (tabCount == 0 || indicator.childCount < tabCount) return null

        var widestTabWidth = 0f
        for (index in 0 until tabCount) {
            val tabView = indicator.getChildAt(index) as? ViewGroup ?: return null
            val tabText = tabLayout.getTabAt(index)?.text?.toString().orEmpty()
            val label = findTabLabel(tabView, tabText) ?: return null
            val text = label.transformationMethod?.getTransformation(label.text, label)?.toString()
                ?: label.text?.toString().orEmpty()
            var compoundDrawableWidth = 0f
            label.compoundDrawablesRelative.forEach { drawable ->
                if (drawable != null) compoundDrawableWidth += drawable.intrinsicWidth.toFloat()
            }
            val drawableGap = if (compoundDrawableWidth > 0f) label.compoundDrawablePadding else 0
            val labelWidth = label.paint.measureText(text) +
                label.paddingStart + label.paddingEnd + compoundDrawableWidth + drawableGap
            val tabWidth = labelWidth + tabView.paddingStart + tabView.paddingEnd
            widestTabWidth = max(widestTabWidth, max(tabView.minimumWidth.toFloat(), tabWidth))
        }

        val requiredWidth = tabCount * widestTabWidth + tabLayout.paddingStart + tabLayout.paddingEnd
        if (requiredWidth <= 0f) return null
        cachedFixedModeRequiredWidth = requiredWidth
        return requiredWidth
    }

    private fun findTabLabel(view: View, expectedText: String): TextView? {
        if (view is TextView && view.text?.toString() == expectedText) return view
        if (view !is ViewGroup) return null
        for (index in 0 until view.childCount) {
            findTabLabel(view.getChildAt(index), expectedText)?.let { return it }
        }
        return null
    }

    private fun maybeRevealSelectedTab(desiredMode: Int, desiredStartMargin: Int, desiredEndMargin: Int) {
        if (isClosed || desiredMode != TabLayout.MODE_SCROLLABLE) return
        if (pendingSelectedRevealGeneration != reflowGeneration) return
        if (tabLayout.isLayoutRequested || tabLayout.width <= 0 || tabLayout.tabMode != desiredMode) return

        val params = tabLayout.layoutParams as? ViewGroup.MarginLayoutParams ?: return
        if (params.marginStart != desiredStartMargin || params.marginEnd != desiredEndMargin) return

        val indicator = tabLayout.getChildAt(0) as? ViewGroup ?: return
        if (indicator.isLayoutRequested || indicator.width <= 0 || indicator.childCount < tabLayout.tabCount) return
        if ((0 until tabLayout.tabCount).any { index ->
                val tabView = indicator.getChildAt(index)
                tabView.width <= 0 || tabView.isLayoutRequested
            }
        ) return

        val selectedPosition = viewPager.currentItem
        if (tabLayout.getTabAt(selectedPosition) == null) return
        tabLayout.setScrollPosition(selectedPosition, 0f, false, false)
        pendingSelectedRevealGeneration = null
    }

    override fun close() {
        if (isClosed) return
        isClosed = true
        tabLayout.removeOnAttachStateChangeListener(attachListener)
        detachFromTreeObserver()

        (tabLayout.layoutParams as? ViewGroup.MarginLayoutParams)?.let { params ->
            if (params.marginStart != baseMargins.first || params.marginEnd != baseMargins.second) {
                params.marginStart = baseMargins.first
                params.marginEnd = baseMargins.second
                tabLayout.layoutParams = params
            }
        }
        if (tabLayout.tabMode != originalTabMode) tabLayout.tabMode = originalTabMode
        if (tabLayout.tabGravity != originalTabGravity) tabLayout.tabGravity = originalTabGravity
        pendingSelectedRevealGeneration = null
    }
}
