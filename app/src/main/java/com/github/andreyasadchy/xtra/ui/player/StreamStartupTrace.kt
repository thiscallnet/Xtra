package com.github.andreyasadchy.xtra.ui.player

import android.os.SystemClock
import android.util.Log
import com.github.andreyasadchy.xtra.BuildConfig

internal class StreamStartupTrace(
    val channelLogin: String,
    private val tappedAtMs: Long,
    val mediaLabel: String,
    private val mediaAgeMs: Long?,
    private val urlWarm: Boolean,
    private val previewAlreadyPlaying: Boolean,
    private val streamStartElapsedMs: Long,
    private val urlAvailableElapsedMs: Long?,
    val tapSource: String,
) {
    var prepareCalledAtMs: Long? = null
    private var readyLogged = false
    private var firstFrameLogged = false
    private var failureLogged = false
    private var stalledLogged = false
    private var prepareStartedLogged = false

    fun tapToUrlAvailableMs(): Long? = urlAvailableElapsedMs?.minus(tappedAtMs)

    fun tapToStartStreamMs(): Long = streamStartElapsedMs - tappedAtMs

    fun markPrepareStarted(): StreamStartupMilestone? {
        if (prepareStartedLogged) return null
        prepareStartedLogged = true
        return milestone(prepareCalledAtMs ?: SystemClock.elapsedRealtime())
    }

    fun markReady(): StreamStartupMilestone? {
        if (readyLogged) return null
        readyLogged = true
        val now = SystemClock.elapsedRealtime()
        if (BuildConfig.DEBUG) {
            Log.d(
                "StreamStartup",
                "StreamStartup channel=$channelLogin url=${if (urlWarm) "warm" else "cold"} media=$mediaLabel mediaAgeMs=${mediaAgeMs ?: -1} " +
                    "preview=$previewAlreadyPlaying tapSource=$tapSource tapToUrlAvailableMs=${tapToUrlAvailableMs() ?: -1} " +
                    "tapToStartStreamMs=${tapToStartStreamMs()} tapToReadyMs=${now - tappedAtMs} " +
                    "prepareToReadyMs=${prepareCalledAtMs?.let { now - it } ?: -1}",
            )
        }
        return milestone(now)
    }

    fun markFirstFrame(): StreamStartupMilestone? {
        if (firstFrameLogged) return null
        firstFrameLogged = true
        val now = SystemClock.elapsedRealtime()
        if (BuildConfig.DEBUG) {
            Log.d(
                "StreamStartup",
                "StreamStartup channel=$channelLogin url=${if (urlWarm) "warm" else "cold"} media=$mediaLabel mediaAgeMs=${mediaAgeMs ?: -1} " +
                    "preview=$previewAlreadyPlaying tapSource=$tapSource tapToUrlAvailableMs=${tapToUrlAvailableMs() ?: -1} " +
                    "tapToStartStreamMs=${tapToStartStreamMs()} tapToFirstFrameMs=${now - tappedAtMs}",
            )
        }
        return milestone(now)
    }

    fun markFailure(): StreamStartupMilestone? {
        if (firstFrameLogged || failureLogged) return null
        failureLogged = true
        return milestone(SystemClock.elapsedRealtime())
    }

    fun markStalled(): StreamStartupMilestone? {
        if (firstFrameLogged || failureLogged || stalledLogged) return null
        stalledLogged = true
        return milestone(SystemClock.elapsedRealtime())
    }

    private fun milestone(now: Long): StreamStartupMilestone = StreamStartupMilestone(
        tapToUrlAvailableMs = tapToUrlAvailableMs()?.takeIf { it >= 0L },
        tapToStartStreamMs = tapToStartStreamMs().coerceAtLeast(0L),
        tapToPrepareMs = prepareCalledAtMs?.minus(tappedAtMs)?.takeIf { it >= 0L },
        tapToMilestoneMs = (now - tappedAtMs).coerceAtLeast(0L),
        prepareToMilestoneMs = prepareCalledAtMs?.let { (now - it).coerceAtLeast(0L) },
        mediaLabel = mediaLabel,
        urlWarm = urlWarm,
        previewAlreadyPlaying = previewAlreadyPlaying,
        tapSource = tapSource,
    )
}

internal data class StreamStartupMilestone(
    val tapToUrlAvailableMs: Long?,
    val tapToStartStreamMs: Long,
    val tapToPrepareMs: Long?,
    val tapToMilestoneMs: Long,
    val prepareToMilestoneMs: Long?,
    val mediaLabel: String,
    val urlWarm: Boolean,
    val previewAlreadyPlaying: Boolean,
    val tapSource: String,
)
