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
    fun `fresh login remains signed out while the first grant is staged`() {
        val preferences = MemoryPreferences()
        val tokenPreferences = MemoryPreferences()
        val store = AuthSessionStore(preferences, tokenPreferences)
        val repository = FakeAuthOperations(
            officialValidation = ValidationResponse("new-helix", userId = "new-user", scopes = HELIX_SCOPES),
        )

        runBlocking {
            AuthCoordinator(repository, store).validateOfficial(
                officialToken("staged-access"),
                "new-helix",
                reauthorize = false,
            )
        }

        assertNull(store.read())
        assertNull(tokenPreferences.getString(C.TOKEN, null))
        assertNull(tokenPreferences.getString(C.GQL_TOKEN2, null))
    }

    @Test
    fun `official grant without a refresh token is rejected before staging`() {
        val (store, tokenPreferences) = seededStore()
        val repository = FakeAuthOperations(
            officialValidation = ValidationResponse("new-helix", userId = "user", scopes = HELIX_SCOPES),
        )

        val error = runCatching {
            runBlocking {
                AuthCoordinator(repository, store).validateOfficial(
                    officialToken("unmaintainable").copy(refreshToken = null),
                    "new-helix",
                    reauthorize = false,
                )
            }
        }.exceptionOrNull()

        assertTrue(error is TwitchAuthProtocolException)
        assertEquals("old-access", store.read()?.accessToken)
        assertEquals("old-gql", tokenPreferences.getString(C.GQL_TOKEN2, null))
    }

    @Test
    fun `official validation stages without changing the active pair`() {
        val (store, tokenPreferences) = seededStore()
        val repository = FakeAuthOperations(
            officialValidation = ValidationResponse("new-helix", userId = "user", scopes = REAUTH_SCOPES),
        )

        val staged = runBlocking {
            AuthCoordinator(repository, store, nowMillis = { 1_000L }).validateOfficial(
                tokenResponse = officialToken("staged-access", scopes = REAUTH_SCOPES),
                expectedClientId = "new-helix",
                reauthorize = true,
            )
        }

        assertEquals("staged-access", staged.accessToken)
        assertEquals("old-access", store.read()?.accessToken)
        assertEquals("old-gql", tokenPreferences.getString(C.GQL_TOKEN2, null))
        assertTrue(repository.revoked.isEmpty())
    }

    @Test
    fun `compatibility validation stages without changing the active pair`() {
        val (store, tokenPreferences) = seededStore()
        val repository = FakeAuthOperations(
            compatibilityValidation = ValidationResponse("new-gql-client", userId = "user", scopes = GQL_SCOPES),
        )

        val staged = runBlocking {
            AuthCoordinator(repository, store, nowMillis = { 1_000L }).validateCompatibility(
                tokenResponse = compatibilityToken("staged-gql"),
                expectedClientId = "new-gql-client",
                expectedUserId = "user",
            )
        }

        assertEquals("staged-gql", staged.accessToken)
        assertEquals("old-access", store.read()?.accessToken)
        assertEquals("old-gql", tokenPreferences.getString(C.GQL_TOKEN2, null))
    }

    @Test
    fun `successful pair commit writes both grants without revoking the old pair`() {
        val (store, tokenPreferences) = seededStore()
        val repository = FakeAuthOperations(
            officialValidation = ValidationResponse("new-helix", userId = "new-user", scopes = HELIX_SCOPES),
            compatibilityValidation = ValidationResponse("new-gql-client", userId = "new-user", scopes = GQL_SCOPES),
        )
        val coordinator = AuthCoordinator(repository, store, nowMillis = { 1_000L })

        val result = runBlocking {
            val official = coordinator.validateOfficial(officialToken("new-access"), "new-helix", reauthorize = false)
            val compatibility = coordinator.validateCompatibility(
                compatibilityToken("new-gql"),
                "new-gql-client",
                official.userId,
            )
            coordinator.commitCompleteSession(official, compatibility, reauthorize = false)
        }

        assertTrue(result.accountChanged)
        assertEquals("new-access", store.read()?.accessToken)
        assertEquals("new-user", store.read()?.userId)
        assertEquals("new-gql", tokenPreferences.getString(C.GQL_TOKEN2, null))
        assertTrue(repository.revoked.isEmpty())
    }

    @Test
    fun `successful reauthorization swaps the complete pair for the same account`() {
        val (store, tokenPreferences) = seededStore()
        val repository = FakeAuthOperations(
            officialValidation = ValidationResponse("new-helix", userId = "user", scopes = REAUTH_SCOPES),
            compatibilityValidation = ValidationResponse("new-gql-client", userId = "user", scopes = GQL_SCOPES),
        )
        val coordinator = AuthCoordinator(repository, store, nowMillis = { 1_000L })

        val result = runBlocking {
            val official = coordinator.validateOfficial(
                officialToken("replacement-access", scopes = REAUTH_SCOPES),
                "new-helix",
                reauthorize = true,
            )
            val compatibility = coordinator.validateCompatibility(
                compatibilityToken("replacement-gql"),
                "new-gql-client",
                official.userId,
            )
            coordinator.commitCompleteSession(official, compatibility, reauthorize = true)
        }

        assertFalse(result.accountChanged)
        assertEquals("replacement-access", store.read()?.accessToken)
        assertEquals("replacement-gql", tokenPreferences.getString(C.GQL_TOKEN2, null))
        assertTrue(repository.revoked.isEmpty())
    }

    @Test
    fun `compatibility account mismatch leaves the active pair untouched`() {
        val (store, tokenPreferences) = seededStore()
        val repository = FakeAuthOperations(
            compatibilityValidation = ValidationResponse("new-gql-client", userId = "other-user", scopes = GQL_SCOPES),
        )

        val error = runCatching {
            runBlocking {
                AuthCoordinator(repository, store).validateCompatibility(
                    compatibilityToken("wrong-account"),
                    "new-gql-client",
                    "user",
                )
            }
        }.exceptionOrNull()

        assertTrue(error is TwitchAuthAccountMismatchException)
        assertEquals("old-access", store.read()?.accessToken)
        assertEquals("old-gql", tokenPreferences.getString(C.GQL_TOKEN2, null))
        assertTrue(repository.revoked.isEmpty())
    }

    @Test
    fun `reauthorization requires the staged official grant to belong to the active account`() {
        val (store, tokenPreferences) = seededStore()
        val repository = FakeAuthOperations(
            officialValidation = ValidationResponse("new-helix", userId = "other-user", scopes = HELIX_SCOPES),
        )

        val error = runCatching {
            runBlocking {
                AuthCoordinator(repository, store).validateOfficial(
                    officialToken("wrong-account"),
                    "new-helix",
                    reauthorize = true,
                )
            }
        }.exceptionOrNull()

        assertTrue(error is TwitchAuthAccountMismatchException)
        assertEquals("old-access", store.read()?.accessToken)
        assertEquals("old-gql", tokenPreferences.getString(C.GQL_TOKEN2, null))
        assertTrue(repository.revoked.isEmpty())
    }

    @Test
    fun `commit rejects a pair without refreshable credentials and leaves old pair intact`() {
        val (store, tokenPreferences) = seededStore()
        val coordinator = AuthCoordinator(FakeAuthOperations(), store)
        val official = AuthSession(
            clientId = "new-helix",
            accessToken = "new-access",
            refreshToken = null,
            expiresAtMillis = 100_000L,
            userId = "user",
            login = "viewer",
            scopes = HELIX_SCOPES.toSet(),
        )
        val compatibility = compatibilitySession("new-gql")

        val error = runCatching {
            runBlocking { coordinator.commitCompleteSession(official, compatibility, reauthorize = false) }
        }.exceptionOrNull()

        assertTrue(error is TwitchAuthException)
        assertEquals("old-access", store.read()?.accessToken)
        assertEquals("old-gql", tokenPreferences.getString(C.GQL_TOKEN2, null))
    }

    @Test
    fun `persistence failure during the final swap leaves the old pair intact`() {
        val (store, tokenPreferences) = seededStore()
        tokenPreferences.commitSucceeds = false
        val coordinator = AuthCoordinator(FakeAuthOperations(), store)

        val error = runCatching {
            runBlocking {
                coordinator.commitCompleteSession(
                    official = oldOfficial().copy(accessToken = "replacement-access"),
                    compatibility = oldCompatibility().copy(accessToken = "replacement-gql"),
                    reauthorize = true,
                )
            }
        }.exceptionOrNull()

        assertTrue(error is TwitchAuthException)
        assertEquals("old-access", store.read()?.accessToken)
        assertEquals("old-gql", tokenPreferences.getString(C.GQL_TOKEN2, null))
    }

    @Test
    fun `staged credentials can be revoked without touching active preferences`() {
        val (store, tokenPreferences) = seededStore()
        val repository = FakeAuthOperations()
        val coordinator = AuthCoordinator(repository, store)

        val failures = runBlocking {
            coordinator.revokeStagedCredentials(
                official = AuthSession(
                    clientId = "staged-helix",
                    accessToken = "staged-access",
                    refreshToken = "staged-refresh",
                    expiresAtMillis = 100_000L,
                    userId = "user",
                    login = "viewer",
                    scopes = HELIX_SCOPES.toSet(),
                ),
                compatibility = compatibilitySession("staged-gql"),
            )
        }

        assertEquals(0, failures)
        assertEquals(listOf("staged-access", "staged-gql"), repository.revoked.map { it.second })
        assertEquals("old-access", store.read()?.accessToken)
        assertEquals("old-gql", tokenPreferences.getString(C.GQL_TOKEN2, null))
    }

    @Test
    fun `refresh rotation preserves the complete pair`() {
        val (store, tokenPreferences) = seededStore(expired = true)
        val repository = FakeAuthOperations(
            officialValidation = ValidationResponse("old-helix", userId = "user", scopes = HELIX_SCOPES),
            refresh = officialToken("refreshed-access", refreshToken = "rotated-refresh"),
        )

        val refreshed = runBlocking {
            AuthCoordinator(repository, store, nowMillis = { 10_000L }).refreshIfNeeded()
        }

        assertEquals("refreshed-access", refreshed?.accessToken)
        assertEquals("rotated-refresh", store.read()?.refreshToken)
        assertEquals("old-gql", tokenPreferences.getString(C.GQL_TOKEN2, null))
    }

    @Test
    fun `raw legacy compatibility credentials are not a complete session`() {
        val preferences = MemoryPreferences()
        val tokenPreferences = MemoryPreferences()
        val store = AuthSessionStore(preferences, tokenPreferences)
        tokenPreferences.edit()
            .putString(C.TOKEN, "official")
            .putString(C.TOKEN_REFRESH, "refresh")
            .putString(C.TOKEN_CLIENT_ID, "helix")
            .putLong(C.TOKEN_EXPIRES_AT, 100_000L)
            .putString(C.USER_ID, "user")
            .putString(C.GQL_TOKEN2, "legacy-gql")
            .commit()

        assertFalse(store.hasCompatibilityCredential())
        assertNull(store.readCompatibility())
        assertTrue(store.diagnostics().gqlToken2Present)
        assertFalse(store.diagnostics().structuredCompatibilityPresent)
    }

    @Test
    fun `private gql recommendation credential must match the official account`() {
        val (store, tokenPreferences) = seededStore()

        assertEquals("user", store.readPrivateGqlCredential(nowMillis = 10_000L)?.userId)
        assertEquals("old-gql", store.readPrivateGqlCredential(nowMillis = 10_000L)?.accessToken)

        tokenPreferences.edit().putString(C.GQL_TOKEN2_USER_ID, "different-user").commit()

        assertNull(store.readPrivateGqlCredential(nowMillis = 10_000L))
    }

    @Test
    fun `legacy web credential needs a verified account marker`() {
        val preferences = MemoryPreferences()
        val tokenPreferences = MemoryPreferences()
        val store = AuthSessionStore(preferences, tokenPreferences)
        tokenPreferences.edit()
            .putString(C.TOKEN, "official")
            .putString(C.TOKEN_REFRESH, "refresh")
            .putString(C.TOKEN_CLIENT_ID, "helix")
            .putString(C.USER_ID, "user")
            .putString(C.GQL_TOKEN_WEB, "web-token")
            .commit()

        assertNull(store.readPrivateGqlCredential(nowMillis = 10_000L))

        tokenPreferences.edit().putString(C.GQL_TOKEN_WEB_USER_ID, "user").commit()

        assertEquals(PrivateGqlCredentialType.WEB, store.readPrivateGqlCredential(nowMillis = 10_000L)?.type)
    }

    private fun seededStore(expired: Boolean = false): Pair<AuthSessionStore, MemoryPreferences> {
        val preferences = MemoryPreferences()
        val tokenPreferences = MemoryPreferences()
        val store = AuthSessionStore(preferences, tokenPreferences)
        assertTrue(store.commitCompleteSession(oldOfficial(expired), oldCompatibility()))
        return store to tokenPreferences
    }

    private fun oldOfficial(expired: Boolean = false) = AuthSession(
        clientId = "old-helix",
        accessToken = "old-access",
        refreshToken = "old-refresh",
        expiresAtMillis = if (expired) 1L else 100_000L,
        userId = "user",
        login = "viewer",
        scopes = HELIX_SCOPES.toSet(),
    )

    private fun oldCompatibility() = compatibilitySession("old-gql")

    private fun compatibilitySession(token: String) = CompatibilitySession(
        clientId = "old-gql-client",
        accessToken = token,
        refreshToken = "$token-refresh",
        expiresAtMillis = 100_000L,
        userId = "user",
        scopes = GQL_SCOPES.toSet(),
        tokenType = "Bearer",
    )

    private fun officialToken(
        accessToken: String,
        refreshToken: String = "new-refresh",
        scopes: List<String> = HELIX_SCOPES,
    ) = TokenResponse(
        accessToken = accessToken,
        refreshToken = refreshToken,
        expiresIn = 3_600,
        scopes = scopes,
    )

    private fun compatibilityToken(accessToken: String) = TokenResponse(
        accessToken = accessToken,
        refreshToken = "$accessToken-refresh",
        expiresIn = 3_600,
        scopes = GQL_SCOPES,
        tokenType = "Bearer",
    )

    private class FakeAuthOperations(
        private val officialValidation: ValidationResponse = ValidationResponse("old-helix", userId = "user", scopes = HELIX_SCOPES),
        private val compatibilityValidation: ValidationResponse = ValidationResponse("old-gql-client", userId = "user", scopes = GQL_SCOPES),
        private val refresh: TokenResponse = TokenResponse(),
    ) : TwitchAuthOperations {
        val revoked = mutableListOf<Pair<String, String>>()
        var onRevoke: suspend (String, String) -> Unit = { _, _ -> }

        override suspend fun startDeviceAuthorization(clientId: String, scopes: Collection<String>) = DeviceCodeResponse()
        override suspend fun pollDeviceAuthorization(clientId: String, deviceCode: String, scopes: Collection<String>) = TokenResponse()
        override suspend fun refreshUserToken(clientId: String, refreshToken: String) = refresh
        override suspend fun validate(accessToken: String) = officialValidation
        override suspend fun validateCompatibility(accessToken: String) = compatibilityValidation
        override suspend fun revoke(clientId: String, accessToken: String) {
            revoked += clientId to accessToken
            onRevoke(clientId, accessToken)
        }
    }

    private class MemoryPreferences(
        initialValues: MutableMap<String, Any> = mutableMapOf(),
    ) : SharedPreferences {
        private val values = initialValues.toMutableMap()
        var commitSucceeds = true

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
                if (!commitSucceeds) return false
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
        val REAUTH_SCOPES = HELIX_SCOPES + REAUTHORIZATION_ACCOUNT_SCOPES
        val GQL_SCOPES = listOf("channel_read", "chat:read")
    }
}
