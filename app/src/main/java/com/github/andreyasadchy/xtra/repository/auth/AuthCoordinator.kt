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
    suspend fun validateAndCommit(
        tokenResponse: TokenResponse,
        expectedClientId: String,
        reauthorize: Boolean,
    ): AuthCommitResult = SESSION_MUTEX.withLock {
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
        val previous = sessionStore.snapshot()
        val previousUserId = sessionStore.read()?.userId
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
        // Keep private compatibility credentials only for the same Twitch account. A normal
        // account switch must not leave the new account using the previous account's GQL token.
        val preserveCompatibility = reauthorize || previousUserId == userId
        if (!sessionStore.commitOfficialSession(session, preserveCompatibility = preserveCompatibility)) {
            throw TwitchAuthException("Unable to save the Twitch session")
        }

        return AuthCommitResult(
            session = session,
            accountChanged = previousUserId != null && previousUserId != userId,
            revocationFailures = revokeSupersededCredentials(
                previous = previous,
                replacement = session,
                preserveCompatibility = preserveCompatibility,
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

    suspend fun validateAndCommitCompatibility(
        tokenResponse: TokenResponse,
        expectedClientId: String,
        expectedUserId: String,
    ): Boolean = SESSION_MUTEX.withLock {
        val accessToken = tokenResponse.accessToken?.takeIf { it.isNotBlank() }
            ?: throw TwitchAuthProtocolException("Twitch did not return a compatibility access token")
        val refreshToken = tokenResponse.refreshToken?.takeIf { it.isNotBlank() }
            ?: throw TwitchAuthProtocolException("Twitch did not return a compatibility refresh token")
        val expiresIn = tokenResponse.expiresIn?.takeIf { it > 0 }
            ?: throw TwitchAuthProtocolException("Twitch did not return a compatibility token lifetime")
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
            expiresAtMillis = nowMillis() + expiresIn * 1_000L,
            userId = expectedUserId,
            scopes = validation.scopes.toSet().ifEmpty { tokenResponse.scopes.toSet() },
            tokenType = tokenResponse.tokenType,
        )
        if (!sessionStore.commitCompatibilitySession(session)) {
            throw TwitchAuthException("Unable to save Twitch compatibility credentials")
        }
        return true
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
        val expiresIn = response.expiresIn?.takeIf { it > 0 }
            ?: throw TwitchAuthProtocolException("Twitch did not return a refreshed compatibility token lifetime")
        val validation = repository.validateCompatibility(accessToken)
        if (validation.clientId != current.clientId || validation.userId != current.userId) {
            throw TwitchAuthAccountMismatchException()
        }
        val refreshed = current.copy(
            accessToken = accessToken,
            refreshToken = replacementRefreshToken,
            expiresAtMillis = nowMillis() + expiresIn * 1_000L,
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
        replacement: AuthSession,
        preserveCompatibility: Boolean,
    ): Int {
        var failures = 0
        if (!previous.helixToken.isNullOrBlank() && previous.helixToken != replacement.accessToken) {
            failures += revoke(previous.helixClientId, previous.helixToken)
        }
        if (!preserveCompatibility) {
            if (!previous.gqlToken.isNullOrBlank() && previous.gqlToken != previous.gqlWebToken) {
                failures += revoke(previous.gqlClientId, previous.gqlToken)
            }
            if (!previous.gqlWebToken.isNullOrBlank()) {
                failures += revoke(previous.gqlWebClientId, previous.gqlWebToken)
            }
        }
        return failures
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
