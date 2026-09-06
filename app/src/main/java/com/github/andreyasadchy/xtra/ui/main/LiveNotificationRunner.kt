package com.github.andreyasadchy.xtra.ui.main

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.util.Log
import androidx.core.content.edit
import com.github.andreyasadchy.xtra.BuildConfig
import com.github.andreyasadchy.xtra.XtraApp
import com.github.andreyasadchy.xtra.repository.HelixRateLimit
import com.github.andreyasadchy.xtra.repository.TwitchApiException
import com.github.andreyasadchy.xtra.util.C
import com.github.andreyasadchy.xtra.util.TwitchApiHelper
import com.github.andreyasadchy.xtra.util.prefs
import com.github.andreyasadchy.xtra.util.sanitizeLiveNotificationTechnicalMessage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.max

internal suspend fun awaitLiveNotificationRetry(
    retryDelayMs: Long,
    interruptible: Boolean,
    wakeSignal: ReceiveChannel<Unit>,
) {
    if (interruptible) {
        withTimeoutOrNull(retryDelayMs) {
            wakeSignal.receiveCatching()
        }
    } else {
        delay(retryDelayMs)
        // A non-interruptible rate-limit wait must not leave the deferred
        // wake token queued for the normal wait after the recovery poll.
        wakeSignal.tryReceive()
    }
}

internal fun isLiveNotificationRetryInterruptible(error: TwitchApiException): Boolean =
    error.statusCode != 429 && error.rateLimitResetEpochSeconds == null

internal class LiveNotificationWakeController {
    val signal = Channel<Unit>(Channel.CONFLATED)
    private val pendingReason = AtomicReference<String?>(null)

    fun request(isRunnerActive: Boolean, reason: String): Boolean {
        if (!isRunnerActive) return false
        pendingReason.set(reason)
        signal.trySend(Unit)
        return true
    }

    fun consumeReason(): String? = pendingReason.getAndSet(null)
}

/**
 * Shared Twitch monitoring runner used by Fast mode and Persistent real-time.
 * The owner controls Android process lifetime; this class owns EventSub,
 * Helix reconciliation, and durable notification delivery exactly once.
 */
class LiveNotificationRunner(
    context: Context,
    private val shouldContinue: () -> Boolean = { true },
    private val onMonitoringStopped: (() -> Unit)? = null,
) {

    private val applicationContext = context.applicationContext
    private val xtraApp = applicationContext as XtraApp
    private val module = xtraApp.xtraModule
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val wakeController = LiveNotificationWakeController()
    private val networkAvailable = AtomicBoolean(false)
    private val nextDelayMs = AtomicLong(PARTIAL_EVENTSUB_RECONCILE_INTERVAL_MS)
    private val eventSubStarted = AtomicBoolean(false)
    private val networkCallbackRegistered = AtomicBoolean(false)
    private val monitor = LiveNotificationMonitor(applicationContext)
    private val eventSub = LiveNotificationEventSub(
        okHttpClient = module.okHttpClient,
        helixRepository = module.helixRepository,
        networkLibrary = { applicationContext.prefs().getString(C.NETWORK_LIBRARY, C.OKHTTP) },
        helixHeaders = { TwitchApiHelper.getHelixHeaders(applicationContext) },
        channelIds = { module.notificationsRepository.getNotificationUserIds() },
        scope = scope,
        onStreamOnline = {
            requestImmediateReconciliation("eventsub_online")
        },
        onRevocation = { revocation ->
            recordRevocation(revocation)
            requestImmediateReconciliation("eventsub_revocation")
        },
        onReconnected = {
            requestImmediateReconciliation("eventsub_reconnect")
        },
    )
    private enum class State { NEW, RUNNING, STOPPED }
    private val lifecycleLock = Any()
    private var state = State.NEW
    private var monitorJob: Job? = null
    private var networkCallback: ConnectivityManager.NetworkCallback? = null

    fun start() {
        synchronized(lifecycleLock) {
            when (state) {
                State.STOPPED -> return
                State.RUNNING -> {
                    if (monitorJob?.isActive == true) {
                        wakeController.signal.trySend(Unit)
                        return
                    }
                    unregisterNetworkCallback()
                    state = State.NEW
                }
                State.NEW -> Unit
            }
            state = State.RUNNING
            networkAvailable.set(hasValidatedNetwork())
            registerNetworkCallback()
            monitorJob = scope.launch { monitorLoop() }
        }
    }

    fun stop() {
        synchronized(lifecycleLock) {
            if (state == State.STOPPED) {
                return
            }
            state = State.STOPPED
            unregisterNetworkCallback()
            monitorJob?.cancel()
            monitorJob = null
            eventSub.shutdown()
            wakeController.signal.close()
            scope.cancel()
        }
    }

    internal fun isRunning(): Boolean = synchronized(lifecycleLock) {
        state == State.RUNNING && monitorJob?.isActive == true
    }

    internal fun isHealthy(): Boolean = synchronized(lifecycleLock) {
        liveNotificationOwnerIsHealthy(
            runnerRunning = state == State.RUNNING && monitorJob?.isActive == true,
            networkWakeAvailable = networkCallbackRegistered.get(),
        )
    }

    private suspend fun monitorLoop() {
        var notifyOwner = false
        var reconciliationReason = "startup"
        try {
            while (currentCoroutineContext().isActive && isMonitoringEnabled()) {
                if (!networkAvailable.get()) {
                    if (!networkCallbackRegistered.get() && hasValidatedNetwork()) {
                        networkAvailable.set(true)
                        reconciliationReason = "network_recovered"
                        continue
                    }
                    nextDelayMs.set(offlineLiveNotificationRetryDelayMs(
                        cachedChannelCount = applicationContext.prefs()
                            .getInt(C.LIVE_NOTIFICATION_CACHED_CHANNEL_COUNT, -1),
                        networkWakeAvailable = networkCallbackRegistered.get(),
                    )
                    )
                    reconciliationReason = waitForNextPoll() ?: "network_retry"
                    continue
                }
                ensureEventSubStarted()
                if (eventSubStarted.get()) {
                    try {
                        eventSub.refreshIfNeeded()
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        Log.w(TAG, "Unable to refresh EventSub subscriptions", e)
                    }
                }
                var coverage = if (eventSubStarted.get()) {
                    eventSub.coverage()
                } else {
                    LiveEventSubCoverage(
                        desiredChannelCount = 0,
                        activeSubscriptionCount = 0,
                        connected = false,
                        suspended = false,
                    )
                }
                applicationContext.prefs().edit {
                    putLong(C.LIVE_NOTIFICATION_LAST_RUN, System.currentTimeMillis())
                }
                var retryDelayMs: Long? = null
                var retryDelayInterruptible = false
                var helixMinimumDelayMs: Long? = null
                val result = try {
                    monitor.poll(onHelixRateLimit = { rateLimit ->
                        val minimumDelayMs = helixRateLimitMinimumDelayMs(rateLimit)
                        if (minimumDelayMs != null) {
                            helixMinimumDelayMs = maxOf(
                                helixMinimumDelayMs ?: 0L,
                                minimumDelayMs,
                            )
                        }
                    })
                } catch (e: CancellationException) {
                    throw e
                } catch (e: TwitchApiException) {
                    Log.w(TAG, "Fast live notification poll failed: ${e.message}", e)
                    retryDelayMs = applyHelixMinimumDelay(
                        coverageDelayMs = rateLimitDelay(e),
                        helixMinimumDelayMs = helixMinimumDelayMs,
                    )
                    retryDelayInterruptible = isLiveNotificationRetryInterruptible(e)
                    recordFailure(e)
                    null
                } catch (e: Exception) {
                    Log.w(TAG, "Fast live notification poll failed", e)
                    retryDelayMs = applyHelixMinimumDelay(
                        coverageDelayMs = NETWORK_RETRY_INTERVAL_MS,
                        helixMinimumDelayMs = helixMinimumDelayMs,
                    )
                    retryDelayInterruptible = true
                    recordFailure(e)
                    null
                }
                result?.let {
                    recordSuccess(it.delivered, it.api)
                    var postPollRefreshSucceeded = true
                    if (eventSubStarted.get()) {
                        try {
                            // The poll may have synchronized the desired
                            // notification users, so recalculate coverage
                            // before selecting the next safety interval.
                            eventSub.refreshIfNeeded()
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            postPollRefreshSucceeded = false
                            Log.w(TAG, "Unable to refresh EventSub after poll", e)
                        }
                    }
                    coverage = if (eventSubStarted.get() && postPollRefreshSucceeded) {
                        eventSub.coverage()
                    } else {
                        coverage.copy(connected = false, suspended = true)
                    }
                    nextDelayMs.set(nextReconciliationDelay(coverage, helixMinimumDelayMs))
                    if (BuildConfig.PERF_DIAGNOSTICS) {
                        val coverageState = liveNotificationCoverageState(
                            desiredChannelCount = coverage.desiredChannelCount,
                            activeEventSubChannelCount = coverage.activeSubscriptionCount,
                            eventSubConnected = coverage.connected,
                            eventSubSuspended = coverage.suspended,
                        )
                        Log.i(
                            TAG,
                            "eventSub state=$coverageState desired=${coverage.desiredChannelCount} " +
                                "active=${coverage.activeSubscriptionCount} " +
                                "connected=${coverage.connected} suspended=${coverage.suspended}",
                        )
                        Log.i(TAG, "notification nextReconcileMs=${nextDelayMs.get()}")
                        Log.i(TAG, "notification reconciliation reason=$reconciliationReason")
                    }
                }
                if (retryDelayMs != null) {
                    awaitLiveNotificationRetry(retryDelayMs, retryDelayInterruptible, wakeController.signal)
                    reconciliationReason = wakeController.consumeReason() ?: "rate_limit_retry"
                } else {
                    reconciliationReason = waitForNextPoll()
                        ?: if (coverage.complete) "periodic_safety" else "periodic_partial_coverage"
                }
            }
        } finally {
            val loopJob = currentCoroutineContext()[Job]
            withContext(NonCancellable) {
                if (eventSubStarted.getAndSet(false)) {
                    runCatching { eventSub.stop() }
                }
                synchronized(lifecycleLock) {
                    if (state == State.RUNNING && monitorJob === loopJob) {
                        monitorJob = null
                        unregisterNetworkCallback()
                        state = State.NEW
                        notifyOwner = true
                    }
                }
            }
            if (notifyOwner) {
                runCatching { onMonitoringStopped?.invoke() }
            }
        }
    }

    private suspend fun waitForNextPoll(timeoutMs: Long = nextDelayMs.get()): String? {
        val woke = withTimeoutOrNull(timeoutMs) {
            wakeController.signal.receiveCatching()
            true
        } ?: false
        return if (woke) wakeController.consumeReason() else null
    }

    internal fun requestImmediateReconciliation(reason: String): Boolean = synchronized(lifecycleLock) {
        if (state != State.RUNNING || monitorJob?.isActive != true) {
            false
        } else {
            wakeController.request(isRunnerActive = true, reason = reason)
        }
    }

    private fun nextReconciliationDelay(
        coverage: LiveEventSubCoverage,
        helixMinimumDelayMs: Long?,
    ): Long {
        val coverageState = liveNotificationCoverageState(
            desiredChannelCount = coverage.desiredChannelCount,
            activeEventSubChannelCount = coverage.activeSubscriptionCount,
            eventSubConnected = coverage.connected,
            eventSubSuspended = coverage.suspended,
        )
        val coverageDelayMs = if (coverageState == LiveNotificationCoverageState.NO_CHANNELS) {
            monitor.nextNotificationUserSyncDelayMs()
        } else {
            reconcileIntervalMs(
                desiredChannelCount = coverage.desiredChannelCount,
                activeEventSubChannelCount = coverage.activeSubscriptionCount,
                eventSubConnected = coverage.connected,
                eventSubSuspended = coverage.suspended,
            )
        }
        return applyHelixMinimumDelay(coverageDelayMs, helixMinimumDelayMs)
    }

    private suspend fun ensureEventSubStarted() {
        if (!eventSubStarted.compareAndSet(false, true)) {
            return
        }
        try {
            eventSub.start()
        } catch (e: CancellationException) {
            eventSubStarted.set(false)
            throw e
        } catch (e: Exception) {
            eventSubStarted.set(false)
            Log.w(TAG, "Unable to start EventSub", e)
        }
    }

    private fun rateLimitDelay(error: TwitchApiException): Long {
        return liveNotificationFailureRetryDelayMs(
            statusCode = error.statusCode,
            rateLimitResetEpochSeconds = error.rateLimitResetEpochSeconds,
            nowEpochMs = System.currentTimeMillis(),
        )
    }

    private fun helixRateLimitMinimumDelayMs(rateLimit: HelixRateLimit): Long? {
        val limit = rateLimit.limit
        val remaining = rateLimit.remaining
        val resetDelay = if (remaining == 0L) {
            rateLimit.resetEpochSeconds?.let {
                (it * 1_000L - System.currentTimeMillis() + RATE_LIMIT_SAFETY_MARGIN_MS)
                    .takeIf { delay -> delay > 0L }
            }
        } else null
        return resetDelay ?: if (limit != null && remaining != null && remaining <= max(1L, limit / 10L)) {
            RATE_LIMIT_RETRY_INTERVAL_MS
        } else {
            null
        }
    }

    private fun recordSuccess(delivered: Int, api: String) {
        applicationContext.prefs().edit {
            putLong(C.LIVE_NOTIFICATION_LAST_SUCCESS, System.currentTimeMillis())
            putString(C.LIVE_NOTIFICATION_LAST_API, api)
            putInt(C.LIVE_NOTIFICATION_LAST_EVENT_COUNT, delivered)
            remove(C.LIVE_NOTIFICATION_LAST_ERROR)
        }
    }

    private fun recordFailure(error: Exception) {
        val message = sanitizeLiveNotificationTechnicalMessage(
            "${error::class.simpleName}: ${error.message.orEmpty()}"
        ) ?: error::class.simpleName.orEmpty()
        applicationContext.prefs().edit {
            putLong(C.LIVE_NOTIFICATION_LAST_ERROR_AT, System.currentTimeMillis())
            putString(C.LIVE_NOTIFICATION_LAST_ERROR, message)
        }
    }

    private fun recordRevocation(revocation: LiveEventSubRevocation) {
        val description = buildString {
            append(revocation.status ?: "unknown")
            revocation.subscriptionType?.let {
                append(" / ")
                append(it)
            }
            revocation.broadcasterUserId?.let {
                append(" / broadcaster=")
                append(it)
            }
        }
        Log.w(TAG, "EventSub subscription revoked: $description")
        applicationContext.prefs().edit {
            putLong(C.LIVE_NOTIFICATION_LAST_REVOCATION_AT, System.currentTimeMillis())
            putString(C.LIVE_NOTIFICATION_LAST_REVOCATION, description.take(500))
        }
    }

    private fun unregisterNetworkCallback() {
        networkCallback?.let { callback ->
            runCatching {
                (applicationContext.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager)
                    .unregisterNetworkCallback(callback)
            }
        }
        networkCallback = null
        networkCallbackRegistered.set(false)
    }

    private fun registerNetworkCallback() {
        val connectivityManager = applicationContext.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        networkCallback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                val available = hasValidatedNetwork()
                val wasAvailable = networkAvailable.getAndSet(available)
                if (!wasAvailable && available) {
                    requestImmediateReconciliation("network_recovered")
                }
            }

            override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) {
                val available = capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
                val wasAvailable = networkAvailable.getAndSet(available)
                if (!wasAvailable && available) {
                    requestImmediateReconciliation("network_recovered")
                }
            }

            override fun onLost(network: Network) {
                val available = hasValidatedNetwork()
                val wasAvailable = networkAvailable.getAndSet(available)
                if (wasAvailable && !available) {
                    requestImmediateReconciliation("network_lost")
                }
            }
        }
        runCatching {
            connectivityManager.registerNetworkCallback(
                NetworkRequest.Builder()
                    .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                    .addCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
                    .build(),
                networkCallback!!,
            )
            networkCallbackRegistered.set(true)
        }.onFailure {
            networkCallbackRegistered.set(false)
            networkCallback = null
            Log.w(TAG, "Unable to register validated network callback", it)
        }
    }

    private fun hasValidatedNetwork(): Boolean {
        val connectivityManager = applicationContext.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val capabilities = connectivityManager.getNetworkCapabilities(connectivityManager.activeNetwork)
        return capabilities?.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) == true
    }

    private fun isMonitoringEnabled(): Boolean =
        shouldContinue() &&
            applicationContext.prefs().getBoolean(C.LIVE_NOTIFICATIONS_ENABLED, false) &&
            LiveNotificationNotifier(applicationContext).canPostNotifications()

    companion object {
        private const val TAG = "LiveNotificationRunner"
    }
}
