package com.github.andreyasadchy.xtra.ui.common

import android.graphics.Rect
import android.view.View
import android.view.ViewGroup
import androidx.core.view.updateLayoutParams
import com.github.andreyasadchy.xtra.R
import kotlin.math.roundToInt

/** Keeps the navigation content in the largest clear region around a minimized player. */
internal class PlayerOverlayContentInset(
    private val root: View,
    private val content: View,
    private val bottomNavigation: View,
    private val playerContainer: View,
    private val isInPictureInPictureMode: () -> Boolean,
) : AutoCloseable {

    private val initialParams = content.layoutParams as? ViewGroup.MarginLayoutParams
    private val initialTopMargin = initialParams?.topMargin ?: 0
    private val initialBottomMargin = initialParams?.bottomMargin ?: 0
    private val density = content.resources.displayMetrics.density
    private val gapPx = (8f * density).roundToInt()
    private val rootBounds = Rect()
    private val navigationBounds = Rect()
    private val pageBounds = Rect()
    private val playerBounds = Rect()
    private var systemLeftInset = initialParams?.leftMargin ?: 0
    private var systemRightInset = initialParams?.rightMargin ?: 0
    private var isClosed = false
    private val updateAction = Runnable { updateNow() }

    fun updateSystemInsets(left: Int, right: Int) {
        if (systemLeftInset == left && systemRightInset == right) return
        systemLeftInset = left
        systemRightInset = right
        scheduleUpdate()
    }

    fun scheduleUpdate(delayMillis: Long = 0L) {
        if (isClosed) return
        root.removeCallbacks(updateAction)
        if (delayMillis <= 0L) {
            root.post(updateAction)
        } else {
            root.postDelayed(updateAction, delayMillis)
        }
    }

    fun restore() {
        root.removeCallbacks(updateAction)
        applyMargins(
            left = systemLeftInset,
            top = initialTopMargin,
            right = systemRightInset,
            bottom = initialBottomMargin,
        )
    }

    private fun updateNow() {
        if (isClosed || !root.isAttachedToWindow || isInPictureInPictureMode()) {
            restore()
            return
        }
        val margins = content.layoutParams as? ViewGroup.MarginLayoutParams ?: return
        if (!root.getGlobalVisibleRect(rootBounds) || !bottomNavigation.getGlobalVisibleRect(navigationBounds)) {
            restore()
            return
        }

        pageBounds.set(
            rootBounds.left + systemLeftInset,
            rootBounds.top + initialTopMargin,
            rootBounds.right - systemRightInset,
            navigationBounds.top - initialBottomMargin,
        )
        if (pageBounds.isEmpty) {
            restore()
            return
        }

        val player = playerContainer.findViewById<View>(R.id.playerLayout)
        if (player?.isShown != true || !hasScaledAncestor(player) || !player.getGlobalVisibleRect(playerBounds)) {
            restore()
            return
        }

        val safeRegion = findPlayerOverlaySafeRegion(
            pageBounds = pageBounds,
            playerBounds = playerBounds,
            gapPx = gapPx,
            minimumWidthPx = (pageBounds.width() * 0.55f).roundToInt(),
            minimumHeightPx = (pageBounds.height() * 0.55f).roundToInt(),
        ) ?: run {
            restore()
            return
        }

        var left = systemLeftInset
        var top = initialTopMargin
        var right = systemRightInset
        var bottom = initialBottomMargin
        when (safeRegion.side) {
            PlayerOverlaySafeSide.TOP -> bottom += pageBounds.bottom - safeRegion.bounds.bottom
            PlayerOverlaySafeSide.BOTTOM -> top += safeRegion.bounds.top - pageBounds.top
            PlayerOverlaySafeSide.LEFT -> right += pageBounds.right - safeRegion.bounds.right
            PlayerOverlaySafeSide.RIGHT -> left += safeRegion.bounds.left - pageBounds.left
        }
        if (margins.leftMargin != left || margins.topMargin != top ||
            margins.rightMargin != right || margins.bottomMargin != bottom
        ) {
            applyMargins(left, top, right, bottom)
        }
    }

    private fun hasScaledAncestor(view: View): Boolean {
        var ancestor: View? = view
        while (ancestor != null) {
            if (ancestor.scaleX < 0.99f || ancestor.scaleY < 0.99f) return true
            ancestor = ancestor.parent as? View
        }
        return false
    }

    private fun applyMargins(left: Int, top: Int, right: Int, bottom: Int) {
        content.updateLayoutParams<ViewGroup.MarginLayoutParams> {
            leftMargin = left
            topMargin = top
            rightMargin = right
            bottomMargin = bottom
        }
    }

    override fun close() {
        if (isClosed) return
        root.removeCallbacks(updateAction)
        restore()
        isClosed = true
    }
}
