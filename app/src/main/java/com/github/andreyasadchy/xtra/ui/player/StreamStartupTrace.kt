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

    fun tapToUrlAvailableMs(): Long? = urlAvailableElapsedMs?.minus(tappedAtMs)

    fun tapToStartStreamMs(): Long = streamStartElapsedMs - tappedAtMs

    fun markReady() {
        if (readyLogged || !BuildConfig.DEBUG) return
        readyLogged = true
        val now = SystemClock.elapsedRealtime()
        Log.d(
            "StreamStartup",
            "StreamStartup channel=$channelLogin url=${if (urlWarm) "warm" else "cold"} media=$mediaLabel mediaAgeMs=${mediaAgeMs ?: -1} " +
                "preview=$previewAlreadyPlaying tapSource=$tapSource tapToUrlAvailableMs=${tapToUrlAvailableMs() ?: -1} " +
                "tapToStartStreamMs=${tapToStartStreamMs()} tapToReadyMs=${now - tappedAtMs} " +
                "prepareToReadyMs=${prepareCalledAtMs?.let { now - it } ?: -1}",
        )
    }

    fun markFirstFrame() {
        if (firstFrameLogged || !BuildConfig.DEBUG) return
        firstFrameLogged = true
        val now = SystemClock.elapsedRealtime()
        Log.d(
            "StreamStartup",
            "StreamStartup channel=$channelLogin url=${if (urlWarm) "warm" else "cold"} media=$mediaLabel mediaAgeMs=${mediaAgeMs ?: -1} " +
                "preview=$previewAlreadyPlaying tapSource=$tapSource tapToUrlAvailableMs=${tapToUrlAvailableMs() ?: -1} " +
                "tapToStartStreamMs=${tapToStartStreamMs()} tapToFirstFrameMs=${now - tappedAtMs}",
        )
    }
}
