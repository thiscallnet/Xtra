package com.github.andreyasadchy.xtra

import android.annotation.SuppressLint
import android.app.Application
import android.os.Build
import android.os.SystemClock
import androidx.core.content.edit
import coil3.ImageLoader
import coil3.PlatformContext
import coil3.SingletonImageLoader
import coil3.annotation.ExperimentalCoilApi
import coil3.network.NetworkClient
import coil3.network.NetworkFetcher
import coil3.network.NetworkHeaders
import coil3.network.NetworkRequest
import coil3.network.NetworkResponse
import coil3.network.NetworkResponseBody
import coil3.network.okhttp.OkHttpNetworkFetcherFactory
import coil3.util.DebugLogger
import com.github.andreyasadchy.xtra.util.C
import com.github.andreyasadchy.xtra.util.NetworkUtils
import com.github.andreyasadchy.xtra.util.coil.CacheControlCacheStrategy
import com.github.andreyasadchy.xtra.util.prefs
import com.github.andreyasadchy.xtra.util.tokenPrefs
import com.github.andreyasadchy.xtra.repository.auth.AuthSessionStore
import com.github.andreyasadchy.xtra.repository.streamfeed.StreamFeedPrewarmScheduler
import com.github.andreyasadchy.xtra.ui.main.LiveNotificationScheduler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okio.Buffer
import okio.buffer
import okio.source
import org.chromium.net.apihelpers.UploadDataProviders
import org.conscrypt.Conscrypt
import java.security.Security

class XtraApp : Application(), SingletonImageLoader.Factory {

    companion object {
        lateinit var INSTANCE: Application
    }

    lateinit var xtraModule: XtraModule
    val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val accountCleanupMutex = Mutex()

    @Volatile
    var isInForeground: Boolean = false
        private set
    private var startedActivityCount = 0
    private var backgroundStartedElapsedMs: Long? = null

    override fun onCreate() {
        super.onCreate()
        INSTANCE = this
        xtraModule = XtraModule(this)
        reconcilePendingAccountScopedState()
        xtraModule.authSessionMaintainer.start(applicationScope)
        xtraModule.twitchWebSessionManager.startIntegrityMaintenance(applicationScope)
        registerActivityLifecycleCallbacks(object : ActivityLifecycleCallbacks {
            override fun onActivityStarted(activity: android.app.Activity) {
                val wasInBackground = startedActivityCount == 0
                startedActivityCount += 1
                isInForeground = true
                if (wasInBackground) {
                    val awayMs = backgroundStartedElapsedMs?.let { (SystemClock.elapsedRealtime() - it).coerceAtLeast(0L) }
                    if (awayMs != null) {
                        StreamFeedPrewarmScheduler.recordBackgroundReturn(this@XtraApp, awayMs)
                        xtraModule.streamFeedRefreshCoordinator.onAppForeground(awayMs)
                        xtraModule.gameFeedRefreshCoordinator.onAppForeground(awayMs)
                    }
                    backgroundStartedElapsedMs = null
                    StreamFeedPrewarmScheduler.cancel(this@XtraApp)
                    xtraModule.onStreamPreloadAppForeground()
                }
            }

            override fun onActivityStopped(activity: android.app.Activity) {
                startedActivityCount = (startedActivityCount - 1).coerceAtLeast(0)
                isInForeground = startedActivityCount > 0
                if (!isInForeground) {
                    backgroundStartedElapsedMs = SystemClock.elapsedRealtime()
                    StreamFeedPrewarmScheduler.schedule(this@XtraApp)
                    xtraModule.onStreamPreloadAppBackground()
                }
            }

            override fun onActivityCreated(activity: android.app.Activity, savedInstanceState: android.os.Bundle?) = Unit
            override fun onActivityResumed(activity: android.app.Activity) = Unit
            override fun onActivityPaused(activity: android.app.Activity) = Unit
            override fun onActivitySaveInstanceState(activity: android.app.Activity, outState: android.os.Bundle) = Unit
            override fun onActivityDestroyed(activity: android.app.Activity) = Unit
        })
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            val conscrypt = Conscrypt.newProvider()
            Security.insertProviderAt(conscrypt, 1)
        }
    }

    internal fun scheduleAccountScopedStateCleanup(userId: String?, login: String?) {
        val sessionStore = AuthSessionStore(prefs(), tokenPrefs())
        if (!sessionStore.markAccountCleanupPending(userId, login)) return
        reconcilePendingAccountScopedState()
    }

    private fun reconcilePendingAccountScopedState() {
        val sessionStore = AuthSessionStore(prefs(), tokenPrefs())
        if (sessionStore.pendingAccountCleanups().isEmpty()) return
        applicationScope.launch(Dispatchers.IO) {
            accountCleanupMutex.withLock {
                val pending = sessionStore.pendingAccountCleanups()
                if (pending.isEmpty()) return@withLock
                val globalCleanupSucceeded = runCatching {
                    clearAccountScopedState(
                        disableScheduler = { LiveNotificationScheduler.disable(this@XtraApp) },
                        disableNotifications = {
                            prefs().edit { putBoolean(C.LIVE_NOTIFICATIONS_ENABLED, false) }
                        },
                        clearNotificationState = { xtraModule.notificationsRepository.clearNotificationState() },
                        clearAccountMetadata = {},
                    )
                }.isSuccess
                if (!globalCleanupSucceeded) return@withLock
                pending.forEach { target ->
                    runCatching {
                        val activeSession = sessionStore.read()
                        val belongsToActiveAccount = when {
                            !target.userId.isNullOrBlank() -> target.userId == activeSession?.userId
                            !target.login.isNullOrBlank() -> target.login.equals(activeSession?.login, ignoreCase = true)
                            else -> false
                        }
                        if (!belongsToActiveAccount) {
                            xtraModule.metadataCache.clearAccount(target.userId, target.login)
                        }
                        check(sessionStore.clearAccountCleanup(target))
                    }
                }
            }
        }
    }

    @OptIn(ExperimentalCoilApi::class)
    override fun newImageLoader(context: PlatformContext): ImageLoader {
        return ImageLoader.Builder(context).apply {
            if (BuildConfig.DEBUG) {
                logger(DebugLogger())
            }
            components {
                val networkLibrary = prefs().getString(C.NETWORK_LIBRARY, C.OKHTTP)
                when {
                    networkLibrary == C.HTTP_ENGINE && xtraModule.httpEngine.value != null -> @SuppressLint("NewApi") {
                        add(NetworkFetcher.Factory(
                            networkClient = {
                                object : NetworkClient {
                                    override suspend fun <T> executeRequest(request: NetworkRequest, block: suspend (NetworkResponse) -> T): T {
                                        val requestBody = request.body?.let {
                                            val buffer = Buffer()
                                            it.writeTo(buffer)
                                            buffer.readByteArray()
                                        }
                                        val requestMillis = System.currentTimeMillis()
                                        val response = suspendCancellableCoroutine { continuation ->
                                            val timeout = NetworkUtils.HttpEngineTimeout()
                                            val request = xtraModule.httpEngine.value!!.newUrlRequestBuilder(
                                                request.url,
                                                xtraModule.cronetExecutor.value,
                                                NetworkUtils.ByteArrayUrlCallback(continuation, timeout)
                                            ).apply {
                                                request.headers.asMap().forEach { entry ->
                                                    entry.value.forEach {
                                                        addHeader(entry.key, it)
                                                    }
                                                }
                                                requestBody?.let {
                                                    setUploadDataProvider(NetworkUtils.ByteArrayUploadProvider(requestBody), xtraModule.cronetExecutor.value)
                                                }
                                                setHttpMethod(request.method)
                                            }.build()
                                            timeout.start(request, continuation)
                                            request.start()
                                            continuation.invokeOnCancellation {
                                                request.cancel()
                                                timeout.stop()
                                            }
                                        }
                                        val responseMillis = System.currentTimeMillis()
                                        return block(
                                            NetworkResponse(
                                                code = response.info.httpStatusCode,
                                                requestMillis = requestMillis,
                                                responseMillis = responseMillis,
                                                headers = NetworkHeaders.Builder().apply {
                                                    response.info.headers.asList.forEach {
                                                        add(it.key, it.value)
                                                    }
                                                }.build(),
                                                body = response.body.inputStream().source().buffer().let(::NetworkResponseBody),
                                            )
                                        )
                                    }
                                }
                            },
                            cacheStrategy = { CacheControlCacheStrategy() }
                        ))
                    }
                    networkLibrary == C.CRONET && xtraModule.cronetEngine.value != null -> {
                        add(NetworkFetcher.Factory(
                            networkClient = {
                                object : NetworkClient {
                                    override suspend fun <T> executeRequest(request: NetworkRequest, block: suspend (NetworkResponse) -> T): T {
                                        val requestBody = request.body?.let {
                                            val buffer = Buffer()
                                            it.writeTo(buffer)
                                            buffer.readByteArray()
                                        }
                                        val requestMillis = System.currentTimeMillis()
                                        val response = suspendCancellableCoroutine { continuation ->
                                            val timeout = NetworkUtils.CronetTimeout()
                                            val request = xtraModule.cronetEngine.value!!.newUrlRequestBuilder(
                                                request.url,
                                                NetworkUtils.ByteArrayCronetCallback(continuation, timeout),
                                                xtraModule.cronetExecutor.value
                                            ).apply {
                                                request.headers.asMap().forEach { entry ->
                                                    entry.value.forEach {
                                                        addHeader(entry.key, it)
                                                    }
                                                }
                                                requestBody?.let {
                                                    setUploadDataProvider(UploadDataProviders.create(requestBody), xtraModule.cronetExecutor.value)
                                                }
                                                setHttpMethod(request.method)
                                            }.build()
                                            timeout.start(request, continuation)
                                            request.start()
                                            continuation.invokeOnCancellation {
                                                request.cancel()
                                                timeout.stop()
                                            }
                                        }
                                        val responseMillis = System.currentTimeMillis()
                                        return block(
                                            NetworkResponse(
                                                code = response.info.httpStatusCode,
                                                requestMillis = requestMillis,
                                                responseMillis = responseMillis,
                                                headers = NetworkHeaders.Builder().apply {
                                                    response.info.allHeadersAsList.forEach {
                                                        add(it.key, it.value)
                                                    }
                                                }.build(),
                                                body = response.body.inputStream().source().buffer().let(::NetworkResponseBody),
                                            )
                                        )
                                    }
                                }
                            },
                            cacheStrategy = { CacheControlCacheStrategy() }
                        ))
                    }
                    else -> {
                        add(OkHttpNetworkFetcherFactory(
                            callFactory = { xtraModule.okHttpClient.value },
                            cacheStrategy = { CacheControlCacheStrategy() }
                        ))
                    }
                }
            }
        }.build()
    }
}
