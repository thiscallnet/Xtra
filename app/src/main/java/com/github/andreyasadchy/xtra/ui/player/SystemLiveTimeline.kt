package com.github.andreyasadchy.xtra.ui.player

import androidx.media3.common.C
import androidx.media3.common.Timeline
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.source.ForwardingTimeline
import kotlin.time.Instant

/** Presents one live HLS window on the recording's clock without changing decoder state. */
@UnstableApi
internal class SystemLiveTimeline(
    timeline: Timeline,
    vod: LiveRewindVod,
) : ForwardingTimeline(timeline) {
    private val sourceWindow = timeline.getWindow(0, Window())
    val offsetMs: Long =
        (sourceWindow.windowStartTimeMs - Instant.parse(vod.createdAt).toEpochMilliseconds()).coerceAtLeast(0L)
    private val durationMs = maxOf(vod.predictedDurationMs(), offsetMs + sourceWindow.durationMs.coerceAtLeast(0L))

    override fun getWindow(windowIndex: Int, window: Window, defaultPositionProjectionUs: Long): Window =
        super.getWindow(windowIndex, window, defaultPositionProjectionUs).apply {
            // Media3 suppresses platform positions and ACTION_SEEK_TO for live windows.
            liveConfiguration = null
            durationUs = this@SystemLiveTimeline.durationMs * 1_000L
            defaultPositionUs = (offsetMs * 1_000L + defaultPositionUs).coerceAtMost(durationUs)
            positionInFirstPeriodUs = 0L
            isSeekable = true
            if (windowStartTimeMs != C.TIME_UNSET) windowStartTimeMs -= offsetMs
        }

    override fun getPeriod(periodIndex: Int, period: Period, setIds: Boolean): Period =
        super.getPeriod(periodIndex, period, setIds).apply {
            durationUs = this@SystemLiveTimeline.durationMs * 1_000L
            positionInWindowUs = 0L
        }
}
