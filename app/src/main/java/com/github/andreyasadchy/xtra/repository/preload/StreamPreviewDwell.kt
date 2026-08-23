package com.github.andreyasadchy.xtra.repository.preload

object StreamPreviewDwellPolicy {
    fun startAt(existingStartMs: Long?, nowMs: Long, isScrolling: Boolean): Long? =
        if (isScrolling) null else existingStartMs ?: nowMs

    fun remainingDelay(startedAtMs: Long, nowMs: Long, delayMs: Long): Long =
        (delayMs - (nowMs - startedAtMs)).coerceAtLeast(0L)
}
