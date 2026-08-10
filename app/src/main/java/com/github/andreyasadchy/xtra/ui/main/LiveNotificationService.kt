package com.github.andreyasadchy.xtra.ui.main

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.edit
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleService
import com.github.andreyasadchy.xtra.R
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
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.max

class LiveNotificationService : LifecycleService() {

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val wakeSignal = Channel<Unit>(Channel.CONFLATED)
    private val baselineNextPoll = AtomicBoolean(false)
    private val eventSubStarted = AtomicBoolean(false)
    private val networkAvailable = AtomicBoolean(false)
    private val nextDelayMs = AtomicReference(NORMAL_POLL_INTERVAL_MS)
    private lateinit var monitor: LiveNotificationMonitor
    private lateinit var eventSub: LiveNotificationEventSub
    private var monitorJob: Job? = null
    private var networkCallback: ConnectivityManager.NetworkCallback? = null

    override fun onCreate() {
        super.onCreate()
        monitor = LiveNotificationMonitor(applicationContext)
        val xtraModule = (application as XtraApp).xtraModule
        eventSub = LiveNotificationEventSub(
            okHttpClient = xtraModule.okHttpClient,
            helixRepository = xtraModule.helixRepository,
            networkLibrary = { prefs().getString(C.NETWORK_LIBRARY, C.OKHTTP) },
            helixHeaders = { TwitchApiHelper.getHelixHeaders(applicationContext) },
            channelIds = { xtraModule.notificationsRepository.getNotificationUserIds() },
            scope = serviceScope,
            onStreamOnline = { wakeSignal.trySend(Unit) },
        )
        createServiceNotificationChannel()
        if (!startAsForeground()) {
            stopSelf()
            return
        }
        networkAvailable.set(hasNetwork())
        registerNetworkCallback()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        if (!isRealtimeEnabled()) {
            stopSelfResult(startId)
            return START_NOT_STICKY
        }
        // Re-apply the quiet channel when an already-running service receives a
        // command after an app update. onCreate is not called in that case.
        createServiceNotificationChannel()
        if (!startAsForeground()) {
            stopSelfResult(startId)
            return START_NOT_STICKY
        }
        startMonitor(intent?.getBooleanExtra(EXTRA_BASELINE_ONLY, false) == true)
        if (intent?.action == ACTION_POLL_NOW || intent?.action == ACTION_REFRESH) {
            wakeSignal.trySend(Unit)
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent): IBinder? = super.onBind(intent)

    override fun onDestroy() {
        networkCallback?.let { callback ->
            runCatching {
                (getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager)
                    .unregisterNetworkCallback(callback)
            }
        }
        networkCallback = null
        serviceScope.cancel()
        eventSub.shutdown()
        wakeSignal.close()
        super.onDestroy()
    }

    private fun startMonitor(baselineOnly: Boolean) {
        if (baselineOnly) {
            baselineNextPoll.set(true)
        }
        if (monitorJob?.isActive == true) {
            return
        }
        monitorJob = serviceScope.launch {
            monitorLoop(baselineOnly)
        }
    }

    private suspend fun monitorLoop(initialBaselineOnly: Boolean) {
        var baselineOnly = initialBaselineOnly || baselineNextPoll.getAndSet(false)
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
            prefs().edit { putLong(C.LIVE_NOTIFICATION_LAST_RUN, System.currentTimeMillis()) }
            var retryDelayMs: Long? = null
            val result = try {
                monitor.poll(
                    baselineOnly = baselineOnly,
                    onHelixRateLimit = ::onHelixRateLimit,
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: TwitchApiException) {
                Log.w(TAG, "Real-time live notification poll failed: ${e.message}", e)
                retryDelayMs = rateLimitDelay(e)
                recordFailure(e)
                null
            } catch (e: Exception) {
                Log.w(TAG, "Real-time live notification poll failed", e)
                recordFailure(e)
                null
            }
            baselineOnly = baselineNextPoll.getAndSet(false)
            result?.let {
                updateServiceNotification(it.channelCount)
                recordSuccess(it.delivered, it.api)
            }
            if (!LiveNotificationScheduler.canPostNotifications(this@LiveNotificationService)) {
                break
            }
            if (retryDelayMs != null) {
                delay(retryDelayMs)
            } else {
                waitForNextPoll()
            }
        }
        if (eventSubStarted.getAndSet(false)) {
            eventSub.stop()
        }
        if (!isRealtimeEnabled()) {
            stopSelf()
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
            (it * 1000L - System.currentTimeMillis() + RATE_LIMIT_SAFETY_MARGIN_MS)
                .coerceAtLeast(NORMAL_POLL_INTERVAL_MS)
        } ?: nextDelayMs.get()
    }

    private fun onHelixRateLimit(rateLimit: HelixRateLimit) {
        val limit = rateLimit.limit
        val remaining = rateLimit.remaining
        val resetDelay = if (remaining == 0L) {
            rateLimit.resetEpochSeconds?.let {
                (it * 1000L - System.currentTimeMillis() + RATE_LIMIT_SAFETY_MARGIN_MS)
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

    private fun updateServiceNotification(channelCount: Int) {
        val notification = buildServiceNotification(channelCount)
        (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
            .notify(SERVICE_NOTIFICATION_ID, notification)
    }

    private fun recordSuccess(delivered: Int, api: String) {
        prefs().edit {
            putLong(C.LIVE_NOTIFICATION_LAST_SUCCESS, System.currentTimeMillis())
            putString(C.LIVE_NOTIFICATION_LAST_API, api)
            putInt(C.LIVE_NOTIFICATION_LAST_EVENT_COUNT, delivered)
            remove(C.LIVE_NOTIFICATION_LAST_ERROR)
        }
    }

    private fun recordFailure(error: Exception) {
        prefs().edit {
            putLong(C.LIVE_NOTIFICATION_LAST_ERROR_AT, System.currentTimeMillis())
            putString(C.LIVE_NOTIFICATION_LAST_ERROR, "${error::class.simpleName}: ${error.message.orEmpty()}".take(500))
        }
    }

    private fun startAsForeground(): Boolean = try {
        val notification = buildServiceNotification(0)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(
                SERVICE_NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE,
            )
        } else {
            startForeground(SERVICE_NOTIFICATION_ID, notification)
        }
        true
    } catch (e: Exception) {
        Log.e(TAG, "Unable to start real-time notification service", e)
        LiveNotificationScheduler.enqueueImmediateFallback(applicationContext, baselineOnly = true)
        false
    }

    private fun buildServiceNotification(channelCount: Int) = NotificationCompat.Builder(this, SERVICE_CHANNEL_ID)
        .setSmallIcon(R.drawable.notification_icon)
        .setContentTitle(getString(R.string.live_notifications_service_active))
        .setContentText(getString(R.string.live_notifications_service_monitoring, channelCount))
        .setContentIntent(
            PendingIntent.getActivity(
                this,
                SERVICE_NOTIFICATION_ID,
                Intent(this, MainActivity::class.java).apply {
                    flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
                },
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
        )
        .setOngoing(true)
        .setOnlyAlertOnce(true)
        .setShowWhen(false)
        .setSilent(true)
        .setVisibility(NotificationCompat.VISIBILITY_SECRET)
        .setPriority(NotificationCompat.PRIORITY_MIN)
        .build()

    private fun createServiceNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            notificationManager.deleteNotificationChannel(LEGACY_SERVICE_CHANNEL_ID)
            notificationManager.createNotificationChannel(
                NotificationChannel(
                    SERVICE_CHANNEL_ID,
                    getString(R.string.notification_live_service_channel_title),
                    NotificationManager.IMPORTANCE_MIN,
                ).apply {
                    setShowBadge(false)
                }
            )
        }
    }

    private fun registerNetworkCallback() {
        val connectivityManager = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        networkCallback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                networkAvailable.set(true)
                wakeSignal.trySend(Unit)
            }

            override fun onLost(network: Network) {
                networkAvailable.set(hasNetwork())
            }
        }
        runCatching {
            connectivityManager.registerNetworkCallback(
                NetworkRequest.Builder()
                    .addCapability(android.net.NetworkCapabilities.NET_CAPABILITY_INTERNET)
                    .build(),
                networkCallback!!,
            )
        }.onFailure {
            Log.w(TAG, "Unable to register network callback", it)
        }
    }

    private fun hasNetwork(): Boolean {
        val connectivityManager = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val capabilities = connectivityManager.getNetworkCapabilities(connectivityManager.activeNetwork)
        return capabilities?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true
    }

    private fun isRealtimeEnabled(): Boolean =
        prefs().getBoolean(C.LIVE_NOTIFICATIONS_ENABLED, false) &&
            prefs().getString(C.LIVE_NOTIFICATIONS_MODE, C.LIVE_NOTIFICATIONS_MODE_BATTERY) == C.LIVE_NOTIFICATIONS_MODE_REALTIME

    companion object {
        const val ACTION_POLL_NOW = "com.github.andreyasadchy.xtra.action.LIVE_NOTIFICATION_POLL_NOW"
        const val ACTION_REFRESH = "com.github.andreyasadchy.xtra.action.LIVE_NOTIFICATION_REFRESH"
        const val EXTRA_BASELINE_ONLY = "baseline_only"

        private const val TAG = "LiveNotificationService"
        private const val LEGACY_SERVICE_CHANNEL_ID = "xtra_live_service_channel"
        private const val SERVICE_CHANNEL_ID = "xtra_live_service_channel_quiet"
        private const val SERVICE_NOTIFICATION_ID = 1
        private const val NORMAL_POLL_INTERVAL_MS = 10_000L
        private const val THROTTLED_POLL_INTERVAL_MS = 30_000L
        private const val NETWORK_RETRY_INTERVAL_MS = 60_000L
        private const val RATE_LIMIT_SAFETY_MARGIN_MS = 1_000L
    }
}
