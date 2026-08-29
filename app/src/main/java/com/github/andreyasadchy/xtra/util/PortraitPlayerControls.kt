package com.github.andreyasadchy.xtra.util

import android.graphics.Rect
import android.graphics.RectF
import android.view.MotionEvent
import android.view.TouchDelegate
import android.view.View
import android.view.ViewGroup
import androidx.core.view.doOnLayout
import androidx.media3.ui.TimeBar
import com.github.andreyasadchy.xtra.databinding.FragmentPlayerBinding

/** Keeps the player overlay visually coherent when portrait video is short. */
object PortraitPlayerControls {

    private const val BASELINE_HEIGHT_DP = 380f
    private const val MIN_SCALE = 0.55f
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
        BOTTOM,
    }

    fun schedule(binding: FragmentPlayerBinding, isPortrait: Boolean) {
        apply(binding, isPortrait)
        // Controls can be GONE while the player is being initialized, so the
        // first pass may happen before the anchor containers have dimensions.
        // Reapply after layout to use their real bounds.
        binding.playerControls.root.doOnLayout { apply(binding, isPortrait) }
    }

    private fun apply(binding: FragmentPlayerBinding, isPortrait: Boolean) {
        val scale = if (isPortrait && binding.playerLayout.height > 0) {
            val density = binding.root.resources.displayMetrics.density
            (binding.playerLayout.height / density / BASELINE_HEIGHT_DP)
                .coerceIn(MIN_SCALE, 1f)
        } else {
            1f
        }
        with(binding.playerControls) {
            val compositionContainers = listOf(
                Triple(topLeftLayout, HorizontalAnchor.START, VerticalAnchor.TOP),
                Triple(topRightLayout, HorizontalAnchor.END, VerticalAnchor.TOP),
                Triple(topCenterLayout, HorizontalAnchor.CENTER, VerticalAnchor.TOP),
                Triple(middleLeftLayout, HorizontalAnchor.START, VerticalAnchor.CENTER),
                Triple(middleRightLayout, HorizontalAnchor.END, VerticalAnchor.CENTER),
                Triple(bottomLeftLayout, HorizontalAnchor.START, VerticalAnchor.BOTTOM),
                Triple(bottomRightLayout, HorizontalAnchor.END, VerticalAnchor.BOTTOM),
                Triple(bottomCenterLayout, HorizontalAnchor.CENTER, VerticalAnchor.BOTTOM),
                Triple(streamInfoLayout, HorizontalAnchor.CENTER, VerticalAnchor.BOTTOM),
                Triple(bottomLayout, HorizontalAnchor.CENTER, VerticalAnchor.BOTTOM),
            )
            compositionContainers.forEach { (container, horizontalAnchor, verticalAnchor) ->
                resetDescendantTransforms(container)
                scaleCompositionView(root, container, scale, horizontalAnchor, verticalAnchor)
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
                scaleCompositionView(root, view, scale, horizontalAnchor, verticalAnchor)
            }
            val interactiveTargets = compositionContainers
                .flatMap { (container, _, _) -> interactiveDescendants(container) }
                .plus(rootControls.map { (view, _, _) -> view })
                .filter(::isInteractiveControl)
                .distinct()
            root.touchDelegate = if (scale < 1f) {
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
        horizontalAnchor: HorizontalAnchor,
        verticalAnchor: VerticalAnchor,
    ) {
        // Landscape must use the XML layout exactly. This also clears stale
        // portrait transforms when a control is temporarily GONE/unmeasured.
        if (scale >= 1f) {
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
        if (scale < 1f && horizontalAnchor != HorizontalAnchor.CENTER && view is ViewGroup) {
            alignVisibleChildToEdge(root, view, horizontalAnchor)
        }
        val scaledCenterY = when (verticalAnchor) {
            VerticalAnchor.TOP -> viewCenterY * scale
            VerticalAnchor.CENTER -> rootCenterY + (viewCenterY - rootCenterY) * scale
            VerticalAnchor.BOTTOM -> root.height - (root.height - viewCenterY) * scale
        }
        view.translationY = scaledCenterY - viewCenterY
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
            return RectF(
                centerX - target.width / 2f,
                centerY - target.height / 2f,
                centerX + target.width / 2f,
                centerY + target.height / 2f,
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
