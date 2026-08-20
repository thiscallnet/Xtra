package com.github.andreyasadchy.xtra.repository.auth

import android.content.SharedPreferences
import com.github.andreyasadchy.xtra.model.id.DeviceCodeResponse
import com.github.andreyasadchy.xtra.model.id.TokenResponse
import com.github.andreyasadchy.xtra.model.id.ValidationResponse
import com.github.andreyasadchy.xtra.util.C
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AuthCoordinatorTest {
    @Test
    fun `initial DCF response without a refresh token is rejected before replacement`() {
        val preferences = MemoryPreferences()
        val tokenPreferences = MemoryPreferences()
        val store = AuthSessionStore(preferences, tokenPreferences)
        seedSession(store, tokenPreferences, userId = "old-user")
        val repository = FakeAuthOperations(
            validation = ValidationResponse("new-client", userId = "new-user", scopes = HELIX_SCOPES),
        )

        val error = runCatching {
            runBlocking {
                AuthCoordinator(repository, store, nowMillis = { 1_000L }).validateAndCommit(
                    tokenResponse = newToken("new-access").copy(refreshToken = null),
                    expectedClientId = "new-client",
                    reauthorize = false,
                )
            }
        }.exceptionOrNull()

        assertTrue(error is TwitchAuthProtocolException)
        assertEquals("old-access", store.read()?.accessToken)
        assertEquals("old-refresh", store.read()?.refreshToken)
        assertTrue(repository.revoked.isEmpty())
    }

    @Test
    fun `failed replacement leaves the old session and compatibility credentials untouched`() {
        val preferences = MemoryPreferences()
        val tokenPreferences = MemoryPreferences()
        val store = AuthSessionStore(preferences, tokenPreferences)
        seedSession(store, tokenPreferences, userId = "old-user")
        val repository = FakeAuthOperations(
            validation = ValidationResponse(
                clientId = "different-client",
                userId = "new-user",
                scopes = HELIX_SCOPES,
            ),
        )

        val error = runCatching {
            runBlocking {
                AuthCoordinator(repository, store, nowMillis = { 1_000L }).validateAndCommit(
                    tokenResponse = newToken("new-access"),
                    expectedClientId = "new-client",
                    reauthorize = false,
                )
            }
        }.exceptionOrNull()

        assertTrue(error is TwitchAuthProtocolException)
        assertEquals("old-access", store.read()?.accessToken)
        assertEquals("old-gql", tokenPreferences.getString(C.GQL_TOKEN2, null))
        assertTrue(repository.revoked.isEmpty())
    }

    @Test
    fun `successful replacement commits before revoking superseded credentials`() {
        val preferences = MemoryPreferences()
        val tokenPreferences = MemoryPreferences()
        val store = AuthSessionStore(preferences, tokenPreferences)
        seedSession(store, tokenPreferences, userId = "old-user")
        val repository = FakeAuthOperations(
            validation = ValidationResponse(
                clientId = "new-client",
                userId = "new-user",
                login = "new-login",
                scopes = HELIX_SCOPES,
            ),
        )
        repository.onRevoke = { _, _ ->
            assertEquals("new-access", store.read()?.accessToken)
        }

        val result = runBlocking {
            AuthCoordinator(repository, store, nowMillis = { 1_000L }).validateAndCommit(
                tokenResponse = newToken("new-access"),
                expectedClientId = "new-client",
                reauthorize = false,
            )
        }

        assertTrue(result.accountChanged)
        assertEquals("new-access", store.read()?.accessToken)
        assertEquals("new-user", store.read()?.userId)
        assertNull(tokenPreferences.getString(C.GQL_TOKEN2, null))
        assertEquals(listOf("old-access", "old-gql", "old-web-gql"), repository.revoked.map { it.second })
    }

    @Test
    fun `revocation failure does not undo a committed replacement`() {
        val preferences = MemoryPreferences()
        val tokenPreferences = MemoryPreferences()
        val store = AuthSessionStore(preferences, tokenPreferences)
        seedSession(store, tokenPreferences, userId = "old-user")
        val repository = FakeAuthOperations(
            validation = ValidationResponse("new-client", userId = "new-user", scopes = HELIX_SCOPES),
        )
        repository.onRevoke = { _, _ -> throw TwitchAuthException("revoke failed") }

        val result = runBlocking {
            AuthCoordinator(repository, store, nowMillis = { 1_000L }).validateAndCommit(
                tokenResponse = newToken("new-access"),
                expectedClientId = "new-client",
                reauthorize = false,
            )
        }

        assertEquals(3, result.revocationFailures)
        assertEquals("new-access", store.read()?.accessToken)
    }

    @Test
    fun `same-account normal login preserves optional compatibility credentials`() {
        val preferences = MemoryPreferences()
        val tokenPreferences = MemoryPreferences()
        val store = AuthSessionStore(preferences, tokenPreferences)
        seedSession(store, tokenPreferences, userId = "same-user")
        val repository = FakeAuthOperations(
            validation = ValidationResponse("new-client", userId = "same-user", scopes = HELIX_SCOPES),
        )

        val result = runBlocking {
            AuthCoordinator(repository, store, nowMillis = { 1_000L }).validateAndCommit(
                tokenResponse = newToken("new-access"),
                expectedClientId = "new-client",
                reauthorize = false,
            )
        }

        assertTrue(!result.accountChanged)
        assertEquals("old-gql", tokenPreferences.getString(C.GQL_TOKEN2, null))
        assertEquals("old-web-gql", tokenPreferences.getString(C.GQL_TOKEN_WEB, null))
        assertEquals(listOf("old-access"), repository.revoked.map { it.second })
    }

    @Test
    fun `refresh rotation persists the replacement refresh token`() {
        val preferences = MemoryPreferences()
        val tokenPreferences = MemoryPreferences()
        val store = AuthSessionStore(preferences, tokenPreferences)
        seedSession(store, tokenPreferences, userId = "user", expired = true)
        val repository = FakeAuthOperations(
            validation = ValidationResponse("old-client", userId = "user", scopes = HELIX_SCOPES),
            refresh = TokenResponse(
                accessToken = "refreshed-access",
                refreshToken = "rotated-refresh",
                expiresIn = 3_600,
                scopes = HELIX_SCOPES,
            ),
        )

        val refreshed = runBlocking {
            AuthCoordinator(repository, store, nowMillis = { 10_000L }).refreshIfNeeded()
        }

        assertEquals("refreshed-access", refreshed?.accessToken)
        assertEquals("rotated-refresh", store.read()?.refreshToken)
        assertEquals("refreshed-access", tokenPreferences.getString(C.TOKEN, null))
    }

    @Test
    fun `failed refresh preserves the still stored session`() {
        val preferences = MemoryPreferences()
        val tokenPreferences = MemoryPreferences()
        val store = AuthSessionStore(preferences, tokenPreferences)
        seedSession(store, tokenPreferences, userId = "user", expired = true)
        val repository = FakeAuthOperations(
            validation = ValidationResponse("old-client", userId = "user", scopes = HELIX_SCOPES),
            refreshError = TwitchAuthException("temporary refresh failure"),
        )

        val error = runCatching {
            runBlocking {
                AuthCoordinator(repository, store, nowMillis = { 10_000L }).refreshIfNeeded()
            }
        }.exceptionOrNull()

        assertTrue(error is TwitchAuthException)
        assertEquals("old-access", store.read()?.accessToken)
        assertEquals("old-refresh", store.read()?.refreshToken)
    }

    @Test
    fun `refresh without a replacement refresh token preserves the old session`() {
        val preferences = MemoryPreferences()
        val tokenPreferences = MemoryPreferences()
        val store = AuthSessionStore(preferences, tokenPreferences)
        seedSession(store, tokenPreferences, userId = "user", expired = true)
        val repository = FakeAuthOperations(
            validation = ValidationResponse("old-client", userId = "user", scopes = HELIX_SCOPES),
            refresh = TokenResponse(
                accessToken = "refreshed-access",
                expiresIn = 3_600,
                scopes = HELIX_SCOPES,
            ),
        )

        val error = runCatching {
            runBlocking {
                AuthCoordinator(repository, store, nowMillis = { 10_000L }).refreshIfNeeded()
            }
        }.exceptionOrNull()

        assertTrue(error is TwitchAuthProtocolException)
        assertEquals("old-access", store.read()?.accessToken)
        assertEquals("old-refresh", store.read()?.refreshToken)
    }

    @Test
    fun `compatibility token is committed only for the canonical account`() {
        val preferences = MemoryPreferences()
        val tokenPreferences = MemoryPreferences()
        val store = AuthSessionStore(preferences, tokenPreferences)
        seedSession(store, tokenPreferences, userId = "user")
        val repository = FakeAuthOperations(
            validation = ValidationResponse("compat-client", userId = "user", scopes = emptyList()),
        )

        runBlocking {
            AuthCoordinator(repository, store, nowMillis = { 10_000L }).validateAndCommitCompatibility(
                tokenResponse = TokenResponse(
                    accessToken = "new-gql",
                    refreshToken = "new-gql-refresh",
                    expiresIn = 3_600,
                    scopes = GQL_SCOPES,
                    tokenType = "Bearer",
                ),
                expectedClientId = "compat-client",
                expectedUserId = "user",
            )
        }

        assertEquals("new-gql", tokenPreferences.getString(C.GQL_TOKEN2, null))
        assertEquals("new-gql-refresh", tokenPreferences.getString(C.GQL_TOKEN2_REFRESH, null))
        assertEquals(3_610_000L, tokenPreferences.getLong(C.GQL_TOKEN2_EXPIRES_AT, 0))
        assertEquals("compat-client", tokenPreferences.getString(C.GQL_TOKEN2_CLIENT_ID, null))
        assertEquals("user", tokenPreferences.getString(C.GQL_TOKEN2_USER_ID, null))
        assertEquals("Bearer", tokenPreferences.getString(C.GQL_TOKEN2_TYPE, null))
        assertEquals(GQL_SCOPES.sorted().joinToString(" "), tokenPreferences.getString(C.GQL_TOKEN2_SCOPES, null))
    }

    @Test
    fun `expired compatibility token is not treated as an active credential`() {
        val preferences = MemoryPreferences()
        val tokenPreferences = MemoryPreferences()
        val store = AuthSessionStore(preferences, tokenPreferences)

        assertTrue(
            store.commitCompatibilitySession(
                CompatibilitySession(
                    clientId = "compat-client",
                    accessToken = "expired-gql",
                    refreshToken = "old-gql-refresh",
                    expiresAtMillis = 1_000L,
                    userId = "user",
                    scopes = GQL_SCOPES.toSet(),
                    tokenType = "Bearer",
                ),
            ),
        )

        assertFalse(store.hasCompatibilityCredential(nowMillis = 10_000L))
    }

    @Test
    fun `raw legacy compatibility token is not treated as a refreshable credential`() {
        val preferences = MemoryPreferences()
        val tokenPreferences = MemoryPreferences()
        val store = AuthSessionStore(preferences, tokenPreferences)
        tokenPreferences.edit().putString(C.GQL_TOKEN2, "legacy-gql").commit()

        assertFalse(store.hasCompatibilityCredential(nowMillis = 10_000L))
    }

    @Test
    fun `compatibility refresh rotates its refresh token without changing official session`() {
        val preferences = MemoryPreferences()
        val tokenPreferences = MemoryPreferences()
        val store = AuthSessionStore(preferences, tokenPreferences)
        seedSession(store, tokenPreferences, userId = "user")
        assertTrue(
            store.commitCompatibilitySession(
                CompatibilitySession(
                    clientId = "compat-client",
                    accessToken = "old-gql",
                    refreshToken = "old-gql-refresh",
                    expiresAtMillis = 1L,
                    userId = "user",
                    scopes = GQL_SCOPES.toSet(),
                    tokenType = "Bearer",
                ),
            ),
        )
        val repository = FakeAuthOperations(
            validation = ValidationResponse("compat-client", userId = "user", scopes = GQL_SCOPES),
            refresh = TokenResponse(
                accessToken = "refreshed-gql",
                refreshToken = "rotated-gql-refresh",
                expiresIn = 3_600,
                scopes = GQL_SCOPES,
                tokenType = "Bearer",
            ),
        )

        val refreshed = runBlocking {
            AuthCoordinator(repository, store, nowMillis = { 10_000L }).refreshCompatibilityIfNeeded()
        }

        assertEquals("refreshed-gql", refreshed?.accessToken)
        assertEquals("rotated-gql-refresh", store.readCompatibility()?.refreshToken)
        assertEquals("user", store.readCompatibility()?.userId)
        assertEquals("old-access", store.read()?.accessToken)
    }

    @Test
    fun `failed compatibility refresh preserves both sessions`() {
        val preferences = MemoryPreferences()
        val tokenPreferences = MemoryPreferences()
        val store = AuthSessionStore(preferences, tokenPreferences)
        seedSession(store, tokenPreferences, userId = "user")
        assertTrue(
            store.commitCompatibilitySession(
                CompatibilitySession(
                    clientId = "compat-client",
                    accessToken = "old-gql",
                    refreshToken = "old-gql-refresh",
                    expiresAtMillis = 1L,
                    userId = "user",
                    scopes = GQL_SCOPES.toSet(),
                    tokenType = "Bearer",
                ),
            ),
        )
        val repository = FakeAuthOperations(
            validation = ValidationResponse("compat-client", userId = "user", scopes = GQL_SCOPES),
            refreshError = TwitchAuthException("temporary compatibility refresh failure"),
        )

        val error = runCatching {
            runBlocking {
                AuthCoordinator(repository, store, nowMillis = { 10_000L }).refreshCompatibilityIfNeeded()
            }
        }.exceptionOrNull()

        assertTrue(error is TwitchAuthException)
        assertEquals("old-access", store.read()?.accessToken)
        assertEquals("old-gql", store.readCompatibility()?.accessToken)
        assertEquals("old-gql-refresh", store.readCompatibility()?.refreshToken)
    }

    @Test
    fun `compatibility refresh without a replacement refresh token preserves the old session`() {
        val preferences = MemoryPreferences()
        val tokenPreferences = MemoryPreferences()
        val store = AuthSessionStore(preferences, tokenPreferences)
        seedSession(store, tokenPreferences, userId = "user")
        assertTrue(
            store.commitCompatibilitySession(
                CompatibilitySession(
                    clientId = "compat-client",
                    accessToken = "old-gql",
                    refreshToken = "old-gql-refresh",
                    expiresAtMillis = 1L,
                    userId = "user",
                    scopes = GQL_SCOPES.toSet(),
                    tokenType = "Bearer",
                ),
            ),
        )
        val repository = FakeAuthOperations(
            validation = ValidationResponse("compat-client", userId = "user", scopes = GQL_SCOPES),
            refresh = TokenResponse(
                accessToken = "refreshed-gql",
                expiresIn = 3_600,
                scopes = GQL_SCOPES,
            ),
        )

        val error = runCatching {
            runBlocking {
                AuthCoordinator(repository, store, nowMillis = { 10_000L }).refreshCompatibilityIfNeeded()
            }
        }.exceptionOrNull()

        assertTrue(error is TwitchAuthProtocolException)
        assertEquals("old-gql", store.readCompatibility()?.accessToken)
        assertEquals("old-gql-refresh", store.readCompatibility()?.refreshToken)
        assertEquals("old-access", store.read()?.accessToken)
    }

    @Test
    fun `compatibility unauthorized validation refreshes before invalidating the session`() {
        val preferences = MemoryPreferences()
        val tokenPreferences = MemoryPreferences()
        val store = AuthSessionStore(preferences, tokenPreferences)
        seedSession(store, tokenPreferences, userId = "user")
        assertTrue(
            store.commitCompatibilitySession(
                CompatibilitySession(
                    clientId = "compat-client",
                    accessToken = "old-gql",
                    refreshToken = "old-gql-refresh",
                    expiresAtMillis = 100_000L,
                    userId = "user",
                    scopes = GQL_SCOPES.toSet(),
                    tokenType = "Bearer",
                ),
            ),
        )
        val repository = FakeAuthOperations(
            validation = ValidationResponse("compat-client", userId = "user", scopes = GQL_SCOPES),
            refresh = TokenResponse(
                accessToken = "refreshed-gql",
                refreshToken = "rotated-gql-refresh",
                expiresIn = 3_600,
                scopes = GQL_SCOPES,
                tokenType = "Bearer",
            ),
        )
        var invalidated = false

        val result = runBlocking {
            recoverCompatibilitySessionAfterUnauthorized(
                coordinator = AuthCoordinator(repository, store, nowMillis = { 10_000L }),
                sessionStore = store,
                onInvalid = { invalidated = true },
            )
        }

        assertEquals(CompatibilityUnauthorizedRecovery.RECOVERED, result)
        assertFalse(invalidated)
        assertEquals("refreshed-gql", store.readCompatibility()?.accessToken)
        assertEquals("rotated-gql-refresh", store.readCompatibility()?.refreshToken)
        assertEquals("old-access", store.read()?.accessToken)
    }

    @Test
    fun `compatibility token for another account is rejected without persistence`() {
        val preferences = MemoryPreferences()
        val tokenPreferences = MemoryPreferences()
        val store = AuthSessionStore(preferences, tokenPreferences)
        seedSession(store, tokenPreferences, userId = "user")
        val repository = FakeAuthOperations(
            validation = ValidationResponse("compat-client", userId = "other-user", scopes = emptyList()),
        )

        val error = runCatching {
            runBlocking {
                AuthCoordinator(repository, store).validateAndCommitCompatibility(
                    tokenResponse = TokenResponse(
                        accessToken = "other-gql",
                        refreshToken = "other-gql-refresh",
                        expiresIn = 3_600,
                    ),
                    expectedClientId = "compat-client",
                    expectedUserId = "user",
                )
            }
        }.exceptionOrNull()

        assertTrue(error is TwitchAuthAccountMismatchException)
        assertEquals("old-gql", tokenPreferences.getString(C.GQL_TOKEN2, null))
    }

    private fun seedSession(
        store: AuthSessionStore,
        tokenPreferences: SharedPreferences,
        userId: String,
        expired: Boolean = false,
    ) {
        assertTrue(
            store.commitOfficialSession(
                AuthSession(
                    clientId = "old-client",
                    accessToken = "old-access",
                    refreshToken = "old-refresh",
                    expiresAtMillis = if (expired) 1L else 100_000L,
                    userId = userId,
                    login = "old-login",
                    scopes = HELIX_SCOPES.toSet(),
                ),
                preserveCompatibility = true,
            ),
        )
        tokenPreferences.edit()
            .putString(C.GQL_TOKEN2, "old-gql")
            .putString(C.GQL_TOKEN_WEB, "old-web-gql")
            .commit()
    }

    private fun newToken(accessToken: String) = TokenResponse(
        accessToken = accessToken,
        refreshToken = "new-refresh",
        expiresIn = 3_600,
        scopes = HELIX_SCOPES,
    )

    private class FakeAuthOperations(
        private val validation: ValidationResponse,
        private val refresh: TokenResponse = TokenResponse(),
        private val refreshError: Exception? = null,
    ) : TwitchAuthOperations {
        val revoked = mutableListOf<Pair<String, String>>()
        var onRevoke: suspend (String, String) -> Unit = { _, _ -> }

        override suspend fun startDeviceAuthorization(clientId: String, scopes: Collection<String>) =
            DeviceCodeResponse()

        override suspend fun pollDeviceAuthorization(clientId: String, deviceCode: String, scopes: Collection<String>) = TokenResponse()

        override suspend fun refreshUserToken(clientId: String, refreshToken: String): TokenResponse {
            refreshError?.let { throw it }
            return refresh
        }

        override suspend fun validate(accessToken: String) = validation

        override suspend fun validateCompatibility(accessToken: String) = validation

        override suspend fun revoke(clientId: String, accessToken: String) {
            revoked += clientId to accessToken
            onRevoke(clientId, accessToken)
        }
    }

    private class MemoryPreferences(
        initialValues: MutableMap<String, Any> = mutableMapOf(),
    ) : SharedPreferences {
        private val values = initialValues.toMutableMap()

        override fun getAll(): MutableMap<String, *> = values.toMutableMap()
        override fun getString(key: String?, defValue: String?): String? = values[key] as? String ?: defValue

        @Suppress("UNCHECKED_CAST")
        override fun getStringSet(key: String?, defValues: MutableSet<String>?): MutableSet<String>? =
            (values[key] as? Set<String>)?.toMutableSet() ?: defValues

        override fun getInt(key: String?, defValue: Int): Int = values[key] as? Int ?: defValue
        override fun getLong(key: String?, defValue: Long): Long = values[key] as? Long ?: defValue
        override fun getFloat(key: String?, defValue: Float): Float = values[key] as? Float ?: defValue
        override fun getBoolean(key: String?, defValue: Boolean): Boolean = values[key] as? Boolean ?: defValue
        override fun contains(key: String?): Boolean = values.containsKey(key)
        override fun edit(): SharedPreferences.Editor = Editor()
        override fun registerOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) = Unit
        override fun unregisterOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) = Unit

        private inner class Editor : SharedPreferences.Editor {
            private val changes = mutableMapOf<String, Any?>()
            private var clear = false

            override fun putString(key: String?, value: String?): SharedPreferences.Editor = put(key, value)
            override fun putStringSet(key: String?, values: MutableSet<String>?): SharedPreferences.Editor = put(key, values?.toMutableSet())
            override fun putInt(key: String?, value: Int): SharedPreferences.Editor = put(key, value)
            override fun putLong(key: String?, value: Long): SharedPreferences.Editor = put(key, value)
            override fun putFloat(key: String?, value: Float): SharedPreferences.Editor = put(key, value)
            override fun putBoolean(key: String?, value: Boolean): SharedPreferences.Editor = put(key, value)

            override fun remove(key: String?): SharedPreferences.Editor {
                if (key != null) changes[key] = null
                return this
            }

            override fun clear(): SharedPreferences.Editor {
                clear = true
                return this
            }

            override fun commit(): Boolean {
                applyChanges()
                return true
            }

            override fun apply() = applyChanges()

            private fun <T> put(key: String?, value: T): SharedPreferences.Editor {
                if (key != null) changes[key] = value
                return this
            }

            private fun applyChanges() {
                if (clear) values.clear()
                changes.forEach { (key, value) -> if (value == null) values.remove(key) else values[key] = value }
            }
        }
    }

    private companion object {
        val HELIX_SCOPES = listOf("user:edit", "user:read:follows")
        val GQL_SCOPES = listOf("channel_read", "chat:read")
    }
}
