package com.github.andreyasadchy.xtra.ui.common

import android.graphics.Rect
import android.view.View
import android.view.ViewTreeObserver
import android.widget.FrameLayout
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.github.andreyasadchy.xtra.R
import com.github.andreyasadchy.xtra.model.ui.Stream
import com.github.andreyasadchy.xtra.repository.preload.StreamPreloadCandidate
import com.github.andreyasadchy.xtra.repository.preload.StreamPreloadCoordinator
import kotlin.math.abs
import kotlin.math.max

/** Reports actual visible stream cards without doing any network work itself. */
class StreamPreloadViewportController(
    private val fragment: Fragment,
    private val coordinator: StreamPreloadCoordinator?,
    private val viewportKey: String,
    private val recyclerView: RecyclerView,
    private val streamAtPosition: ((Int) -> Stream?)? = null,
    private val isParentScrolling: () -> Boolean = { false },
    private val previewAtPosition: ((Int, FrameLayout) -> StreamPreviewCandidate?)? = null,
) {
    private var scrollState = RecyclerView.SCROLL_STATE_IDLE
    private var scrollingReported = false
    private var started = false
    private var publishPosted = false
    private val publishRunnable = Runnable {
        publishPosted = false
        publish()
    }
    private val scrollListener = object : RecyclerView.OnScrollListener() {
        override fun onScrollStateChanged(recyclerView: RecyclerView, newState: Int) {
            scrollState = newState
            updateScrollingState()
            if (!isScrolling()) requestPublish()
        }

        override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
            if (!isScrolling()) requestPublish()
        }
    }
    private val scrollChangedListener = ViewTreeObserver.OnScrollChangedListener { requestPublish() }
    private val layoutChangeListener = View.OnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> requestPublish() }
    private val attachStateListener = object : View.OnAttachStateChangeListener {
        override fun onViewAttachedToWindow(view: View) {
            requestPublish()
        }

        override fun onViewDetachedFromWindow(view: View) {
            coordinator?.detachViewport(viewportKey)
            previewCoordinator.detachViewport(viewportKey)
        }
    }

    fun start() {
        if (started) return
        started = true
        recyclerView.addOnScrollListener(scrollListener)
        recyclerView.addOnLayoutChangeListener(layoutChangeListener)
        recyclerView.addOnAttachStateChangeListener(attachStateListener)
        recyclerView.viewTreeObserver.addOnScrollChangedListener(scrollChangedListener)
        updateScrollingState()
        requestPublish()
    }

    fun stop() {
        if (!started) return
        started = false
        recyclerView.removeOnScrollListener(scrollListener)
        recyclerView.removeOnLayoutChangeListener(layoutChangeListener)
        recyclerView.removeOnAttachStateChangeListener(attachStateListener)
        if (recyclerView.viewTreeObserver.isAlive) {
            recyclerView.viewTreeObserver.removeOnScrollChangedListener(scrollChangedListener)
        }
        recyclerView.removeCallbacks(publishRunnable)
        publishPosted = false
        scrollingReported = false
        coordinator?.detachViewport(viewportKey)
        previewCoordinator.detachViewport(viewportKey)
    }

    fun onResume() {
        updateScrollingState()
        requestPublish()
    }

    fun onPause() {
        coordinator?.detachViewport(viewportKey)
        previewCoordinator.detachViewport(viewportKey)
    }

    fun onParentScrollStateChanged() {
        updateScrollingState()
        requestPublish()
    }

    private fun requestPublish() {
        if (!started) return
        updateScrollingState()
        if (isScrolling()) return
        if (publishPosted) return
        publishPosted = true
        recyclerView.postOnAnimation(publishRunnable)
    }

    private fun publish() {
        if (!started) return
        updateScrollingState()
        if (isScrolling()) return
        if (!fragment.isAdded || fragment.view == null ||
            !fragment.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED) ||
            !recyclerView.isAttachedToWindow
        ) {
            coordinator?.detachViewport(viewportKey)
            previewCoordinator.detachViewport(viewportKey)
            return
        }
        val viewportRect = Rect()
        if (!recyclerView.getGlobalVisibleRect(viewportRect) || viewportRect.width() <= 0 || viewportRect.height() <= 0) {
            coordinator?.updateViewport(viewportKey, emptyList(), scrolling = isScrolling())
            previewCoordinator.updateViewport(viewportKey, emptyList(), scrolling = isScrolling())
            return
        }
        val horizontal = (recyclerView.layoutManager as? LinearLayoutManager)?.orientation == RecyclerView.HORIZONTAL
        val viewportCenter = if (horizontal) viewportRect.centerX() else viewportRect.centerY()
        val viewportSize = if (horizontal) viewportRect.width() else viewportRect.height()
        val visibleCards = buildList {
            repeat(recyclerView.childCount) { childIndex ->
                val child = recyclerView.getChildAt(childIndex)
                val position = recyclerView.getChildAdapterPosition(child)
                if (position == RecyclerView.NO_POSITION) return@repeat
                val stream = streamAtPosition?.invoke(position)
                if (stream == null && previewAtPosition == null) return@repeat
                val childRect = Rect()
                if (!child.getGlobalVisibleRect(childRect)) return@repeat
                val fullArea = (child.width.toLong() * child.height.toLong()).coerceAtLeast(1L)
                val visibleArea = childRect.width().toLong() * childRect.height().toLong()
                val visibleFraction = (visibleArea.toDouble() / fullArea).toFloat().coerceIn(0f, 1f)
                if (visibleFraction <= 0f) return@repeat
                val childCenter = if (horizontal) childRect.centerX() else childRect.centerY()
                val halfSpan = max(1, viewportSize / 2 + if (horizontal) child.width else child.height)
                val centerProximity = 1f - (abs(childCenter - viewportCenter).toFloat() / halfSpan).coerceIn(0f, 1f)
                val surface = child.findViewById<FrameLayout>(R.id.previewHost)
                val previewCandidate = surface?.let {
                    if (previewAtPosition != null) {
                        previewAtPosition.invoke(position, it)
                    } else {
                        stream?.let { currentStream ->
                            val channelLogin = currentStream.channelLogin?.trim().orEmpty()
                            channelLogin.takeIf { it.isNotEmpty() }?.let { login ->
                                StreamPreviewCandidate(
                                    streamKey = streamKey(currentStream),
                                    channelLogin = login,
                                    visibleFraction = visibleFraction,
                                    centerProximity = centerProximity,
                                    title = currentStream.title,
                                    channelName = currentStream.channelName,
                                    channelLogo = currentStream.channelImage,
                                    surface = it,
                                )
                            }
                        }
                    }
                }?.copy(
                    visibleFraction = visibleFraction,
                    centerProximity = centerProximity,
                    surface = surface,
                )
                add(VisibleCard(stream, visibleFraction, centerProximity, previewCandidate))
            }
        }
        coordinator?.updateViewport(
            viewportKey,
            visibleCards.mapNotNull { card ->
                val stream = card.stream ?: return@mapNotNull null
                val channelLogin = stream.channelLogin?.trim().orEmpty()
                channelLogin.takeIf { it.isNotEmpty() }?.let { login ->
                    StreamPreloadCandidate(
                        streamKey = streamKey(stream),
                        channelLogin = login,
                        visibleFraction = card.visibleFraction,
                        centerProximity = card.centerProximity,
                        title = stream.title,
                        channelName = stream.channelName,
                        channelLogo = stream.channelImage,
                    )
                }
            },
            scrolling = isScrolling(),
        )
        previewCoordinator.updateViewport(
            viewportKey,
            visibleCards.mapNotNull { it.previewCandidate },
            scrolling = isScrolling(),
        )
    }

    private data class VisibleCard(
        val stream: Stream?,
        val visibleFraction: Float,
        val centerProximity: Float,
        val previewCandidate: StreamPreviewCandidate?,
    )

    private fun isScrolling(): Boolean =
        scrollState != RecyclerView.SCROLL_STATE_IDLE || isParentScrolling()

    private fun updateScrollingState() {
        val scrolling = isScrolling()
        if (scrolling == scrollingReported) return
        scrollingReported = scrolling
        if (scrolling) {
            coordinator?.setViewportScrolling(viewportKey, true)
            previewCoordinator.onScrolling(viewportKey)
        }
    }

    private fun streamKey(stream: Stream): String =
        stream.channelId?.takeIf { it.isNotBlank() }?.let { "channel:$it" }
            ?: stream.channelLogin?.trim()?.lowercase()?.takeIf { it.isNotBlank() }?.let { "login:$it" }
            ?: stream.id?.takeIf { it.isNotBlank() }?.let { "stream:$it" }
            ?: "unknown"

    private val previewCoordinator: StreamPreviewCoordinator by lazy {
        (fragment.requireContext().applicationContext as com.github.andreyasadchy.xtra.XtraApp)
            .xtraModule.streamPreviewCoordinator
    }
}
