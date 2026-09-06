package com.github.andreyasadchy.xtra.repository.auth

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import android.net.http.HttpEngine
import com.github.andreyasadchy.xtra.repository.AuthRepository
import com.github.andreyasadchy.xtra.util.C
import com.github.andreyasadchy.xtra.util.tokenPrefs
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.launch
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import org.chromium.net.CronetEngine
import java.util.concurrent.Executors

class AuthSessionMaintainerTest {
    @Test
    fun `only authoritative reauthorization requires user action`() {
        assertTrue(AuthHealth.REAUTH_REQUIRED.requiresUserAction)
        assertFalse(AuthHealth.SIGNED_OUT.requiresUserAction)
        assertFalse(AuthHealth.HEALTHY.requiresUserAction)
        assertFalse(AuthHealth.UNKNOWN.requiresUserAction)
    }

    @Test
    fun signedOutAndReauthorizationStatesWaitForAnExplicitWake() {
        assertNull(
            authSessionValidationWaitMs(
                sessionPresent = false,
                webTokenPresent = false,
                maintenanceState = AuthSessionMaintenanceState.IDLE,
                validatedNetwork = true,
                validationChecked = false,
                lastValidatedAtMs = 0L,
                nowMs = 100L,
            ),
        )
        assertNull(
            authSessionValidationWaitMs(
                sessionPresent = true,
                webTokenPresent = true,
                maintenanceState = AuthSessionMaintenanceState.REAUTHORIZATION_REQUIRED,
                validatedNetwork = true,
                validationChecked = true,
                lastValidatedAtMs = 100L,
                nowMs = 100L,
            ),
        )
    }

    @Test
    fun initializationDoesNotEmitButHealthyToHealthyAccountChangeDoes() = runBlocking {
        val preferences = AuthMaintainerMemoryPreferences()
        val context = TestContext(preferences)
        putSession(context, accessToken = "account-a-token", userId = "account-a")
        val maintainer = AuthSessionMaintainer(context, unusedAuthRepository())
        val changes = Channel<Long>(Channel.UNLIMITED)
        val collector = launch {
            maintainer.authenticationChangeGeneration
                .filter { it > 0L }
                .collect { changes.send(it) }
        }

        assertEquals(0L, maintainer.authenticationChangeGeneration.value)
        assertNull(withTimeoutOrNull(50L) { changes.receive() })

        putSession(context, accessToken = "account-b-token", userId = "account-b")
        maintainer.onAuthenticationStateChanged()

        assertEquals(1L, withTimeoutOrNull(500L) { changes.receive() })
        assertNull(withTimeoutOrNull(50L) { changes.receive() })
        collector.cancel()
    }

    @Test
    fun freshValidationWaitsUntilOneHourBoundary() {
        val validatedAt = 100_000L
        assertEquals(
            AUTH_SESSION_VALIDATION_INTERVAL_MILLIS - 1L,
            authSessionValidationWaitMs(
                sessionPresent = true,
                webTokenPresent = true,
                maintenanceState = AuthSessionMaintenanceState.VALID,
                validatedNetwork = true,
                validationChecked = true,
                lastValidatedAtMs = validatedAt,
                nowMs = validatedAt + 1L,
            ),
        )
        assertEquals(
            0L,
            authSessionValidationWaitMs(
                sessionPresent = true,
                webTokenPresent = true,
                maintenanceState = AuthSessionMaintenanceState.VALID,
                validatedNetwork = true,
                validationChecked = true,
                lastValidatedAtMs = validatedAt,
                nowMs = validatedAt + AUTH_SESSION_VALIDATION_INTERVAL_MILLIS,
            ),
        )
    }

    @Test
    fun missingNetworkWaitsForConnectivityInsteadOfRetrying() {
        assertNull(
            authSessionValidationWaitMs(
                sessionPresent = true,
                webTokenPresent = true,
                maintenanceState = AuthSessionMaintenanceState.TRANSIENT_FAILURE,
                validatedNetwork = false,
                validationChecked = true,
                lastValidatedAtMs = 0L,
                nowMs = 100L,
                transientRetryDeadlineMs = 200L,
            ),
        )
    }

    @Test
    fun missingNetworkUsesBoundedFallbackWhenWakeCallbackRegistrationFails() {
        assertEquals(
            AUTH_SESSION_NETWORK_RETRY_DELAY_MILLIS,
            authSessionValidationWaitMs(
                sessionPresent = true,
                webTokenPresent = true,
                maintenanceState = AuthSessionMaintenanceState.VALID,
                validatedNetwork = false,
                validationChecked = true,
                lastValidatedAtMs = 100L,
                nowMs = 100L,
                networkWakeAvailable = false,
            ),
        )
    }

    @Test
    fun transientFailureUsesBoundedFiveMinuteRetry() {
        assertEquals(
            AUTH_SESSION_TRANSIENT_RETRY_DELAY_MILLIS,
            authSessionValidationWaitMs(
                sessionPresent = true,
                webTokenPresent = true,
                maintenanceState = AuthSessionMaintenanceState.TRANSIENT_FAILURE,
                validatedNetwork = true,
                validationChecked = true,
                lastValidatedAtMs = 100L,
                nowMs = 100L,
                transientRetryDeadlineMs = 100L + AUTH_SESSION_TRANSIENT_RETRY_DELAY_MILLIS,
            ),
        )
    }

    private fun putSession(context: Context, accessToken: String, userId: String) {
        context.tokenPrefs().edit()
            .putString(C.GQL_TOKEN_WEB, accessToken)
            .putString(C.USER_ID, userId)
            .putString(C.USERNAME, userId)
            .commit()
    }

    private fun unusedAuthRepository() = AuthRepository(
        httpEngine = lazy<HttpEngine?> { null },
        cronetEngine = lazy<CronetEngine?> { null },
        cronetExecutor = lazy { Executors.newSingleThreadExecutor() },
        okHttpClient = lazy { OkHttpClient() },
        json = Json,
    )
}

private class TestContext(
    private val preferences: SharedPreferences,
) : ContextWrapper(null) {
    override fun getPackageName(): String = "com.github.andreyasadchy.xtra.test"

    override fun getSharedPreferences(name: String?, mode: Int): SharedPreferences = preferences

    override fun getApplicationContext(): Context = this
}

private class AuthMaintainerMemoryPreferences(
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
            changes.forEach { (key, value) ->
                if (value == null) values.remove(key) else values[key] = value
            }
        }
    }
}
