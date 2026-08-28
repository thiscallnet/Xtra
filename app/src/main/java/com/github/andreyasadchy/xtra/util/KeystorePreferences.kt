package com.github.andreyasadchy.xtra.util

import android.content.SharedPreferences
import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import androidx.annotation.WorkerThread
import com.github.andreyasadchy.xtra.BuildConfig
import java.security.KeyStore
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

internal data class KeystorePreferenceCounterSnapshot(
    val stringCacheHits: Long,
    val stringCacheMisses: Long,
    val stringSetCacheHits: Long,
    val stringSetCacheMisses: Long,
    val decryptCount: Long,
    val secretKeyLookupCount: Long,
) {
    override fun toString(): String =
        "stringCacheHits=$stringCacheHits " +
            "stringCacheMisses=$stringCacheMisses " +
            "stringSetCacheHits=$stringSetCacheHits " +
            "stringSetCacheMisses=$stringSetCacheMisses " +
            "decryptCount=$decryptCount " +
            "secretKeyLookupCount=$secretKeyLookupCount"
}

/** Debug/perf-only counters for proving that encrypted preferences stay off hot paths. */
internal object KeystorePreferenceDiagnostics {
    private val stringCacheHits = AtomicLong()
    private val stringCacheMisses = AtomicLong()
    private val stringSetCacheHits = AtomicLong()
    private val stringSetCacheMisses = AtomicLong()
    private val decryptCount = AtomicLong()
    private val secretKeyLookupCount = AtomicLong()

    fun recordStringCacheHit() {
        if (BuildConfig.PERF_DIAGNOSTICS) stringCacheHits.incrementAndGet()
    }

    fun recordStringCacheMiss() {
        if (BuildConfig.PERF_DIAGNOSTICS) stringCacheMisses.incrementAndGet()
    }

    fun recordStringSetCacheHit() {
        if (BuildConfig.PERF_DIAGNOSTICS) stringSetCacheHits.incrementAndGet()
    }

    fun recordStringSetCacheMiss() {
        if (BuildConfig.PERF_DIAGNOSTICS) stringSetCacheMisses.incrementAndGet()
    }

    fun recordDecrypt() {
        if (BuildConfig.PERF_DIAGNOSTICS) decryptCount.incrementAndGet()
    }

    fun recordSecretKeyLookup() {
        if (BuildConfig.PERF_DIAGNOSTICS) secretKeyLookupCount.incrementAndGet()
    }

    fun snapshot() = KeystorePreferenceCounterSnapshot(
        stringCacheHits = stringCacheHits.get(),
        stringCacheMisses = stringCacheMisses.get(),
        stringSetCacheHits = stringSetCacheHits.get(),
        stringSetCacheMisses = stringSetCacheMisses.get(),
        decryptCount = decryptCount.get(),
        secretKeyLookupCount = secretKeyLookupCount.get(),
    )
}

internal class KeystorePreferences(
    private val delegate: SharedPreferences,
    private val keyAlias: String,
) : SharedPreferences {
    private val listenerAdapters = ConcurrentHashMap<SharedPreferences.OnSharedPreferenceChangeListener, SharedPreferences.OnSharedPreferenceChangeListener>()
    private val stringCache = ConcurrentHashMap<String, String>()
    private val stringSetCache = ConcurrentHashMap<String, Set<String>>()
    private val secretKeyLock = Any()
    @Volatile
    private var cachedSecretKey: SecretKey? = null
    private val keystoreAvailable by lazy {
        runCatching { KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) } }.isSuccess
    }
    // The JVM unit-test runtime has no Android Keystore provider. Keep its
    // in-memory compatibility, but never downgrade on an actual Android
    // device when the provider is unavailable.
    private val failClosed = Build.FINGERPRINT != null && Build.FINGERPRINT != "robolectric"

    override fun getAll(): MutableMap<String, *> = buildMap {
        delegate.all.forEach { (key, value) ->
            when (value) {
                is String -> {
                    val cached = stringCache[key]
                    if (cached != null) {
                        KeystorePreferenceDiagnostics.recordStringCacheHit()
                        put(key, cached)
                    } else {
                        KeystorePreferenceDiagnostics.recordStringCacheMiss()
                        decryptOrNull(key, value)?.let {
                            stringCache[key] = it
                            put(key, it)
                        }
                    }
                }
                is Set<*> -> {
                    val cached = stringSetCache[key]
                    if (cached != null) {
                        KeystorePreferenceDiagnostics.recordStringSetCacheHit()
                        put(key, cached.toMutableSet())
                    } else {
                        KeystorePreferenceDiagnostics.recordStringSetCacheMiss()
                        value.filterIsInstance<String>().mapNotNullTo(mutableSetOf()) { decryptOrNull(key, it) }
                            .also {
                                stringSetCache[key] = it.toSet()
                                put(key, it)
                            }
                    }
                }
                else -> put(key, value)
            }
        }
    }.toMutableMap()

    override fun getString(key: String?, defValue: String?): String? {
        key ?: return defValue
        stringCache[key]?.let {
            KeystorePreferenceDiagnostics.recordStringCacheHit()
            return it
        }
        KeystorePreferenceDiagnostics.recordStringCacheMiss()
        val stored = delegate.getString(key, null) ?: return defValue
        // A provider initialization failure is transient. Keep the encrypted
        // value so a later process can decrypt it; only a real decryption
        // failure below is treated as corrupt or invalidated ciphertext.
        if (!keystoreAvailable && failClosed) return defValue
        val value = decryptOrNull(key, stored) ?: run {
            // A keystore key can be invalidated by the OS (for example after
            // lock-screen changes). Drop only the unreadable value so startup
            // remains usable and the caller can authenticate again.
            delegate.edit().remove(key).apply()
            return defValue
        }
        migratePlaintext(key, stored, value)
        stringCache[key] = value
        return value
    }

    override fun getStringSet(key: String?, defValues: MutableSet<String>?): MutableSet<String>? {
        key ?: return defValues
        stringSetCache[key]?.let {
            KeystorePreferenceDiagnostics.recordStringSetCacheHit()
            return it.toMutableSet()
        }
        KeystorePreferenceDiagnostics.recordStringSetCacheMiss()
        if (!keystoreAvailable && failClosed) return defValues
        val stored = delegate.getStringSet(key, null) ?: return defValues
        val values = stored.mapNotNullTo(mutableSetOf()) { decryptOrNull(key, it) }
        if (stored.any { !it.startsWith(PREFIX) } || values.size != stored.size) edit().putStringSet(key, values).apply()
        stringSetCache[key] = values.toSet()
        return values
    }

    override fun getInt(key: String?, defValue: Int) = delegate.getInt(key, defValue)
    override fun getLong(key: String?, defValue: Long) = delegate.getLong(key, defValue)
    override fun getFloat(key: String?, defValue: Float) = delegate.getFloat(key, defValue)
    override fun getBoolean(key: String?, defValue: Boolean) = delegate.getBoolean(key, defValue)
    override fun contains(key: String?) = delegate.contains(key)
    override fun edit(): SharedPreferences.Editor = Editor(delegate.edit())

    override fun registerOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) {
        listener ?: return
        val adapter = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            invalidateCaches(key)
            listener.onSharedPreferenceChanged(this, key)
        }
        listenerAdapters[listener] = adapter
        delegate.registerOnSharedPreferenceChangeListener(adapter)
    }

    override fun unregisterOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) {
        listener ?: return
        listenerAdapters.remove(listener)?.let(delegate::unregisterOnSharedPreferenceChangeListener)
    }

    private fun migratePlaintext(key: String, stored: String, value: String) {
        if (keystoreAvailable && !stored.startsWith(PREFIX)) {
            runCatching { delegate.edit().putString(key, encrypt(key, value)).apply() }
        }
    }

    @WorkerThread
    private fun decryptOrNull(key: String, value: String): String? {
        if (!keystoreAvailable && failClosed) return null
        if (!value.startsWith(PREFIX)) return value
        KeystorePreferenceDiagnostics.recordDecrypt()
        return runCatching {
            val parts = value.removePrefix(PREFIX).split(':', limit = 2)
            require(parts.size == 2) { "Invalid encrypted preference" }
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, secretKey(), GCMParameterSpec(128, Base64.decode(parts[0], Base64.NO_WRAP)))
            cipher.updateAAD(key.toByteArray())
            cipher.doFinal(Base64.decode(parts[1], Base64.NO_WRAP)).decodeToString()
        }.getOrNull()
    }

    private fun encrypt(key: String, value: String): String {
        check(keystoreAvailable) { "Android Keystore is unavailable" }
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, secretKey())
        cipher.updateAAD(key.toByteArray())
        val encrypted = cipher.doFinal(value.toByteArray())
        return PREFIX + Base64.encodeToString(cipher.iv, Base64.NO_WRAP) + ":" +
            Base64.encodeToString(encrypted, Base64.NO_WRAP)
    }

    private fun secretKey(): SecretKey {
        check(keystoreAvailable) { "Android Keystore is unavailable" }
        cachedSecretKey?.let { return it }
        return synchronized(secretKeyLock) {
            cachedSecretKey?.let { return@synchronized it }
            runCatching {
                KeystorePreferenceDiagnostics.recordSecretKeyLookup()
                val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
                (keyStore.getKey(keyAlias, null) as? SecretKey)?.let { return@runCatching it }
                KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE).run {
                    init(
                        KeyGenParameterSpec.Builder(
                            keyAlias,
                            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
                        ).setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                            .setRandomizedEncryptionRequired(true)
                            .build(),
                    )
                    generateKey()
                }
            }.getOrElse { throw IllegalStateException("Android Keystore key unavailable", it) }
                .also { cachedSecretKey = it }
        }
    }

    private inner class Editor(private val editor: SharedPreferences.Editor) : SharedPreferences.Editor {
        override fun putString(key: String?, value: String?): SharedPreferences.Editor {
            invalidateCaches(key)
            if (key == null || value == null) {
                editor.putString(key, value)
            } else if (!keystoreAvailable && failClosed) {
                error("Android Keystore is unavailable")
            } else if (keystoreAvailable) {
                editor.putString(key, encrypt(key, value))
            } else {
                editor.putString(key, value)
            }
            return this
        }

        override fun putStringSet(key: String?, values: MutableSet<String>?): SharedPreferences.Editor {
            invalidateCaches(key)
            if (key != null && values != null && !keystoreAvailable && failClosed) {
                error("Android Keystore is unavailable")
            }
            val encrypted = when {
                key == null || values == null -> values
                keystoreAvailable -> values.mapTo(mutableSetOf()) { encrypt(key, it) }
                else -> values
            }
            editor.putStringSet(key, encrypted)
            return this
        }

        override fun putInt(key: String?, value: Int) = apply {
            invalidateCaches(key)
            editor.putInt(key, value)
        }
        override fun putLong(key: String?, value: Long) = apply {
            invalidateCaches(key)
            editor.putLong(key, value)
        }
        override fun putFloat(key: String?, value: Float) = apply {
            invalidateCaches(key)
            editor.putFloat(key, value)
        }
        override fun putBoolean(key: String?, value: Boolean) = apply {
            invalidateCaches(key)
            editor.putBoolean(key, value)
        }
        override fun remove(key: String?) = apply {
            invalidateCaches(key)
            editor.remove(key)
        }
        override fun clear() = apply {
            invalidateCaches(null)
            editor.clear()
        }
        override fun commit() = editor.commit()
        override fun apply() = editor.apply()
    }

    private fun invalidateCaches(key: String?) {
        if (key == null) {
            stringCache.clear()
            stringSetCache.clear()
        } else {
            stringCache.remove(key)
            stringSetCache.remove(key)
        }
    }

    private companion object {
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val PREFIX = "enc:v1:"
    }
}
