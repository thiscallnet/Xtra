package com.github.andreyasadchy.xtra.ui.main

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.util.Log
import androidx.core.content.edit
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
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.max

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
    private val wakeSignal = Channel<Unit>(Channel.CONFLATED)
    private val networkAvailable = AtomicBoolean(false)
    private val nextDelayMs = AtomicLong(NORMAL_POLL_INTERVAL_MS)
    private val eventSubStarted = AtomicBoolean(false)
    private val monitor = LiveNotificationMonitor(applicationContext)
    private val eventSub = LiveNotificationEventSub(
        okHttpClient = module.okHttpClient,
        helixRepository = module.helixRepository,
        networkLibrary = { applicationContext.prefs().getString(C.NETWORK_LIBRARY, C.OKHTTP) },
        helixHeaders = { TwitchApiHelper.getHelixHeaders(applicationContext) },
        channelIds = { module.notificationsRepository.getNotificationUserIds() },
        scope = scope,
        onStreamOnline = {
            wakeSignal.trySend(Unit)
        },
        onRevocation = { revocation ->
            recordRevocation(revocation)
            wakeSignal.trySend(Unit)
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
                        wakeSignal.trySend(Unit)
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
            wakeSignal.close()
            scope.cancel()
        }
    }

    private suspend fun monitorLoop() {
        var notifyOwner = false
        try {
            while (currentCoroutineContext().isActive && isMonitoringEnabled()) {
                if (!networkAvailable.get()) {
                    waitForNextPoll(NETWORK_RETRY_INTERVAL_MS)
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
                applicationContext.prefs().edit {
                    putLong(C.LIVE_NOTIFICATION_LAST_RUN, System.currentTimeMillis())
                }
                var retryDelayMs: Long? = null
                val result = try {
                    monitor.poll(onHelixRateLimit = ::onHelixRateLimit)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: TwitchApiException) {
                    Log.w(TAG, "Fast live notification poll failed: ${e.message}", e)
                    retryDelayMs = rateLimitDelay(e)
                    recordFailure(e)
                    null
                } catch (e: Exception) {
                    Log.w(TAG, "Fast live notification poll failed", e)
                    recordFailure(e)
                    null
                }
                result?.let { recordSuccess(it.delivered, it.api) }
                if (retryDelayMs != null) {
                    delay(retryDelayMs)
                } else {
                    waitForNextPoll()
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

    private suspend fun waitForNextPoll(timeoutMs: Long = nextDelayMs.get()) {
        withTimeoutOrNull(timeoutMs) {
            wakeSignal.receiveCatching()
        }
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
        val reset = error.rateLimitResetEpochSeconds
        return reset?.let {
            (it * 1_000L - System.currentTimeMillis() + RATE_LIMIT_SAFETY_MARGIN_MS)
                .coerceAtLeast(NORMAL_POLL_INTERVAL_MS)
        } ?: nextDelayMs.get()
    }

    private fun onHelixRateLimit(rateLimit: HelixRateLimit) {
        val limit = rateLimit.limit
        val remaining = rateLimit.remaining
        val resetDelay = if (remaining == 0L) {
            rateLimit.resetEpochSeconds?.let {
                (it * 1_000L - System.currentTimeMillis() + RATE_LIMIT_SAFETY_MARGIN_MS)
                    .takeIf { delay -> delay > 0L }
            }
        } else null
        nextDelayMs.set(
            resetDelay ?: if (limit != null && remaining != null && remaining <= max(1L, limit / 10L)) {
                THROTTLED_POLL_INTERVAL_MS
            } else {
                NORMAL_POLL_INTERVAL_MS
            }
        )
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
    }

    private fun registerNetworkCallback() {
        val connectivityManager = applicationContext.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        networkCallback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                networkAvailable.set(hasValidatedNetwork())
                wakeSignal.trySend(Unit)
            }

            override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) {
                networkAvailable.set(capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED))
                wakeSignal.trySend(Unit)
            }

            override fun onLost(network: Network) {
                networkAvailable.set(hasValidatedNetwork())
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
        }.onFailure {
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
        private const val NORMAL_POLL_INTERVAL_MS = 10_000L
        private const val THROTTLED_POLL_INTERVAL_MS = 30_000L
        private const val NETWORK_RETRY_INTERVAL_MS = 60_000L
        private const val RATE_LIMIT_SAFETY_MARGIN_MS = 1_000L
    }
}
