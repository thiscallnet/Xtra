package com.github.andreyasadchy.xtra.util

import com.github.andreyasadchy.xtra.BuildConfig
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

internal data class ChatRenderDiagnosticsSnapshot(
    val binds: Long,
    val draws: Long,
    val animationInvalidations: Long,
    val animationStarts: Long,
    val animationStops: Long,
    val activeAnimations: Int,
    val publications: Long,
    val publicationMessages: Long,
    val maxPublicationMessages: Long,
    val messagesChanged: Long,
    val rowsCompiled: Long,
    val rowsReused: Long,
    val compileCurrentNanos: Long,
    val fullPresentationRebuilds: Long,
    val unchangedPublications: Long,
    val assetCallbacks: Long,
    val assetCallbacksCoalesced: Long,
    val drawOnlyInvalidations: Long,
    val layoutAndDrawInvalidations: Long,
    val staleCallbacksDiscarded: Long,
    val activeAssetObservers: Int,
    val inFlightAssetLoads: Int,
    val cachedAssetStates: Int,
    val retainedDecodedAssetHandles: Int,
    val clipCacheHits: Long,
    val clipStaleHits: Long,
    val clipMisses: Long,
    val clipRefreshes: Long,
    val clipRefreshUnchanged: Long,
    val clipRefreshChanged: Long,
    val clipEvictions: Long,
    val clipNegativeCacheHits: Long,
) {
    override fun toString(): String =
        "binds=$binds draws=$draws animationInvalidations=$animationInvalidations " +
            "animationStarts=$animationStarts animationStops=$animationStops " +
            "activeAnimations=$activeAnimations publications=$publications " +
            "publicationMessages=$publicationMessages maxPublicationMessages=$maxPublicationMessages " +
            "messagesChanged=$messagesChanged rowsCompiled=$rowsCompiled rowsReused=$rowsReused " +
            "compileCurrentNanos=$compileCurrentNanos fullPresentationRebuilds=$fullPresentationRebuilds " +
            "unchangedPublications=$unchangedPublications assetCallbacks=$assetCallbacks " +
            "assetCallbacksCoalesced=$assetCallbacksCoalesced drawOnlyInvalidations=$drawOnlyInvalidations " +
            "layoutAndDrawInvalidations=$layoutAndDrawInvalidations staleCallbacksDiscarded=$staleCallbacksDiscarded " +
            "activeAssetObservers=$activeAssetObservers inFlightAssetLoads=$inFlightAssetLoads " +
            "cachedAssetStates=$cachedAssetStates retainedDecodedAssetHandles=$retainedDecodedAssetHandles " +
            "clipCacheHits=$clipCacheHits clipStaleHits=$clipStaleHits " +
            "clipMisses=$clipMisses clipRefreshes=$clipRefreshes clipRefreshUnchanged=$clipRefreshUnchanged " +
            "clipRefreshChanged=$clipRefreshChanged clipEvictions=$clipEvictions " +
            "clipNegativeCacheHits=$clipNegativeCacheHits"
}

/** Per-report chat rendering counters for performance builds. */
internal object ChatRenderDiagnostics {
    private val binds = AtomicLong()
    private val draws = AtomicLong()
    private val animationInvalidations = AtomicLong()
    private val animationStarts = AtomicLong()
    private val animationStops = AtomicLong()
    private val activeAnimations = AtomicInteger()
    private val publications = AtomicLong()
    private val publicationMessages = AtomicLong()
    private val maxPublicationMessages = AtomicLong()
    private val messagesChanged = AtomicLong()
    private val rowsCompiled = AtomicLong()
    private val rowsReused = AtomicLong()
    private val compileCurrentNanos = AtomicLong()
    private val fullPresentationRebuilds = AtomicLong()
    private val unchangedPublications = AtomicLong()
    private val assetCallbacks = AtomicLong()
    private val assetCallbacksCoalesced = AtomicLong()
    private val drawOnlyInvalidations = AtomicLong()
    private val layoutAndDrawInvalidations = AtomicLong()
    private val staleCallbacksDiscarded = AtomicLong()
    private val activeAssetObservers = AtomicInteger()
    private val inFlightAssetLoads = AtomicInteger()
    private val cachedAssetStates = AtomicInteger()
    private val retainedDecodedAssetHandles = AtomicInteger()
    private val clipCacheHits = AtomicLong()
    private val clipStaleHits = AtomicLong()
    private val clipMisses = AtomicLong()
    private val clipRefreshes = AtomicLong()
    private val clipRefreshUnchanged = AtomicLong()
    private val clipRefreshChanged = AtomicLong()
    private val clipEvictions = AtomicLong()
    private val clipNegativeCacheHits = AtomicLong()

    fun recordBind() {
        if (BuildConfig.PERF_DIAGNOSTICS) binds.incrementAndGet()
    }

    fun recordDraw() {
        if (BuildConfig.PERF_DIAGNOSTICS) draws.incrementAndGet()
    }

    fun recordAnimationInvalidation() {
        if (BuildConfig.PERF_DIAGNOSTICS) animationInvalidations.incrementAndGet()
    }

    fun recordAnimationStarted() {
        if (BuildConfig.PERF_DIAGNOSTICS) {
            animationStarts.incrementAndGet()
            activeAnimations.incrementAndGet()
        }
    }

    fun recordAnimationStopped() {
        if (BuildConfig.PERF_DIAGNOSTICS) {
            animationStops.incrementAndGet()
            if (activeAnimations.get() > 0) activeAnimations.decrementAndGet()
        }
    }

    fun recordPublication(
        messageCount: Int,
        changed: Int,
        compiled: Int,
        reused: Int,
        compileNanos: Long,
        fullRebuild: Boolean,
        uiChanged: Boolean,
    ) {
        if (!BuildConfig.PERF_DIAGNOSTICS) return
        publications.incrementAndGet()
        publicationMessages.addAndGet(messageCount.toLong())
        maxPublicationMessages.accumulateAndGet(messageCount.toLong()) { current, value -> maxOf(current, value) }
        messagesChanged.addAndGet(changed.toLong())
        rowsCompiled.addAndGet(compiled.toLong())
        rowsReused.addAndGet(reused.toLong())
        compileCurrentNanos.addAndGet(compileNanos)
        if (fullRebuild) fullPresentationRebuilds.incrementAndGet()
        if (!uiChanged) unchangedPublications.incrementAndGet()
    }

    fun recordAssetCallbackReceived() {
        if (BuildConfig.PERF_DIAGNOSTICS) assetCallbacks.incrementAndGet()
    }

    fun recordAssetCallbackCoalesced() {
        if (BuildConfig.PERF_DIAGNOSTICS) assetCallbacksCoalesced.incrementAndGet()
    }

    fun recordDrawOnlyInvalidation() {
        if (BuildConfig.PERF_DIAGNOSTICS) drawOnlyInvalidations.incrementAndGet()
    }

    fun recordLayoutAndDrawInvalidation() {
        if (BuildConfig.PERF_DIAGNOSTICS) layoutAndDrawInvalidations.incrementAndGet()
    }

    fun recordStaleCallbackDiscarded() {
        if (BuildConfig.PERF_DIAGNOSTICS) staleCallbacksDiscarded.incrementAndGet()
    }

    fun setAssetRepositoryState(
        activeObservers: Int,
        inFlightLoads: Int,
        cachedStates: Int,
        decodedHandles: Int,
    ) {
        if (!BuildConfig.PERF_DIAGNOSTICS) return
        activeAssetObservers.set(activeObservers)
        inFlightAssetLoads.set(inFlightLoads)
        cachedAssetStates.set(cachedStates)
        retainedDecodedAssetHandles.set(decodedHandles)
    }

    fun recordClipCacheHit(stale: Boolean, negative: Boolean) {
        if (!BuildConfig.PERF_DIAGNOSTICS) return
        if (stale) clipStaleHits.incrementAndGet() else clipCacheHits.incrementAndGet()
        if (negative) clipNegativeCacheHits.incrementAndGet()
    }

    fun recordClipCacheMiss() {
        if (BuildConfig.PERF_DIAGNOSTICS) clipMisses.incrementAndGet()
    }

    fun recordClipRefresh() {
        if (BuildConfig.PERF_DIAGNOSTICS) clipRefreshes.incrementAndGet()
    }

    fun recordClipRefreshUnchanged() {
        if (BuildConfig.PERF_DIAGNOSTICS) clipRefreshUnchanged.incrementAndGet()
    }

    fun recordClipRefreshChanged() {
        if (BuildConfig.PERF_DIAGNOSTICS) clipRefreshChanged.incrementAndGet()
    }

    fun recordClipEviction() {
        if (BuildConfig.PERF_DIAGNOSTICS) clipEvictions.incrementAndGet()
    }

    fun snapshotAndReset(): ChatRenderDiagnosticsSnapshot = ChatRenderDiagnosticsSnapshot(
        binds = binds.getAndSet(0),
        draws = draws.getAndSet(0),
        animationInvalidations = animationInvalidations.getAndSet(0),
        animationStarts = animationStarts.getAndSet(0),
        animationStops = animationStops.getAndSet(0),
        activeAnimations = activeAnimations.get(),
        publications = publications.getAndSet(0),
        publicationMessages = publicationMessages.getAndSet(0),
        maxPublicationMessages = maxPublicationMessages.getAndSet(0),
        messagesChanged = messagesChanged.getAndSet(0),
        rowsCompiled = rowsCompiled.getAndSet(0),
        rowsReused = rowsReused.getAndSet(0),
        compileCurrentNanos = compileCurrentNanos.getAndSet(0),
        fullPresentationRebuilds = fullPresentationRebuilds.getAndSet(0),
        unchangedPublications = unchangedPublications.getAndSet(0),
        assetCallbacks = assetCallbacks.getAndSet(0),
        assetCallbacksCoalesced = assetCallbacksCoalesced.getAndSet(0),
        drawOnlyInvalidations = drawOnlyInvalidations.getAndSet(0),
        layoutAndDrawInvalidations = layoutAndDrawInvalidations.getAndSet(0),
        staleCallbacksDiscarded = staleCallbacksDiscarded.getAndSet(0),
        activeAssetObservers = activeAssetObservers.get(),
        inFlightAssetLoads = inFlightAssetLoads.get(),
        cachedAssetStates = cachedAssetStates.get(),
        retainedDecodedAssetHandles = retainedDecodedAssetHandles.get(),
        clipCacheHits = clipCacheHits.getAndSet(0),
        clipStaleHits = clipStaleHits.getAndSet(0),
        clipMisses = clipMisses.getAndSet(0),
        clipRefreshes = clipRefreshes.getAndSet(0),
        clipRefreshUnchanged = clipRefreshUnchanged.getAndSet(0),
        clipRefreshChanged = clipRefreshChanged.getAndSet(0),
        clipEvictions = clipEvictions.getAndSet(0),
        clipNegativeCacheHits = clipNegativeCacheHits.getAndSet(0),
    )
}
