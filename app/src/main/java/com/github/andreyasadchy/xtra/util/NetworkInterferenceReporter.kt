package com.github.andreyasadchy.xtra.util

import android.os.SystemClock
import java.net.UnknownHostException
import java.util.ArrayDeque
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/**
 * Reports repeated DNS failures for Twitch requests without claiming that a specific blocker was
 * identified. A failed lookup can also be caused by a VPN, proxy, or an ordinary network outage.
 */
object NetworkInterferenceReporter {
    data class PotentialDnsFailure(val host: String)

    private val state = NetworkInterferenceState()
    private val _events = MutableSharedFlow<PotentialDnsFailure>(
        extraBufferCapacity = 1,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )

    val events = _events.asSharedFlow()

    fun report(host: String?, error: Throwable) {
        if (!isDnsResolutionFailure(error)) return

        if (state.recordFailure(SystemClock.elapsedRealtime())) {
            _events.tryEmit(PotentialDnsFailure(host.orEmpty()))
        }
    }

    /** Returns true only when a warning may be shown to the user now. */
    fun tryAcquireWarningPermit(): Boolean =
        state.tryAcquireWarningPermit(SystemClock.elapsedRealtime())

    internal fun isDnsResolutionFailure(error: Throwable): Boolean {
        val seen = HashSet<Throwable>()
        var current: Throwable? = error
        while (current != null && seen.add(current)) {
            if (current is UnknownHostException) return true

            val message = current.message.orEmpty()
            if (DNS_FAILURE_MARKERS.any { message.contains(it, ignoreCase = true) }) return true
            current = current.cause
        }
        return false
    }

    private val DNS_FAILURE_MARKERS = arrayOf(
        "ERR_DNS_TIMED_OUT",
        "ERR_NAME_NOT_RESOLVED",
        "NAME_NOT_RESOLVED",
        "UNKNOWN_HOST",
        "unable to resolve host",
        "nodename nor servname provided",
    )
}

internal class NetworkInterferenceState(
    private val failureWindowMillis: Long = 90_000L,
    private val warningCooldownMillis: Long = 15 * 60 * 1_000L,
) {
    private companion object {
        const val MIN_FAILURES = 2
    }

    private val lock = Any()
    private val recentFailures = ArrayDeque<Long>()
    private var lastWarningElapsedMillis: Long? = null

    fun recordFailure(nowElapsedMillis: Long): Boolean = synchronized(lock) {
        while (recentFailures.peekFirst()?.let { nowElapsedMillis - it > failureWindowMillis } == true) {
            recentFailures.removeFirst()
        }
        recentFailures.addLast(nowElapsedMillis)
        recentFailures.size >= MIN_FAILURES
    }

    fun tryAcquireWarningPermit(nowElapsedMillis: Long): Boolean = synchronized(lock) {
        val lastWarning = lastWarningElapsedMillis
        if (lastWarning != null && nowElapsedMillis - lastWarning < warningCooldownMillis) {
            false
        } else {
            lastWarningElapsedMillis = nowElapsedMillis
            true
        }
    }
}
