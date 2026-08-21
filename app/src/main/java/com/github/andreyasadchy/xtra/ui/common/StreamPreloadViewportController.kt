package com.github.andreyasadchy.xtra.ui.common

import android.graphics.Rect
import android.view.View
import android.view.ViewTreeObserver
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.github.andreyasadchy.xtra.model.ui.Stream
import com.github.andreyasadchy.xtra.repository.preload.StreamPreloadCandidate
import com.github.andreyasadchy.xtra.repository.preload.StreamPreloadCoordinator
import kotlin.math.abs
import kotlin.math.max

/** Reports actual visible stream cards without doing any network work itself. */
class StreamPreloadViewportController(
    private val fragment: Fragment,
    private val coordinator: StreamPreloadCoordinator,
    private val viewportKey: String,
    private val recyclerView: RecyclerView,
    private val streamAtPosition: (Int) -> Stream?,
    private val isParentScrolling: () -> Boolean = { false },
) {
    private var scrollState = RecyclerView.SCROLL_STATE_IDLE
    private var started = false
    private val scrollListener = object : RecyclerView.OnScrollListener() {
        override fun onScrollStateChanged(recyclerView: RecyclerView, newState: Int) {
            scrollState = newState
            requestPublish()
        }

        override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
            requestPublish()
        }
    }
    private val scrollChangedListener = ViewTreeObserver.OnScrollChangedListener { requestPublish() }
    private val layoutChangeListener = View.OnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> requestPublish() }

    fun start() {
        if (started) return
        started = true
        recyclerView.addOnScrollListener(scrollListener)
        recyclerView.addOnLayoutChangeListener(layoutChangeListener)
        recyclerView.viewTreeObserver.addOnScrollChangedListener(scrollChangedListener)
        requestPublish()
    }

    fun stop() {
        if (!started) return
        started = false
        recyclerView.removeOnScrollListener(scrollListener)
        recyclerView.removeOnLayoutChangeListener(layoutChangeListener)
        if (recyclerView.viewTreeObserver.isAlive) {
            recyclerView.viewTreeObserver.removeOnScrollChangedListener(scrollChangedListener)
        }
        coordinator.detachViewport(viewportKey)
    }

    fun onResume() {
        requestPublish()
    }

    fun onPause() {
        coordinator.detachViewport(viewportKey)
    }

    fun onParentScrollStateChanged() {
        requestPublish()
    }

    private fun requestPublish() {
        if (!started) return
        if (isScrolling()) {
            coordinator.setViewportScrolling(viewportKey, scrolling = true)
        } else {
            recyclerView.post(::publish)
        }
    }

    private fun publish() {
        if (!started || isScrolling()) return
        if (!fragment.isAdded || fragment.view == null ||
            !fragment.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED) ||
            !recyclerView.isAttachedToWindow
        ) {
            coordinator.detachViewport(viewportKey)
            return
        }
        val viewportRect = Rect()
        if (!recyclerView.getGlobalVisibleRect(viewportRect) || viewportRect.width() <= 0 || viewportRect.height() <= 0) {
            coordinator.updateViewport(viewportKey, emptyList(), scrolling = isScrolling())
            return
        }
        val horizontal = (recyclerView.layoutManager as? LinearLayoutManager)?.orientation == RecyclerView.HORIZONTAL
        val viewportCenter = if (horizontal) viewportRect.centerX() else viewportRect.centerY()
        val viewportSize = if (horizontal) viewportRect.width() else viewportRect.height()
        val candidates = buildList {
            repeat(recyclerView.childCount) { childIndex ->
                val child = recyclerView.getChildAt(childIndex)
                val position = recyclerView.getChildAdapterPosition(child)
                if (position == RecyclerView.NO_POSITION) return@repeat
                val stream = streamAtPosition(position) ?: return@repeat
                val childRect = Rect()
                if (!child.getGlobalVisibleRect(childRect)) return@repeat
                val fullArea = (child.width.toLong() * child.height.toLong()).coerceAtLeast(1L)
                val visibleArea = childRect.width().toLong() * childRect.height().toLong()
                val visibleFraction = (visibleArea.toDouble() / fullArea).toFloat().coerceIn(0f, 1f)
                if (visibleFraction <= 0f) return@repeat
                val childCenter = if (horizontal) childRect.centerX() else childRect.centerY()
                val halfSpan = max(1, viewportSize / 2 + if (horizontal) child.width else child.height)
                val centerProximity = 1f - (abs(childCenter - viewportCenter).toFloat() / halfSpan).coerceIn(0f, 1f)
                val channelLogin = stream.channelLogin?.trim().orEmpty()
                if (channelLogin.isNotEmpty()) {
                    add(
                        StreamPreloadCandidate(
                            streamKey = streamKey(stream),
                            channelLogin = channelLogin,
                            visibleFraction = visibleFraction,
                            centerProximity = centerProximity,
                        )
                    )
                }
            }
        }
        coordinator.updateViewport(viewportKey, candidates, isScrolling())
    }

    private fun isScrolling(): Boolean =
        scrollState != RecyclerView.SCROLL_STATE_IDLE || isParentScrolling()

    private fun streamKey(stream: Stream): String =
        stream.channelId?.takeIf { it.isNotBlank() }?.let { "channel:$it" }
            ?: stream.channelLogin?.trim()?.lowercase()?.takeIf { it.isNotBlank() }?.let { "login:$it" }
            ?: stream.id?.takeIf { it.isNotBlank() }?.let { "stream:$it" }
            ?: "unknown"
}
