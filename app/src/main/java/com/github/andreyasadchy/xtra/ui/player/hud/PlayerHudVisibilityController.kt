package com.github.andreyasadchy.xtra.ui.player.hud

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.content.Context
import android.view.View
import android.view.ViewPropertyAnimator
import androidx.fragment.app.DialogFragment
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentManager

class PlayerHudVisibilityController(
    private val rootProvider: () -> View?,
    private val televisionProvider: () -> Boolean,
    private val autoHideDelayMs: Long = 3_000L,
) {
    enum class State { HIDDEN, VISIBLE, PINNED, EDITING }

    var autoHideEnabled = true
        set(value) {
            field = value
            if (value) scheduleHide() else cancelHide()
        }
    var hideOnTouch = true
        set(value) {
            field = value
            if (value) scheduleHide() else cancelHide()
        }
    var state: State = State.HIDDEN
        private set
    var scrubbing = false
        private set
    var interactionActive = false
        private set
    var modalPinned = false
        private set
    var isAnimating = false
        private set

    private var animation: ViewPropertyAnimator? = null
    private var generation = 0L
    private var modalDialogCount = 0
    private var boundFragmentManager: FragmentManager? = null
    private val dialogLifecycleCallbacks = object : FragmentManager.FragmentLifecycleCallbacks() {
        override fun onFragmentPreAttached(fm: FragmentManager, f: Fragment, context: Context) {
            if (f is DialogFragment) {
                modalDialogCount++
                setModalPinned(true)
            }
        }

        override fun onFragmentDetached(fm: FragmentManager, f: Fragment) {
            if (f is DialogFragment) {
                modalDialogCount = (modalDialogCount - 1).coerceAtLeast(0)
                setModalPinned(modalDialogCount > 0)
            }
        }
    }
    private val hideAction = Runnable {
        if (autoHideEnabled && hideOnTouch && !scrubbing && !interactionActive && !modalPinned) {
            hide(force = false)
        }
    }

    fun show(force: Boolean = false) {
        val root = rootProvider() ?: return
        if (state == State.EDITING) return
        state = if (modalPinned) State.PINNED else State.VISIBLE
        root.removeCallbacks(hideAction)
        cancelAnimation()
        if (force) {
            root.alpha = 1f
            root.visibility = View.VISIBLE
            scheduleHide()
            return
        }
        if (root.visibility == View.VISIBLE && root.alpha == 1f) {
            scheduleHide()
            return
        }
        val currentGeneration = ++generation
        root.alpha = 0f
        root.visibility = View.VISIBLE
        isAnimating = true
        animation = root.animate().alpha(1f).setDuration(140L).setListener(object : AnimatorListenerAdapter() {
            override fun onAnimationEnd(animation: Animator) {
                if (currentGeneration != generation) return
                isAnimating = false
                this@PlayerHudVisibilityController.animation = null
                scheduleHide()
            }
        }).also { it.start() }
    }

    fun hide(force: Boolean = false) {
        val root = rootProvider() ?: return
        if (televisionProvider() && !force) return
        root.removeCallbacks(hideAction)
        if (state != State.EDITING) state = State.HIDDEN
        cancelAnimation()
        if (force) {
            root.alpha = 0f
            root.visibility = View.GONE
            return
        }
        if (root.visibility != View.VISIBLE) return
        val currentGeneration = ++generation
        isAnimating = true
        animation = root.animate().alpha(0f).setDuration(180L).setListener(object : AnimatorListenerAdapter() {
            override fun onAnimationEnd(animation: Animator) {
                if (currentGeneration != generation) return
                isAnimating = false
                this@PlayerHudVisibilityController.animation = null
                root.visibility = View.GONE
            }
        }).also { it.start() }
    }

    fun toggle(): Boolean {
        val visible = state != State.HIDDEN
        if (hideOnTouch && visible) {
            hide()
            return false
        }
        show()
        return true
    }

    fun scheduleHide() {
        val root = rootProvider() ?: return
        root.removeCallbacks(hideAction)
        if (state == State.VISIBLE && autoHideEnabled && hideOnTouch && !scrubbing && !interactionActive && !modalPinned && !televisionProvider()) {
            root.postDelayed(hideAction, autoHideDelayMs)
        }
    }

    fun cancelHide() {
        rootProvider()?.removeCallbacks(hideAction)
    }

    fun onScrubStart() {
        scrubbing = true
        cancelHide()
    }

    fun onScrubStop() {
        scrubbing = false
        scheduleHide()
    }

    fun setInteractionActive(active: Boolean) {
        interactionActive = active
        if (active) cancelHide() else scheduleHide()
    }

    fun setModalPinned(pinned: Boolean) {
        modalPinned = pinned
        state = when {
            pinned && state == State.VISIBLE -> State.PINNED
            !pinned && state == State.PINNED -> State.VISIBLE
            else -> state
        }
        if (pinned) cancelHide() else scheduleHide()
    }

    fun enterEditing() {
        cancelHide()
        cancelAnimation()
        state = State.EDITING
    }

    fun leaveEditing() {
        state = State.HIDDEN
    }

    fun hideRunnable(): Runnable = hideAction

    fun bindDialogLifecycle(fragmentManager: FragmentManager) {
        if (boundFragmentManager === fragmentManager) return
        unbindDialogLifecycle()
        boundFragmentManager = fragmentManager
        fragmentManager.registerFragmentLifecycleCallbacks(dialogLifecycleCallbacks, false)
    }

    fun unbindDialogLifecycle() {
        boundFragmentManager?.unregisterFragmentLifecycleCallbacks(dialogLifecycleCallbacks)
        boundFragmentManager = null
        modalDialogCount = 0
        setModalPinned(false)
    }

    private fun cancelAnimation() {
        ++generation
        animation?.setListener(null)
        animation?.cancel()
        animation = null
        isAnimating = false
    }
}
