package com.github.andreyasadchy.xtra.repository.auth

import android.content.Context
import android.util.Log
import com.github.andreyasadchy.xtra.model.id.ValidationResponse
import com.github.andreyasadchy.xtra.repository.AuthRepository
import com.github.andreyasadchy.xtra.repository.MissingAuthenticationException
import com.github.andreyasadchy.xtra.util.C
import com.github.andreyasadchy.xtra.util.TwitchApiHelper
import com.github.andreyasadchy.xtra.util.prefs
import com.github.andreyasadchy.xtra.util.tokenPrefs
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONObject
import org.mozilla.geckoview.GeckoResult
import org.mozilla.geckoview.GeckoRuntime
import org.mozilla.geckoview.GeckoSession
import org.mozilla.geckoview.StorageController
import org.mozilla.geckoview.WebExtension
import java.io.IOException
import kotlin.coroutines.resume

sealed interface TwitchWebSessionState {
    data object SignedOut : TwitchWebSessionState
    data object Opening : TwitchWebSessionState
    data object Validating : TwitchWebSessionState
    data class Authenticated(
        val userId: String,
        val login: String?,
        val accountChanged: Boolean,
    ) : TwitchWebSessionState
    data object AccountMismatch : TwitchWebSessionState
    data object RecoverableError : TwitchWebSessionState
}

/** Owns the process-wide Twitch browser profile and the native session bridge. */
class TwitchWebSessionManager(
    context: Context,
    private val authRepository: AuthRepository,
    private val authSessionMaintainer: AuthSessionMaintainer,
) {
    private val applicationContext = context.applicationContext
    private val sessionStore = AuthSessionStore(applicationContext.prefs(), applicationContext.tokenPrefs())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val candidateMutex = Mutex()
    private val integrityRefreshLock = Mutex()
    private val integrityStateLock = Any()
    private val sessionLock = Any()
    private val cookiesLock = Any()
    private val _state = MutableStateFlow<TwitchWebSessionState>(TwitchWebSessionState.SignedOut)
    private val _loginSession = MutableStateFlow<GeckoSession?>(null)
    private var runtime: GeckoRuntime? = null
    private var session: GeckoSession? = null
    private var bridgeInstallationStarted = false
    private var bridgeInstalled = false
    private var pageLoadStarted = false
    @Volatile
    private var reauthorize = false
    @Volatile
    private var expectedReauthorizationUserId: String? = null
    private var lastCandidateToken: String? = null
    private var rejectedCandidateToken: String? = null
    @Volatile
    private var sessionGeneration = 0L
    private var cookieSnapshot: List<TwitchWebCookie> = emptyList()
    private var hasCookieSnapshot = false
    private var temporaryIntegritySession: GeckoSession? = null
    private val pendingBridgeLoads = mutableSetOf<GeckoSession>()
    @Volatile
    private var integrityRefreshWaiter: CompletableDeferred<Boolean>? = null
    @Volatile
    private var pendingGeckoGqlIdentity: GeckoGqlIdentity? = null
    @Volatile
    private var integrityInvalidatedAt = 0L

    val state: StateFlow<TwitchWebSessionState> = _state.asStateFlow()
    val loginSession: StateFlow<GeckoSession?> = _loginSession.asStateFlow()

    /** Returns the persistent Gecko session that the login Activity should display. */
    fun openLoginSession(reauthorize: Boolean): GeckoSession = synchronized(sessionLock) {
        val startingReauthorization = reauthorize && !this.reauthorize
        this.reauthorize = reauthorize
        if (startingReauthorization) {
            expectedReauthorizationUserId = sessionStore.storedUserId()
        } else if (!reauthorize) {
            expectedReauthorizationUserId = null
        }
        val created = session == null
        val currentSession = session ?: GeckoSession().also {
            it.open(runtime())
            session = it
            _loginSession.value = it
        }
        if (reauthorize || _state.value !is TwitchWebSessionState.Authenticated) {
            _state.value = TwitchWebSessionState.Opening
        }
        _loginSession.value = currentSession
        if (created) pageLoadStarted = false
        installBridgeAndLoad(currentSession)
        currentSession
    }

    /** Stops the current Twitch page while retaining the process-wide Gecko profile. */
    fun closeLoginSession() {
        invalidatePendingWork()
        closeSessionInternal()
    }

    fun cookieHeaderFor(url: String): String? {
        val snapshot = synchronized(cookiesLock) { cookieSnapshot }
        if (synchronized(cookiesLock) { hasCookieSnapshot }) {
            return TwitchWebCookiePolicy.headerFor(url, snapshot)
        }
        return applicationContext.tokenPrefs().getString(C.TWITCH_WEB_COOKIE_HEADER, null)
            ?.takeIf { it.isNotBlank() }
    }

    fun isWebSessionActive(): Boolean = sessionStore.read() != null

    /** Returns the captured identity only while it belongs to the current Gecko account. */
    fun geckoGqlIdentity(): GeckoGqlIdentity? {
        val currentSession = sessionStore.read() ?: return null
        val identity = sessionStore.readGeckoGqlIdentity() ?: return null
        if (identity.userId != currentSession.userId ||
            !authorizationBelongsTo(identity.authorization, currentSession.accessToken) ||
            identity.authTokenFingerprint != GeckoGqlIdentity.fingerprintForAccessToken(currentSession.accessToken)
        ) return null
        return identity
    }

    /** Builds native GQL headers from one captured identity and the live Gecko cookies. */
    fun geckoGqlHeaders(): Map<String, String>? = geckoGqlRequest()?.headers

    /** Returns the atomic identity/cookie snapshot used for one native request. */
    fun geckoGqlRequest(): GeckoGqlRequest? {
        val identity = geckoGqlIdentity() ?: return null
        if (!identity.canProtectMutations()) return null
        val cookieHeader = cookieHeaderFor(GQL_URL) ?: return null
        if (!identity.matchesCookieHeader(cookieHeader)) return null
        return GeckoGqlRequest(
            identity = identity,
            headers = TwitchApiHelper.buildGeckoGqlHeaders(
                identity = identity,
                cookieHeader = cookieHeader,
            ),
        )
    }

    fun isCurrentGeckoAuthorization(authorization: String?): Boolean {
        val currentSession = sessionStore.read() ?: return false
        return authorization?.let { authorizationBelongsTo(it, currentSession.accessToken) } == true
    }

    fun invalidateGeckoGqlIdentity() {
        synchronized(integrityStateLock) {
            integrityInvalidatedAt = System.currentTimeMillis()
            pendingGeckoGqlIdentity = null
            sessionStore.clearGeckoGqlIdentity()
        }
    }

    /** Invalidates only the identity that produced a rejected response. */
    fun invalidateGeckoGqlIdentityIfCurrent(identity: GeckoGqlIdentity): Boolean {
        synchronized(integrityStateLock) {
            if (!isCurrentGeckoGqlIdentity(geckoGqlIdentity(), identity)) return false
            invalidateGeckoGqlIdentity()
            return true
        }
    }

    /**
     * Executes an authenticated GQL request with one atomic Gecko identity.
     *
     * The generic response type lets every GQL transport share the same
     * missing-identity, refresh, retry, and stale-response handling without
     * forcing transports to serialize their own response objects.
     */
    suspend fun <T> executeIntegrityAwareGql(
        fallbackHeaders: Map<String, String>,
        requireActiveWebSession: Boolean = false,
        isFailedIntegrityCheck: (T) -> Boolean,
        send: suspend (Map<String, String>) -> T,
    ): T = IntegrityAwareGqlExecutor<T>(
        isWebSessionActive = ::isWebSessionActive,
        isCurrentAuthorization = ::isCurrentGeckoAuthorization,
        currentRequest = ::geckoGqlRequest,
        refresh = ::refreshGeckoGqlIdentity,
        invalidateIfCurrent = ::invalidateGeckoGqlIdentityIfCurrent,
    ).execute(
        fallbackHeaders = fallbackHeaders,
        requireActiveWebSession = requireActiveWebSession,
        isFailedIntegrityCheck = isFailedIntegrityCheck,
        send = send,
    )

    /** Debug-only fault injection for exercising the server-rejection recovery path. */
    fun debugCorruptGeckoGqlIdentity(): Boolean {
        synchronized(integrityStateLock) {
            val identity = sessionStore.readGeckoGqlIdentity() ?: return false
            return sessionStore.commitGeckoGqlIdentity(
                identity.copy(clientIntegrity = DEBUG_INVALID_CLIENT_INTEGRITY),
            )
        }
    }

    /** Lazily creates a Gecko page that can produce a fresh browser GQL request. */
    suspend fun refreshGeckoGqlIdentity(): Boolean {
        if (geckoGqlHeaders() != null) return true
        if (!isWebSessionActive()) return false
        return integrityRefreshLock.withLock {
            if (geckoGqlHeaders() != null) return@withLock true
            val waiter = CompletableDeferred<Boolean>()
            integrityRefreshWaiter = waiter
            val started = withContext(Dispatchers.Main.immediate) {
                startIntegrityRefreshPage()
            }
            try {
                if (!started) false
                else withTimeoutOrNull(INTEGRITY_REFRESH_TIMEOUT_MILLIS) { waiter.await() } == true
            } finally {
                if (integrityRefreshWaiter === waiter) integrityRefreshWaiter = null
                withContext(Dispatchers.Main.immediate) {
                    closeTemporaryIntegritySession()
                }
            }
        }
    }

    /** Signs out Xtra and optionally clears the persistent Gecko Twitch session. */
    suspend fun logout(): Boolean {
        // Invalidate work before waiting for the serialized event processor. A
        // candidate that is already queued must not resurrect the account after
        // this explicit logout completes.
        invalidatePendingWork()
        return candidateMutex.withLock {
            // StorageController warns that an open session can repopulate data
            // while it is being cleared. The runtime/profile remains alive; only
            // the current page/tab is closed.
            closeSessionInternal()
            val clearBrowserSession = applicationContext.prefs().getBoolean(
                C.SETTINGS_CLEAR_TWITCH_BROWSER_SESSION_ON_LOGOUT,
                false,
            )
            val browserCleared = if (clearBrowserSession) {
                withContextNonCancellable { clearTwitchSiteData() }
            } else {
                true
            }
            if (!browserCleared) {
                Log.w(TAG, "Could not clear the Twitch browser session during logout")
            }
            val nativeCleared = withContextNonCancellable { sessionStore.clearAll() }
            if (nativeCleared) {
                clearAcceptedCookieSnapshot()
                rejectedCandidateToken = null
                authSessionMaintainer.onAuthenticationStateChanged()
                _state.value = TwitchWebSessionState.SignedOut
            }
            nativeCleared
        }
    }

    private fun installBridgeAndLoad(currentSession: GeckoSession) {
        if (bridgeInstalled) {
            loadTwitchPage(currentSession)
            return
        }
        pendingBridgeLoads += currentSession
        if (bridgeInstallationStarted) {
            return
        }
        bridgeInstallationStarted = true
        runtime().webExtensionController
            .ensureBuiltIn(SESSION_BRIDGE_LOCATION, SESSION_BRIDGE_ID)
            .accept(
                { extension ->
                    if (extension == null) {
                        _state.value = TwitchWebSessionState.RecoverableError
                    } else {
                        bridgeInstalled = true
                        extension.setMessageDelegate(messageDelegate, NATIVE_APP)
                        val sessionsToLoad = pendingBridgeLoads.toList()
                        pendingBridgeLoads.clear()
                        sessionsToLoad.forEach { pendingSession ->
                            loadTwitchPage(pendingSession)
                        }
                    }
                },
                {
                    bridgeInstallationStarted = false
                    pendingBridgeLoads.clear()
                    _state.value = TwitchWebSessionState.RecoverableError
                },
            )
    }

    private fun loadTwitchPage(currentSession: GeckoSession) {
        if (currentSession === session) {
            if (pageLoadStarted) return
            pageLoadStarted = true
        }
        currentSession.loadUri(
            if (currentSession === temporaryIntegritySession) TWITCH_HOME_URL else TWITCH_LOGIN_URL,
        )
    }

    private fun startIntegrityRefreshPage(): Boolean {
        if (sessionStore.read() == null) return false
        val refreshSession = GeckoSession().also {
            it.open(runtime())
        }
        temporaryIntegritySession = refreshSession
        installBridgeAndLoad(refreshSession)
        return true
    }

    private fun closeTemporaryIntegritySession() {
        val refreshSession = temporaryIntegritySession ?: return
        temporaryIntegritySession = null
        pendingBridgeLoads.remove(refreshSession)
        runCatching { refreshSession.close() }
    }

    private val messageDelegate = object : WebExtension.MessageDelegate {
        override fun onMessage(
            nativeApp: String,
            message: Any,
            sender: WebExtension.MessageSender,
        ): GeckoResult<Any>? {
            if (nativeApp != NATIVE_APP || message !is JSONObject) return null

            if (message.optString("type") == "twitch_integrity") {
                parseGeckoGqlIdentity(message)?.let { capturedIdentity ->
                    val eventGeneration = sessionGeneration
                    scope.launch {
                        candidateMutex.withLock {
                            processGeckoGqlIdentityEvent(eventGeneration, capturedIdentity)
                        }
                    }
                }
                return null
            }

            if (message.optString("type") != "twitch_session") return null

            val reason = message.optString("reason")
            val cookies = parseCookies(message.optJSONArray("cookies"))
            val authToken = cookies.firstOrNull { it.name == AUTH_TOKEN_COOKIE }?.value
                ?: message.optString("authToken").takeIf { it.isNotBlank() }
            val change = message.optJSONObject("change")
            val changedCookie = change?.optJSONObject("cookie")
            val authTokenWasRemoved = if (changedCookie != null) {
                changedCookie.optString("name") == AUTH_TOKEN_COOKIE &&
                    change.optBoolean("removed") && change.optString("cause") != "overwrite"
            } else {
                false
            }
            val eventGeneration = sessionGeneration
            scope.launch {
                candidateMutex.withLock {
                    processSessionEvent(
                        generation = eventGeneration,
                        reason = reason,
                        authToken = authToken,
                        cookies = cookies,
                        authTokenWasRemoved = authTokenWasRemoved,
                    )
                }
            }
            return null
        }
    }

    private suspend fun processGeckoGqlIdentityEvent(
        generation: Long,
        capturedIdentity: GeckoGqlIdentity,
    ) {
        if (generation != sessionGeneration) return
        synchronized(integrityStateLock) {
            if (capturedIdentity.capturedAt <= integrityInvalidatedAt) return
            val currentSession = sessionStore.read()
            if (currentSession == null) {
                pendingGeckoGqlIdentity = capturedIdentity
                return
            }
            if (!authorizationBelongsTo(capturedIdentity.authorization, currentSession.accessToken)) {
                pendingGeckoGqlIdentity = capturedIdentity
                return
            }
            commitGeckoGqlIdentity(capturedIdentity, currentSession.userId)
        }
    }

    private fun commitGeckoGqlIdentity(capturedIdentity: GeckoGqlIdentity, userId: String) {
        synchronized(integrityStateLock) {
            if (capturedIdentity.capturedAt <= integrityInvalidatedAt) return
            val identity = capturedIdentity.copy(userId = userId)
            if (!sessionStore.commitGeckoGqlIdentity(identity)) return
            pendingGeckoGqlIdentity = null
            integrityRefreshWaiter?.complete(true)
        }
    }

    private fun commitPendingGeckoGqlIdentity(accessToken: String, userId: String) {
        synchronized(integrityStateLock) {
            val pendingIdentity = pendingGeckoGqlIdentity ?: return
            if (!authorizationBelongsTo(pendingIdentity.authorization, accessToken)) return
            commitGeckoGqlIdentity(pendingIdentity, userId)
        }
    }

    private fun parseGeckoGqlIdentity(message: JSONObject): GeckoGqlIdentity? {
        val authorization = message.optString("authorization").takeIf { it.isNotBlank() }
        val clientId = message.optString("clientId").takeIf { it.isNotBlank() }
        val clientIntegrity = message.optString("clientIntegrity").takeIf { it.isNotBlank() }
        val xDeviceId = message.optString("xDeviceId").takeIf { it.isNotBlank() }
        if (authorization == null || clientId == null || clientIntegrity == null || xDeviceId == null) return null
        val accessToken = authorization.substring("OAuth ".length).takeIf {
            authorization.startsWith("OAuth ", ignoreCase = true)
        } ?: return null
        return GeckoGqlIdentity(
            authorization = authorization,
            clientId = clientId,
            clientIntegrity = clientIntegrity,
            xDeviceId = xDeviceId,
            clientSessionId = message.optString("clientSessionId").takeIf { it.isNotBlank() },
            userId = "",
            authTokenFingerprint = GeckoGqlIdentity.fingerprintForAccessToken(accessToken),
            capturedAt = message.optLong("capturedAt", System.currentTimeMillis()),
        )
    }

    private fun authorizationBelongsTo(authorization: String, accessToken: String): Boolean {
        val prefix = "OAuth "
        return authorization.startsWith(prefix, ignoreCase = true) &&
            authorization.substring(prefix.length) == accessToken
    }

    private suspend fun processSessionEvent(
        generation: Long,
        reason: String,
        authToken: String?,
        cookies: List<TwitchWebCookie>,
        authTokenWasRemoved: Boolean,
    ) {
        if (generation != sessionGeneration) return
        if (authToken.isNullOrBlank()) {
            val shouldSignOut = !reauthorize &&
                (authTokenWasRemoved || reason == INITIAL_REASON || reason == PAGE_REQUEST_REASON)
            if (shouldSignOut) signOutAfterBrowserSessionDisappeared(generation)
            return
        }

        val stored = sessionStore.read()
        if (stored?.accessToken == authToken && _state.value is TwitchWebSessionState.Authenticated) {
            // Cookie metadata may have changed while the authenticated token did
            // not. Keep the live header, but do not validate the same token again.
            if (!publishAcceptedCookies(cookies)) {
                _state.value = TwitchWebSessionState.RecoverableError
            }
            commitPendingGeckoGqlIdentity(authToken, stored.userId)
            return
        }
        validateCandidateLocked(authToken, cookies, generation)
    }

    private suspend fun validateCandidateLocked(
        accessToken: String,
        cookies: List<TwitchWebCookie>,
        generation: Long,
    ) {
        if (generation != sessionGeneration) return
        if (rejectedCandidateToken == accessToken) return
        if (lastCandidateToken == accessToken && _state.value is TwitchWebSessionState.Validating) return
        lastCandidateToken = accessToken
        _state.value = TwitchWebSessionState.Validating
        try {
            // Twitch web sessions are OAuth credentials for the web/GQL client.
            val response = authRepository.validate(
                networkLibrary = applicationContext.prefs().getString(C.NETWORK_LIBRARY, C.OKHTTP),
                authorization = "OAuth $accessToken",
            )
            ensureExpectedWebSession(response)
            if (generation != sessionGeneration) return
            val userId = response.userId ?: error("Twitch returned no account identity")
            val previous = sessionStore.read()
            val expectedUserId = expectedReauthorizationUserId
            if (reauthorize && expectedUserId != null && expectedUserId != userId) {
                _state.value = TwitchWebSessionState.AccountMismatch
                return
            }
            if (generation != sessionGeneration) return
            val committed = withContextNonCancellable {
                sessionStore.commitWebSession(
                    accessToken = accessToken,
                    userId = userId,
                    login = response.login,
                    scopes = response.scopes,
                    cookieHeader = TwitchWebCookiePolicy.headerFor(GQL_URL, cookies),
                )
            }
            if (!committed) error("Could not save Twitch session")
            publishAcceptedCookies(cookies, persist = false)
            commitPendingGeckoGqlIdentity(accessToken, userId)
            authSessionMaintainer.onAuthenticationStateChanged()
            _state.value = TwitchWebSessionState.Authenticated(
                userId = userId,
                login = response.login,
                accountChanged = previous?.userId?.let { it != userId } == true,
            )
        } catch (error: CancellationException) {
            throw error
        } catch (error: TwitchAuthHttpException) {
            if (error.statusCode == 401 && generation == sessionGeneration) {
                rejectedCandidateToken = accessToken
                clearCandidateAndReload(accessToken, generation)
            } else if (generation == sessionGeneration) {
                _state.value = TwitchWebSessionState.RecoverableError
            }
        } catch (_: IOException) {
            if (generation != sessionGeneration) return
            _state.value = TwitchWebSessionState.RecoverableError
        } catch (_: Exception) {
            if (generation != sessionGeneration) return
            _state.value = TwitchWebSessionState.RecoverableError
        }
    }

    private suspend fun clearCandidateAndReload(accessToken: String, generation: Long) {
        if (generation != sessionGeneration) return
        invalidatePendingWork()
        val recoveryGeneration = sessionGeneration
        rejectedCandidateToken = accessToken
        withContext(Dispatchers.Main.immediate) {
            closeSessionForStorageClear()
        }
        if (!clearTwitchSiteData()) {
            if (recoveryGeneration != sessionGeneration) return
            _state.value = TwitchWebSessionState.RecoverableError
            withContext(Dispatchers.Main.immediate) {
                if (recoveryGeneration == sessionGeneration) createLoginSessionInternal()
            }
            return
        }
        if (recoveryGeneration != sessionGeneration) return
        _state.value = TwitchWebSessionState.Opening
        withContext(Dispatchers.Main.immediate) {
            if (recoveryGeneration == sessionGeneration) createLoginSessionInternal()
        }
    }

    private suspend fun signOutAfterBrowserSessionDisappeared(generation: Long) {
        if (generation != sessionGeneration) return
        invalidatePendingWork()
        val nativeCleared = withContextNonCancellable { sessionStore.clearAll() }
        if (!nativeCleared) {
            _state.value = TwitchWebSessionState.RecoverableError
            return
        }
        clearAcceptedCookieSnapshot()
        authSessionMaintainer.onAuthenticationStateChanged()
        _state.value = TwitchWebSessionState.SignedOut
    }

    private suspend fun publishAcceptedCookies(
        cookies: List<TwitchWebCookie>,
        persist: Boolean = true,
    ): Boolean {
        val cookieHeader = TwitchWebCookiePolicy.headerFor(GQL_URL, cookies)
        if (persist && !sessionStore.updateWebCookieHeader(cookieHeader)) return false
        synchronized(cookiesLock) {
            cookieSnapshot = cookies
            hasCookieSnapshot = true
        }
        return true
    }

    private fun clearAcceptedCookieSnapshot() {
        synchronized(cookiesLock) {
            cookieSnapshot = emptyList()
            hasCookieSnapshot = true
        }
    }

    private fun invalidatePendingWork() {
        synchronized(sessionLock) {
            sessionGeneration += 1
        }
        lastCandidateToken = null
        rejectedCandidateToken = null
        synchronized(integrityStateLock) {
            pendingGeckoGqlIdentity = null
        }
        integrityRefreshWaiter?.complete(false)
    }

    private fun closeSessionInternal() {
        synchronized(sessionLock) {
            session?.let { runCatching { it.close() } }
            temporaryIntegritySession?.let { runCatching { it.close() } }
            temporaryIntegritySession = null
            pendingBridgeLoads.clear()
            session = null
            _loginSession.value = null
            pageLoadStarted = false
            reauthorize = false
            expectedReauthorizationUserId = null
        }
    }

    private fun closeSessionForStorageClear() {
        synchronized(sessionLock) {
            session?.let { runCatching { it.close() } }
            temporaryIntegritySession?.let { runCatching { it.close() } }
            temporaryIntegritySession = null
            pendingBridgeLoads.clear()
            session = null
            _loginSession.value = null
            pageLoadStarted = false
        }
    }

    private fun createLoginSessionInternal(): GeckoSession = synchronized(sessionLock) {
        val freshSession = GeckoSession().also {
            it.open(runtime())
            session = it
            _loginSession.value = it
        }
        pageLoadStarted = false
        installBridgeAndLoad(freshSession)
        freshSession
    }

    private suspend fun clearTwitchSiteData(): Boolean = withContext(Dispatchers.Main.immediate) {
        suspendCancellableCoroutine { continuation ->
            val flags = StorageController.ClearFlags.SITE_DATA or StorageController.ClearFlags.AUTH_SESSIONS
            runtime().storageController
                .clearDataFromBaseDomain(TWITCH_BASE_DOMAIN, flags)
                .accept(
                    { if (continuation.isActive) continuation.resume(true) },
                    { if (continuation.isActive) continuation.resume(false) },
                )
        }
    }

    private fun parseCookies(array: JSONArray?): List<TwitchWebCookie> {
        if (array == null) return emptyList()
        return buildList {
            for (index in 0 until array.length()) {
                val cookie = array.optJSONObject(index) ?: continue
                val name = cookie.optString("name").takeIf { it.isNotBlank() } ?: continue
                val value = cookie.optString("value")
                val domain = cookie.optString("domain").takeIf { it.isNotBlank() } ?: continue
                val expirationDateMillis = if (cookie.has("expirationDate") && !cookie.isNull("expirationDate")) {
                    (cookie.optDouble("expirationDate") * 1_000L).toLong()
                } else {
                    null
                }
                add(
                    TwitchWebCookie(
                        name = name,
                        value = value,
                        domain = domain,
                        path = cookie.optString("path", "/"),
                        secure = cookie.optBoolean("secure"),
                        hostOnly = cookie.optBoolean("hostOnly"),
                        expirationDateMillis = expirationDateMillis,
                    ),
                )
            }
        }
    }

    private fun ensureExpectedWebSession(response: ValidationResponse) {
        if (response.clientId != C.DEFAULT_GQL_CLIENT_ID_WEB) error("Unexpected Twitch web client")
        if (response.userId.isNullOrBlank()) error("Twitch returned no account identity")
    }

    private fun runtime(): GeckoRuntime = synchronized(sessionLock) {
        runtime ?: GeckoRuntime.create(applicationContext).also {
            runtime = it
            it.warmUp()
        }
    }

    private suspend fun <T> withContextNonCancellable(block: suspend () -> T): T =
        kotlinx.coroutines.withContext(NonCancellable) { block() }

    private companion object {
        const val TAG = "TwitchWebSessionManager"
        const val AUTH_TOKEN_COOKIE = "auth-token"
        const val INITIAL_REASON = "initial"
        const val PAGE_REQUEST_REASON = "page_request"
        const val TWITCH_BASE_DOMAIN = "twitch.tv"
        const val TWITCH_LOGIN_URL = "https://www.twitch.tv/login"
        const val TWITCH_HOME_URL = "https://www.twitch.tv/"
        const val GQL_URL = "https://gql.twitch.tv/gql"
        const val SESSION_BRIDGE_LOCATION = "resource://android/assets/twitch_session_bridge/"
        const val SESSION_BRIDGE_ID = "twitch-session-bridge@xtra"
        const val NATIVE_APP = "com.github.andreyasadchy.xtra.session"
        const val INTEGRITY_REFRESH_TIMEOUT_MILLIS = 30_000L
        const val DEBUG_INVALID_CLIENT_INTEGRITY = "xtra-debug-invalid-client-integrity"
    }
}
