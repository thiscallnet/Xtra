package com.github.andreyasadchy.xtra.util.watch

import android.os.SystemClock
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

class WatchTelemetryReporter(
    private val scope: CoroutineScope,
    private val sendMinuteWatched: suspend (TwitchWatchSession) -> Boolean,
    private val isAuthenticated: () -> Boolean = { true },
    private val elapsedRealtime: () -> Long = SystemClock::elapsedRealtime,
    private val log: (String) -> Unit = {},
) {
    private var job: Job? = null
    private var session: TwitchWatchSession? = null

    @Volatile
    private var actuallyPlaying = false

    fun start(newSession: TwitchWatchSession) {
        if (
            session?.channelId == newSession.channelId &&
            session?.streamId == newSession.streamId &&
            session?.userId == newSession.userId &&
            job?.isActive == true
        ) {
            return
        }

        stop()
        session = newSession
        log(
            "WatchSession created channel=${newSession.channelId} " +
                "stream=${newSession.streamId ?: "null"} session=${shortSessionId(newSession.sessionId)}",
        )

        job = scope.launch {
            var watchedMs = 0L
            var previous = elapsedRealtime()
            var minuteCount = 0

            while (isActive) {
                delay(TICK_MS)
                val now = elapsedRealtime()
                val elapsed = (now - previous).coerceAtLeast(0L)
                previous = now

                if (!actuallyPlaying) {
                    continue
                }

                watchedMs += elapsed
                while (watchedMs >= MINUTE_MS) {
                    watchedMs -= MINUTE_MS
                    val current = session ?: break
                    if (!isAuthenticated()) {
                        log("WatchSession telemetry skipped: not authenticated")
                        continue
                    }
                    try {
                        if (sendMinuteWatched(current)) {
                            minuteCount++
                            log("WatchSession minute watched #$minuteCount")
                        } else {
                            log("WatchSession minute send failed; waiting for next scheduled minute")
                        }
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        log("WatchSession telemetry failed error=${e.javaClass.simpleName}")
                    }
                }
            }
        }
    }

    fun setActuallyPlaying(value: Boolean) {
        if (actuallyPlaying != value) {
            actuallyPlaying = value
            log("WatchSession playback active=$value")
        }
    }

    fun stop() {
        job?.cancel()
        job = null
        session = null
        actuallyPlaying = false
        log("WatchSession stopped")
    }

    private fun shortSessionId(value: String): String = value.take(8)

    companion object {
        const val LOG_TAG = "XtraWatchCredit"
        private const val TICK_MS = 1_000L
        private const val MINUTE_MS = 60_000L
    }
}
