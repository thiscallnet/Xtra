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
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.max

/**
 * Best-effort fast notification delivery while Xtra's process is alive.
 *
 * This deliberately is not a foreground service: Android would require a
 * persistent user-visible notification for that. WorkManager remains the
 * durable background reconciliation path when the process is suspended or
 * killed.
 */
object LiveNotificationRealtimeEngine {

    private val lock = Any()
    private var current: Engine? = null

    fun start(context: Context) {
        val engine = synchronized(lock) {
            current ?: Engine(context.applicationContext).also { current = it }
        }
        engine.start()
    }

    fun stop() {
        val engine = synchronized(lock) {
            current.also { current = null }
        }
        engine?.stop()
    }

    private class Engine(context: Context) {
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
            onStreamOnline = { event ->
                monitor.handleStreamOnline(event)
                wakeSignal.trySend(Unit)
            },
        )
        private var monitorJob: Job? = null
        private var networkCallback: ConnectivityManager.NetworkCallback? = null

        fun start() {
            if (monitorJob?.isActive == true) {
                wakeSignal.trySend(Unit)
                return
            }
            networkAvailable.set(hasValidatedNetwork())
            registerNetworkCallback()
            monitorJob = scope.launch { monitorLoop() }
        }

        fun stop() {
            networkCallback?.let { callback ->
                runCatching {
                    (applicationContext.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager)
                        .unregisterNetworkCallback(callback)
                }
            }
            networkCallback = null
            monitorJob?.cancel()
            eventSub.shutdown()
            wakeSignal.close()
            scope.cancel()
        }

        private suspend fun monitorLoop() {
            while (currentCoroutineContext().isActive && isRealtimeEnabled()) {
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
            if (eventSubStarted.getAndSet(false)) {
                eventSub.stop()
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
            applicationContext.prefs().edit {
                putLong(C.LIVE_NOTIFICATION_LAST_ERROR_AT, System.currentTimeMillis())
                putString(
                    C.LIVE_NOTIFICATION_LAST_ERROR,
                    "${error::class.simpleName}: ${error.message.orEmpty()}".take(500),
                )
            }
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

        private fun isRealtimeEnabled(): Boolean =
            applicationContext.prefs().getBoolean(C.LIVE_NOTIFICATIONS_ENABLED, false) &&
                applicationContext.prefs().getString(
                    C.LIVE_NOTIFICATIONS_MODE,
                    C.LIVE_NOTIFICATIONS_MODE_BATTERY,
                ) == C.LIVE_NOTIFICATIONS_MODE_REALTIME

        companion object {
            private const val TAG = "LiveNotificationRealtime"
            private const val NORMAL_POLL_INTERVAL_MS = 10_000L
            private const val THROTTLED_POLL_INTERVAL_MS = 30_000L
            private const val NETWORK_RETRY_INTERVAL_MS = 60_000L
            private const val RATE_LIMIT_SAFETY_MARGIN_MS = 1_000L
        }
    }
}
