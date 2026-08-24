package com.github.andreyasadchy.xtra.repository.auth

import android.content.SharedPreferences
import com.github.andreyasadchy.xtra.util.C
import org.json.JSONObject

data class AuthSession(
    val clientId: String,
    val accessToken: String,
    val refreshToken: String?,
    val expiresAtMillis: Long,
    val userId: String,
    val login: String?,
    val scopes: Set<String>,
) {
    fun isAccessTokenExpired(nowMillis: Long = System.currentTimeMillis()): Boolean =
        expiresAtMillis > 0 && nowMillis >= expiresAtMillis - EXPIRY_SAFETY_WINDOW_MILLIS

    private companion object {
        const val EXPIRY_SAFETY_WINDOW_MILLIS = 60_000L
    }
}

data class CompatibilitySession(
    val clientId: String,
    val accessToken: String,
    val refreshToken: String?,
    val expiresAtMillis: Long,
    val userId: String,
    val scopes: Set<String>,
    val tokenType: String?,
) {
    fun isAccessTokenExpired(nowMillis: Long = System.currentTimeMillis()): Boolean =
        expiresAtMillis > 0 && nowMillis >= expiresAtMillis - EXPIRY_SAFETY_WINDOW_MILLIS

    private companion object {
        const val EXPIRY_SAFETY_WINDOW_MILLIS = 60_000L
    }
}

enum class PrivateGqlCredentialType {
    COMPATIBILITY,
    WEB,
}

data class PrivateGqlCredential(
    val type: PrivateGqlCredentialType,
    val clientId: String,
    val accessToken: String,
    val userId: String,
)

internal data class PendingAccountCleanup(
    val userId: String?,
    val login: String?,
)

data class StoredCredentials(
    val helixClientId: String?,
    val helixToken: String?,
    val gqlClientId: String?,
    val gqlToken: String?,
    val compatibilitySession: CompatibilitySession?,
    val gqlWebClientId: String?,
    val gqlWebToken: String?,
)

data class AuthCredentialDiagnostics(
    val officialAccessTokenPresent: Boolean,
    val officialRefreshTokenPresent: Boolean,
    val officialExpiresAtMillis: Long,
    val officialClientIdPresent: Boolean,
    val gqlToken2Present: Boolean,
    val gqlTokenWebPresent: Boolean,
    val gqlToken2RefreshPresent: Boolean,
    val gqlToken2ClientIdPresent: Boolean,
    val gqlToken2UserIdPresent: Boolean,
    val gqlToken2ExpiresAtMillis: Long,
    val gqlToken2ScopesPresent: Boolean,
    val gqlToken2TypePresent: Boolean,
    val gqlHeadersPresent: Boolean,
    val gqlHeadersAuthorizationPresent: Boolean,
    val structuredCompatibilityPresent: Boolean,
    val gqlAuthorizationPresent: Boolean,
)

@Suppress("UseKtx")
class AuthSessionStore(
    private val preferences: SharedPreferences,
    private val tokenPreferences: SharedPreferences,
) {
    internal fun pendingAccountCleanups(): List<PendingAccountCleanup> =
        tokenPreferences.getStringSet(C.ACCOUNT_CLEANUP_TARGETS, mutableSetOf())
            .orEmpty()
            .mapNotNull(::decodePendingAccountCleanup)

    fun markAccountCleanupPending(userId: String?, login: String?): Boolean {
        if (userId.isNullOrBlank() && login.isNullOrBlank()) return true
        val targets = pendingAccountCleanupValues()
        targets += encodePendingAccountCleanup(userId, login)
        return tokenPreferences.edit()
            .putBoolean(C.ACCOUNT_CLEANUP_PENDING, true)
            .putStringSet(C.ACCOUNT_CLEANUP_TARGETS, targets)
            .commit()
    }

    internal fun clearAccountCleanup(target: PendingAccountCleanup): Boolean {
        val targets = pendingAccountCleanupValues()
        if (!targets.remove(encodePendingAccountCleanup(target.userId, target.login))) return true
        val editor = tokenPreferences.edit()
        if (targets.isEmpty()) {
            editor.remove(C.ACCOUNT_CLEANUP_PENDING)
            editor.remove(C.ACCOUNT_CLEANUP_TARGETS)
        } else {
            editor.putBoolean(C.ACCOUNT_CLEANUP_PENDING, true)
            editor.putStringSet(C.ACCOUNT_CLEANUP_TARGETS, targets)
        }
        return editor.commit()
    }

    fun read(): AuthSession? {
        val accessToken = tokenPreferences.getString(C.TOKEN, null)?.takeIf { it.isNotBlank() } ?: return null
        val clientId = tokenPreferences.getString(C.TOKEN_CLIENT_ID, null)
            ?.takeIf { it.isNotBlank() }
            ?: preferences.getString(C.HELIX_CLIENT_ID, C.DEFAULT_HELIX_CLIENT_ID)?.takeIf { it.isNotBlank() }
            ?: return null
        val userId = tokenPreferences.getString(C.USER_ID, null)?.takeIf { it.isNotBlank() } ?: return null
        return AuthSession(
            clientId = clientId,
            accessToken = accessToken,
            refreshToken = tokenPreferences.getString(C.TOKEN_REFRESH, null)?.takeIf { it.isNotBlank() },
            expiresAtMillis = tokenPreferences.getLong(C.TOKEN_EXPIRES_AT, 0),
            userId = userId,
            login = tokenPreferences.getString(C.USERNAME, null),
            scopes = tokenPreferences.getString(C.TOKEN_SCOPES, null)
                ?.split(' ')
                ?.filter { it.isNotBlank() }
                ?.toSet()
                ?: emptySet(),
        )
    }

    fun snapshot(): StoredCredentials {
        val integrityHeaders = integrityGqlHeaders()
        val compatibilitySession = readCompatibility()
        return StoredCredentials(
            helixClientId = tokenPreferences.getString(C.TOKEN_CLIENT_ID, null)
                ?.takeIf { it.isNotBlank() }
                ?: preferences.getString(C.HELIX_CLIENT_ID, C.DEFAULT_HELIX_CLIENT_ID),
            helixToken = tokenPreferences.getString(C.TOKEN, null),
            gqlClientId = compatibilitySession?.clientId
                ?: integrityHeaders?.optString(C.HEADER_CLIENT_ID)?.takeIf { it.isNotBlank() }
                ?: preferences.getString(C.GQL_CLIENT_ID2, C.DEFAULT_GQL_CLIENT_ID2),
            gqlToken = integrityHeaders?.optString(C.HEADER_TOKEN)?.removePrefix("OAuth ")?.takeIf { it.isNotBlank() }
                ?: tokenPreferences.getString(C.GQL_TOKEN2, null),
            compatibilitySession = compatibilitySession,
            gqlWebClientId = preferences.getString(C.GQL_CLIENT_ID_WEB, C.DEFAULT_GQL_CLIENT_ID_WEB),
            gqlWebToken = tokenPreferences.getString(C.GQL_TOKEN_WEB, null),
        )
    }

    /** Presence-only auth diagnostics. Never expose credential values through this object. */
    fun diagnostics(): AuthCredentialDiagnostics {
        val gqlToken2 = tokenPreferences.getString(C.GQL_TOKEN2, null).hasText()
        val gqlWebToken = tokenPreferences.getString(C.GQL_TOKEN_WEB, null).hasText()
        val gqlHeaders = integrityGqlHeaders()
        val gqlHeadersAuthorization = gqlHeaders?.optString(C.HEADER_TOKEN).hasText()
        val integrityEnabled = preferences.getBoolean(C.ENABLE_INTEGRITY, false)
        return AuthCredentialDiagnostics(
            officialAccessTokenPresent = tokenPreferences.getString(C.TOKEN, null).hasText(),
            officialRefreshTokenPresent = tokenPreferences.getString(C.TOKEN_REFRESH, null).hasText(),
            officialExpiresAtMillis = tokenPreferences.getLong(C.TOKEN_EXPIRES_AT, 0),
            officialClientIdPresent = tokenPreferences.getString(C.TOKEN_CLIENT_ID, null).hasText(),
            gqlToken2Present = gqlToken2,
            gqlTokenWebPresent = gqlWebToken,
            gqlToken2RefreshPresent = tokenPreferences.getString(C.GQL_TOKEN2_REFRESH, null).hasText(),
            gqlToken2ClientIdPresent = tokenPreferences.getString(C.GQL_TOKEN2_CLIENT_ID, null).hasText(),
            gqlToken2UserIdPresent = tokenPreferences.getString(C.GQL_TOKEN2_USER_ID, null).hasText(),
            gqlToken2ExpiresAtMillis = tokenPreferences.getLong(C.GQL_TOKEN2_EXPIRES_AT, 0),
            gqlToken2ScopesPresent = tokenPreferences.getString(C.GQL_TOKEN2_SCOPES, null).hasText(),
            gqlToken2TypePresent = tokenPreferences.getString(C.GQL_TOKEN2_TYPE, null).hasText(),
            gqlHeadersPresent = tokenPreferences.getString(C.GQL_HEADERS, null).hasText(),
            gqlHeadersAuthorizationPresent = gqlHeadersAuthorization,
            structuredCompatibilityPresent = readCompatibility() != null,
            gqlAuthorizationPresent = if (integrityEnabled) {
                gqlHeadersAuthorization || gqlToken2
            } else {
                gqlToken2 || gqlWebToken
            },
        )
    }

    fun compatibilityClientId(): String? =
        tokenPreferences.getString(C.GQL_TOKEN2_CLIENT_ID, null)
            ?.takeIf { it.isNotBlank() }
            ?: preferences.getString(C.GQL_CLIENT_ID2, C.DEFAULT_GQL_CLIENT_ID2)?.takeIf { it.isNotBlank() }

    /** Returns the identity retained by legacy installs without an official OAuth session. */
    internal fun storedUserId(): String? =
        tokenPreferences.getString(C.USER_ID, null)?.takeIf { it.isNotBlank() }

    internal fun storedLogin(): String? =
        tokenPreferences.getString(C.USERNAME, null)?.takeIf { it.isNotBlank() }

    fun readCompatibility(): CompatibilitySession? {
        val accessToken = tokenPreferences.getString(C.GQL_TOKEN2, null)?.takeIf { it.isNotBlank() } ?: return null
        val refreshToken = tokenPreferences.getString(C.GQL_TOKEN2_REFRESH, null)?.takeIf { it.isNotBlank() }
        val clientId = tokenPreferences.getString(C.GQL_TOKEN2_CLIENT_ID, null)
            ?.takeIf { it.isNotBlank() }
            ?: preferences.getString(C.GQL_CLIENT_ID2, C.DEFAULT_GQL_CLIENT_ID2)?.takeIf { it.isNotBlank() }
        val userId = tokenPreferences.getString(C.GQL_TOKEN2_USER_ID, null)?.takeIf { it.isNotBlank() }
        val expiresAtMillis = tokenPreferences.getLong(C.GQL_TOKEN2_EXPIRES_AT, 0)
        if (refreshToken == null || clientId == null || userId == null) return null
        return CompatibilitySession(
            clientId = clientId,
            accessToken = accessToken,
            refreshToken = refreshToken,
            expiresAtMillis = expiresAtMillis,
            userId = userId,
            scopes = tokenPreferences.getString(C.GQL_TOKEN2_SCOPES, null)
                ?.split(' ')
                ?.filter { it.isNotBlank() }
                ?.toSet()
                ?: emptySet(),
            tokenType = tokenPreferences.getString(C.GQL_TOKEN2_TYPE, null)?.takeIf { it.isNotBlank() },
        )
    }

    fun hasCompatibilityCredential(nowMillis: Long = System.currentTimeMillis()): Boolean =
        readCompatibility()?.let { !it.isAccessTokenExpired(nowMillis) } == true

    /**
     * Returns only a private-GQL credential whose Twitch identity is tied to the current official
     * account. Raw credentials and integrity headers are deliberately excluded.
     */
    fun readPrivateGqlCredential(nowMillis: Long = System.currentTimeMillis()): PrivateGqlCredential? {
        val officialUserId = read()?.userId ?: return null
        readCompatibility()
            ?.takeIf { !it.isAccessTokenExpired(nowMillis) && it.userId == officialUserId }
            ?.let {
                return PrivateGqlCredential(
                    type = PrivateGqlCredentialType.COMPATIBILITY,
                    clientId = it.clientId,
                    accessToken = it.accessToken,
                    userId = it.userId,
                )
            }

        val webToken = tokenPreferences.getString(C.GQL_TOKEN_WEB, null)?.takeIf { it.isNotBlank() }
        val webUserId = tokenPreferences.getString(C.GQL_TOKEN_WEB_USER_ID, null)
            ?.takeIf { it.isNotBlank() }
        val webClientId = preferences.getString(C.GQL_CLIENT_ID_WEB, C.DEFAULT_GQL_CLIENT_ID_WEB)
            ?.takeIf { it.isNotBlank() }
        if (webToken != null && webUserId == officialUserId && webClientId != null) {
            return PrivateGqlCredential(
                type = PrivateGqlCredentialType.WEB,
                clientId = webClientId,
                accessToken = webToken,
                userId = webUserId,
            )
        }
        return null
    }

    /**
     * Persists the two grants as one complete Xtra account session.
     *
     * The login flow must not call either single-session writer while it is still acquiring the
     * other grant. A single synchronous editor commit keeps a process death from exposing the
     * official half without its compatibility partner.
     */
    fun commitCompleteSession(
        official: AuthSession,
        compatibility: CompatibilitySession,
    ): Boolean {
        if (official.refreshToken.isNullOrBlank() || compatibility.refreshToken.isNullOrBlank() ||
            official.userId != compatibility.userId
        ) {
            return false
        }
        val editor = tokenPreferences.edit()
        editor.apply {
            appendAccountCleanupTarget(this, official.userId, official.login)
            putString(C.TOKEN, official.accessToken)
            putString(C.TOKEN_REFRESH, official.refreshToken)
            putLong(C.TOKEN_EXPIRES_AT, official.expiresAtMillis)
            putString(C.TOKEN_CLIENT_ID, official.clientId)
            putString(C.TOKEN_SCOPES, official.scopes.sorted().joinToString(" "))
            putLong(C.TOKEN_VALIDATED_AT, System.currentTimeMillis())
            putString(C.USER_ID, official.userId)
            putString(C.USERNAME, official.login)

            putString(C.GQL_TOKEN2, compatibility.accessToken)
            putString(C.GQL_TOKEN2_REFRESH, compatibility.refreshToken)
            putLong(C.GQL_TOKEN2_EXPIRES_AT, compatibility.expiresAtMillis)
            putString(C.GQL_TOKEN2_CLIENT_ID, compatibility.clientId)
            putString(C.GQL_TOKEN2_USER_ID, compatibility.userId)
            putString(C.GQL_TOKEN2_SCOPES, compatibility.scopes.sorted().joinToString(" "))
            putString(C.GQL_TOKEN2_TYPE, compatibility.tokenType)

            // A complete modern session must not continue to depend on older raw credentials or
            // an integrity header that could override the staged compatibility token.
            remove(C.GQL_HEADERS)
            remove(C.GQL_TOKEN)
            remove(C.GQL_TOKEN_WEB)
            remove(C.GQL_TOKEN_WEB_USER_ID)
            putLong(C.INTEGRITY_EXPIRATION, 0)
        }
        return editor.commit()
    }

    /** Persists the official account without requiring compatibility GQL to be available. */
    fun commitOfficialSession(official: AuthSession): Boolean {
        if (official.refreshToken.isNullOrBlank()) return false
        val compatibilityBelongsToAccount =
            tokenPreferences.getString(C.GQL_TOKEN2_USER_ID, null) == official.userId
        val editor = tokenPreferences.edit()
        editor.apply {
            appendAccountCleanupTarget(this, official.userId, official.login)
            putString(C.TOKEN, official.accessToken)
            putString(C.TOKEN_REFRESH, official.refreshToken)
            putLong(C.TOKEN_EXPIRES_AT, official.expiresAtMillis)
            putString(C.TOKEN_CLIENT_ID, official.clientId)
            putString(C.TOKEN_SCOPES, official.scopes.sorted().joinToString(" "))
            putLong(C.TOKEN_VALIDATED_AT, System.currentTimeMillis())
            putString(C.USER_ID, official.userId)
            putString(C.USERNAME, official.login)

            if (!compatibilityBelongsToAccount) {
                remove(C.GQL_TOKEN2)
                remove(C.GQL_TOKEN2_REFRESH)
                remove(C.GQL_TOKEN2_EXPIRES_AT)
                remove(C.GQL_TOKEN2_CLIENT_ID)
                remove(C.GQL_TOKEN2_USER_ID)
                remove(C.GQL_TOKEN2_SCOPES)
                remove(C.GQL_TOKEN2_TYPE)
                remove(C.GQL_HEADERS)
                remove(C.GQL_TOKEN)
                remove(C.GQL_TOKEN_WEB)
                remove(C.GQL_TOKEN_WEB_USER_ID)
                putLong(C.INTEGRITY_EXPIRATION, 0)
            }
        }
        return editor.commit()
    }

    fun updateCompatibilitySession(session: CompatibilitySession): Boolean =
        tokenPreferences.edit()
            .putString(C.GQL_TOKEN2, session.accessToken)
            .putString(C.GQL_TOKEN2_REFRESH, session.refreshToken)
            .putLong(C.GQL_TOKEN2_EXPIRES_AT, session.expiresAtMillis)
            .putString(C.GQL_TOKEN2_CLIENT_ID, session.clientId)
            .putString(C.GQL_TOKEN2_USER_ID, session.userId)
            .putString(C.GQL_TOKEN2_SCOPES, session.scopes.sorted().joinToString(" "))
            .putString(C.GQL_TOKEN2_TYPE, session.tokenType)
            // An old integrity header can override the new DCF token. Force callers to
            // use the structured compatibility session after the atomic write.
            .remove(C.GQL_HEADERS)
            .putLong(C.INTEGRITY_EXPIRATION, 0)
            .commit()

    fun clearCompatibilitySession(): Boolean =
        tokenPreferences.edit()
            .remove(C.GQL_TOKEN2)
            .remove(C.GQL_TOKEN2_REFRESH)
            .remove(C.GQL_TOKEN2_EXPIRES_AT)
            .remove(C.GQL_TOKEN2_CLIENT_ID)
            .remove(C.GQL_TOKEN2_USER_ID)
            .remove(C.GQL_TOKEN2_SCOPES)
            .remove(C.GQL_TOKEN2_TYPE)
            .remove(C.GQL_HEADERS)
            .putLong(C.INTEGRITY_EXPIRATION, 0)
            .commit()

    fun clearLegacyWebCredential(): Boolean =
        tokenPreferences.edit()
            .remove(C.GQL_TOKEN_WEB)
            .remove(C.GQL_TOKEN_WEB_USER_ID)
            .commit()

    fun rememberLegacyWebCredentialUser(userId: String): Boolean {
        if (userId.isBlank()) return false
        return tokenPreferences.edit().putString(C.GQL_TOKEN_WEB_USER_ID, userId).commit()
    }

    /** Durably writes a replacement access/refresh pair returned by Twitch. */
    fun updateTokens(
        accessToken: String,
        refreshToken: String?,
        expiresAtMillis: Long,
        clientId: String,
        userId: String,
        login: String?,
        scopes: Collection<String>,
        validatedAtMillis: Long? = System.currentTimeMillis(),
    ): Boolean {
        val editor = tokenPreferences.edit()
        editor.apply {
            putString(C.TOKEN, accessToken)
            putString(C.TOKEN_REFRESH, refreshToken)
            putLong(C.TOKEN_EXPIRES_AT, expiresAtMillis)
            putString(C.TOKEN_CLIENT_ID, clientId)
            putString(C.TOKEN_SCOPES, scopes.sorted().joinToString(" "))
            validatedAtMillis?.let { putLong(C.TOKEN_VALIDATED_AT, it) }
            putString(C.USER_ID, userId)
            putString(C.USERNAME, login)
        }
        return editor.commit()
    }

    fun clearAll(): Boolean {
        val targets = pendingAccountCleanupValues()
        val previousUserId = tokenPreferences.getString(C.USER_ID, null)
        val previousLogin = tokenPreferences.getString(C.USERNAME, null)
        if (!previousUserId.isNullOrBlank() || !previousLogin.isNullOrBlank()) {
            targets += encodePendingAccountCleanup(previousUserId, previousLogin)
        }
        val editor = tokenPreferences.edit().clear()
        if (targets.isNotEmpty()) {
            editor
                .putBoolean(C.ACCOUNT_CLEANUP_PENDING, true)
                .putStringSet(C.ACCOUNT_CLEANUP_TARGETS, targets)
        }
        return editor.commit()
    }

    private fun integrityGqlHeaders(): JSONObject? =
        tokenPreferences.getString(C.GQL_HEADERS, null)
            ?.let { runCatching { JSONObject(it) }.getOrNull() }

    private fun String?.hasText(): Boolean = !isNullOrBlank()

    private fun pendingAccountCleanupValues(): MutableSet<String> =
        tokenPreferences.getStringSet(C.ACCOUNT_CLEANUP_TARGETS, mutableSetOf())?.toMutableSet()
            ?: mutableSetOf()

    private fun appendAccountCleanupTarget(
        editor: SharedPreferences.Editor,
        newUserId: String,
        newLogin: String?,
    ) {
        val previousUserId = tokenPreferences.getString(C.USER_ID, null)
        val previousLogin = tokenPreferences.getString(C.USERNAME, null)
        val accountChanged = when {
            !previousUserId.isNullOrBlank() -> previousUserId != newUserId
            !previousLogin.isNullOrBlank() -> !previousLogin.equals(newLogin, ignoreCase = true)
            else -> false
        }
        if (!accountChanged || (previousUserId.isNullOrBlank() && previousLogin.isNullOrBlank())) return
        val targets = pendingAccountCleanupValues()
        targets += encodePendingAccountCleanup(previousUserId, previousLogin)
        editor
            .putBoolean(C.ACCOUNT_CLEANUP_PENDING, true)
            .putStringSet(C.ACCOUNT_CLEANUP_TARGETS, targets)
    }

    private fun encodePendingAccountCleanup(userId: String?, login: String?): String =
        "${userId.orEmpty()}$ACCOUNT_CLEANUP_SEPARATOR${login.orEmpty()}"

    private fun decodePendingAccountCleanup(value: String): PendingAccountCleanup? {
        val separator = value.indexOf(ACCOUNT_CLEANUP_SEPARATOR)
        val userId = if (separator >= 0) value.substring(0, separator) else value
        val login = if (separator >= 0) value.substring(separator + 1) else ""
        if (userId.isBlank() && login.isBlank()) return null
        return PendingAccountCleanup(
            userId = userId.takeIf { it.isNotBlank() },
            login = login.takeIf { it.isNotBlank() },
        )
    }

    private companion object {
        const val ACCOUNT_CLEANUP_SEPARATOR = '|'
    }
}
