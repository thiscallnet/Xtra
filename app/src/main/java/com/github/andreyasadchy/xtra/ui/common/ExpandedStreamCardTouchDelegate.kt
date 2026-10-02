package com.github.andreyasadchy.xtra.ui.common

import android.graphics.Rect
import android.view.MotionEvent
import android.view.TouchDelegate
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.widget.HorizontalScrollView

/** Expands small stream-card actions to 48dp without changing their visual layout. */
internal class ExpandedStreamCardTouchDelegate(
    private val card: ViewGroup,
    private val detailTargets: List<View>,
    private val tagTargets: () -> List<View>,
    private val titleScroll: View,
    private val targetSizePx: Int,
) : TouchDelegate(Rect(), card) {
    private val cardLocation = IntArray(2)
    private val targetLocation = IntArray(2)
    private val bounds = Rect()
    private val touchSlop = ViewConfiguration.get(card.context).scaledTouchSlop
    private var activeTarget: View? = null
    private var activeTagTarget: View? = null
    private var activeTagScroll: HorizontalScrollView? = null
    private var tagDownX = 0f
    private var tagDownY = 0f
    private var tagMoved = false

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (event.actionMasked == MotionEvent.ACTION_DOWN) {
            clearActiveTarget()
            if (contains(titleScroll, event.x, event.y)) return false

            val exactDetailTarget = findTarget(detailTargets, event.x, event.y, expand = false)
            if (exactDetailTarget != null) {
                activeTarget = exactDetailTarget
                return dispatchTo(exactDetailTarget, event)
            }

            val tags = tagTargets()
            if (findTarget(tags, event.x, event.y, expand = false) != null) return false

            val detailTarget = findTarget(detailTargets, event.x, event.y, expand = true)
            val tagTarget = findTarget(tags, event.x, event.y, expand = true)
            val detailDistance = detailTarget?.let { distanceTo(it, event.x, event.y, expand = true) }
                ?: Float.MAX_VALUE
            val tagDistance = tagTarget?.let { distanceTo(it, event.x, event.y, expand = true) }
                ?: Float.MAX_VALUE

            if (tagTarget != null && tagDistance < detailDistance) {
                val scrollView = findHorizontalScrollView(tagTarget) ?: return false
                activeTagTarget = tagTarget
                activeTagScroll = scrollView
                tagDownX = event.x
                tagDownY = event.y
                tagMoved = false
                dispatchTo(scrollView, event)
                return true
            }

            val target = detailTarget ?: return false
            activeTarget = target
            return dispatchTo(target, event)
        }

        activeTagScroll?.let { scrollView ->
            if (event.actionMasked == MotionEvent.ACTION_MOVE && movedBeyondSlop(event)) {
                tagMoved = true
            } else if (event.actionMasked != MotionEvent.ACTION_MOVE &&
                event.actionMasked != MotionEvent.ACTION_UP
            ) {
                tagMoved = true
            }

            dispatchTo(scrollView, event)
            if (event.actionMasked == MotionEvent.ACTION_UP) {
                val target = activeTagTarget
                val shouldClick = target != null && !tagMoved && target.isShown &&
                    target.isEnabled && target.isClickable && containsExpanded(target, event.x, event.y)
                clearActiveTarget()
                if (target != null && shouldClick) target.performClick()
            } else if (event.actionMasked == MotionEvent.ACTION_CANCEL) {
                clearActiveTarget()
            }
            return true
        }

        val target = activeTarget ?: return false
        val handled = dispatchTo(target, event)
        if (event.actionMasked == MotionEvent.ACTION_UP || event.actionMasked == MotionEvent.ACTION_CANCEL) {
            clearActiveTarget()
        }
        return handled
    }

    private fun findTarget(
        targets: List<View>,
        x: Float,
        y: Float,
        expand: Boolean,
    ): View? {
        var best: View? = null
        var bestDistance = Float.MAX_VALUE
        targets.forEach { target ->
            if (!target.isShown || !target.isEnabled || !target.isClickable || !boundsInCard(target, expand)) {
                return@forEach
            }
            if (!bounds.contains(x.toInt(), y.toInt())) return@forEach
            val dx = x - bounds.exactCenterX()
            val dy = y - bounds.exactCenterY()
            val distance = dx * dx + dy * dy
            if (distance < bestDistance) {
                best = target
                bestDistance = distance
            }
        }
        return best
    }

    private fun distanceTo(target: View, x: Float, y: Float, expand: Boolean): Float {
        if (!boundsInCard(target, expand)) return Float.MAX_VALUE
        val dx = x - bounds.exactCenterX()
        val dy = y - bounds.exactCenterY()
        return dx * dx + dy * dy
    }

    private fun contains(view: View, x: Float, y: Float): Boolean {
        return view.isShown && boundsInCard(view, expand = false) && bounds.contains(x.toInt(), y.toInt())
    }

    private fun containsExpanded(view: View, x: Float, y: Float): Boolean {
        return boundsInCard(view, expand = true) && bounds.contains(x.toInt(), y.toInt())
    }

    private fun boundsInCard(view: View, expand: Boolean): Boolean {
        card.getLocationOnScreen(cardLocation)
        view.getLocationOnScreen(targetLocation)
        val left = targetLocation[0] - cardLocation[0]
        val top = targetLocation[1] - cardLocation[1]
        bounds.set(left, top, left + view.width, top + view.height)
        if (expand) {
            val extraWidth = (targetSizePx - bounds.width()).coerceAtLeast(0) / 2
            val extraHeight = (targetSizePx - bounds.height()).coerceAtLeast(0) / 2
            bounds.inset(-extraWidth, -extraHeight)
            bounds.intersect(0, 0, card.width, card.height)
        }
        return !bounds.isEmpty
    }

    private fun dispatchTo(target: View, event: MotionEvent): Boolean {
        card.getLocationOnScreen(cardLocation)
        target.getLocationOnScreen(targetLocation)
        val translated = MotionEvent.obtain(event)
        translated.offsetLocation(
            (cardLocation[0] - targetLocation[0]).toFloat(),
            (cardLocation[1] - targetLocation[1]).toFloat(),
        )
        val handled = target.dispatchTouchEvent(translated)
        translated.recycle()
        return handled
    }

    private fun movedBeyondSlop(event: MotionEvent): Boolean =
        kotlin.math.abs(event.x - tagDownX) > touchSlop || kotlin.math.abs(event.y - tagDownY) > touchSlop

    private fun findHorizontalScrollView(target: View): HorizontalScrollView? {
        var parent = target.parent
        while (parent is View) {
            if (parent is HorizontalScrollView) return parent
            parent = parent.parent
        }
        return null
    }

    private fun clearActiveTarget() {
        activeTarget = null
        activeTagTarget = null
        activeTagScroll = null
        tagMoved = false
    }
}
