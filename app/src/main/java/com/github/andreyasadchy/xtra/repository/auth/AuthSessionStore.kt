package com.github.andreyasadchy.xtra.repository.auth

import android.content.SharedPreferences
import com.github.andreyasadchy.xtra.util.C

data class AuthSession(
    val clientId: String,
    val accessToken: String,
    val userId: String,
    val login: String?,
    val scopes: Set<String>,
)

enum class PrivateGqlCredentialType { WEB }

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

data class AuthCredentialDiagnostics(
    val gqlTokenWebPresent: Boolean,
    val gqlWebCookieHeaderPresent: Boolean,
    val userIdPresent: Boolean,
    val scopesPresent: Boolean,
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
        // Xtra has one login credential: the session captured from its Twitch browser profile.
        val accessToken = tokenPreferences.getString(C.GQL_TOKEN_WEB, null)?.takeIf { it.isNotBlank() } ?: return null
        val clientId = preferences.getString(C.GQL_CLIENT_ID_WEB, C.DEFAULT_GQL_CLIENT_ID_WEB)
            ?.takeIf { it.isNotBlank() }
            ?: return null
        val userId = tokenPreferences.getString(C.USER_ID, null)?.takeIf { it.isNotBlank() } ?: return null
        return AuthSession(
            clientId = clientId,
            accessToken = accessToken,
            userId = userId,
            login = tokenPreferences.getString(C.USERNAME, null),
            scopes = tokenPreferences.getString(C.TOKEN_SCOPES, null)
                ?.split(' ')
                ?.filter { it.isNotBlank() }
                ?.toSet()
                ?: emptySet(),
        )
    }

    /** Presence-only auth diagnostics. Never expose credential values through this object. */
    fun diagnostics(): AuthCredentialDiagnostics {
        return AuthCredentialDiagnostics(
            gqlTokenWebPresent = tokenPreferences.getString(C.GQL_TOKEN_WEB, null).hasText(),
            gqlWebCookieHeaderPresent = tokenPreferences.getString(C.TWITCH_WEB_COOKIE_HEADER, null).hasText(),
            userIdPresent = tokenPreferences.getString(C.USER_ID, null).hasText(),
            scopesPresent = tokenPreferences.getString(C.TOKEN_SCOPES, null).hasText(),
        )
    }

    /** Returns the identity currently retained by the Gecko-backed Twitch session. */
    internal fun storedUserId(): String? =
        tokenPreferences.getString(C.USER_ID, null)?.takeIf { it.isNotBlank() }

    internal fun storedLogin(): String? =
        tokenPreferences.getString(C.USERNAME, null)?.takeIf { it.isNotBlank() }

    /**
     * Returns the private-GQL credential whose Twitch identity is tied to the current web session.
     */
    fun readPrivateGqlCredential(webTokenOverride: String? = null): PrivateGqlCredential? {
        val webToken = (webTokenOverride ?: tokenPreferences.getString(C.GQL_TOKEN_WEB, null))
            ?.takeIf { it.isNotBlank() }
        val webUserId = tokenPreferences.getString(C.GQL_TOKEN_WEB_USER_ID, null)
            ?.takeIf { it.isNotBlank() }
            ?: tokenPreferences.getString(C.USER_ID, null)?.takeIf { it.isNotBlank() }
        val webClientId = preferences.getString(C.GQL_CLIENT_ID_WEB, C.DEFAULT_GQL_CLIENT_ID_WEB)
            ?.takeIf { it.isNotBlank() }
        if (webToken != null && webUserId != null && webClientId != null) {
            return PrivateGqlCredential(
                type = PrivateGqlCredentialType.WEB,
                clientId = webClientId,
                accessToken = webToken,
                userId = webUserId,
            )
        }
        return null
    }

    /** Atomically installs the GeckoView Twitch session for GQL callers. */
    fun commitWebSession(
        accessToken: String,
        userId: String,
        login: String?,
        scopes: Collection<String>,
        cookieHeader: String?,
    ): Boolean {
        if (accessToken.isBlank() || userId.isBlank()) return false
        val previousAccessToken = tokenPreferences.getString(C.GQL_TOKEN_WEB, null)
        val previousUserId = tokenPreferences.getString(C.GQL_TOKEN_WEB_USER_ID, null)
        val editor = tokenPreferences.edit()
        editor.apply {
            appendAccountCleanupTarget(this, userId, login)
            // Gecko is the account authority. Do not retain an unrelated legacy Helix bearer.
            remove(C.TOKEN)
            remove(C.TOKEN_CLIENT_ID)
            putString(C.GQL_TOKEN_WEB, accessToken)
            putString(C.GQL_TOKEN_WEB_USER_ID, userId)
            putString(C.TWITCH_WEB_COOKIE_HEADER, cookieHeader)
            putString(C.TOKEN_SCOPES, scopes.sorted().joinToString(" "))
            putLong(C.TOKEN_VALIDATED_AT, System.currentTimeMillis())
            putString(C.USER_ID, userId)
            putString(C.USERNAME, login)
            if (previousAccessToken != accessToken || previousUserId != userId) {
                clearGeckoGqlIdentity(this)
            }
        }
        return editor.commit()
    }

    /** Keeps the native fallback synchronized with the live Gecko cookie snapshot. */
    fun updateWebCookieHeader(cookieHeader: String?): Boolean =
        tokenPreferences.edit()
            .apply {
                if (cookieHeader.isNullOrBlank()) remove(C.TWITCH_WEB_COOKIE_HEADER)
                else putString(C.TWITCH_WEB_COOKIE_HEADER, cookieHeader)
            }
            .commit()

    fun readGeckoGqlIdentity(): GeckoGqlIdentity? {
        val authorization = tokenPreferences.getString(C.GECKO_GQL_AUTHORIZATION, null)
            ?.takeIf { it.isNotBlank() }
        val clientId = tokenPreferences.getString(C.GECKO_GQL_CLIENT_ID, null)
            ?.takeIf { it.isNotBlank() }
        val clientIntegrity = tokenPreferences.getString(C.GECKO_GQL_CLIENT_INTEGRITY, null)
            ?.takeIf { it.isNotBlank() }
        val xDeviceId = tokenPreferences.getString(C.GECKO_GQL_X_DEVICE_ID, null)
            ?.takeIf { it.isNotBlank() }
        val userId = tokenPreferences.getString(C.GECKO_GQL_USER_ID, null)
            ?.takeIf { it.isNotBlank() }
        val authTokenFingerprint = tokenPreferences
            .getString(C.GECKO_GQL_AUTH_TOKEN_FINGERPRINT, null)
            ?.takeIf { it.isNotBlank() }
        if (authorization == null || clientId == null || clientIntegrity == null ||
            xDeviceId == null || userId == null || authTokenFingerprint == null
        ) return null
        return GeckoGqlIdentity(
            authorization = authorization,
            clientId = clientId,
            clientIntegrity = clientIntegrity,
            xDeviceId = xDeviceId,
            clientSessionId = tokenPreferences.getString(C.GECKO_GQL_CLIENT_SESSION_ID, null),
            userId = userId,
            authTokenFingerprint = authTokenFingerprint,
            capturedAt = tokenPreferences.getLong(C.GECKO_GQL_CAPTURED_AT, 0L),
        )
    }

    fun commitGeckoGqlIdentity(identity: GeckoGqlIdentity): Boolean {
        val current = read()
        if (current == null || identity.userId != current.userId ||
            !authorizationMatches(identity.authorization, current.accessToken) ||
            identity.authTokenFingerprint != GeckoGqlIdentity.fingerprintForAccessToken(current.accessToken)
        ) return false
        if (identity.authorization.isBlank() || identity.clientId.isBlank() ||
            identity.clientIntegrity.isBlank() || identity.xDeviceId.isBlank()
        ) return false
        return tokenPreferences.edit()
            .putString(C.GECKO_GQL_AUTHORIZATION, identity.authorization)
            .putString(C.GECKO_GQL_CLIENT_ID, identity.clientId)
            .putString(C.GECKO_GQL_CLIENT_INTEGRITY, identity.clientIntegrity)
            .putString(C.GECKO_GQL_X_DEVICE_ID, identity.xDeviceId)
            .putOptionalString(C.GECKO_GQL_CLIENT_SESSION_ID, identity.clientSessionId)
            .putString(C.GECKO_GQL_USER_ID, identity.userId)
            .putString(C.GECKO_GQL_AUTH_TOKEN_FINGERPRINT, identity.authTokenFingerprint)
            .putLong(C.GECKO_GQL_CAPTURED_AT, identity.capturedAt)
            .commit()
    }

    fun clearGeckoGqlIdentity(): Boolean = tokenPreferences.edit()
        .apply(::clearGeckoGqlIdentity)
        .commit()

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

    private fun String?.hasText(): Boolean = !isNullOrBlank()

    private fun SharedPreferences.Editor.putOptionalString(key: String, value: String?): SharedPreferences.Editor =
        if (value.isNullOrBlank()) remove(key) else putString(key, value)

    private fun clearGeckoGqlIdentity(editor: SharedPreferences.Editor) {
        editor
            .remove(C.GECKO_GQL_AUTHORIZATION)
            .remove(C.GECKO_GQL_CLIENT_ID)
            .remove(C.GECKO_GQL_CLIENT_INTEGRITY)
            .remove(C.GECKO_GQL_X_DEVICE_ID)
            .remove(C.GECKO_GQL_CLIENT_SESSION_ID)
            .remove(C.GECKO_GQL_USER_ID)
            .remove(C.GECKO_GQL_AUTH_TOKEN_FINGERPRINT)
            .remove(C.GECKO_GQL_CAPTURED_AT)
    }

    private fun authorizationMatches(authorization: String, accessToken: String): Boolean {
        val prefix = "OAuth "
        return authorization.startsWith(prefix, ignoreCase = true) &&
            authorization.substring(prefix.length) == accessToken
    }

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
