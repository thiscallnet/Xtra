package com.github.andreyasadchy.xtra.repository.auth

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import com.github.andreyasadchy.xtra.model.id.ValidationResponse
import com.github.andreyasadchy.xtra.repository.AuthRepository
import com.github.andreyasadchy.xtra.util.C
import com.github.andreyasadchy.xtra.util.TwitchApiHelper
import com.github.andreyasadchy.xtra.util.prefs
import com.github.andreyasadchy.xtra.util.tokenPrefs
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import java.io.IOException

internal const val AUTH_SESSION_VALIDATION_INTERVAL_MILLIS = 60 * 60 * 1_000L
internal const val AUTH_SESSION_TRANSIENT_RETRY_DELAY_MILLIS = 5 * 60 * 1_000L
internal const val AUTH_SESSION_NETWORK_RETRY_DELAY_MILLIS = 5 * 60 * 1_000L

/** Returns null when only an explicit wake can make the next validation meaningful. */
internal fun authSessionValidationWaitMs(
    sessionPresent: Boolean,
    webTokenPresent: Boolean,
    maintenanceState: AuthSessionMaintenanceState,
    validatedNetwork: Boolean,
    validationChecked: Boolean,
    lastValidatedAtMs: Long,
    nowMs: Long,
    transientRetryDeadlineMs: Long? = null,
    networkWakeAvailable: Boolean = true,
): Long? {
    if (!sessionPresent || !webTokenPresent ||
        maintenanceState == AuthSessionMaintenanceState.REAUTHORIZATION_REQUIRED
    ) return null
    if (!validatedNetwork) {
        return if (networkWakeAvailable) null else AUTH_SESSION_NETWORK_RETRY_DELAY_MILLIS
    }
    transientRetryDeadlineMs?.let { return (it - nowMs).coerceAtLeast(0L) }
    if (!validationChecked || lastValidatedAtMs <= 0L) return 0L
    return (lastValidatedAtMs + AUTH_SESSION_VALIDATION_INTERVAL_MILLIS - nowMs)
        .coerceAtLeast(0L)
}

internal class AuthSessionChangeSignal {
    private val _generation = MutableStateFlow(0L)
    val generation: StateFlow<Long> = _generation.asStateFlow()

    fun signal() {
        _generation.update { it + 1L }
    }
}

/** Public lifecycle states retained for the existing foreground/background consumers. */
enum class AuthSessionMaintenanceState {
    IDLE,
    VALID,
    TRANSIENT_FAILURE,
    REAUTHORIZATION_REQUIRED,
}

/**
 * Maintains the Twitch web/GQL credential imported from GeckoView.
 *
 * There is deliberately no refresh path here. A Twitch web auth-token is validated in place;
 * transient failures retain the credential, while only an authoritative 401 requests login.
 */
class AuthSessionMaintainer(
    context: Context,
    private val authRepository: AuthRepository,
    private val networkLibrary: () -> String? = {
        context.prefs().getString(C.NETWORK_LIBRARY, C.OKHTTP)
    },
    private val nowMillis: () -> Long = { System.currentTimeMillis() },
) {
    private val applicationContext = context.applicationContext
    private val validationMutex = Mutex()
    private val _state = MutableStateFlow(AuthSessionMaintenanceState.IDLE)
    private val _authHealth = MutableStateFlow(AuthHealth.SIGNED_OUT)
    private val authenticationChangeSignal = AuthSessionChangeSignal()
    private val wakeSignal = Channel<Unit>(Channel.CONFLATED)
    private var promptedReauthorization = false
    private var schedulerJob: Job? = null
    private var networkCallback: ConnectivityManager.NetworkCallback? = null
    @Volatile
    private var networkCallbackRegistered = false

    val state: StateFlow<AuthSessionMaintenanceState> = _state.asStateFlow()
    val authHealth: StateFlow<AuthHealth> = _authHealth.asStateFlow()
    val authenticationChangeGeneration: StateFlow<Long> = authenticationChangeSignal.generation

    init {
        refreshAuthenticationState()
    }

    @Synchronized
    fun start(scope: CoroutineScope) {
        if (schedulerJob?.isActive == true) return
        registerNetworkCallback()
        schedulerJob = scope.launch(Dispatchers.IO) {
            schedulerLoop()
        }.also { job ->
            job.invokeOnCompletion { unregisterNetworkCallback() }
        }
    }

    private suspend fun schedulerLoop() {
        var transientRetryDeadlineMs: Long? = null
        while (currentCoroutineContext().isActive) {
            val tokenPrefs = applicationContext.tokenPrefs()
            val sessionPresent = AuthSessionStore(applicationContext.prefs(), tokenPrefs).read() != null
            val waitMs = authSessionValidationWaitMs(
                sessionPresent = sessionPresent,
                webTokenPresent = !tokenPrefs.getString(C.GQL_TOKEN_WEB, null).isNullOrBlank(),
                maintenanceState = _state.value,
                validatedNetwork = hasValidatedNetwork(),
                validationChecked = TwitchApiHelper.checkedValidation,
                lastValidatedAtMs = tokenPrefs.getLong(C.TOKEN_VALIDATED_AT, 0L),
                nowMs = nowMillis(),
                transientRetryDeadlineMs = transientRetryDeadlineMs,
                networkWakeAvailable = networkCallbackRegistered,
            )
            if (waitMs == null) {
                wakeSignal.receive()
                transientRetryDeadlineMs = null
                continue
            }
            if (waitMs > 0L) {
                val woke = withTimeoutOrNull(waitMs) { wakeSignal.receive() }
                if (woke != null) transientRetryDeadlineMs = null
                continue
            }
            val result = try {
                validateIfDue()
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                publish(AuthSessionMaintenanceState.TRANSIENT_FAILURE, AuthHealth.UNKNOWN)
                AuthSessionMaintenanceState.TRANSIENT_FAILURE
            }
            transientRetryDeadlineMs = if (result == AuthSessionMaintenanceState.TRANSIENT_FAILURE) {
                nowMillis() + AUTH_SESSION_TRANSIENT_RETRY_DELAY_MILLIS
            } else null
        }
    }

    private fun registerNetworkCallback() {
        val connectivityManager = applicationContext
            .getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                wakeSignal.trySend(Unit)
            }

            override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) {
                if (capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)) {
                    wakeSignal.trySend(Unit)
                }
            }

            override fun onLost(network: Network) {
                wakeSignal.trySend(Unit)
            }
        }
        networkCallback = callback
        runCatching {
            connectivityManager.registerNetworkCallback(
                NetworkRequest.Builder()
                    .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                    .addCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
                    .build(),
                callback,
            )
        }.onSuccess {
            networkCallbackRegistered = true
        }.onFailure {
            networkCallback = null
            networkCallbackRegistered = false
        }
    }

    private fun unregisterNetworkCallback() {
        val callback = networkCallback ?: return
        networkCallback = null
        networkCallbackRegistered = false
        runCatching {
            (applicationContext.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager)
                ?.unregisterNetworkCallback(callback)
        }
    }

    suspend fun validateIfDue(): AuthSessionMaintenanceState = validationMutex.withLock {
        val sessionStore = AuthSessionStore(applicationContext.prefs(), applicationContext.tokenPrefs())
        if (sessionStore.read() == null) {
            publish(AuthSessionMaintenanceState.IDLE, AuthHealth.SIGNED_OUT)
            return@withLock AuthSessionMaintenanceState.IDLE
        }
        if (_state.value == AuthSessionMaintenanceState.REAUTHORIZATION_REQUIRED) {
            return@withLock _state.value
        }
        if (!hasValidatedNetwork()) {
            publish(AuthSessionMaintenanceState.TRANSIENT_FAILURE, AuthHealth.UNKNOWN)
            return@withLock _state.value
        }
        if (!TwitchApiHelper.isSessionValidationDue(applicationContext)) {
            return@withLock _state.value
        }

        when (validateWebSession(sessionStore)) {
            ValidationOutcome.VALID -> publish(AuthSessionMaintenanceState.VALID, AuthHealth.HEALTHY)
            ValidationOutcome.INVALID -> publish(
                AuthSessionMaintenanceState.REAUTHORIZATION_REQUIRED,
                AuthHealth.REAUTH_REQUIRED,
            )
            ValidationOutcome.TRANSIENT -> publish(
                AuthSessionMaintenanceState.TRANSIENT_FAILURE,
                AuthHealth.UNKNOWN,
            )
        }
        return@withLock _state.value
    }

    fun consumeReauthorizationRequest(): AuthSessionMaintenanceState? {
        if (_state.value != AuthSessionMaintenanceState.REAUTHORIZATION_REQUIRED || promptedReauthorization) {
            return null
        }
        promptedReauthorization = true
        return _state.value
    }

    /** Refreshes the in-memory state after the Gecko manager commits a web session. */
    fun onAuthenticationStateChanged() {
        refreshAuthenticationState()
        authenticationChangeSignal.signal()
        wakeSignal.trySend(Unit)
    }

    private fun refreshAuthenticationState() {
        val sessionStore = AuthSessionStore(applicationContext.prefs(), applicationContext.tokenPrefs())
        promptedReauthorization = false
        if (sessionStore.read() == null) {
            publish(AuthSessionMaintenanceState.IDLE, AuthHealth.SIGNED_OUT)
        } else {
            publish(AuthSessionMaintenanceState.VALID, AuthHealth.HEALTHY)
        }
    }

    private suspend fun validateWebSession(sessionStore: AuthSessionStore): ValidationOutcome {
        val session = sessionStore.read() ?: return ValidationOutcome.INVALID
        return try {
            val response = authRepository.validate(
                networkLibrary(),
                "OAuth ${session.accessToken}",
            )
            if (!isExpectedWebSession(response, session)) {
                ValidationOutcome.INVALID
            } else {
                val editor = applicationContext.tokenPrefs().edit()
                    .putLong(C.TOKEN_VALIDATED_AT, nowMillis())
                    .putString(C.USER_ID, response.userId)
                    .putString(C.GQL_TOKEN_WEB_USER_ID, response.userId)
                    .putString(C.TOKEN_SCOPES, response.scopes.sorted().joinToString(" "))
                response.login?.takeIf { it.isNotBlank() }?.let { editor.putString(C.USERNAME, it) }
                if (!editor.commit()) ValidationOutcome.TRANSIENT else {
                    TwitchApiHelper.checkedValidation = true
                    ValidationOutcome.VALID
                }
            }
        } catch (error: CancellationException) {
            throw error
        } catch (error: TwitchAuthHttpException) {
            if (error.statusCode == 401) ValidationOutcome.INVALID else ValidationOutcome.TRANSIENT
        } catch (_: IOException) {
            ValidationOutcome.TRANSIENT
        } catch (_: Exception) {
            // Validation endpoint outages and malformed intermediary responses do not prove that
            // the browser session is dead.
            ValidationOutcome.TRANSIENT
        }
    }

    private fun isExpectedWebSession(response: ValidationResponse, session: AuthSession): Boolean =
        response.clientId == session.clientId &&
            !response.userId.isNullOrBlank() &&
            response.userId == session.userId

    private fun hasValidatedNetwork(): Boolean {
        val connectivityManager = applicationContext.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            ?: return false
        val capabilities = connectivityManager.getNetworkCapabilities(connectivityManager.activeNetwork)
        return capabilities?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true &&
            capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) == true
    }

    private fun publish(state: AuthSessionMaintenanceState, health: AuthHealth) {
        _state.value = state
        _authHealth.value = health
    }

    private enum class ValidationOutcome {
        VALID,
        INVALID,
        TRANSIENT,
    }

}
