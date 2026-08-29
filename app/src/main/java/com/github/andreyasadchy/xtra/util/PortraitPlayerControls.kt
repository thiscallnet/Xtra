package com.github.andreyasadchy.xtra.util

import android.graphics.Rect
import android.graphics.RectF
import android.view.MotionEvent
import android.view.TouchDelegate
import android.view.View
import android.view.ViewGroup
import androidx.core.view.doOnPreDraw
import androidx.media3.ui.TimeBar
import com.github.andreyasadchy.xtra.databinding.FragmentPlayerBinding

/** Keeps the player overlay visually coherent when portrait video is short. */
object PortraitPlayerControls {

    private const val BASELINE_HEIGHT_DP = 380f
    private const val MIN_SCALE = 0.55f

    fun schedule(binding: FragmentPlayerBinding, isPortrait: Boolean) {
        apply(binding, isPortrait)
        binding.playerControls.root.doOnPreDraw { apply(binding, isPortrait) }
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
                topLeftLayout,
                topRightLayout,
                topCenterLayout,
                middleLeftLayout,
                middleRightLayout,
                bottomLeftLayout,
                bottomRightLayout,
                bottomCenterLayout,
                streamInfoLayout,
                bottomLayout,
            )
            compositionContainers.forEach { container ->
                resetDescendantTransforms(container)
                scaleCompositionView(root, container, scale)
            }

            val rootControls = listOf(playPause, rewind, fastForward, position, duration)
            rootControls.forEach { view ->
                resetTransform(view)
                scaleCompositionView(root, view, scale)
            }
            val interactiveTargets = compositionContainers
                .flatMap(::interactiveDescendants)
                .plus(rootControls)
                .filter(::isInteractiveControl)
                .distinct()
            root.touchDelegate = if (scale < 1f) {
                ScaledControlTouchDelegate(root, interactiveTargets)
            } else {
                null
            }
        }
    }

    private fun scaleCompositionView(root: View, view: View, scale: Float) {
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
        view.translationX = rootCenterX + (viewCenterX - rootCenterX) * scale - viewCenterX
        view.translationY = rootCenterY + (viewCenterY - rootCenterY) * scale - viewCenterY
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
