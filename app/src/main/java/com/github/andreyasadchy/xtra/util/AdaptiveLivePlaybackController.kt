package com.github.andreyasadchy.xtra.util

import android.util.Log
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Timeline
import androidx.media3.exoplayer.DefaultLivePlaybackSpeedControl
import androidx.media3.exoplayer.LoadControl
import androidx.media3.exoplayer.LivePlaybackSpeedControl
import androidx.media3.exoplayer.analytics.PlayerId
import androidx.media3.exoplayer.source.MediaSource.MediaPeriodId
import androidx.media3.exoplayer.source.TrackGroupArray
import androidx.media3.exoplayer.trackselection.ExoTrackSelection
import androidx.media3.exoplayer.upstream.Allocator
import androidx.media3.common.util.Util
import com.github.andreyasadchy.xtra.BuildConfig
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.max

/** Changes live buffering only after playback demonstrates that the current policy is too aggressive. */
class AdaptiveLivePlaybackController(
    initialPolicy: LivePlaybackPolicy,
    @Volatile private var adaptiveEnabled: Boolean = initialPolicy.lowLatency,
) {
    enum class State { NORMAL, LOW_LATENCY, BUFFERED, RECOVERING }

    private val policy = AtomicReference(initialPolicy)
    private var state = if (adaptiveEnabled && initialPolicy.lowLatency) State.LOW_LATENCY else State.NORMAL
    private var recentRebuffers = 0
    private var lastHandledRebufferRealtimeMs = Long.MIN_VALUE
    private var stableSinceRealtimeMs = Long.MIN_VALUE

    @Synchronized
    fun reset(initialPolicy: LivePlaybackPolicy, enabled: Boolean) {
        adaptiveEnabled = enabled
        state = if (enabled && initialPolicy.lowLatency) State.LOW_LATENCY else State.NORMAL
        recentRebuffers = 0
        lastHandledRebufferRealtimeMs = Long.MIN_VALUE
        stableSinceRealtimeMs = Long.MIN_VALUE
        policy.set(initialPolicy)
    }

    fun currentPolicy(): LivePlaybackPolicy = policy.get()

    @Synchronized
    fun onRebuffer(realtimeMs: Long): Boolean {
        if (!adaptiveEnabled || realtimeMs == lastHandledRebufferRealtimeMs) return false
        if (lastHandledRebufferRealtimeMs == Long.MIN_VALUE ||
            realtimeMs - lastHandledRebufferRealtimeMs > REBUFFER_WINDOW_MS
        ) {
            recentRebuffers = 0
        }
        recentRebuffers++
        lastHandledRebufferRealtimeMs = realtimeMs
        stableSinceRealtimeMs = Long.MIN_VALUE
        if (BuildConfig.DEBUG) {
            Log.d(TAG, "rebuffer state=$state count=$recentRebuffers realtimeMs=$realtimeMs")
        }
        val next = when {
            state == State.LOW_LATENCY -> State.BUFFERED
            state == State.BUFFERED && recentRebuffers >= 2 -> State.RECOVERING
            else -> state
        }
        return setState(next)
    }

    @Synchronized
    fun onStableSample(bufferedMs: Long, realtimeMs: Long): Boolean {
        if (!adaptiveEnabled || state == State.NORMAL) return false
        if (lastHandledRebufferRealtimeMs != Long.MIN_VALUE &&
            realtimeMs - lastHandledRebufferRealtimeMs < RECOVERY_COOLDOWN_MS
        ) return false
        if (bufferedMs < stableBufferFloorMs()) {
            stableSinceRealtimeMs = Long.MIN_VALUE
            return false
        }
        if (stableSinceRealtimeMs == Long.MIN_VALUE) stableSinceRealtimeMs = realtimeMs
        if (realtimeMs - stableSinceRealtimeMs < STABLE_PROMOTION_MS) return false
        return setState(
            when (state) {
                State.RECOVERING -> State.BUFFERED
                State.BUFFERED -> State.LOW_LATENCY
                else -> state
            },
        )
    }

    private fun stableBufferFloorMs(): Long = when (state) {
        State.RECOVERING -> 6_000L
        State.BUFFERED -> 4_000L
        else -> 1_000L
    }

    private fun setState(next: State): Boolean {
        if (state == next) return false
        val previous = state
        state = next
        stableSinceRealtimeMs = Long.MIN_VALUE
        policy.set(
            when (next) {
                State.NORMAL -> LivePlaybackPolicies.NORMAL
                State.LOW_LATENCY -> LivePlaybackPolicies.LOW_LATENCY
                State.BUFFERED -> LivePlaybackPolicies.BUFFERED
                State.RECOVERING -> LivePlaybackPolicies.RECOVERING
            },
        )
        if (BuildConfig.DEBUG) {
            val nextPolicy = policy.get()
            Log.d(
                TAG,
                "state $previous -> $next targetMs=${nextPolicy.targetOffsetMs}",
            )
        }
        return true
    }

    companion object {
        private const val TAG = "AdaptiveLivePlayback"
        private const val REBUFFER_WINDOW_MS = 60_000L
        private const val RECOVERY_COOLDOWN_MS = 30_000L
        private const val STABLE_PROMOTION_MS = 90_000L
    }
}

/** Keeps Xtra's existing non-live LoadControl behavior while adapting live thresholds. */
class AdaptiveLiveLoadControl(
    private val controller: AdaptiveLivePlaybackController,
    private val initialPolicy: LivePlaybackPolicy,
    private val onPolicyChanged: () -> Unit = {},
) : LoadControl {
    private val delegate = LivePlaybackPolicies.RECOVERING.buffers.buildLoadControl()
    private val loadingByPlayer = ConcurrentHashMap<PlayerId, Boolean>()

    override fun onPrepared(playerId: PlayerId) {
        delegate.onPrepared(playerId)
        loadingByPlayer[playerId] = false
    }

    override fun onTracksSelected(
        parameters: LoadControl.Parameters,
        trackGroups: TrackGroupArray,
        trackSelections: Array<out ExoTrackSelection?>,
    ) {
        delegate.onTracksSelected(parameters, trackGroups, trackSelections)
    }

    override fun onStopped(playerId: PlayerId) {
        delegate.onStopped(playerId)
        loadingByPlayer.remove(playerId)
    }

    override fun onReleased(playerId: PlayerId) {
        delegate.onReleased(playerId)
        loadingByPlayer.remove(playerId)
    }

    override fun getAllocator(playerId: PlayerId): Allocator = delegate.getAllocator(playerId)

    override fun getBackBufferDurationUs(playerId: PlayerId): Long = delegate.getBackBufferDurationUs(playerId)

    override fun retainBackBufferFromKeyframe(playerId: PlayerId): Boolean =
        delegate.retainBackBufferFromKeyframe(playerId)

    override fun shouldContinueLoading(parameters: LoadControl.Parameters): Boolean {
        val isLive = parameters.targetLiveOffsetUs != C.TIME_UNSET
        if (handleRebuffer(parameters)) onPolicyChanged()
        val buffers = if (isLive) controller.currentPolicy().buffers else initialPolicy.buffers
        val minBufferUs = if (parameters.playbackSpeed > 1f) {
            Util.getMediaDurationForPlayoutDuration(
                buffers.minBufferMs * 1_000L,
                parameters.playbackSpeed,
            ).coerceAtMost(buffers.maxBufferMs * 1_000L)
        } else buffers.minBufferMs * 1_000L
        val maxBufferUs = buffers.maxBufferMs * 1_000L
        val currentlyLoading = loadingByPlayer[parameters.playerId] ?: false
        val nextLoading = when {
            parameters.bufferedDurationUs < max(500_000L, minBufferUs) -> true
            parameters.bufferedDurationUs >= maxBufferUs -> false
            else -> currentlyLoading
        }
        loadingByPlayer[parameters.playerId] = nextLoading
        return nextLoading && delegate.shouldContinueLoading(parameters)
    }

    override fun shouldContinuePreloading(
        playerId: PlayerId,
        timeline: Timeline,
        mediaPeriodId: MediaPeriodId,
        bufferedDurationUs: Long,
    ): Boolean = loadingByPlayer.values.none { it } &&
        delegate.shouldContinuePreloading(playerId, timeline, mediaPeriodId, bufferedDurationUs)

    override fun shouldStartPlayback(parameters: LoadControl.Parameters): Boolean {
        if (handleRebuffer(parameters)) onPolicyChanged()
        val buffers = if (parameters.targetLiveOffsetUs != C.TIME_UNSET) {
            controller.currentPolicy().buffers
        } else {
            initialPolicy.buffers
        }
        val requiredUs = if (parameters.rebuffering) {
            buffers.bufferForPlaybackAfterRebufferMs * 1_000L
        } else buffers.bufferForPlaybackMs * 1_000L
        val playoutDurationUs = Util.getPlayoutDurationForMediaDuration(
            parameters.bufferedDurationUs,
            parameters.playbackSpeed,
        )
        val startupThresholdUs = if (parameters.targetLiveOffsetUs != C.TIME_UNSET) {
            minOf(requiredUs, parameters.targetLiveOffsetUs / 2)
        } else {
            requiredUs
        }
        return playoutDurationUs >= startupThresholdUs
    }

    private fun handleRebuffer(parameters: LoadControl.Parameters): Boolean =
        parameters.rebuffering &&
            parameters.targetLiveOffsetUs != C.TIME_UNSET &&
            parameters.lastRebufferRealtimeMs != C.TIME_UNSET &&
            controller.onRebuffer(parameters.lastRebufferRealtimeMs)
}

/** Keeps Media3's seek override separate from Xtra's adaptive target. */
class AdaptiveLivePlaybackSpeedControl(
    private val delegate: DefaultLivePlaybackSpeedControl,
) : LivePlaybackSpeedControl {
    private val adaptiveTargetOffsetUs = AtomicLong(C.TIME_UNSET)
    private val media3OverrideOffsetUs = AtomicLong(C.TIME_UNSET)
    private var appliedAdaptiveTargetOffsetUs = C.TIME_UNSET

    fun setAdaptiveTargetLiveOffsetUs(offsetUs: Long) {
        adaptiveTargetOffsetUs.set(offsetUs)
    }

    override fun setLiveConfiguration(liveConfiguration: MediaItem.LiveConfiguration) {
        delegate.setLiveConfiguration(liveConfiguration)
        applyAdaptiveTargetIfAllowed()
    }

    override fun setTargetLiveOffsetOverrideUs(liveOffsetUs: Long) {
        media3OverrideOffsetUs.set(liveOffsetUs)
        appliedAdaptiveTargetOffsetUs = C.TIME_UNSET
        delegate.setTargetLiveOffsetOverrideUs(liveOffsetUs)
        if (liveOffsetUs == C.TIME_UNSET) applyAdaptiveTargetIfAllowed()
    }

    override fun notifyRebuffer() = delegate.notifyRebuffer()

    override fun getAdjustedPlaybackSpeed(liveOffsetUs: Long, bufferedDurationUs: Long): Float {
        applyAdaptiveTargetIfAllowed()
        return delegate.getAdjustedPlaybackSpeed(liveOffsetUs, bufferedDurationUs)
    }

    override fun getTargetLiveOffsetUs(): Long {
        applyAdaptiveTargetIfAllowed()
        return delegate.getTargetLiveOffsetUs()
    }

    private fun applyAdaptiveTargetIfAllowed() {
        if (media3OverrideOffsetUs.get() != C.TIME_UNSET) return
        val targetUs = adaptiveTargetOffsetUs.get()
        if (targetUs != C.TIME_UNSET && targetUs != appliedAdaptiveTargetOffsetUs) {
            delegate.setTargetLiveOffsetOverrideUs(targetUs)
            appliedAdaptiveTargetOffsetUs = targetUs
            if (BuildConfig.DEBUG) {
                Log.d(TAG, "adaptiveTarget targetMs=${targetUs / 1_000L}")
            }
        }
    }

    private companion object {
        const val TAG = "AdaptiveLivePlayback"
    }
}
