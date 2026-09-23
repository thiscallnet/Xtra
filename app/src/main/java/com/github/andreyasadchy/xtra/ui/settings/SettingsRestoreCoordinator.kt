package com.github.andreyasadchy.xtra.ui.settings

import android.content.Context
import android.content.SharedPreferences
import com.github.andreyasadchy.xtra.util.C
import com.github.andreyasadchy.xtra.util.DatabaseRestoreRecovery
import com.github.andreyasadchy.xtra.util.proxyPrefs
import com.github.andreyasadchy.xtra.util.proxyPrefsBacking
import com.github.andreyasadchy.xtra.util.prefs
import com.github.andreyasadchy.xtra.util.rawPrefs
import org.json.JSONObject
import java.io.File

/** Bridges a validated import to the next process, before Android loads the preference cache. */
internal object SettingsRestoreCoordinator {
    private const val PENDING_DIRECTORY = "settings-restore-pending"
    private const val RESULT_FILE = "settings-restore-result.json"
    private const val MANIFEST_FILE = "pending.json"
    private const val SETTINGS_FILE = SettingsBackup.SETTINGS_ENTRY
    private const val DATABASE_FILE = SettingsBackup.DATABASE_ENTRY
    private const val PROXY_FILE = SettingsBackup.PROXY_ENTRY
    private const val PROXY_BEFORE_FILE = "proxy-before.json"
    private const val SETTINGS_APPLIED_FILE = "settings-applied"

    data class Result(val succeeded: Boolean, val message: String? = null)

    private data class Pending(
        val directory: File,
        val settingsSelected: Boolean,
        val databaseSelected: Boolean,
        val proxySelected: Boolean,
        val ignoredLooseFiles: Boolean,
        val sourceSettingsSchemaVersion: Int,
        val settingsApplied: Boolean,
    )

    fun stage(
        context: Context,
        settings: Map<String, *>?,
        settingsSchemaVersion: Int,
        database: File?,
        proxy: File?,
        ignoredLooseFiles: Boolean = false,
    ) {
        require(settings != null || database != null) { "No settings or database were selected" }
        require(settingsSchemaVersion in 0..C.SETTINGS_SCHEMA_VERSION) { "Backup settings version is unsupported" }
        val directory = File(context.noBackupFilesDir, PENDING_DIRECTORY)
        require(!directory.exists() || directory.deleteRecursively()) { "An earlier restore could not be cleared" }
        check(directory.mkdirs()) { "Unable to create restore staging directory" }
        try {
            settings?.let { SettingsBackup.writeTypedPreferences(File(directory, SETTINGS_FILE), it, settingsSchemaVersion) }
            database?.copyTo(File(directory, DATABASE_FILE))
            proxy?.let {
                it.copyTo(File(directory, PROXY_FILE))
                SettingsBackup.writeTypedPreferences(
                    File(directory, PROXY_BEFORE_FILE),
                    context.proxyPrefsBacking().all,
                    schemaVersion = 0,
                    includeExcluded = true,
                )
            }
            val manifest = JSONObject().apply {
                put("version", 1)
                put("settings", settings != null)
                put("database", database != null)
                put("proxy", proxy != null)
                put("ignoredLooseFiles", ignoredLooseFiles)
                put("sourceSettingsSchemaVersion", settingsSchemaVersion)
            }
            val temporary = File(directory, "$MANIFEST_FILE.tmp")
            temporary.writeText(manifest.toString())
            check(temporary.renameTo(File(directory, MANIFEST_FILE))) { "Unable to prepare restore" }
        } catch (error: Exception) {
            directory.deleteRecursively()
            throw error
        }
    }

    fun hasPending(context: Context): Boolean = readPending(context) != null

    fun hasPendingSettingsMigration(context: Context): Boolean =
        readPending(context)?.let { it.settingsSelected && it.settingsApplied } == true

    fun discardStaged(context: Context) {
        File(context.noBackupFilesDir, PENDING_DIRECTORY).deleteRecursively()
    }

    /** Installs only the database file. Preference APIs are deliberately untouched until Room validates it. */
    fun installPendingDatabase(context: Context): Boolean {
        val directory = File(context.noBackupFilesDir, PENDING_DIRECTORY)
        if (!directory.exists()) return false
        val pending = readPending(context)
        if (pending == null) {
            writeResult(context, Result(false, "The staged restore is incomplete"))
            directory.deleteRecursively()
            return false
        }
        return try {
            DatabaseRestoreRecovery.recoverBeforeDatabaseOpen(context)
            if (DatabaseRestoreRecovery.hasPendingRestore(context)) return true

            val databaseTarget = context.getDatabasePath("database")
            DatabaseRestoreRecovery.begin(
                context,
                databaseSelected = pending.databaseSelected,
                databaseExisted = databaseTarget.exists(),
                preferencesSelected = pending.settingsSelected,
                preferencesExisted = preferencesFile(context).exists(),
            )
            if (pending.databaseSelected) {
                val source = File(pending.directory, DATABASE_FILE)
                check(source.isFile) { "Staged database is missing" }
                databaseTarget.parentFile?.mkdirs()
                val next = File(databaseTarget.parentFile, "${databaseTarget.name}.restore-new")
                val previous = File(databaseTarget.parentFile, "${databaseTarget.name}.restore-old")
                next.delete()
                previous.delete()
                source.copyTo(next)
            }
            DatabaseRestoreRecovery.markSwapping(context)
            if (pending.databaseSelected) {
                val target = context.getDatabasePath("database")
                val next = File(target.parentFile, "${target.name}.restore-new")
                val previous = File(target.parentFile, "${target.name}.restore-old")
                if (target.exists()) check(target.renameTo(previous)) { "Unable to stage the current database" }
                check(next.renameTo(target)) { "Unable to install the restored database" }
                File(target.parentFile, "database-shm").delete()
                File(target.parentFile, "database-wal").delete()
            }
            DatabaseRestoreRecovery.markInstalled(context)
            true
        } catch (error: Exception) {
            runCatching { DatabaseRestoreRecovery.rollback(context) }
                .exceptionOrNull()
                ?.let(error::addSuppressed)
            writeResult(context, Result(false, error.message))
            pending.directory.deleteRecursively()
            false
        }
    }

    /** Called by the Room provider if its production schema validation rejects the candidate. */
    fun markDatabaseValidationFailed(context: Context, message: String?) {
        if (readPending(context) != null) writeResult(context, Result(false, message))
    }

    /** Applies settings only after the fresh process has opened the staged Room database successfully. */
    fun finishAfterDatabaseValidation(context: Context) {
        val pending = readPending(context) ?: return
        if (pending.settingsSelected && pending.settingsApplied) return
        takeFailure(context)?.let { failure ->
            pending.directory.deleteRecursively()
            writeResult(context, failure)
            return
        }
        try {
            if (pending.settingsSelected) {
                val settingsFile = File(pending.directory, SETTINGS_FILE)
                val values = SettingsBackup.readTypedPreferences(settingsFile) +
                    (C.SETTINGS_VERSION to pending.sourceSettingsSchemaVersion)
                val preferences = context.rawPrefs()
                val restored = SettingsBackup.restoredPreferences(
                    imported = values,
                    existingLocal = preferences.all,
                    schemaVersion = pending.sourceSettingsSchemaVersion,
                )
                DatabaseRestoreRecovery.preservePreferencesForRollback(context)
                check(applyPreferences(preferences, restored, portableOnly = false)) {
                    "Unable to save restored settings"
                }
            }
            if (pending.settingsSelected) {
                check(File(pending.directory, SETTINGS_APPLIED_FILE).createNewFile() || File(pending.directory, SETTINGS_APPLIED_FILE).exists()) {
                    "Unable to mark restored settings as pending migration"
                }
            } else {
                complete(context, pending)
            }
        } catch (error: Exception) {
            throw error
        }
    }

    fun completeAfterSettingsMigration(context: Context) {
        val pending = requireNotNull(readPending(context)) { "The pending settings restore is missing" }
        check(pending.settingsSelected && pending.settingsApplied) { "Restored settings were not installed" }
        check(context.rawPrefs().getInt(C.SETTINGS_VERSION, -1) == C.SETTINGS_SCHEMA_VERSION) {
            "Restored settings did not migrate to the current version"
        }
        try {
            check(context.rawPrefs().edit().commit()) { "Unable to save migrated settings" }
            if (pending.proxySelected) {
                applyProxyConfiguration(context, JSONObject(File(pending.directory, PROXY_FILE).readText()))
            }
            complete(context, pending)
        } catch (error: Exception) {
            if (pending.proxySelected) {
                runCatching {
                    val previous = SettingsBackup.readTypedPreferences(
                        File(pending.directory, PROXY_BEFORE_FILE),
                        includeExcluded = true,
                    )
                    check(applyPreferences(context.proxyPrefsBacking(), previous, portableOnly = false)) {
                        "Unable to restore local proxy credentials"
                    }
                }.exceptionOrNull()?.let(error::addSuppressed)
            }
            throw error
        }
    }

    private fun complete(context: Context, pending: Pending) {
        // Persist the user-visible outcome while both preference and database rollback copies still exist.
        writeResult(
            context,
            Result(true, if (pending.ignoredLooseFiles) "archive_authoritative" else null),
        )
        // Cleanup is best-effort after the durable success result; it must not turn a committed restore into a reported failure.
        runCatching { DatabaseRestoreRecovery.complete(context) }
        runCatching { pending.directory.deleteRecursively() }
    }

    fun failAfterBootstrap(context: Context, message: String?) {
        File(context.noBackupFilesDir, PENDING_DIRECTORY).deleteRecursively()
        writeResult(context, Result(false, message))
    }

    fun takeResult(context: Context): Result? {
        val file = File(context.noBackupFilesDir, RESULT_FILE)
        if (!file.isFile) return null
        val result = runCatching {
            JSONObject(file.readText()).let { Result(it.getBoolean("succeeded"), it.optString("message").takeIf(String::isNotBlank)) }
        }.getOrNull()
        file.delete()
        return result
    }

    private fun takeFailure(context: Context): Result? {
        val file = File(context.noBackupFilesDir, RESULT_FILE)
        if (!file.isFile) return null
        val result = runCatching {
            JSONObject(file.readText()).let {
                if (it.getBoolean("succeeded")) null
                else Result(false, it.optString("message").takeIf(String::isNotBlank))
            }
        }.getOrNull()
        if (result != null) file.delete()
        return result
    }

    private fun readPending(context: Context): Pending? = runCatching {
        val directory = File(context.noBackupFilesDir, PENDING_DIRECTORY)
        val manifest = JSONObject(File(directory, MANIFEST_FILE).readText())
        require(manifest.getInt("version") == 1)
        val sourceSettingsSchemaVersion = manifest.optInt("sourceSettingsSchemaVersion", 0)
        require(sourceSettingsSchemaVersion in 0..C.SETTINGS_SCHEMA_VERSION)
        Pending(
            directory = directory,
            settingsSelected = manifest.getBoolean("settings"),
            databaseSelected = manifest.getBoolean("database"),
            proxySelected = manifest.getBoolean("proxy"),
            ignoredLooseFiles = manifest.optBoolean("ignoredLooseFiles", false),
            sourceSettingsSchemaVersion = sourceSettingsSchemaVersion,
            settingsApplied = File(directory, SETTINGS_APPLIED_FILE).isFile,
        )
    }.getOrNull()

    private fun applyPreferences(
        preferences: SharedPreferences,
        values: Map<String, *>,
        portableOnly: Boolean = true,
    ): Boolean {
        val preserved: Map<String, *> = if (portableOnly) SettingsBackup.localPreferences(preferences.all) else emptyMap<String, Any>()
        val editor = preferences.edit().clear()
        (if (portableOnly) preserved + SettingsBackup.portablePreferences(values) else values).forEach { (key, value) ->
            when (value) {
                is Boolean -> editor.putBoolean(key, value)
                is Int -> editor.putInt(key, value)
                is Long -> editor.putLong(key, value)
                is Float -> editor.putFloat(key, value)
                is String -> editor.putString(key, value)
                is Set<*> -> editor.putStringSet(key, value.filterIsInstance<String>().toMutableSet())
                else -> error("A preference has an unsupported value type")
            }
        }
        return editor.commit()
    }

    private fun applyProxyConfiguration(context: Context, json: JSONObject) {
        val preferences = context.prefs()
        val proxyPreferences = context.proxyPrefs()
        val oldEndpoint = listOf(
            proxyPreferences.getString(C.PROXY_HOST, null),
            proxyPreferences.getString(C.PROXY_PORT, null),
            proxyPreferences.getString(C.PROXY_USER, null),
        )
        val newEndpoint = listOf(json.optString("host").takeIf(String::isNotEmpty), json.optString("port").takeIf(String::isNotEmpty), json.optString("user").takeIf(String::isNotEmpty))
        if (json.has("enabled") || json.has("allowDirectFallback")) {
            preferences.edit().apply {
                if (json.has("enabled")) putBoolean(C.SETTINGS_HTTP_PROXY_ENABLED, json.getBoolean("enabled"))
                if (json.has("allowDirectFallback")) putBoolean(C.PROXY_ALLOW_DIRECT_FALLBACK, json.getBoolean("allowDirectFallback"))
            }.commit().also { check(it) { "Unable to save restored proxy settings" } }
        }
        proxyPreferences.edit().apply {
            remove(C.PROXY_HOST)
            remove(C.PROXY_PORT)
            remove(C.PROXY_USER)
            if (oldEndpoint != newEndpoint) remove(C.PROXY_PASSWORD)
            if (json.has("host")) putString(C.PROXY_HOST, json.getString("host"))
            if (json.has("port")) putString(C.PROXY_PORT, json.getString("port"))
            if (json.has("user")) putString(C.PROXY_USER, json.getString("user"))
        }.commit().also { check(it) { "Unable to save restored proxy settings" } }
    }

    private fun preferencesFile(context: Context): File = File(
        context.applicationInfo.dataDir,
        "shared_prefs/${context.packageName}_preferences.xml",
    )

    private fun writeResult(context: Context, result: Result) {
        File(context.noBackupFilesDir, RESULT_FILE).writeText(JSONObject().apply {
            put("succeeded", result.succeeded)
            result.message?.let { put("message", it.take(512)) }
        }.toString())
    }
}
