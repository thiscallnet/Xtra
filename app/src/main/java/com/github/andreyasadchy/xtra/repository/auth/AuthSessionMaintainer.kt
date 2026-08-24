package com.github.andreyasadchy.xtra.repository.auth

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import androidx.core.content.edit
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

enum class AuthSessionMaintenanceState {
    IDLE,
    VALID,
    TRANSIENT_FAILURE,
    REAUTHORIZATION_REQUIRED,
    COMPATIBILITY_REAUTHORIZATION_REQUIRED,
}

internal enum class OfficialAuthState {
    IDLE,
    VALID,
    TRANSIENT_FAILURE,
    REAUTHORIZATION_REQUIRED,
}

internal enum class CompatibilityAuthState {
    UNAVAILABLE,
    AVAILABLE,
    TRANSIENT_FAILURE,
    REAUTHORIZATION_REQUIRED,
}

internal fun rawAccessTokenFromAuthorizationHeader(value: String?): String? {
    val authorization = value?.trim()?.takeIf { it.isNotBlank() } ?: return null
    return when {
        authorization.startsWith("Bearer ", ignoreCase = true) -> authorization.substring(7)
        authorization.startsWith("OAuth ", ignoreCase = true) -> authorization.substring(6)
        else -> authorization
    }.takeIf { it.isNotBlank() }
}

/** Keeps the official and compatibility halves of the composite Xtra session in sync. */
internal class AuthSessionMaintenanceStateMachine {
    private val lock = Any()
    private var officialStateValue = OfficialAuthState.IDLE
    private var compatibilityStateValue = CompatibilityAuthState.UNAVAILABLE
    private var promptedState: AuthSessionMaintenanceState? = null

    val officialState: OfficialAuthState
        get() = synchronized(lock) { officialStateValue }

    val compatibilityState: CompatibilityAuthState
        get() = synchronized(lock) { compatibilityStateValue }

    val maintenanceState: AuthSessionMaintenanceState
        get() = synchronized(lock) { maintenanceStateLocked() }

    fun setOfficialState(state: OfficialAuthState) = synchronized(lock) {
        officialStateValue = state
    }

    fun setCompatibilityState(state: CompatibilityAuthState) = synchronized(lock) {
        compatibilityStateValue = state
    }

    fun shouldSkipOfficialValidation(): Boolean = synchronized(lock) {
        officialStateValue == OfficialAuthState.REAUTHORIZATION_REQUIRED
    }

    fun consumeReauthorizationRequest(): AuthSessionMaintenanceState? = synchronized(lock) {
        val current = maintenanceStateLocked().takeIf {
            it == AuthSessionMaintenanceState.REAUTHORIZATION_REQUIRED ||
                it == AuthSessionMaintenanceState.COMPATIBILITY_REAUTHORIZATION_REQUIRED
        } ?: return@synchronized null
        if (promptedState == current) return@synchronized null
        promptedState = current
        current
    }

    fun onAuthenticationStateChanged(hasOfficialSession: Boolean, hasCompatibilitySession: Boolean) = synchronized(lock) {
        officialStateValue = if (hasOfficialSession) OfficialAuthState.VALID else OfficialAuthState.IDLE
        compatibilityStateValue = if (hasOfficialSession && hasCompatibilitySession) {
            CompatibilityAuthState.AVAILABLE
        } else {
            CompatibilityAuthState.UNAVAILABLE
        }
        promptedState = null
    }

    private fun maintenanceStateLocked(): AuthSessionMaintenanceState = when {
        officialStateValue == OfficialAuthState.REAUTHORIZATION_REQUIRED ->
            AuthSessionMaintenanceState.REAUTHORIZATION_REQUIRED
        compatibilityStateValue == CompatibilityAuthState.REAUTHORIZATION_REQUIRED ->
            AuthSessionMaintenanceState.COMPATIBILITY_REAUTHORIZATION_REQUIRED
        officialStateValue == OfficialAuthState.TRANSIENT_FAILURE ||
            compatibilityStateValue == CompatibilityAuthState.TRANSIENT_FAILURE ->
            AuthSessionMaintenanceState.TRANSIENT_FAILURE
        officialStateValue == OfficialAuthState.VALID -> AuthSessionMaintenanceState.VALID
        else -> AuthSessionMaintenanceState.IDLE
    }
}

internal enum class CompatibilityUnauthorizedRecovery {
    RECOVERED,
    INVALID,
    TRANSIENT_FAILURE,
}

internal suspend fun recoverCompatibilitySessionAfterUnauthorized(
    coordinator: AuthCoordinator,
    sessionStore: AuthSessionStore,
    onInvalid: suspend () -> Unit,
): CompatibilityUnauthorizedRecovery {
    val previousToken = sessionStore.readCompatibility()?.accessToken
        ?: run {
            onInvalid()
            return CompatibilityUnauthorizedRecovery.INVALID
        }
    return try {
        val refreshed = coordinator.refreshCompatibilityAfterUnauthorized(previousToken)
        if (refreshed == null || refreshed.accessToken == previousToken) {
            onInvalid()
            CompatibilityUnauthorizedRecovery.INVALID
        } else {
            // AuthCoordinator durably stores the rotated pair before validating it.
            CompatibilityUnauthorizedRecovery.RECOVERED
        }
    } catch (error: CancellationException) {
        throw error
    } catch (error: Exception) {
        if (isTransientAuthRefreshFailure(error)) {
            CompatibilityUnauthorizedRecovery.TRANSIENT_FAILURE
        } else {
            onInvalid()
            CompatibilityUnauthorizedRecovery.INVALID
        }
    }
}

internal fun isTransientAuthRefreshFailure(error: Exception): Boolean = when (error) {
    is TwitchAuthHttpException -> error.statusCode == 408 || error.statusCode == 429 || error.statusCode >= 500
    is TwitchAuthProtocolException,
    is TwitchAuthAccountMismatchException,
    is TwitchAuthMissingScopesException,
    -> false
    is TwitchAuthException -> true
    else -> true
}

/**
 * Keeps the OAuth session validated independently of any Activity lifecycle.
 *
 * The same instance is used by foreground UI and authenticated background work. It owns the
 * validation mutex, while reauthorization remains a foreground concern exposed through [state].
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
    private val stateMachine = AuthSessionMaintenanceStateMachine()
    private val _state = MutableStateFlow(stateMachine.maintenanceState)
    private val _authHealth = MutableStateFlow(AuthHealth.SIGNED_OUT)
    private var schedulerJob: Job? = null

    val state: StateFlow<AuthSessionMaintenanceState> = _state.asStateFlow()
    val authHealth: StateFlow<AuthHealth> = _authHealth.asStateFlow()

    init {
        onAuthenticationStateChanged()
    }

    /** Starts at most one process-wide validation loop. */
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
                    stateMachine.setOfficialState(OfficialAuthState.TRANSIENT_FAILURE)
                    publishState()
                }
                delay(VALIDATION_CHECK_INTERVAL_MILLIS)
            }
        }
    }

    /** Runs the due validation immediately, or returns the current maintenance state. */
    suspend fun validateIfDue(): AuthSessionMaintenanceState = validationMutex.withLock {
        if (applicationContext.tokenPrefs().getString(C.TOKEN, null).isNullOrBlank()) {
            stateMachine.onAuthenticationStateChanged(false, false)
            publishState()
            return@withLock AuthSessionMaintenanceState.IDLE
        }
        if (stateMachine.shouldSkipOfficialValidation()) {
            return@withLock _state.value
        }
        if (!hasValidatedNetwork()) {
            stateMachine.setOfficialState(OfficialAuthState.TRANSIENT_FAILURE)
            publishState()
            return@withLock AuthSessionMaintenanceState.TRANSIENT_FAILURE
        }
        if (!TwitchApiHelper.isSessionValidationDue(applicationContext)) {
            return@withLock _state.value
        }
        validateSession()
    }

    /**
     * Returns a reauthorization request once per invalid state. Background callers never launch
     * an Activity; the foreground collector consumes this request and owns the UI.
     */
    fun consumeReauthorizationRequest(): AuthSessionMaintenanceState? =
        stateMachine.consumeReauthorizationRequest()

    /** Reconciles maintenance state with the complete credential pair committed by LoginActivity. */
    fun onAuthenticationStateChanged() {
        val sessionStore = AuthSessionStore(applicationContext.prefs(), applicationContext.tokenPrefs())
        val official = sessionStore.read()
        val compatibility = sessionStore.readCompatibility()
        val hasOfficialSession = official != null
        val hasCompatibilitySession = compatibility?.userId == official?.userId
        stateMachine.onAuthenticationStateChanged(hasOfficialSession, hasCompatibilitySession)
        publishState()
    }

    private suspend fun validateSession(): AuthSessionMaintenanceState {
        val sessionStore = AuthSessionStore(applicationContext.prefs(), applicationContext.tokenPrefs())
        val typedAuthRepository = TwitchAuthRepository(authRepository, networkLibrary())
        val coordinator = AuthCoordinator(typedAuthRepository, sessionStore, nowMillis)

        // Local expiry is only an optimization. Twitch validation remains authoritative because
        // an access token may be invalidated before its advertised expiry.
        try {
            coordinator.refreshIfNeeded()
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            // Preserve the existing access token for the authoritative validation below.
        }

        var helixResult = validateHelixSession(typedAuthRepository)
        if (helixResult == ValidationResult.UNAUTHORIZED) {
            val previousToken = sessionStore.read()?.accessToken
            helixResult = try {
                val refreshed = coordinator.refreshIfNeeded(
                    force = true,
                    expectedAccessToken = previousToken,
                )
                if (refreshed == null || refreshed.accessToken == previousToken) {
                    ValidationResult.INVALID
                } else {
                    validateHelixSession(typedAuthRepository)
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                if (isTransientAuthRefreshFailure(error)) {
                    ValidationResult.TRANSIENT_FAILURE
                } else {
                    ValidationResult.INVALID
                }
            }
        }

        return when (helixResult) {
            ValidationResult.VALID -> {
                val compatibilityResult = validateCompatibilitySessions(typedAuthRepository, coordinator)
                applicationContext.tokenPrefs().edit { putLong(C.TOKEN_VALIDATED_AT, nowMillis()) }
                TwitchApiHelper.checkedValidation = true
                stateMachine.setOfficialState(OfficialAuthState.VALID)
                when (compatibilityResult) {
                    ValidationResult.INVALID -> stateMachine.setCompatibilityState(CompatibilityAuthState.REAUTHORIZATION_REQUIRED)
                    ValidationResult.TRANSIENT_FAILURE -> stateMachine.setCompatibilityState(CompatibilityAuthState.TRANSIENT_FAILURE)
                    ValidationResult.VALID -> stateMachine.setCompatibilityState(CompatibilityAuthState.AVAILABLE)
                    ValidationResult.NO_CREDENTIAL -> {
                        if (stateMachine.compatibilityState != CompatibilityAuthState.REAUTHORIZATION_REQUIRED) {
                            stateMachine.setCompatibilityState(CompatibilityAuthState.UNAVAILABLE)
                        }
                    }
                    ValidationResult.UNAUTHORIZED -> Unit
                }
                stateMachine.maintenanceState
            }
            ValidationResult.UNAUTHORIZED,
            ValidationResult.INVALID,
            -> {
                stateMachine.setOfficialState(OfficialAuthState.REAUTHORIZATION_REQUIRED)
                stateMachine.maintenanceState
            }
            ValidationResult.TRANSIENT_FAILURE -> {
                stateMachine.setOfficialState(OfficialAuthState.TRANSIENT_FAILURE)
                stateMachine.maintenanceState
            }
            ValidationResult.NO_CREDENTIAL -> {
                stateMachine.setOfficialState(OfficialAuthState.IDLE)
                stateMachine.maintenanceState
            }
        }.also { publishState() }
    }

    private suspend fun validateHelixSession(repository: TwitchAuthOperations): ValidationResult {
        val headers = TwitchApiHelper.getHelixHeaders(applicationContext)
        val token = applicationContext.tokenPrefs().getString(C.TOKEN, null)
            ?.takeIf { it.isNotBlank() }
            ?: return ValidationResult.NO_CREDENTIAL
        val expectedUserId = applicationContext.tokenPrefs().getString(C.USER_ID, null)
        val expectedLogin = applicationContext.tokenPrefs().getString(C.USERNAME, null)
        return try {
            val response = repository.validate(token)
            if (response.clientId.isBlank() || response.clientId != headers[C.HEADER_CLIENT_ID] || response.userId.isNullOrBlank()) {
                ValidationResult.INVALID
            } else if ((!expectedUserId.isNullOrBlank() && response.userId != expectedUserId) ||
                (!expectedLogin.isNullOrBlank() && !response.login.isNullOrBlank() && !response.login.equals(expectedLogin, ignoreCase = true))
            ) {
                ValidationResult.INVALID
            } else if (!hasRequiredOfficialScopes(response.scopes)) {
                ValidationResult.INVALID
            } else {
                applicationContext.tokenPrefs().edit {
                    putString(C.USER_ID, response.userId)
                    response.login?.takeIf { it.isNotBlank() }?.let { putString(C.USERNAME, it) }
                }
                ValidationResult.VALID
            }
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            if (isUnauthorized(error)) ValidationResult.UNAUTHORIZED else ValidationResult.TRANSIENT_FAILURE
        }
    }

    private suspend fun validateCompatibilitySessions(
        repository: TwitchAuthOperations,
        coordinator: AuthCoordinator,
    ): ValidationResult {
        val expectedUserId = applicationContext.tokenPrefs().getString(C.USER_ID, null)
        val expectedLogin = applicationContext.tokenPrefs().getString(C.USERNAME, null)
        val sessionStore = AuthSessionStore(applicationContext.prefs(), applicationContext.tokenPrefs())
        var invalid = false
        var transientFailure = false

        if (sessionStore.readCompatibility() != null) {
            try {
                coordinator.refreshCompatibilityIfNeeded()
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                if (isTransientAuthRefreshFailure(error)) {
                    transientFailure = true
                } else {
                    coordinator.clearCompatibility()
                    invalid = true
                }
            }
        }

        // A failed refresh may be temporary. Keep both the access and refresh tokens for a later
        // retry instead of deleting a valid compatibility session on a network/server failure.
        if (transientFailure) return ValidationResult.TRANSIENT_FAILURE

        val webToken = applicationContext.tokenPrefs().getString(C.GQL_TOKEN_WEB, null)?.takeIf { it.isNotBlank() }
        val gqlHeaders = TwitchApiHelper.getGQLHeaders(applicationContext, true)
        val rawGqlToken = rawAccessTokenFromAuthorizationHeader(gqlHeaders[C.HEADER_TOKEN])
        val gqlTokenIsLegacyWeb = !webToken.isNullOrBlank() &&
            applicationContext.tokenPrefs().getString(C.GQL_TOKEN2, null).isNullOrBlank() &&
            rawGqlToken == webToken
        if (!rawGqlToken.isNullOrBlank()) {
            when (validateCompatibilityToken(
                    repository = repository,
                    token = rawGqlToken,
                    acceptedClientIds = setOfNotNull(
                        gqlHeaders[C.HEADER_CLIENT_ID],
                        sessionStore.compatibilityClientId(),
                        applicationContext.prefs().getString(C.GQL_CLIENT_ID2, C.DEFAULT_GQL_CLIENT_ID2),
                    ),
                    expectedUserId = expectedUserId,
                    expectedLogin = expectedLogin,
                    onInvalid = {
                        if (gqlTokenIsLegacyWeb) {
                            sessionStore.clearLegacyWebCredential()
                        } else {
                            coordinator.clearCompatibility()
                        }
                    },
                    onValid = { response ->
                        if (gqlTokenIsLegacyWeb) {
                            response.userId?.let(sessionStore::rememberLegacyWebCredentialUser)
                        }
                    },
                    onUnauthorized = {
                        when (recoverCompatibilitySessionAfterUnauthorized(coordinator, sessionStore) {
                            if (gqlTokenIsLegacyWeb) {
                                sessionStore.clearLegacyWebCredential()
                            } else {
                                coordinator.clearCompatibility()
                            }
                        }) {
                            CompatibilityUnauthorizedRecovery.RECOVERED -> ValidationResult.VALID
                            CompatibilityUnauthorizedRecovery.INVALID -> ValidationResult.INVALID
                            CompatibilityUnauthorizedRecovery.TRANSIENT_FAILURE -> ValidationResult.TRANSIENT_FAILURE
                        }
                    },
                )
            ) {
                ValidationResult.INVALID -> invalid = true
                ValidationResult.TRANSIENT_FAILURE -> transientFailure = true
                ValidationResult.VALID,
                ValidationResult.UNAUTHORIZED,
                ValidationResult.NO_CREDENTIAL,
                -> Unit
            }
        }

        if (!webToken.isNullOrBlank() && webToken != rawGqlToken) {
            when (validateCompatibilityToken(
                    repository = repository,
                    token = webToken,
                    acceptedClientIds = setOfNotNull(
                        applicationContext.prefs().getString(C.GQL_CLIENT_ID_WEB, C.DEFAULT_GQL_CLIENT_ID_WEB),
                    ),
                    expectedUserId = expectedUserId,
                    expectedLogin = expectedLogin,
                    onInvalid = { sessionStore.clearLegacyWebCredential() },
                    onValid = { response ->
                        response.userId?.let(sessionStore::rememberLegacyWebCredentialUser)
                    },
                    onUnauthorized = {
                        sessionStore.clearLegacyWebCredential()
                        ValidationResult.INVALID
                    },
                )
            ) {
                ValidationResult.INVALID -> invalid = true
                ValidationResult.TRANSIENT_FAILURE -> transientFailure = true
                ValidationResult.VALID,
                ValidationResult.UNAUTHORIZED,
                ValidationResult.NO_CREDENTIAL,
                -> Unit
            }
        }
        return when {
            transientFailure -> ValidationResult.TRANSIENT_FAILURE
            invalid -> ValidationResult.INVALID
            // Legacy raw/web credentials may still validate, but they do not form a complete
            // refreshable Xtra session. Keep them for migration diagnostics and require a full
            // composite reauthorization instead of publishing them as healthy.
            sessionStore.readCompatibility() != null && !rawGqlToken.isNullOrBlank() -> ValidationResult.VALID
            !rawGqlToken.isNullOrBlank() || !webToken.isNullOrBlank() -> ValidationResult.INVALID
            else -> ValidationResult.NO_CREDENTIAL
        }
    }

    private suspend fun validateCompatibilityToken(
        repository: TwitchAuthOperations,
        token: String,
        acceptedClientIds: Set<String>,
        expectedUserId: String?,
        expectedLogin: String?,
        onInvalid: suspend () -> Unit,
        onValid: suspend (ValidationResponse) -> Unit = {},
        onUnauthorized: suspend () -> ValidationResult,
    ): ValidationResult = try {
        val response = repository.validateCompatibility(token)
        if (response.clientId.isBlank() || response.clientId !in acceptedClientIds ||
            !isCompatibilityAccountAllowed(response.userId, response.login, expectedUserId, expectedLogin)
        ) {
            onInvalid()
            ValidationResult.INVALID
        } else {
            onValid(response)
            ValidationResult.VALID
        }
    } catch (error: CancellationException) {
        throw error
    } catch (error: Exception) {
        if (isUnauthorized(error)) onUnauthorized() else ValidationResult.TRANSIENT_FAILURE
    }

    private fun isCompatibilityAccountAllowed(
        userId: String?,
        login: String?,
        accountId: String?,
        accountLogin: String?,
    ): Boolean {
        return (userId.isNullOrBlank() || accountId.isNullOrBlank() || userId == accountId) &&
            (login.isNullOrBlank() || accountLogin.isNullOrBlank() || login.equals(accountLogin, ignoreCase = true))
    }

    private fun isUnauthorized(error: Exception): Boolean =
        error is TwitchAuthHttpException && error.statusCode == 401

    private fun hasValidatedNetwork(): Boolean {
        val connectivityManager = applicationContext.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            ?: return false
        val capabilities = connectivityManager.getNetworkCapabilities(connectivityManager.activeNetwork)
        return capabilities?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true &&
            capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
    }

    private enum class ValidationResult {
        VALID,
        UNAUTHORIZED,
        INVALID,
        TRANSIENT_FAILURE,
        NO_CREDENTIAL,
    }

    private fun publishState() {
        _state.value = stateMachine.maintenanceState
        val sessionStore = AuthSessionStore(applicationContext.prefs(), applicationContext.tokenPrefs())
        val diagnostics = sessionStore.diagnostics()
        val officialSession = sessionStore.read()
        val structuredCompatibility = sessionStore.readCompatibility()
        _authHealth.value = classifyAuthHealth(
            officialState = stateMachine.officialState,
            compatibilityState = stateMachine.compatibilityState,
            officialSessionComplete = diagnostics.officialAccessTokenPresent &&
                diagnostics.officialRefreshTokenPresent &&
                diagnostics.officialClientIdPresent &&
                officialSession?.userId != null &&
                diagnostics.officialExpiresAtMillis > 0 &&
                !officialSession.isAccessTokenExpired(nowMillis()),
            structuredCompatibilityPresent = structuredCompatibility != null &&
                !structuredCompatibility.isAccessTokenExpired(nowMillis()),
            compatibilityUserMatches = structuredCompatibility?.userId == officialSession?.userId,
            legacyCredentialPresent = diagnostics.gqlToken2Present || diagnostics.gqlTokenWebPresent,
            storedAccountIdentityPresent = !applicationContext.tokenPrefs().getString(C.USER_ID, null).isNullOrBlank() ||
                !applicationContext.tokenPrefs().getString(C.USERNAME, null).isNullOrBlank(),
        )
    }

    private companion object {
        const val VALIDATION_CHECK_INTERVAL_MILLIS = 60_000L
    }
}
