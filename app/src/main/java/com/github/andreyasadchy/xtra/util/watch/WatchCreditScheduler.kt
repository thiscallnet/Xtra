package com.github.andreyasadchy.xtra.util.watch

import android.os.SystemClock
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.selects.onTimeout
import kotlin.random.Random

private const val WATCH_CREDIT_MINUTE_INTERVAL_MILLIS = 60_000L

internal data class WatchCreditScheduleInput(
    val session: WatchCreditSession?,
    val liveEligible: Boolean,
    val dropsStreamLive: Boolean?,
    val isPlaying: Boolean,
    val isBuffering: Boolean,
    val game: String? = null,
    val gameId: String? = null,
)

/** Tracks cumulative watch minutes against actively playing time for one playback session. */
internal class WatchCreditClock(
    private val nextSecondsOffset: () -> Int = { Random.nextInt(60) },
    private val repeatIntervalMillis: Long = WATCH_CREDIT_MINUTE_INTERVAL_MILLIS,
) {
    var session: WatchCreditSession? = null
        private set
    var secondsOffset: Int? = null
        private set
    var minutesLogged: Int = 0
        private set
    private var remainingActiveMillis = 0L
    private var activeSinceElapsedMillis: Long? = null

    init {
        require(repeatIntervalMillis > 0L)
    }

    fun update(input: WatchCreditScheduleInput, nowElapsedMillis: Long) {
        val nextSession = input.session.takeIf { input.liveEligible && input.dropsStreamLive == true }
        if (nextSession != session) {
            reset()
            if (nextSession != null) {
                session = nextSession
                secondsOffset = nextSecondsOffset().coerceIn(0, 59)
                remainingActiveMillis = secondsOffset!!.toLong() * 1_000L
            }
        }

        val shouldRun = session != null && input.isPlaying && !input.isBuffering &&
                input.liveEligible && input.dropsStreamLive == true
        val activeSince = activeSinceElapsedMillis
        if (activeSince != null && !shouldRun) {
            remainingActiveMillis = (remainingActiveMillis - (nowElapsedMillis - activeSince))
                .coerceAtLeast(0L)
            activeSinceElapsedMillis = null
        } else if (activeSince == null && shouldRun) {
            activeSinceElapsedMillis = nowElapsedMillis
        }
    }

    fun remainingActiveMillis(nowElapsedMillis: Long): Long? {
        val activeSince = activeSinceElapsedMillis ?: return null
        return (remainingActiveMillis - (nowElapsedMillis - activeSince)).coerceAtLeast(0L)
    }

    fun takeMinuteIfDue(nowElapsedMillis: Long): WatchCreditMinute? {
        if (remainingActiveMillis(nowElapsedMillis) != 0L) return null
        val offset = secondsOffset ?: return null
        minutesLogged += 1
        remainingActiveMillis = repeatIntervalMillis
        activeSinceElapsedMillis = nowElapsedMillis
        return WatchCreditMinute(minutesLogged, offset)
    }

    fun reset() {
        session = null
        secondsOffset = null
        minutesLogged = 0
        remainingActiveMillis = 0L
        activeSinceElapsedMillis = null
    }
}

/** Runs the watch-credit clock independently of Hermes connection lifetimes. */
@OptIn(ExperimentalCoroutinesApi::class)
internal class WatchCreditScheduler(
    private val scope: CoroutineScope,
    private val input: StateFlow<WatchCreditScheduleInput>,
    private val onMinuteWatched: suspend (
        session: WatchCreditSession,
        minute: WatchCreditMinute,
        game: String?,
        gameId: String?,
    ) -> Unit,
    nextSecondsOffset: () -> Int = { Random.nextInt(60) },
    private val elapsedRealtime: () -> Long = SystemClock::elapsedRealtime,
    repeatIntervalMillis: Long = WATCH_CREDIT_MINUTE_INTERVAL_MILLIS,
) {
    private val clock = WatchCreditClock(nextSecondsOffset, repeatIntervalMillis)

    fun start(): Job = scope.launch(Dispatchers.IO) {
        val pendingMinutes = Channel<ScheduledWatchCreditMinute>(Channel.UNLIMITED)
        launch(Dispatchers.IO) {
            for (scheduled in pendingMinutes) {
                try {
                    onMinuteWatched(
                        scheduled.session,
                        scheduled.minute,
                        scheduled.game,
                        scheduled.gameId,
                    )
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    android.util.Log.e(
                        WatchCreditTelemetry.LOG_TAG,
                        "watch heartbeat request failed minutes=${scheduled.minute.minutesLogged}",
                        e,
                    )
                }
            }
        }
        try {
            while (isActive) {
                val current = input.value
                val now = elapsedRealtime()
                clock.update(current, now)
                val remaining = clock.remainingActiveMillis(now)
                if (remaining == null) {
                    input.first { it != current }
                    continue
                }

                val inputChange = async { input.first { it != current } }
                val deadlineReached = select {
                    onTimeout(remaining) { true }
                    inputChange.onAwait { false }
                }
                if (!deadlineReached) continue
                inputChange.cancelAndJoin()

                val deadlineTime = elapsedRealtime()
                clock.update(input.value, deadlineTime)
                val minute = clock.takeMinuteIfDue(deadlineTime) ?: continue
                val session = clock.session ?: continue
                val latest = input.value
                pendingMinutes.send(
                    ScheduledWatchCreditMinute(
                        session = session,
                        minute = minute,
                        game = latest.game,
                        gameId = latest.gameId,
                    ),
                )
            }
        } finally {
            pendingMinutes.close()
        }
    }

    private data class ScheduledWatchCreditMinute(
        val session: WatchCreditSession,
        val minute: WatchCreditMinute,
        val game: String?,
        val gameId: String?,
    )
}
