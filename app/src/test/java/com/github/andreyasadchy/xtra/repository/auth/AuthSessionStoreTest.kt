package com.github.andreyasadchy.xtra.repository.auth

import android.content.SharedPreferences
import com.github.andreyasadchy.xtra.util.C
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AuthSessionStoreTest {

    @Test
    fun `committing a Gecko session removes retained legacy Helix credentials`() {
        val tokenPreferences = MemoryPreferences(
            mutableMapOf(
                C.TOKEN to "legacy-helix-token",
                C.TOKEN_CLIENT_ID to "legacy-helix-client",
                C.USER_ID to "legacy-user",
                C.USERNAME to "legacy-login",
            ),
        )
        val store = AuthSessionStore(MemoryPreferences(), tokenPreferences)

        assertTrue(
            store.commitWebSession(
                accessToken = "gecko-token",
                userId = "gecko-user",
                login = "gecko-login",
                scopes = listOf("user:read:follows"),
                cookieHeader = "session=redacted",
            ),
        )

        assertNull(tokenPreferences.getString(C.TOKEN, null))
        assertNull(tokenPreferences.getString(C.TOKEN_CLIENT_ID, null))
        assertEquals("gecko-token", tokenPreferences.getString(C.GQL_TOKEN_WEB, null))
        assertEquals("gecko-user", tokenPreferences.getString(C.USER_ID, null))
        assertEquals("gecko-login", tokenPreferences.getString(C.USERNAME, null))
    }
}

private class MemoryPreferences(
    initialValues: MutableMap<String, Any> = mutableMapOf(),
) : SharedPreferences {
    private val values = initialValues.toMutableMap()

    override fun getAll(): MutableMap<String, *> = values.toMutableMap()

    override fun getString(key: String?, defValue: String?): String? =
        values[key] as? String ?: defValue

    @Suppress("UNCHECKED_CAST")
    override fun getStringSet(key: String?, defValues: MutableSet<String>?): MutableSet<String>? =
        (values[key] as? Set<String>)?.toMutableSet() ?: defValues

    override fun getInt(key: String?, defValue: Int): Int = values[key] as? Int ?: defValue

    override fun getLong(key: String?, defValue: Long): Long = values[key] as? Long ?: defValue

    override fun getFloat(key: String?, defValue: Float): Float = values[key] as? Float ?: defValue

    override fun getBoolean(key: String?, defValue: Boolean): Boolean =
        values[key] as? Boolean ?: defValue

    override fun contains(key: String?): Boolean = values.containsKey(key)

    override fun edit(): SharedPreferences.Editor = Editor()

    override fun registerOnSharedPreferenceChangeListener(
        listener: SharedPreferences.OnSharedPreferenceChangeListener?,
    ) = Unit

    override fun unregisterOnSharedPreferenceChangeListener(
        listener: SharedPreferences.OnSharedPreferenceChangeListener?,
    ) = Unit

    private inner class Editor : SharedPreferences.Editor {
        private val changes = mutableMapOf<String, Any?>()
        private var clear = false

        override fun putString(key: String?, value: String?): SharedPreferences.Editor = put(key, value)

        override fun putStringSet(
            key: String?,
            values: MutableSet<String>?,
        ): SharedPreferences.Editor = put(key, values?.toMutableSet())

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
