package com.github.andreyasadchy.xtra.repository.auth

import com.github.andreyasadchy.xtra.model.id.TokenResponse
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

data class AuthCommitResult(
    val session: AuthSession,
    val accountChanged: Boolean,
    val revocationFailures: Int,
)

class AuthCoordinator(
    private val repository: TwitchAuthOperations,
    private val sessionStore: AuthSessionStore,
    private val nowMillis: () -> Long = { System.currentTimeMillis() },
) {
    /** Validates the official grant without publishing it to the active account. */
    suspend fun validateOfficial(
        tokenResponse: TokenResponse,
        expectedClientId: String,
        reauthorize: Boolean,
    ): AuthSession = SESSION_MUTEX.withLock {
        val accessToken = tokenResponse.accessToken?.takeIf { it.isNotBlank() }
            ?: throw TwitchAuthProtocolException("Twitch did not return an access token")
        val expiresIn = tokenResponse.expiresIn?.takeIf { it > 0 }
            ?: throw TwitchAuthProtocolException("Twitch did not return an access-token lifetime")
        val refreshToken = tokenResponse.refreshToken?.takeIf { it.isNotBlank() }
            ?: throw TwitchAuthProtocolException("Twitch did not return a refresh token")
        val validation = repository.validate(accessToken)
        if (validation.clientId != expectedClientId) {
            throw TwitchAuthProtocolException("Twitch returned a token for a different client")
        }
        val userId = validation.userId?.takeIf { it.isNotBlank() }
            ?: throw TwitchAuthProtocolException("Twitch did not return an account ID")
        val previousSession = sessionStore.read()
        val previousUserId = previousSession?.userId ?: sessionStore.storedUserId()
        if (!isReauthorizationUserAllowed(reauthorize, previousUserId, userId)) {
            throw TwitchAuthAccountMismatchException()
        }
        if (reauthorize && !hasRequiredReauthorizationScopes(validation.scopes)) {
            throw TwitchAuthMissingScopesException()
        }

        val session = AuthSession(
            clientId = expectedClientId,
            accessToken = accessToken,
            refreshToken = refreshToken,
            expiresAtMillis = nowMillis() + expiresIn * 1_000L,
            userId = userId,
            login = validation.login,
            scopes = validation.scopes.toSet(),
        )
        return session
    }

    /**
     * Commits a fully validated official + compatibility pair as one logical account change.
     * No active credentials are changed if either validation or this persistence step fails.
     */
    suspend fun commitCompleteSession(
        official: AuthSession,
        compatibility: CompatibilitySession,
        reauthorize: Boolean,
    ): AuthCommitResult = SESSION_MUTEX.withLock {
        if (official.userId != compatibility.userId) {
            throw TwitchAuthAccountMismatchException()
        }
        val previous = sessionStore.snapshot()
        val previousSession = sessionStore.read()
        val previousUserId = previousSession?.userId ?: sessionStore.storedUserId()
        val previousLogin = previousSession?.login ?: sessionStore.storedLogin()
        if (!isReauthorizationUserAllowed(reauthorize, previousUserId, official.userId)) {
            throw TwitchAuthAccountMismatchException()
        }
        val sameStoredAccount = when {
            previousUserId != null -> previousUserId == official.userId
            previousLogin != null -> official.login?.equals(previousLogin, ignoreCase = true) == true
            else -> false
        }
        val hasStoredIdentity = previousUserId != null || previousLogin != null
        val accountChanged = hasStoredIdentity && !sameStoredAccount
        if (!sessionStore.commitCompleteSession(official, compatibility)) {
            throw TwitchAuthException("Unable to save the complete Twitch session")
        }

        return AuthCommitResult(
            session = official,
            accountChanged = accountChanged,
            revocationFailures = revokeSupersededCredentials(
                previous = previous,
                replacementOfficial = official,
                replacementCompatibility = compatibility,
            ),
        )
    }

    suspend fun refreshIfNeeded(force: Boolean = false): AuthSession? = SESSION_MUTEX.withLock {
        val current = sessionStore.read() ?: return null
        if (!force && !current.isAccessTokenExpired(nowMillis())) return current
        val refreshToken = current.refreshToken ?: return current
        val response = repository.refreshUserToken(current.clientId, refreshToken)
        val accessToken = response.accessToken?.takeIf { it.isNotBlank() }
            ?: throw TwitchAuthProtocolException("Twitch did not return a refreshed access token")
        val expiresIn = response.expiresIn?.takeIf { it > 0 }
            ?: throw TwitchAuthProtocolException("Twitch did not return a refreshed token lifetime")
        val validation = repository.validate(accessToken)
        if (validation.clientId != current.clientId || validation.userId != current.userId) {
            throw TwitchAuthAccountMismatchException()
        }
        val rotatedRefreshToken = response.refreshToken?.takeIf { it.isNotBlank() }
            ?: throw TwitchAuthProtocolException("Twitch refresh response did not contain a refresh token")
        val refreshed = current.copy(
            accessToken = accessToken,
            refreshToken = rotatedRefreshToken,
            expiresAtMillis = nowMillis() + expiresIn * 1_000L,
            login = validation.login ?: current.login,
            scopes = validation.scopes.toSet().ifEmpty { current.scopes },
        )
        if (!sessionStore.updateTokens(
                accessToken = refreshed.accessToken,
                refreshToken = refreshed.refreshToken,
                expiresAtMillis = refreshed.expiresAtMillis,
                clientId = refreshed.clientId,
                userId = refreshed.userId,
                login = refreshed.login,
                scopes = refreshed.scopes,
            )
        ) {
            throw TwitchAuthException("Unable to save the refreshed Twitch session")
        }
        return refreshed
    }

    /** Validates the compatibility grant without persisting it. */
    suspend fun validateCompatibility(
        tokenResponse: TokenResponse,
        expectedClientId: String,
        expectedUserId: String,
    ): CompatibilitySession = SESSION_MUTEX.withLock {
        val accessToken = tokenResponse.accessToken?.takeIf { it.isNotBlank() }
            ?: throw TwitchAuthProtocolException("Twitch did not return a compatibility access token")
        val refreshToken = tokenResponse.refreshToken?.takeIf { it.isNotBlank() }
            ?: throw TwitchAuthProtocolException("Twitch did not return a compatibility refresh token")
        val validation = repository.validateCompatibility(accessToken)
        if (validation.clientId != expectedClientId) {
            throw TwitchAuthProtocolException("Twitch returned a compatibility token for a different client")
        }
        if (validation.userId.isNullOrBlank() || validation.userId != expectedUserId) {
            throw TwitchAuthAccountMismatchException()
        }
        val session = CompatibilitySession(
            clientId = expectedClientId,
            accessToken = accessToken,
            refreshToken = refreshToken,
            // Twitch's compatibility device client currently omits expires_in. Zero means
            // "no locally known expiry"; validation remains authoritative.
            expiresAtMillis = tokenResponse.expiresIn
                ?.takeIf { it > 0 }
                ?.let { nowMillis() + it * 1_000L }
                ?: 0L,
            userId = expectedUserId,
            scopes = validation.scopes.toSet().ifEmpty { tokenResponse.scopes.toSet() },
            tokenType = tokenResponse.tokenType,
        )
        return session
    }

    /** Best-effort cleanup for grants staged by a login that never reached the final commit. */
    suspend fun revokeStagedCredentials(
        official: AuthSession?,
        compatibility: CompatibilitySession?,
    ): Int = SESSION_MUTEX.withLock {
        val credentials = linkedSetOf<Pair<String?, String>>()
        official?.accessToken?.takeIf { it.isNotBlank() }?.let {
            credentials += official.clientId to it
        }
        compatibility?.accessToken?.takeIf { it.isNotBlank() }?.let {
            credentials += compatibility.clientId to it
        }
        credentials.sumOf { (clientId, token) -> revoke(clientId, token) }
    }

    suspend fun revokeStagedCredential(clientId: String?, accessToken: String?): Int =
        SESSION_MUTEX.withLock {
            if (accessToken.isNullOrBlank()) 0 else revoke(clientId, accessToken)
        }

    suspend fun refreshCompatibilityIfNeeded(force: Boolean = false): CompatibilitySession? = SESSION_MUTEX.withLock {
        val current = sessionStore.readCompatibility() ?: return null
        if (!force && !current.isAccessTokenExpired(nowMillis())) return current
        val refreshToken = current.refreshToken?.takeIf { it.isNotBlank() }
            ?: throw TwitchAuthProtocolException("Twitch compatibility session has no refresh token")
        val response = repository.refreshUserToken(current.clientId, refreshToken)
        val accessToken = response.accessToken?.takeIf { it.isNotBlank() }
            ?: throw TwitchAuthProtocolException("Twitch did not return a refreshed compatibility access token")
        val replacementRefreshToken = response.refreshToken?.takeIf { it.isNotBlank() }
            ?: throw TwitchAuthProtocolException("Twitch compatibility refresh response did not contain a refresh token")
        val validation = repository.validateCompatibility(accessToken)
        if (validation.clientId != current.clientId || validation.userId != current.userId) {
            throw TwitchAuthAccountMismatchException()
        }
        val refreshed = current.copy(
            accessToken = accessToken,
            refreshToken = replacementRefreshToken,
            expiresAtMillis = response.expiresIn
                ?.takeIf { it > 0 }
                ?.let { nowMillis() + it * 1_000L }
                ?: current.expiresAtMillis,
            scopes = validation.scopes.toSet().ifEmpty { response.scopes.toSet().ifEmpty { current.scopes } },
            tokenType = response.tokenType ?: current.tokenType,
        )
        if (!sessionStore.updateCompatibilitySession(refreshed)) {
            throw TwitchAuthException("Unable to save the refreshed Twitch compatibility session")
        }
        return refreshed
    }

    suspend fun clearCompatibility() = SESSION_MUTEX.withLock {
        if (!sessionStore.clearCompatibilitySession()) {
            throw TwitchAuthException("Unable to clear Twitch compatibility credentials")
        }
    }

    suspend fun logout(): Int = SESSION_MUTEX.withLock {
        val previous = sessionStore.snapshot()
        var failures = 0
        try {
            if (!previous.helixToken.isNullOrBlank()) {
                failures += revoke(previous.helixClientId, previous.helixToken)
            }
            if (!previous.gqlToken.isNullOrBlank() && previous.gqlToken != previous.gqlWebToken) {
                failures += revoke(previous.gqlClientId, previous.gqlToken)
            }
            if (!previous.gqlWebToken.isNullOrBlank()) {
                failures += revoke(previous.gqlWebClientId, previous.gqlWebToken)
            }
        } finally {
            if (!sessionStore.clearAll()) failures += 1
        }
        return failures
    }

    private suspend fun revokeSupersededCredentials(
        previous: StoredCredentials,
        replacementOfficial: AuthSession,
        replacementCompatibility: CompatibilitySession,
    ): Int {
        val credentials = linkedSetOf<Pair<String?, String>>()
        previous.helixToken
            ?.takeIf { it.isNotBlank() && it != replacementOfficial.accessToken }
            ?.let { credentials += previous.helixClientId to it }
        previous.gqlToken
            ?.takeIf { it.isNotBlank() && it != replacementCompatibility.accessToken }
            ?.let { credentials += previous.gqlClientId to it }
        previous.gqlWebToken
            ?.takeIf {
                it.isNotBlank() &&
                    it != previous.gqlToken &&
                    it != replacementCompatibility.accessToken
            }
            ?.let { credentials += previous.gqlWebClientId to it }
        return credentials.sumOf { (clientId, token) -> revoke(clientId, token) }
    }

    private suspend fun revoke(clientId: String?, token: String): Int {
        if (clientId.isNullOrBlank()) return 0
        return try {
            repository.revoke(clientId, token)
            0
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            1
        }
    }

    private companion object {
        /** Serializes refresh, replacement, compatibility, and logout across coordinator instances. */
        val SESSION_MUTEX = Mutex()
    }
}
