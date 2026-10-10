package com.github.andreyasadchy.xtra.ui.following.overview

import com.github.andreyasadchy.xtra.model.ChannelStreamStart
import java.time.Instant
import java.time.ZoneId
import kotlin.math.roundToInt

internal data class ExpectedStart(
    val channelId: String,
    val expectedAtMs: Long,
)

private const val MINUTES_PER_DAY = 24 * 60
private const val WEEK_MINUTES = 7 * MINUTES_PER_DAY
private const val WEEK_MS = 7 * 24 * 60 * 60_000L
private const val CLUSTER_TOLERANCE_MINUTES = 60
private const val MIN_REGULAR_WEEKS = 3

/**
 * Predicts the next start for each channel that repeats a weekly time slot.
 *
 * A slot counts when starts within [CLUSTER_TOLERANCE_MINUTES] of the same time of week
 * appear in at least [MIN_REGULAR_WEEKS] different weeks. Only predictions whose slot
 * begins within [horizonMs] are returned. Live channels are excluded.
 */
internal fun predictExpectedStarts(
    starts: List<ChannelStreamStart>,
    liveChannelIds: Set<String>,
    nowMs: Long,
    horizonMs: Long,
    zone: ZoneId,
): List<ExpectedStart> {
    return starts
        .groupBy { it.channelId }
        .mapNotNull { (channelId, channelStarts) ->
            if (channelId in liveChannelIds) return@mapNotNull null
            val slot = nextRegularSlotMs(channelStarts.map { it.startedAt }, nowMs, zone) ?: return@mapNotNull null
            if (slot - nowMs > horizonMs) null else ExpectedStart(channelId, slot)
        }
        .sortedBy { it.expectedAtMs }
}

private class Slot(val center: Int, val weeks: Set<Long>, val size: Int)

private fun nextRegularSlotMs(startsMs: List<Long>, nowMs: Long, zone: ZoneId): Long? {
    val samples = startsMs.map { it to weekMinute(it, zone) }
    var best: Slot? = null
    for ((_, center) in samples) {
        val members = samples.filter { (_, minute) -> circularDistance(minute, center) <= CLUSTER_TOLERANCE_MINUTES }
        val weeks = members.mapTo(hashSetOf()) { (ms, _) -> ms / WEEK_MS }
        if (weeks.size < MIN_REGULAR_WEEKS) continue
        val current = best
        if (current == null || weeks.size > current.weeks.size ||
            (weeks.size == current.weeks.size && members.size > current.size)
        ) {
            val offset = members.map { (_, minute) -> signedDistance(center, minute) }.average().roundToInt()
            best = Slot(Math.floorMod(center + offset, WEEK_MINUTES), weeks, members.size)
        }
    }
    val slot = best ?: return null
    val minutesUntilSlot = Math.floorMod(slot.center - weekMinute(nowMs, zone), WEEK_MINUTES)
    return nowMs + minutesUntilSlot * 60_000L
}

private fun weekMinute(epochMs: Long, zone: ZoneId): Int {
    val time = Instant.ofEpochMilli(epochMs).atZone(zone)
    return (time.dayOfWeek.value - 1) * MINUTES_PER_DAY + time.hour * 60 + time.minute
}

private fun circularDistance(a: Int, b: Int): Int = kotlin.math.abs(signedDistance(a, b))

private fun signedDistance(from: Int, to: Int): Int {
    return Math.floorMod(to - from + WEEK_MINUTES / 2, WEEK_MINUTES) - WEEK_MINUTES / 2
}
