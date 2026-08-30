package com.github.andreyasadchy.xtra.util

import android.graphics.Rect
import android.graphics.RectF
import android.view.MotionEvent
import android.view.TouchDelegate
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.RelativeLayout
import androidx.core.view.doOnLayout
import androidx.media3.ui.TimeBar
import com.github.andreyasadchy.xtra.R
import com.github.andreyasadchy.xtra.databinding.FragmentPlayerBinding
import kotlin.math.abs
import kotlin.math.max

/** Keeps the player overlay visually coherent when portrait video is short. */
object PortraitPlayerControls {

    private const val BASELINE_HEIGHT_DP = 380f
    private const val MIN_SCALE = 0.55f
    private const val MAX_SCALE = 1.2f
    private const val AUTO_SCALE = "auto"
    private const val MIDDLE_QUICK_GAP_DP = 8f
    // The anchor layouts already provide the landscape-safe margins. Adding a
    // second portrait inset leaves a visible gap beside the outer controls.
    private const val EDGE_INSET_DP = 0f

    private enum class HorizontalAnchor {
        START,
        CENTER,
        END,
    }

    private enum class VerticalAnchor {
        TOP,
        CENTER,
        MIDDLE_QUICK,
        BOTTOM,
    }

    fun schedule(binding: FragmentPlayerBinding, isPortrait: Boolean) {
        apply(binding, isPortrait)
        // Controls start GONE and can be unmeasured. Wait for their first real
        // layout, then allow one more frame for placement-rule changes to settle.
        binding.playerControls.root.doOnLayout {
            binding.playerControls.root.postOnAnimation {
                apply(binding, isPortrait)
            }
        }
    }

    private fun apply(binding: FragmentPlayerBinding, isPortrait: Boolean) {
        val scale = controlScale(binding, isPortrait)
        val quickControlPosition = quickControlPosition(binding)
        applyQuickControlPosition(binding, quickControlPosition)
        with(binding.playerControls) {
            val quickVerticalAnchor = if (quickControlPosition == QuickControlPosition.MIDDLE) {
                VerticalAnchor.MIDDLE_QUICK
            } else {
                VerticalAnchor.BOTTOM
            }
            val middleQuickCenterY = if (quickControlPosition == QuickControlPosition.MIDDLE) {
                calculateMiddleQuickCenterY(
                    root = root,
                    scale = scale,
                    topContainers = listOf(topLeftLayout, topRightLayout, topCenterLayout),
                    transportControls = listOf(playPause, rewind, fastForward),
                    sideContainers = listOf(middleLeftLayout, middleRightLayout),
                    quickContainers = listOf(bottomLeftLayout, bottomRightLayout, bottomCenterLayout),
                    timeline = bottomLayout,
                )
            } else {
                null
            }
            val compositionContainers = listOf(
                Triple(topLeftLayout, HorizontalAnchor.START, VerticalAnchor.TOP),
                Triple(topRightLayout, HorizontalAnchor.END, VerticalAnchor.TOP),
                Triple(topCenterLayout, HorizontalAnchor.CENTER, VerticalAnchor.TOP),
                Triple(middleLeftLayout, HorizontalAnchor.START, VerticalAnchor.CENTER),
                Triple(middleRightLayout, HorizontalAnchor.END, VerticalAnchor.CENTER),
                Triple(bottomLeftLayout, HorizontalAnchor.START, quickVerticalAnchor),
                Triple(bottomRightLayout, HorizontalAnchor.END, quickVerticalAnchor),
                Triple(bottomCenterLayout, HorizontalAnchor.CENTER, quickVerticalAnchor),
                Triple(streamInfoLayout, HorizontalAnchor.CENTER, VerticalAnchor.BOTTOM),
                Triple(bottomLayout, HorizontalAnchor.CENTER, VerticalAnchor.BOTTOM),
            )
            compositionContainers.forEach { (container, horizontalAnchor, verticalAnchor) ->
                resetDescendantTransforms(container)
                scaleCompositionView(
                    root,
                    container,
                    scale,
                    isPortrait,
                    horizontalAnchor,
                    verticalAnchor,
                    middleQuickCenterY,
                )
            }

            val rootControls = listOf(
                Triple(playPause, HorizontalAnchor.CENTER, VerticalAnchor.CENTER),
                Triple(rewind, HorizontalAnchor.CENTER, VerticalAnchor.CENTER),
                Triple(fastForward, HorizontalAnchor.CENTER, VerticalAnchor.CENTER),
                Triple(position, HorizontalAnchor.START, VerticalAnchor.BOTTOM),
                Triple(duration, HorizontalAnchor.END, VerticalAnchor.BOTTOM),
            )
            rootControls.forEach { (view, horizontalAnchor, verticalAnchor) ->
                resetTransform(view)
                scaleCompositionView(
                    root,
                    view,
                    scale,
                    isPortrait,
                    horizontalAnchor,
                    verticalAnchor,
                    middleQuickCenterY,
                )
            }
            val interactiveTargets = compositionContainers
                .flatMap { (container, _, _) -> interactiveDescendants(container) }
                .plus(rootControls.map { (view, _, _) -> view })
                .filter(::isInteractiveControl)
                .distinct()
            root.touchDelegate = if (scale != 1f) {
                ScaledControlTouchDelegate(root, interactiveTargets)
            } else {
                null
            }
        }
    }

    private fun scaleCompositionView(
        root: View,
        view: View,
        scale: Float,
        isPortrait: Boolean,
        horizontalAnchor: HorizontalAnchor,
        verticalAnchor: VerticalAnchor,
        middleQuickCenterY: Float?,
    ) {
        // The default landscape layout must remain exactly as defined in XML.
        // This also clears stale portrait transforms when a control is
        // temporarily GONE/unmeasured.
        val keepLandscapeLayout = !isPortrait && scale == 1f
        if (keepLandscapeLayout && verticalAnchor != VerticalAnchor.MIDDLE_QUICK) {
            resetTransform(view)
            return
        }
        if (view.width <= 0 || view.height <= 0) {
            view.scaleX = scale
            view.scaleY = scale
            return
        }
        view.pivotX = view.width / 2f
        view.pivotY = view.height / 2f
        view.scaleX = scale
        view.scaleY = scale

        val viewCenterX = view.left + view.width / 2f
        val viewCenterY = view.top + view.height / 2f
        val rootCenterX = root.width / 2f
        val rootCenterY = root.height / 2f
        if (keepLandscapeLayout) {
            view.translationX = 0f
        } else {
            val scaledCenterX = when (horizontalAnchor) {
                HorizontalAnchor.START -> {
                    val contentEdge = contentEdge(view, isStart = true)
                    if (contentEdge != null) {
                        root.paddingLeft - scaledChildEdge(view, contentEdge, scale) + viewCenterX
                    } else {
                        root.paddingLeft + view.width * scale / 2f
                    }
                }
                HorizontalAnchor.CENTER -> rootCenterX + (viewCenterX - rootCenterX)
                HorizontalAnchor.END -> {
                    val contentEdge = contentEdge(view, isStart = false)
                    if (contentEdge != null) {
                        root.width - root.paddingRight - scaledChildEdge(view, contentEdge, scale) + viewCenterX
                    } else {
                        root.width - root.paddingRight - view.width * scale / 2f
                    }
                }
            }
            view.translationX = scaledCenterX - viewCenterX
            if (scale != 1f && horizontalAnchor != HorizontalAnchor.CENTER && view is ViewGroup) {
                alignVisibleChildToEdge(root, view, horizontalAnchor)
            }
        }
        val scaledCenterY = when (verticalAnchor) {
            VerticalAnchor.TOP -> viewCenterY * scale
            VerticalAnchor.CENTER -> rootCenterY + (viewCenterY - rootCenterY) * scale
            VerticalAnchor.MIDDLE_QUICK -> middleQuickCenterY
                ?: rootCenterY - 56f * root.resources.displayMetrics.density * scale
            VerticalAnchor.BOTTOM -> root.height - (root.height - viewCenterY) * scale
        }
        view.translationY = scaledCenterY - viewCenterY
    }

    private fun calculateMiddleQuickCenterY(
        root: View,
        scale: Float,
        topContainers: List<View>,
        transportControls: List<View>,
        sideContainers: List<View>,
        quickContainers: List<View>,
        timeline: View,
    ): Float? {
        if (root.height <= 0 || quickContainers.none { it.height > 0 }) return null

        val density = root.resources.displayMetrics.density
        val topContentBottom = topContainers.maxOfOrNull { it.bottom.toFloat() * scale } ?: 0f
        val quickHalfHeight = quickContainers.maxOfOrNull { it.height * scale / 2f } ?: return null
        val transportHalfHeight = transportControls.maxOfOrNull { it.height * scale / 2f } ?: return null
        val sideHalfHeight = sideContainers.maxOfOrNull { it.height * scale / 2f } ?: 0f
        val gap = MIDDLE_QUICK_GAP_DP * density * scale
        val blockedHalfHeight = max(transportHalfHeight, sideHalfHeight)
        val blockedTop = root.height / 2f - blockedHalfHeight
        val blockedBottom = root.height / 2f + blockedHalfHeight
        val minimumCenterY = topContentBottom + quickHalfHeight + gap
        val timelineTop = if (timeline.height > 0) {
            root.height - (root.height - timeline.top) * scale
        } else {
            root.height.toFloat()
        }
        val maximumCenterY = timelineTop - quickHalfHeight - gap
        val candidates = listOf(
            blockedTop - quickHalfHeight - gap,
            blockedBottom + quickHalfHeight + gap,
            minimumCenterY,
            maximumCenterY,
        )
        return candidates
            .filter { centerY ->
                centerY - quickHalfHeight >= minimumCenterY &&
                    centerY + quickHalfHeight <= maximumCenterY &&
                    (centerY + quickHalfHeight + gap <= blockedTop ||
                        centerY - quickHalfHeight - gap >= blockedBottom)
            }
            .minByOrNull { centerY -> abs(centerY - root.height / 2f) }
            ?: minimumCenterY.coerceIn(quickHalfHeight, root.height - quickHalfHeight)
    }

    private fun contentEdge(view: View, isStart: Boolean): Float? {
        if (view !is ViewGroup) return if (isStart) 0f else view.width.toFloat()
        val children = (0 until view.childCount)
            .map(view::getChildAt)
            .filter { it.visibility == View.VISIBLE && it.width > 0 && it.height > 0 }
        return if (isStart) {
            children.minOfOrNull { it.left.toFloat() }
        } else {
            children.maxOfOrNull { it.right.toFloat() }
        }
    }

    private fun scaledChildEdge(view: View, childEdge: Float, scale: Float): Float =
        view.left + (childEdge - view.width / 2f) * scale + view.width / 2f

    private fun controlScale(binding: FragmentPlayerBinding, isPortrait: Boolean): Float {
        val density = binding.root.resources.displayMetrics.density
        val automaticScale = if (isPortrait && binding.playerLayout.height > 0) {
            (binding.playerLayout.height / density / BASELINE_HEIGHT_DP)
                .coerceIn(MIN_SCALE, 1f)
        } else {
            1f
        }
        val key = if (isPortrait) {
            C.PLAYER_CONTROL_SCALE_PORTRAIT
        } else {
            C.PLAYER_CONTROL_SCALE_LANDSCAPE
        }
        val defaultValue = if (isPortrait) AUTO_SCALE else "100"
        val value = binding.root.context.prefs().getString(key, defaultValue)
        return if (value == AUTO_SCALE) {
            automaticScale
        } else {
            value?.toFloatOrNull()?.div(100f)?.coerceIn(MIN_SCALE, MAX_SCALE) ?: automaticScale
        }
    }

    private enum class QuickControlPosition {
        ABOVE,
        BELOW,
        MIDDLE,
    }

    private fun quickControlPosition(binding: FragmentPlayerBinding): QuickControlPosition = when (
        binding.root.context.prefs().getString(
            C.PLAYER_CONTROL_POSITION,
            C.PLAYER_CONTROL_POSITION_ABOVE,
        )
    ) {
        C.PLAYER_CONTROL_POSITION_BELOW -> QuickControlPosition.BELOW
        C.PLAYER_CONTROL_POSITION_MIDDLE -> QuickControlPosition.MIDDLE
        else -> QuickControlPosition.ABOVE
    }

    private fun applyQuickControlPosition(
        binding: FragmentPlayerBinding,
        position: QuickControlPosition,
    ) {
        val bottom = binding.playerControls.bottomLayout
        val bottomParams = bottom.layoutParams as? RelativeLayout.LayoutParams ?: return
        val bottomLeft = binding.playerControls.bottomLeftLayout
        val bottomLeftParams = bottomLeft.layoutParams as? RelativeLayout.LayoutParams ?: return
        val bottomRight = binding.playerControls.bottomRightLayout
        val bottomRightParams = bottomRight.layoutParams as? RelativeLayout.LayoutParams ?: return
        val bottomCenter = binding.playerControls.bottomCenterLayout
        val bottomCenterParams = bottomCenter.layoutParams as? RelativeLayout.LayoutParams ?: return
        val positionView = binding.playerControls.position
        val positionParams = positionView.layoutParams as? RelativeLayout.LayoutParams ?: return
        val duration = binding.playerControls.duration
        val durationParams = duration.layoutParams as? RelativeLayout.LayoutParams ?: return
        val progress = binding.playerControls.progressBar
        val progressParams = progress.layoutParams as? LinearLayout.LayoutParams ?: return
        val bottomAnchor = binding.playerControls.quickControlsBottomAnchor
        val density = binding.root.resources.displayMetrics.density
        val timelineRowHeight = (48 * density).toInt()
        val bottomAnchorParams = bottomAnchor.layoutParams as? RelativeLayout.LayoutParams ?: return
        bottomAnchorParams.height = (53 * density).toInt()
        bottomAnchorParams.removeRule(RelativeLayout.ALIGN_PARENT_BOTTOM)
        bottomAnchorParams.addRule(RelativeLayout.ALIGN_PARENT_BOTTOM)
        bottomAnchor.layoutParams = bottomAnchorParams

        when (position) {
            QuickControlPosition.BELOW -> {
                bottomParams.height = timelineRowHeight
                bottomParams.removeRule(RelativeLayout.ALIGN_PARENT_BOTTOM)
                bottomParams.addRule(RelativeLayout.ABOVE, R.id.quickControlsBottomAnchor)
                progressParams.bottomMargin = 0
                positionParams.height = timelineRowHeight
                positionParams.bottomMargin = 0
                positionParams.removeRule(RelativeLayout.ALIGN_PARENT_BOTTOM)
                positionParams.addRule(RelativeLayout.ABOVE, R.id.quickControlsBottomAnchor)
                durationParams.height = timelineRowHeight
                durationParams.bottomMargin = 0
                durationParams.removeRule(RelativeLayout.ALIGN_PARENT_BOTTOM)
                durationParams.addRule(RelativeLayout.ABOVE, R.id.quickControlsBottomAnchor)
                setQuickControlRule(bottomLeftParams, QuickControlRule.BELOW)
                setQuickControlRule(bottomRightParams, QuickControlRule.BELOW)
                setQuickControlRule(bottomCenterParams, QuickControlRule.BELOW)
            }
            QuickControlPosition.MIDDLE -> {
                restoreTimelineLayout(bottomParams, progressParams, positionParams, durationParams, density)
                setQuickControlRule(bottomLeftParams, QuickControlRule.MIDDLE)
                setQuickControlRule(bottomRightParams, QuickControlRule.MIDDLE)
                setQuickControlRule(bottomCenterParams, QuickControlRule.MIDDLE)
            }
            QuickControlPosition.ABOVE -> {
                restoreTimelineLayout(bottomParams, progressParams, positionParams, durationParams, density)
                setQuickControlRule(bottomLeftParams, QuickControlRule.ABOVE)
                setQuickControlRule(bottomRightParams, QuickControlRule.ABOVE)
                bottomCenterParams.removeRule(RelativeLayout.ALIGN_PARENT_BOTTOM)
                bottomCenterParams.removeRule(RelativeLayout.CENTER_VERTICAL)
                bottomCenterParams.addRule(RelativeLayout.ABOVE, R.id.streamInfoLayout)
            }
        }
        bottom.layoutParams = bottomParams
        bottomLeft.layoutParams = bottomLeftParams
        bottomRight.layoutParams = bottomRightParams
        bottomCenter.layoutParams = bottomCenterParams
        progress.layoutParams = progressParams
        positionView.layoutParams = positionParams
        duration.layoutParams = durationParams
    }

    private enum class QuickControlRule {
        ABOVE,
        BELOW,
        MIDDLE,
    }

    private fun setQuickControlRule(params: RelativeLayout.LayoutParams, rule: QuickControlRule) {
        params.removeRule(RelativeLayout.ABOVE)
        params.removeRule(RelativeLayout.ALIGN_PARENT_BOTTOM)
        params.removeRule(RelativeLayout.CENTER_VERTICAL)
        when (rule) {
            QuickControlRule.ABOVE -> params.addRule(RelativeLayout.ABOVE, R.id.bottomLayout)
            QuickControlRule.BELOW -> params.addRule(RelativeLayout.ALIGN_PARENT_BOTTOM)
            QuickControlRule.MIDDLE -> params.addRule(RelativeLayout.CENTER_VERTICAL)
        }
    }

    private fun restoreTimelineLayout(
        bottomParams: RelativeLayout.LayoutParams,
        progressParams: LinearLayout.LayoutParams,
        positionParams: RelativeLayout.LayoutParams,
        durationParams: RelativeLayout.LayoutParams,
        density: Float,
    ) {
        bottomParams.height = ViewGroup.LayoutParams.WRAP_CONTENT
        bottomParams.removeRule(RelativeLayout.ABOVE)
        bottomParams.addRule(RelativeLayout.ALIGN_PARENT_BOTTOM)
        progressParams.bottomMargin = (5 * density).toInt()
        positionParams.height = ViewGroup.LayoutParams.WRAP_CONTENT
        positionParams.bottomMargin = (10 * density).toInt()
        positionParams.removeRule(RelativeLayout.ABOVE)
        positionParams.addRule(RelativeLayout.ALIGN_PARENT_BOTTOM)
        durationParams.height = ViewGroup.LayoutParams.WRAP_CONTENT
        durationParams.bottomMargin = (10 * density).toInt()
        durationParams.removeRule(RelativeLayout.ABOVE)
        durationParams.addRule(RelativeLayout.ALIGN_PARENT_BOTTOM)
    }

    private fun alignVisibleChildToEdge(
        root: View,
        container: ViewGroup,
        horizontalAnchor: HorizontalAnchor,
    ) {
        val childBounds = Rect()
        val rootLocation = IntArray(2)
        root.getLocationOnScreen(rootLocation)
        val visibleChildren = (0 until container.childCount)
            .map(container::getChildAt)
            .filter { child ->
                child.visibility == View.VISIBLE && child.width > 0 && child.height > 0 &&
                    child.getGlobalVisibleRect(childBounds)
            }
        if (visibleChildren.isEmpty()) return

        val visibleEdge = if (horizontalAnchor == HorizontalAnchor.START) {
            visibleChildren.minOf { child ->
                val bounds = Rect()
                child.getGlobalVisibleRect(bounds)
                bounds.left - rootLocation[0].toFloat()
            }
        } else {
            visibleChildren.maxOf { child ->
                val bounds = Rect()
                child.getGlobalVisibleRect(bounds)
                bounds.right - rootLocation[0].toFloat()
            }
        }
        val inset = EDGE_INSET_DP * root.resources.displayMetrics.density
        val targetEdge = if (horizontalAnchor == HorizontalAnchor.START) {
            root.paddingLeft + inset
        } else {
            root.width - root.paddingRight - inset
        }
        container.translationX += targetEdge - visibleEdge
    }

    private fun resetDescendantTransforms(view: ViewGroup) {
        for (index in 0 until view.childCount) {
            val child = view.getChildAt(index)
            resetTransform(child)
            if (child is ViewGroup) resetDescendantTransforms(child)
        }
    }

    private fun resetTransform(view: View) {
        view.scaleX = 1f
        view.scaleY = 1f
        view.translationX = 0f
        view.translationY = 0f
    }

    private fun interactiveDescendants(container: ViewGroup): List<View> = buildList {
        for (index in 0 until container.childCount) {
            val child = container.getChildAt(index)
            if (isInteractiveControl(child)) add(child)
            if (child is ViewGroup) addAll(interactiveDescendants(child))
        }
    }

    private fun isInteractiveControl(view: View): Boolean =
        view.hasOnClickListeners() || view.isClickable || view.isLongClickable || view is TimeBar

    private class ScaledControlTouchDelegate(
        private val host: View,
        private val targets: List<View>,
    ) : TouchDelegate(Rect(), host) {

        private var targetedView: View? = null

        override fun onTouchEvent(event: MotionEvent): Boolean {
            if (event.actionMasked == MotionEvent.ACTION_DOWN) {
                targetedView = targets
                    .asSequence()
                    .filter { target ->
                        target.isShown && target.width > 0 && target.height > 0 &&
                            touchBounds(target).contains(event.x, event.y)
                    }
                    .minByOrNull { target ->
                        val bounds = visualBounds(target)
                        val dx = event.x - bounds.centerX()
                        val dy = event.y - bounds.centerY()
                        dx * dx + dy * dy
                    }
            }

            val target = targetedView ?: return false
            val delegatedEvent = MotionEvent.obtain(event)
            val handled = if (target is TimeBar) {
                val visualBounds = visualBounds(target)
                val x = if (visualBounds.width() > 0f) {
                    ((event.x - visualBounds.left) / visualBounds.width() * target.width)
                        .coerceIn(0f, (target.width - 1).coerceAtLeast(0).toFloat())
                } else {
                    target.width / 2f
                }
                delegatedEvent.setLocation(x, target.height / 2f)
                target.dispatchTouchEvent(delegatedEvent)
            } else {
                delegatedEvent.setLocation(target.width / 2f, target.height / 2f)
                target.dispatchTouchEvent(delegatedEvent)
            }
            delegatedEvent.recycle()

            if (event.actionMasked == MotionEvent.ACTION_UP || event.actionMasked == MotionEvent.ACTION_CANCEL) {
                targetedView = null
            }
            return handled || targetedView != null
        }

        private fun touchBounds(target: View): RectF {
            val visualBounds = visualBounds(target)
            val centerX = visualBounds.centerX()
            val centerY = visualBounds.centerY()
            val halfWidth = max(target.width / 2f, visualBounds.width() / 2f)
            val halfHeight = max(target.height / 2f, visualBounds.height() / 2f)
            return RectF(
                centerX - halfWidth,
                centerY - halfHeight,
                centerX + halfWidth,
                centerY + halfHeight,
            )
        }

        private fun visualBounds(target: View): RectF {
            val bounds = Rect()
            target.getGlobalVisibleRect(bounds)
            val hostLocation = IntArray(2)
            host.getLocationOnScreen(hostLocation)
            return RectF(
                bounds.left - hostLocation[0].toFloat(),
                bounds.top - hostLocation[1].toFloat(),
                bounds.right - hostLocation[0].toFloat(),
                bounds.bottom - hostLocation[1].toFloat(),
            )
        }
    }
}
