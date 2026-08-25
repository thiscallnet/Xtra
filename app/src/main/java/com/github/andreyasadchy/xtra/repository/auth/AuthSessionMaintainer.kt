package com.github.andreyasadchy.xtra.repository.auth

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
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
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.IOException

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
    private var promptedReauthorization = false
    private var schedulerJob: Job? = null

    val state: StateFlow<AuthSessionMaintenanceState> = _state.asStateFlow()
    val authHealth: StateFlow<AuthHealth> = _authHealth.asStateFlow()

    init {
        onAuthenticationStateChanged()
    }

    @Synchronized
    fun start(scope: CoroutineScope) {
        if (schedulerJob?.isActive == true) return
        schedulerJob = scope.launch(Dispatchers.IO) {
            while (isActive) {
                try {
                    validateIfDue()
                } catch (error: CancellationException) {
                    throw error
                } catch (_: Exception) {
                    publish(AuthSessionMaintenanceState.TRANSIENT_FAILURE, AuthHealth.UNKNOWN)
                }
                delay(VALIDATION_CHECK_INTERVAL_MILLIS)
            }
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

    private companion object {
        const val VALIDATION_CHECK_INTERVAL_MILLIS = 60_000L
    }
}
