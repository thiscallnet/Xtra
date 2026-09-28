package com.github.andreyasadchy.xtra.ui.common

import android.graphics.Rect
import android.view.View
import android.view.ViewTreeObserver
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import com.github.andreyasadchy.xtra.databinding.CommonRecyclerViewLayoutBinding
import kotlin.math.roundToInt

/** Keeps common loading, empty, and error overlays centered in the visible part of a clipped page. */
fun CommonRecyclerViewLayoutBinding.installVisibleViewportStatePositioning(lifecycleOwner: LifecycleOwner) {
    installVisibleViewportStatePositioningForViews(
        page = root,
        lifecycleOwner = lifecycleOwner,
        stateViews = listOf(nothingHere, progressBar, errorContainer),
        topObstruction = topInsetGuard,
    )
}

fun View.installVisibleViewportStatePositioning(
    lifecycleOwner: LifecycleOwner,
    stateViews: List<View>,
    topObstruction: View? = null,
    overlayProvider: (() -> View?)? = null,
    overlayAvoidanceTarget: View? = null,
    overlayGapDp: Float = 0f,
) {
    installVisibleViewportStatePositioningForViews(
        page = this,
        lifecycleOwner = lifecycleOwner,
        stateViews = stateViews,
        topObstruction = topObstruction,
        overlayProvider = overlayProvider,
        overlayAvoidanceTarget = overlayAvoidanceTarget,
        overlayGapDp = overlayGapDp,
    )
}

private fun installVisibleViewportStatePositioningForViews(
    page: View,
    lifecycleOwner: LifecycleOwner,
    stateViews: List<View>,
    topObstruction: View?,
    overlayProvider: (() -> View?)? = null,
    overlayAvoidanceTarget: View? = null,
    overlayGapDp: Float = 0f,
) {
    val visibleRect = Rect()
    val lastVisibleRect = Rect()
    val pageGlobalVisibleRect = Rect()
    val stateGlobalVisibleRect = Rect()
    val overlayGlobalVisibleRect = Rect()
    var hasLastVisibleRect = false
    var lastPageHeight = -1
    var baseTranslationY = 0f
    var activeTreeObserver: ViewTreeObserver? = null
    var hasGlobalLayoutListener = false
    var hasPreDrawListener = false

    lateinit var preDrawListener: ViewTreeObserver.OnPreDrawListener
    lateinit var globalLayoutListener: ViewTreeObserver.OnGlobalLayoutListener

    fun removePreDrawListener() {
        activeTreeObserver?.let { treeObserver ->
            if (hasPreDrawListener && treeObserver.isAlive) {
                treeObserver.removeOnPreDrawListener(preDrawListener)
            }
        }
        hasPreDrawListener = false
    }

    fun addPreDrawListener() {
        val treeObserver = activeTreeObserver ?: return
        if (!treeObserver.isAlive || hasPreDrawListener) return
        treeObserver.addOnPreDrawListener(preDrawListener)
        hasPreDrawListener = true
    }

    fun removeGlobalLayoutListener() {
        activeTreeObserver?.let { treeObserver ->
            if (hasGlobalLayoutListener && treeObserver.isAlive) {
                treeObserver.removeOnGlobalLayoutListener(globalLayoutListener)
            }
        }
        hasGlobalLayoutListener = false
    }

    fun resetTranslations() {
        stateViews.forEach { it.translationY = 0f }
        hasLastVisibleRect = false
        lastPageHeight = -1
        baseTranslationY = 0f
    }

    fun positionStateAroundOverlay() {
        val target = overlayAvoidanceTarget ?: return
        if (target.visibility != View.VISIBLE || page.visibility != View.VISIBLE) return

        var desiredTranslationY = baseTranslationY
        val overlay = overlayProvider?.invoke()
        if (overlay != null && overlay.visibility == View.VISIBLE && overlay.isShown) {
            var scaledOverlay = false
            var ancestor: View? = overlay
            while (ancestor != null && ancestor !== page) {
                if (ancestor.scaleX < 0.99f || ancestor.scaleY < 0.99f) {
                    scaledOverlay = true
                    break
                }
                ancestor = ancestor.parent as? View
            }
            if (scaledOverlay &&
                page.getGlobalVisibleRect(pageGlobalVisibleRect) &&
                target.getGlobalVisibleRect(stateGlobalVisibleRect) &&
                overlay.getGlobalVisibleRect(overlayGlobalVisibleRect)
            ) {
                stateGlobalVisibleRect.offset(
                    0,
                    (baseTranslationY - target.translationY).roundToInt(),
                )
                if (Rect.intersects(stateGlobalVisibleRect, overlayGlobalVisibleRect)) {
                    val overlapTop = maxOf(stateGlobalVisibleRect.top, overlayGlobalVisibleRect.top)
                    val overlapBottom = minOf(stateGlobalVisibleRect.bottom, overlayGlobalVisibleRect.bottom)
                    if (overlapBottom > overlapTop) {
                        val gapPx = (overlayGapDp * page.resources.displayMetrics.density).roundToInt()
                        val moveUp = overlapBottom - overlayGlobalVisibleRect.top + gapPx
                        val moveDown = overlayGlobalVisibleRect.bottom - overlapTop + gapPx
                        val roomUp = (stateGlobalVisibleRect.top - pageGlobalVisibleRect.top).coerceAtLeast(0)
                        val roomDown = (pageGlobalVisibleRect.bottom - stateGlobalVisibleRect.bottom).coerceAtLeast(0)
                        val upFits = roomUp >= moveUp
                        val downFits = roomDown >= moveDown
                        val offset = when {
                            upFits && downFits -> if (moveUp <= moveDown) -moveUp else moveDown
                            upFits -> -moveUp
                            downFits -> moveDown
                            roomUp >= roomDown -> -roomUp
                            else -> roomDown
                        }
                        desiredTranslationY = baseTranslationY + offset
                    }
                }
            }
        }
        if (target.translationY != desiredTranslationY) target.translationY = desiredTranslationY
    }

    fun updatePreDrawListener() {
        if (stateViews.any { it.visibility == View.VISIBLE }) {
            addPreDrawListener()
        } else {
            if (hasLastVisibleRect) resetTranslations()
            removePreDrawListener()
        }
    }

    fun attachToTreeObserver() {
        val treeObserver = page.viewTreeObserver
        if (!treeObserver.isAlive) return
        if (activeTreeObserver !== treeObserver) {
            removePreDrawListener()
            removeGlobalLayoutListener()
            activeTreeObserver = treeObserver
        }
        if (!hasGlobalLayoutListener) {
            treeObserver.addOnGlobalLayoutListener(globalLayoutListener)
            hasGlobalLayoutListener = true
        }
        updatePreDrawListener()
    }

    preDrawListener = ViewTreeObserver.OnPreDrawListener {
        if (stateViews.none { it.visibility == View.VISIBLE }) {
            resetTranslations()
            removePreDrawListener()
        } else if (page.height > 0 && page.getLocalVisibleRect(visibleRect)) {
            if (topObstruction?.visibility == View.VISIBLE) {
                visibleRect.top = maxOf(visibleRect.top, topObstruction.bottom)
            }
            if (visibleRect.height() > 0) {
                if (!hasLastVisibleRect || lastPageHeight != page.height || lastVisibleRect != visibleRect) {
                    baseTranslationY = visibleRect.exactCenterY() - page.height / 2f
                    stateViews.forEach { stateView ->
                        if (stateView.translationY != baseTranslationY) stateView.translationY = baseTranslationY
                    }
                    lastVisibleRect.set(visibleRect)
                    lastPageHeight = page.height
                    hasLastVisibleRect = true
                }
                positionStateAroundOverlay()
            } else {
                hasLastVisibleRect = false
            }
        } else {
            hasLastVisibleRect = false
        }
        true
    }
    globalLayoutListener = ViewTreeObserver.OnGlobalLayoutListener {
        updatePreDrawListener()
    }

    val attachListener = object : View.OnAttachStateChangeListener {
        override fun onViewAttachedToWindow(view: View) = attachToTreeObserver()

        override fun onViewDetachedFromWindow(view: View) {
            removePreDrawListener()
            removeGlobalLayoutListener()
            activeTreeObserver = null
            resetTranslations()
        }
    }
    page.addOnAttachStateChangeListener(attachListener)
    if (page.isAttachedToWindow) attachToTreeObserver()

    lifecycleOwner.lifecycle.addObserver(object : DefaultLifecycleObserver {
        override fun onDestroy(owner: LifecycleOwner) {
            page.removeOnAttachStateChangeListener(attachListener)
            removePreDrawListener()
            removeGlobalLayoutListener()
            activeTreeObserver = null
            resetTranslations()
            owner.lifecycle.removeObserver(this)
        }
    })
}
