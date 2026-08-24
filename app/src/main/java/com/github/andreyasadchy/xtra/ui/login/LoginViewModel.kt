package com.github.andreyasadchy.xtra.ui.login

import android.app.Application
import androidx.core.net.toUri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.github.andreyasadchy.xtra.XtraApp
import com.github.andreyasadchy.xtra.model.id.TokenResponse
import com.github.andreyasadchy.xtra.repository.auth.AuthCoordinator
import com.github.andreyasadchy.xtra.repository.auth.AuthSession
import com.github.andreyasadchy.xtra.repository.auth.AuthSessionStore
import com.github.andreyasadchy.xtra.repository.auth.CompatibilitySession
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.IOException

internal fun canCancelLogin(finalCommitStarted: Boolean): Boolean = !finalCommitStarted

internal fun isCompatibilityLoginPhase(
    compatibilityOnly: Boolean,
    officialCommittedThisFlow: Boolean,
): Boolean = compatibilityOnly || officialCommittedThisFlow

/**
 * Runs the official Twitch grant and the optional compatibility grant for an Xtra login.
 *
 * The official grant is committed as soon as it validates. Compatibility remains staged until its
 * own validation and can be repaired without changing the official account.
 */
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
    private var stagedOfficial: AuthSession? = null
    private var stagedCompatibility: CompatibilitySession? = null
    private var finalCommitStarted = false
    private var officialCommittedThisFlow = false
    private var officialAccountChanged = false

    val state: StateFlow<LoginUiState> = _state.asStateFlow()
    val browserRequest = browserRequests.receiveAsFlow()

    fun hasCommittedOfficialSession(): Boolean = officialCommittedThisFlow

    fun didChangeOfficialAccount(): Boolean = officialAccountChanged

    fun startAuthorization() {
        if (authorizationJob?.isActive == true) return
        LoginAuthorizationService.start(app)
        authorizationJob = viewModelScope.launch {
            if (compatibilityOnly) runCompatibilityOnlyAuthorization() else runOfficialAuthorization()
        }
    }

    fun logout() {
        if (authorizationJob?.isActive == true) return
        authorizationJob = viewModelScope.launch {
            try {
                _state.value = LoginUiState.Starting
                val failures = coordinator.logout()
                _state.value = LoginUiState.LoggedOut(revocationFailures = failures)
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

    /** Starts the second Twitch approval without making it a second Xtra account state. */
    fun continueCompatibilityAuthorization() {
        if (_state.value !is LoginUiState.CompatibilityReady || authorizationJob?.isActive == true) return
        LoginAuthorizationService.start(app)
        authorizationJob = viewModelScope.launch {
            runCompatibilityAuthorization()
        }
    }

    fun browserUnavailable() {
        if (finalCommitStarted) return
        authorizationJob?.cancel()
        LoginAuthorizationService.stop(app)
        _state.value = if (isCompatibilityLoginPhase(compatibilityOnly, officialCommittedThisFlow)) {
            LoginUiState.CompatibilityError(
                type = LoginError.BROWSER_UNAVAILABLE,
                recoverable = true,
            )
        } else {
            LoginUiState.Error(LoginError.BROWSER_UNAVAILABLE, recoverable = true)
        }
    }

    fun retry() {
        when (val state = _state.value) {
            is LoginUiState.CompatibilityError -> {
                if (state.recoverable) startCompatibilityRetry()
            }
            is LoginUiState.Error -> {
                if (state.recoverable) startAuthorization()
            }
            else -> Unit
        }
    }

    /** Cancels the transaction and leaves the currently active account untouched. */
    fun cancel(): Boolean {
        if (!canCancelLogin(finalCommitStarted)) return false
        authorizationJob?.cancel()
        authorizationJob = null
        LoginAuthorizationService.stop(app)
        discardStagedCredentials()
        _state.value = LoginUiState.Idle
        return true
    }

    override fun onCleared() {
        LoginAuthorizationService.stop(app)
        if (!finalCommitStarted) {
            discardStagedCredentials()
        }
        browserRequests.close()
    }

    private suspend fun runOfficialAuthorization() {
        var tokenResponse: TokenResponse? = null
        var clientId: String? = null
        try {
            val configuredClientId = TwitchClientConfig.publicClientId()
            if (configuredClientId == null) {
                LoginAuthorizationService.stop(app)
                _state.value = LoginUiState.Error(LoginError.SETUP_REQUIRED, recoverable = false)
                return
            }
            clientId = configuredClientId
            _state.value = LoginUiState.Starting
            val deviceAuthorization = repository.startDeviceAuthorization(configuredClientId, HELIX_LOGIN_SCOPES)
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
            tokenResponse = DeviceAuthorizationPoller(
                requestToken = { deviceCode, scopes -> repository.pollDeviceAuthorization(configuredClientId, deviceCode, scopes) },
            ).poll(deviceAuthorization, HELIX_LOGIN_SCOPES)
            _state.value = LoginUiState.Validating
            val official = coordinator.validateOfficial(
                tokenResponse = tokenResponse,
                expectedClientId = configuredClientId,
                reauthorize = reauthorize,
            )
            stagedOfficial = official
            tokenResponse = null
            val commit = withContext(NonCancellable) {
                coordinator.commitOfficialSession(official, reauthorize)
            }
            officialCommittedThisFlow = true
            officialAccountChanged = commit.accountChanged
            stagedOfficial = null
            // The official grant is usable on its own. The second page only enables enhanced
            // compatibility features and can be retried without restarting official OAuth.
            _state.value = LoginUiState.CompatibilityReady
        } catch (e: CancellationException) {
            revokeTokenAfterCancellation(clientId, tokenResponse)
            LoginAuthorizationService.stop(app)
            throw e
        } catch (e: Exception) {
            revokeTokenBestEffort(clientId, tokenResponse?.accessToken)
            discardStagedCredentials()
            LoginAuthorizationService.stop(app)
            _state.value = LoginUiState.Error(mapError(e), recoverable = isRecoverable(e))
        }
    }

    private suspend fun runCompatibilityAuthorization() {
        try {
            val official = stagedOfficial ?: sessionStore.read()
                ?: throw TwitchAuthProtocolException("The official Twitch authorization is no longer available")
            val compatibility = acquireCompatibility(official.userId)
            stagedCompatibility = compatibility
            finalCommitStarted = true
            _state.value = LoginUiState.Committing
            val commit = withContext(NonCancellable) {
                coordinator.commitCompatibilitySession(compatibility)
            }
            stagedOfficial = null
            stagedCompatibility = null
            finalCommitStarted = false
            LoginAuthorizationService.stop(app)
            _state.value = LoginUiState.Complete(
                accountChanged = officialAccountChanged || commit.accountChanged,
                revocationFailures = commit.revocationFailures,
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            finalCommitStarted = false
            // A failed compatibility validation leaves the official grant active so the user can
            // retry only step 2. A failure after a validated compatibility grant exists must
            // discard that grant because it is not active.
            if (stagedCompatibility != null || e is TwitchAuthException &&
                e.message?.contains("save", ignoreCase = true) == true
            ) {
                discardStagedCredentials()
            }
            LoginAuthorizationService.stop(app)
            _state.value = LoginUiState.CompatibilityError(
                type = mapError(e),
                recoverable = isRecoverable(e),
            )
        }
    }

    private suspend fun runCompatibilityOnlyAuthorization() {
        try {
            val official = sessionStore.read()
                ?: throw TwitchAuthProtocolException("The official Twitch authorization is no longer available")
            val compatibility = acquireCompatibility(official.userId)
            stagedCompatibility = compatibility
            finalCommitStarted = true
            _state.value = LoginUiState.Committing
            val commit = withContext(NonCancellable) {
                coordinator.commitCompatibilitySession(compatibility)
            }
            stagedCompatibility = null
            finalCommitStarted = false
            LoginAuthorizationService.stop(app)
            _state.value = LoginUiState.Complete(
                accountChanged = commit.accountChanged,
                revocationFailures = commit.revocationFailures,
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            finalCommitStarted = false
            if (stagedCompatibility != null || e is TwitchAuthException &&
                e.message?.contains("save", ignoreCase = true) == true
            ) {
                discardStagedCredentials()
            }
            LoginAuthorizationService.stop(app)
            _state.value = LoginUiState.CompatibilityError(
                type = mapError(e),
                recoverable = isRecoverable(e),
            )
        }
    }

    private suspend fun acquireCompatibility(userId: String): CompatibilitySession {
        val clientId = sessionStore.compatibilityClientId()
            ?: throw TwitchAuthProtocolException("Twitch compatibility client ID is not configured")
        var tokenResponse: TokenResponse? = null
        try {
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
            tokenResponse = DeviceAuthorizationPoller(
                requestToken = { deviceCode, scopes -> repository.pollDeviceAuthorization(clientId, deviceCode, scopes) },
            ).poll(deviceAuthorization, GQL_COMPATIBILITY_SCOPES)
            return coordinator.validateCompatibility(tokenResponse, clientId, userId)
        } catch (e: CancellationException) {
            revokeTokenAfterCancellation(clientId, tokenResponse)
            throw e
        } catch (e: Exception) {
            revokeTokenBestEffort(clientId, tokenResponse?.accessToken)
            throw e
        }
    }

    private fun discardStagedCredentials() {
        val official = stagedOfficial
        val compatibility = stagedCompatibility
        stagedOfficial = null
        stagedCompatibility = null
        if (official == null && compatibility == null) return
        app.applicationScope.launch(Dispatchers.IO) {
            withContext(NonCancellable) {
                coordinator.revokeStagedCredentials(official, compatibility)
            }
        }
    }

    private suspend fun revokeTokenAfterCancellation(clientId: String?, tokenResponse: TokenResponse?) {
        withContext(NonCancellable) {
            revokeTokenBestEffort(clientId, tokenResponse?.accessToken)
        }
    }

    private suspend fun revokeTokenBestEffort(clientId: String?, accessToken: String?) {
        if (accessToken.isNullOrBlank()) return
        withContext(NonCancellable) {
            runCatching { coordinator.revokeStagedCredential(clientId, accessToken) }
        }
    }

    private fun startCompatibilityRetry() {
        if (authorizationJob?.isActive == true) return
        LoginAuthorizationService.start(app)
        authorizationJob = viewModelScope.launch {
            if (compatibilityOnly) runCompatibilityOnlyAuthorization() else runCompatibilityAuthorization()
        }
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
        LoginError.PERSISTENCE,
        -> false
        else -> true
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
