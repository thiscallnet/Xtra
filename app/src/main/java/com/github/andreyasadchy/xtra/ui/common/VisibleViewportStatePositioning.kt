package com.github.andreyasadchy.xtra.ui.common

import android.graphics.Rect
import android.view.View
import android.view.ViewTreeObserver
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import com.github.andreyasadchy.xtra.databinding.CommonRecyclerViewLayoutBinding

/** Keeps common loading, empty, and error overlays centered in the visible part of a clipped page. */
fun CommonRecyclerViewLayoutBinding.installVisibleViewportStatePositioning(lifecycleOwner: LifecycleOwner) {
    val page = root
    val stateViews = listOf(nothingHere, progressBar, errorContainer)
    val visibleRect = Rect()
    val lastVisibleRect = Rect()
    var hasLastVisibleRect = false
    var lastPageHeight = -1
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
            if (!hasLastVisibleRect || lastPageHeight != page.height || lastVisibleRect != visibleRect) {
                val translationY = visibleRect.exactCenterY() - page.height / 2f
                stateViews.forEach { stateView ->
                    if (stateView.translationY != translationY) stateView.translationY = translationY
                }
                lastVisibleRect.set(visibleRect)
                lastPageHeight = page.height
                hasLastVisibleRect = true
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
