package com.github.andreyasadchy.xtra.ui.login

import android.app.Application
import androidx.core.net.toUri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.github.andreyasadchy.xtra.XtraApp
import com.github.andreyasadchy.xtra.repository.auth.AuthCommitResult
import com.github.andreyasadchy.xtra.repository.auth.AuthCoordinator
import com.github.andreyasadchy.xtra.repository.auth.AuthSessionStore
import com.github.andreyasadchy.xtra.repository.auth.DeviceAuthorizationPoller
import com.github.andreyasadchy.xtra.repository.auth.TwitchAuthAccountMismatchException
import com.github.andreyasadchy.xtra.repository.auth.TwitchAuthException
import com.github.andreyasadchy.xtra.repository.auth.TwitchAuthHttpException
import com.github.andreyasadchy.xtra.repository.auth.TwitchAuthMissingScopesException
import com.github.andreyasadchy.xtra.repository.auth.TwitchAuthProtocolException
import com.github.andreyasadchy.xtra.repository.auth.TwitchAuthRepository
import com.github.andreyasadchy.xtra.repository.auth.TwitchClientConfig
import com.github.andreyasadchy.xtra.util.C
import com.github.andreyasadchy.xtra.util.prefs
import com.github.andreyasadchy.xtra.util.tokenPrefs
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import java.io.IOException

class LoginViewModel(
    application: Application,
    private val reauthorize: Boolean,
    private val compatibilityOnly: Boolean = false,
) : ViewModel() {
    private val app = application as XtraApp
    private val networkLibrary = application.prefs().getString(C.NETWORK_LIBRARY, C.OKHTTP)
    private val repository = TwitchAuthRepository(app.xtraModule.authRepository, networkLibrary)
    private val sessionStore = AuthSessionStore(application.prefs(), application.tokenPrefs())
    private val coordinator = AuthCoordinator(repository, sessionStore)
    private val _state = MutableStateFlow<LoginUiState>(LoginUiState.Idle)
    private val browserRequests = Channel<android.net.Uri>(Channel.BUFFERED)
    private var authorizationJob: kotlinx.coroutines.Job? = null
    private var officialCommit: AuthCommitResult? = null

    val state: StateFlow<LoginUiState> = _state.asStateFlow()
    val browserRequest = browserRequests.receiveAsFlow()

    fun startAuthorization() {
        if (authorizationJob?.isActive == true) return
        authorizationJob = viewModelScope.launch {
            try {
                if (compatibilityOnly) {
                    startCompatibilityAuthorizationOnly()
                    return@launch
                }
                val clientId = TwitchClientConfig.publicClientId()
                if (clientId == null) {
                    _state.value = LoginUiState.Error(LoginError.SETUP_REQUIRED, recoverable = false)
                    return@launch
                }
                _state.value = LoginUiState.Starting
                val deviceAuthorization = repository.startDeviceAuthorization(clientId, HELIX_LOGIN_SCOPES)
                val verificationUri = selectVerificationUri(deviceAuthorization)
                    ?.let { it.toUri() }
                    ?.takeIf { it.scheme == "https" && !it.host.isNullOrBlank() }
                    ?: throw TwitchAuthProtocolException("Twitch returned an invalid verification URL")
                val expiresIn = deviceAuthorization.expiresIn
                    ?: throw TwitchAuthProtocolException("Twitch did not return a device-code lifetime")
                val userCode = deviceAuthorization.userCode
                    ?: throw TwitchAuthProtocolException("Twitch did not return a user code")
                val expiresAtMillis = System.currentTimeMillis() + expiresIn * 1_000L
                _state.value = LoginUiState.WaitingForAuthorization(
                    verificationUri = verificationUri,
                    userCode = userCode,
                    expiresAtMillis = expiresAtMillis,
                    isPolling = false,
                )
                browserRequests.send(verificationUri)
                _state.value = LoginUiState.WaitingForAuthorization(
                    verificationUri = verificationUri,
                    userCode = userCode,
                    expiresAtMillis = expiresAtMillis,
                    isPolling = true,
                )
                val tokenResponse = DeviceAuthorizationPoller(
                    requestToken = { deviceCode, scopes -> repository.pollDeviceAuthorization(clientId, deviceCode, scopes) },
                ).poll(deviceAuthorization, HELIX_LOGIN_SCOPES)
                _state.value = LoginUiState.Validating
                val result = coordinator.validateAndCommit(
                    tokenResponse = tokenResponse,
                    expectedClientId = clientId,
                    reauthorize = reauthorize,
                )
                officialCommit = result
                val compatibilityAvailable = acquireCompatibilityIfNeeded(result.session.userId)
                _state.value = LoginUiState.Complete(
                    accountChanged = result.accountChanged,
                    revocationFailures = result.revocationFailures,
                    compatibilityAvailable = compatibilityAvailable,
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.value = LoginUiState.Error(mapError(e), recoverable = isRecoverable(e))
            }
        }
    }

    private suspend fun startCompatibilityAuthorizationOnly() {
        val session = sessionStore.read()
            ?: throw TwitchAuthProtocolException("A signed-in Twitch account is required for compatibility authorization")
        if (!acquireCompatibilityIfNeeded(session.userId, failOnError = true)) {
            throw TwitchAuthException("Twitch compatibility authorization was not completed")
        }
        _state.value = LoginUiState.Complete(
            accountChanged = false,
            revocationFailures = 0,
            compatibilityAvailable = true,
        )
    }

    fun logout() {
        if (authorizationJob?.isActive == true) return
        authorizationJob = viewModelScope.launch {
            try {
                _state.value = LoginUiState.Starting
                val failures = coordinator.logout()
                _state.value = LoginUiState.Complete(accountChanged = true, revocationFailures = failures)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.value = LoginUiState.Error(mapError(e), recoverable = false)
            }
        }
    }

    fun openBrowserAgain() {
        val uri = when (val state = _state.value) {
            is LoginUiState.WaitingForAuthorization -> state.verificationUri
            is LoginUiState.CompatibilityAuthorization -> state.verificationUri
            else -> return
        }
        browserRequests.trySend(uri)
    }

    fun browserUnavailable() {
        authorizationJob?.cancel()
        val commit = officialCommit
        _state.value = if (commit == null) {
            LoginUiState.Error(LoginError.BROWSER_UNAVAILABLE, recoverable = true)
        } else {
            LoginUiState.Complete(
                accountChanged = commit.accountChanged,
                revocationFailures = commit.revocationFailures,
                compatibilityAvailable = false,
            )
        }
    }

    fun retry() {
        if ((_state.value as? LoginUiState.Error)?.recoverable == true) startAuthorization()
    }

    fun cancel() {
        authorizationJob?.cancel()
        authorizationJob = null
        val commit = officialCommit
        _state.value = if (commit == null) {
            LoginUiState.Idle
        } else {
            LoginUiState.Complete(
                accountChanged = commit.accountChanged,
                revocationFailures = commit.revocationFailures,
                compatibilityAvailable = false,
            )
        }
    }

    override fun onCleared() {
        browserRequests.close()
    }

    private fun mapError(error: Exception): LoginError = when (error) {
        is TwitchAuthAccountMismatchException -> LoginError.ACCOUNT_MISMATCH
        is TwitchAuthMissingScopesException -> LoginError.MISSING_SCOPES
        is TwitchAuthHttpException -> when {
            error.statusCode in 500..599 -> LoginError.SERVER
            error.statusCode == 401 -> LoginError.VALIDATION
            else -> LoginError.NETWORK
        }
        is TwitchAuthProtocolException -> LoginError.MALFORMED_RESPONSE
        is TwitchAuthException -> when {
            error.message?.contains("denied", ignoreCase = true) == true -> LoginError.DENIED
            error.message?.contains("expired", ignoreCase = true) == true -> LoginError.EXPIRED
            error.message?.contains("save", ignoreCase = true) == true -> LoginError.PERSISTENCE
            else -> LoginError.NETWORK
        }
        is IOException -> LoginError.NETWORK
        else -> LoginError.UNKNOWN
    }

    private fun isRecoverable(error: Exception): Boolean = when (mapError(error)) {
        LoginError.SETUP_REQUIRED,
        LoginError.MISSING_SCOPES,
        LoginError.PERSISTENCE -> false
        else -> true
    }

    private suspend fun acquireCompatibilityIfNeeded(userId: String, failOnError: Boolean = false): Boolean {
        if (sessionStore.hasCompatibilityCredential()) return true
        return try {
            val clientId = sessionStore.compatibilityClientId()
                ?: throw TwitchAuthProtocolException("Twitch compatibility client ID is not configured")
            val deviceAuthorization = repository.startDeviceAuthorization(clientId, GQL_COMPATIBILITY_SCOPES)
            val verificationUri = selectVerificationUri(deviceAuthorization)
                ?.let { it.toUri() }
                ?.takeIf { it.scheme == "https" && !it.host.isNullOrBlank() }
                ?: throw TwitchAuthProtocolException("Twitch returned an invalid compatibility URL")
            val expiresIn = deviceAuthorization.expiresIn
                ?: throw TwitchAuthProtocolException("Twitch did not return a compatibility code lifetime")
            val userCode = deviceAuthorization.userCode
                ?: throw TwitchAuthProtocolException("Twitch did not return a compatibility user code")
            val expiresAtMillis = System.currentTimeMillis() + expiresIn * 1_000L
            _state.value = LoginUiState.CompatibilityAuthorization(
                verificationUri = verificationUri,
                userCode = userCode,
                expiresAtMillis = expiresAtMillis,
                isPolling = false,
            )
            browserRequests.send(verificationUri)
            _state.value = LoginUiState.CompatibilityAuthorization(
                verificationUri = verificationUri,
                userCode = userCode,
                expiresAtMillis = expiresAtMillis,
                isPolling = true,
            )
            val tokenResponse = DeviceAuthorizationPoller(
                requestToken = { deviceCode, scopes -> repository.pollDeviceAuthorization(clientId, deviceCode, scopes) },
            ).poll(deviceAuthorization, GQL_COMPATIBILITY_SCOPES)
            coordinator.validateAndCommitCompatibility(tokenResponse, clientId, userId)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            if (failOnError) throw e
            false
        }
    }

    class Factory(
        private val application: Application,
        private val reauthorize: Boolean,
        private val compatibilityOnly: Boolean = false,
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            require(modelClass.isAssignableFrom(LoginViewModel::class.java))
            return LoginViewModel(application, reauthorize, compatibilityOnly) as T
        }
    }
}
