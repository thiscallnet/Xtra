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

data class StoredCredentials(
    val helixClientId: String?,
    val helixToken: String?,
    val gqlClientId: String?,
    val gqlToken: String?,
    val compatibilitySession: CompatibilitySession?,
    val gqlWebClientId: String?,
    val gqlWebToken: String?,
)

@Suppress("UseKtx")
class AuthSessionStore(
    private val preferences: SharedPreferences,
    private val tokenPreferences: SharedPreferences,
) {
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

    fun compatibilityClientId(): String? =
        tokenPreferences.getString(C.GQL_TOKEN2_CLIENT_ID, null)
            ?.takeIf { it.isNotBlank() }
            ?: preferences.getString(C.GQL_CLIENT_ID2, C.DEFAULT_GQL_CLIENT_ID2)?.takeIf { it.isNotBlank() }

    fun readCompatibility(): CompatibilitySession? {
        val accessToken = tokenPreferences.getString(C.GQL_TOKEN2, null)?.takeIf { it.isNotBlank() } ?: return null
        val refreshToken = tokenPreferences.getString(C.GQL_TOKEN2_REFRESH, null)?.takeIf { it.isNotBlank() }
        val clientId = tokenPreferences.getString(C.GQL_TOKEN2_CLIENT_ID, null)
            ?.takeIf { it.isNotBlank() }
            ?: preferences.getString(C.GQL_CLIENT_ID2, C.DEFAULT_GQL_CLIENT_ID2)?.takeIf { it.isNotBlank() }
        val userId = tokenPreferences.getString(C.GQL_TOKEN2_USER_ID, null)?.takeIf { it.isNotBlank() }
        val expiresAtMillis = tokenPreferences.getLong(C.GQL_TOKEN2_EXPIRES_AT, 0)
        if (refreshToken == null || clientId == null || userId == null || expiresAtMillis <= 0) return null
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

    fun commitCompatibilitySession(session: CompatibilitySession): Boolean =
        updateCompatibilitySession(session)

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
        tokenPreferences.edit().remove(C.GQL_TOKEN_WEB).commit()

    /** Uses one synchronous preferences commit so a process death cannot expose half a session. */
    fun commitOfficialSession(session: AuthSession, preserveCompatibility: Boolean): Boolean {
        val editor = tokenPreferences.edit()
        editor.apply {
            putString(C.TOKEN, session.accessToken)
            putString(C.TOKEN_REFRESH, session.refreshToken)
            putLong(C.TOKEN_EXPIRES_AT, session.expiresAtMillis)
            putString(C.TOKEN_CLIENT_ID, session.clientId)
            putString(C.TOKEN_SCOPES, session.scopes.sorted().joinToString(" "))
            putLong(C.TOKEN_VALIDATED_AT, System.currentTimeMillis())
            putString(C.USER_ID, session.userId)
            putString(C.USERNAME, session.login)
            if (!preserveCompatibility) {
                remove(C.GQL_HEADERS)
                remove(C.GQL_TOKEN2)
                remove(C.GQL_TOKEN2_REFRESH)
                remove(C.GQL_TOKEN2_EXPIRES_AT)
                remove(C.GQL_TOKEN2_CLIENT_ID)
                remove(C.GQL_TOKEN2_USER_ID)
                remove(C.GQL_TOKEN2_SCOPES)
                remove(C.GQL_TOKEN2_TYPE)
                remove(C.GQL_TOKEN_WEB)
                remove(C.GQL_TOKEN)
                remove(C.INTEGRITY_EXPIRATION)
            }
        }
        return editor.commit()
    }

    /** Writes rotated access/refresh tokens only after the replacement has been validated. */
    fun updateTokens(
        accessToken: String,
        refreshToken: String?,
        expiresAtMillis: Long,
        clientId: String,
        userId: String,
        login: String?,
        scopes: Collection<String>,
    ): Boolean {
        val editor = tokenPreferences.edit()
        editor.apply {
            putString(C.TOKEN, accessToken)
            putString(C.TOKEN_REFRESH, refreshToken)
            putLong(C.TOKEN_EXPIRES_AT, expiresAtMillis)
            putString(C.TOKEN_CLIENT_ID, clientId)
            putString(C.TOKEN_SCOPES, scopes.sorted().joinToString(" "))
            putLong(C.TOKEN_VALIDATED_AT, System.currentTimeMillis())
            putString(C.USER_ID, userId)
            putString(C.USERNAME, login)
        }
        return editor.commit()
    }

    fun clearAll(): Boolean {
        val editor = tokenPreferences.edit()
        editor.clear()
        return editor.commit()
    }

    private fun integrityGqlHeaders(): JSONObject? =
        tokenPreferences.getString(C.GQL_HEADERS, null)
            ?.let { runCatching { JSONObject(it) }.getOrNull() }
}
