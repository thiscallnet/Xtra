package com.github.andreyasadchy.xtra.ui.settings

import org.json.JSONArray
import org.json.JSONObject
import com.github.andreyasadchy.xtra.util.C
import java.io.BufferedInputStream
import java.io.File
import java.io.FileInputStream
import java.io.InputStream
import java.io.OutputStream
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import javax.xml.parsers.DocumentBuilderFactory

internal object SettingsBackup {
    const val ARCHIVE_FILE_NAME = "xtra-settings-backup.zip"
    const val PREFERENCES_ENTRY = "preferences.xml"
    const val SETTINGS_ENTRY = "settings.json"
    const val DATABASE_ENTRY = "database.sqlite3"
    const val PROXY_ENTRY = "proxy.json"
    private const val MANIFEST_ENTRY = "manifest.json"
    private const val FORMAT = "xtra-settings-backup"
    private const val VERSION = 2
    private const val MAX_MANIFEST_BYTES = 64L * 1024L
    private const val MAX_PROXY_BYTES = 64L * 1024L
    const val MAX_SETTINGS_BYTES = 16L * 1024L * 1024L
    private const val MAX_ENTRY_BYTES = 1024L * 1024L * 1024L
    private const val MAX_ARCHIVE_BYTES = MAX_ENTRY_BYTES * 2
    private val excludedPreferenceKeys = setOf(
        "token",
        "token_client_id",
        "user_id",
        "username",
        "token_scopes",
        "settings_version",
        "proxy_host",
        "proxy_port",
        "proxy_user",
        "proxy_password",
    )

    enum class FileType { ARCHIVE, PREFERENCES, DATABASE, UNKNOWN }

    data class Contents(
        val preferences: File?,
        val settings: File?,
        val database: File?,
        val proxy: File? = null,
        val formatVersion: Int = 1,
        val settingsSchemaVersion: Int? = null,
        val databaseSchemaVersion: Int? = null,
        val ignoredLooseFiles: Boolean = false,
    )

    fun writeArchive(
        output: OutputStream,
        preferences: Map<String, *>,
        settingsSchemaVersion: Int,
        database: File,
        databaseSchemaVersion: Int,
        proxy: File? = null,
        appVersionCode: Int = 0,
    ) {
        val settings = encodeSettings(preferences, settingsSchemaVersion)
        val files = buildList {
            add(SETTINGS_ENTRY to settings)
            add(DATABASE_ENTRY to database)
            proxy?.let { add(PROXY_ENTRY to it) }
        }
        files.forEach { (name, file) ->
            val maxBytes = when (name) {
                PROXY_ENTRY -> MAX_PROXY_BYTES
                SETTINGS_ENTRY -> MAX_SETTINGS_BYTES
                else -> MAX_ENTRY_BYTES
            }
            require(file.isFile && file.length() <= maxBytes) { "Backup source is missing or too large" }
        }
        val manifest = JSONObject().apply {
            put("format", FORMAT)
            put("version", VERSION)
            put("settingsSchemaVersion", settingsSchemaVersion)
            put("databaseSchemaVersion", databaseSchemaVersion)
            put("appVersionCode", appVersionCode)
            put("files", JSONArray().apply {
                files.forEach { (name, file) ->
                    put(JSONObject().apply {
                        put("name", name)
                        put("bytes", file.length())
                        put("sha256", sha256(file))
                    })
                }
            })
        }.toString().toByteArray()

        try {
            ZipOutputStream(output.buffered()).use { zip ->
                zip.putNextEntry(ZipEntry(MANIFEST_ENTRY))
                zip.write(manifest)
                zip.closeEntry()
                files.forEach { (name, file) ->
                    zip.putNextEntry(ZipEntry(name))
                    file.inputStream().use { it.copyTo(zip) }
                    zip.closeEntry()
                }
            }
        } finally {
            settings.delete()
        }
    }

    /** Writes a v1 envelope for migration fixtures. New backups must use [writeArchive]. */
    fun writeLegacyArchive(output: OutputStream, preferences: File, database: File, proxy: File? = null) {
        val files = buildList {
            add(PREFERENCES_ENTRY to preferences)
            add(DATABASE_ENTRY to database)
            proxy?.let { add(PROXY_ENTRY to it) }
        }
        files.forEach { (name, file) ->
            val maxBytes = if (name == PROXY_ENTRY) MAX_PROXY_BYTES else MAX_ENTRY_BYTES
            require(file.isFile && file.length() <= maxBytes) { "Backup source is missing or too large" }
        }
        val manifest = JSONObject().apply {
            put("format", FORMAT)
            put("version", 1)
            put("files", JSONArray().apply {
                files.forEach { (name, file) ->
                    put(JSONObject().apply {
                        put("name", name)
                        put("bytes", file.length())
                        put("sha256", sha256(file))
                    })
                }
            })
        }.toString().toByteArray()
        ZipOutputStream(output.buffered()).use { zip ->
            zip.putNextEntry(ZipEntry(MANIFEST_ENTRY))
            zip.write(manifest)
            zip.closeEntry()
            files.forEach { (name, file) ->
                zip.putNextEntry(ZipEntry(name))
                file.inputStream().use { it.copyTo(zip) }
                zip.closeEntry()
            }
        }
    }

    fun extractArchive(input: InputStream, stagingDirectory: File): Contents {
        val extracted = mutableMapOf<String, File>()
        var extractedBytes = 0L
        val stagingRoot = stagingDirectory.canonicalFile
        ZipInputStream(BufferedInputStream(input)).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                if (entry.isDirectory) {
                    zip.closeEntry()
                    continue
                }
                val output = when (entry.name) {
                    MANIFEST_ENTRY -> File(stagingRoot, MANIFEST_ENTRY)
                    PREFERENCES_ENTRY -> File(stagingRoot, PREFERENCES_ENTRY)
                    SETTINGS_ENTRY -> File(stagingRoot, SETTINGS_ENTRY)
                    DATABASE_ENTRY -> File(stagingRoot, DATABASE_ENTRY)
                    PROXY_ENTRY -> File(stagingRoot, PROXY_ENTRY)
                    else -> throw IllegalArgumentException("Unexpected backup entry ${entry.name}")
                }.canonicalFile
                val entryName = output.name
                check(extracted[entryName] == null) { "Duplicate backup entry $entryName" }
                require(output.parentFile == stagingRoot) { "Backup entry escapes its staging directory" }
                output.outputStream().use {
                    val entryLimit = when (entryName) {
                        MANIFEST_ENTRY -> MAX_MANIFEST_BYTES
                        PROXY_ENTRY -> MAX_PROXY_BYTES
                        PREFERENCES_ENTRY, SETTINGS_ENTRY -> MAX_SETTINGS_BYTES
                        else -> MAX_ENTRY_BYTES
                    }
                    copyLimited(zip, it, (MAX_ARCHIVE_BYTES - extractedBytes).coerceAtMost(entryLimit))
                }
                extractedBytes += output.length()
                require(extractedBytes <= MAX_ARCHIVE_BYTES) { "Backup archive is too large" }
                extracted[entryName] = output
                zip.closeEntry()
            }
        }

        val manifestFile = requireNotNull(extracted[MANIFEST_ENTRY]) { "Backup manifest is missing" }
        val manifest = JSONObject(manifestFile.readText())
        require(manifest.optString("format") == FORMAT) { "Unsupported backup format" }
        val version = manifest.optInt("version")
        require(version in 1..VERSION) { "Unsupported backup version" }
        val expectedFiles = manifest.getJSONArray("files")
        require(expectedFiles.length() in 2..3) { "Backup manifest has an unsupported file count" }
        val settingsEntry = if (version == 1) PREFERENCES_ENTRY else SETTINGS_ENTRY
        val expectedNames = buildSet {
            for (index in 0 until expectedFiles.length()) add(expectedFiles.getJSONObject(index).getString("name"))
        }
        require(
            expectedNames == setOf(settingsEntry, DATABASE_ENTRY) ||
                expectedNames == setOf(settingsEntry, DATABASE_ENTRY, PROXY_ENTRY),
        ) { "Backup manifest is incomplete" }
        require(extracted.keys - MANIFEST_ENTRY == expectedNames) { "Backup entries do not match the manifest" }
        for (index in 0 until expectedFiles.length()) {
            val expected = expectedFiles.getJSONObject(index)
            val name = expected.getString("name")
            require(name == PREFERENCES_ENTRY || name == SETTINGS_ENTRY || name == DATABASE_ENTRY || name == PROXY_ENTRY) {
                "Unexpected backup entry $name"
            }
            val file = requireNotNull(extracted[name]) { "Backup entry $name is missing" }
            require(file.length() == expected.getLong("bytes")) { "Backup entry $name has the wrong size" }
            require(sha256(file).equals(expected.getString("sha256"), ignoreCase = true)) {
                "Backup entry $name failed its checksum"
            }
        }
        return if (version == 1) {
            Contents(
                preferences = extracted[PREFERENCES_ENTRY],
                settings = null,
                database = extracted[DATABASE_ENTRY],
                proxy = extracted[PROXY_ENTRY],
                formatVersion = version,
            )
        } else {
            val schemaVersion = manifest.getInt("settingsSchemaVersion")
            val databaseSchemaVersion = manifest.getInt("databaseSchemaVersion")
            require(schemaVersion >= 0) { "Backup settings version is invalid" }
            require(databaseSchemaVersion > 0) { "Backup database version is invalid" }
            val settingsFile = requireNotNull(extracted[SETTINGS_ENTRY]) { "Backup settings are missing" }
            validateTypedPreferences(settingsFile, schemaVersion)
            Contents(
                preferences = null,
                settings = settingsFile,
                database = extracted[DATABASE_ENTRY],
                proxy = extracted[PROXY_ENTRY],
                formatVersion = version,
                settingsSchemaVersion = manifest.getInt("settingsSchemaVersion"),
                databaseSchemaVersion = databaseSchemaVersion,
            )
        }
    }

    fun detectType(file: File): FileType {
        val prefix = ByteArray(32)
        val count = FileInputStream(file).use { it.read(prefix) }
        if (count >= 4 && prefix[0] == 'P'.code.toByte() && prefix[1] == 'K'.code.toByte()) {
            return FileType.ARCHIVE
        }
        val text = prefix.copyOf(maxOf(0, count)).decodeToString().trimStart()
        return when {
            text.startsWith("<?xml") || text.startsWith("<map") -> FileType.PREFERENCES
            text.startsWith("SQLite format 3\u0000") -> FileType.DATABASE
            else -> FileType.UNKNOWN
        }
    }

    fun validatePreferences(file: File) {
        val factory = DocumentBuilderFactory.newInstance().apply {
            isExpandEntityReferences = false
            setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
            setFeature("http://xml.org/sax/features/external-general-entities", false)
            setFeature("http://xml.org/sax/features/external-parameter-entities", false)
        }
        val document = file.inputStream().use { factory.newDocumentBuilder().parse(it) }
        require(document.documentElement?.tagName == "map") {
            "The preferences backup is not an Android preferences file"
        }
    }

    fun readLegacyPreferences(file: File): Map<String, Any> {
        validatePreferences(file)
        val factory = DocumentBuilderFactory.newInstance().apply {
            isExpandEntityReferences = false
            setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
            setFeature("http://xml.org/sax/features/external-general-entities", false)
            setFeature("http://xml.org/sax/features/external-parameter-entities", false)
        }
        val document = file.inputStream().use { factory.newDocumentBuilder().parse(it) }
        val values = linkedMapOf<String, Any>()
        val children = document.documentElement.childNodes
        for (index in 0 until children.length) {
            val element = children.item(index) as? org.w3c.dom.Element ?: continue
            val name = element.getAttribute("name")
            require(name.isNotBlank() && name !in values) { "Preferences backup has an invalid or duplicate key" }
            val value: Any = when (element.tagName) {
                "boolean" -> element.getAttribute("value").toBooleanStrict()
                "int" -> element.getAttribute("value").toInt()
                "long" -> element.getAttribute("value").toLong()
                "float" -> element.getAttribute("value").toFloat()
                "string" -> element.textContent ?: ""
                "set" -> buildSet {
                    val items = element.childNodes
                    for (childIndex in 0 until items.length) {
                        val item = items.item(childIndex) as? org.w3c.dom.Element ?: continue
                        require(item.tagName == "string") { "Preferences backup has an invalid string set" }
                        add(item.textContent ?: "")
                    }
                }
                else -> throw IllegalArgumentException("Preferences backup has an unsupported value type")
            }
            values[name] = value
        }
        return values
    }

    fun readTypedPreferences(file: File, includeExcluded: Boolean = false): Map<String, Any> {
        val json = JSONObject(file.readText())
        require(json.getInt("schemaVersion") >= 0) { "Backup settings version is invalid" }
        val values = json.getJSONObject("values")
        val result = linkedMapOf<String, Any>()
        values.keys().forEach { key ->
            if (!includeExcluded && key in excludedPreferenceKeys) return@forEach
            val item = values.getJSONObject(key)
            val value: Any = when (item.getString("type")) {
                "boolean" -> item.getBoolean("value")
                "int" -> item.getInt("value")
                "long" -> item.getLong("value")
                "float" -> item.getDouble("value").toFloat().also { require(it.isFinite()) }
                "string" -> item.getString("value")
                "string_set" -> item.getJSONArray("value").let { array ->
                    buildSet {
                        for (index in 0 until array.length()) add(array.getString(index))
                    }
                }
                else -> throw IllegalArgumentException("Backup has an unsupported preference value type")
            }
            result[key] = value
        }
        return result
    }

    fun validateTypedPreferences(file: File, expectedSchemaVersion: Int) {
        val json = JSONObject(file.readText())
        require(json.getInt("schemaVersion") == expectedSchemaVersion) { "Backup settings version does not match its manifest" }
        require(!json.getJSONObject("values").has("settings_version")) {
            "Backup settings schema is duplicated inside the settings payload"
        }
        readTypedPreferences(file)
    }

    fun writeTypedPreferences(file: File, values: Map<String, *>, schemaVersion: Int, includeExcluded: Boolean = false) {
        file.writeText(typedPreferencesJson(values, schemaVersion, includeExcluded).toString())
    }

    fun portablePreferences(values: Map<String, *>): Map<String, *> = values.filterKeys { it !in excludedPreferenceKeys }

    fun localPreferences(values: Map<String, *>): Map<String, *> =
        values.filterKeys { it in excludedPreferenceKeys && it != "settings_version" }

    fun restoredPreferences(
        imported: Map<String, *>,
        existingLocal: Map<String, *>,
        schemaVersion: Int,
    ): Map<String, *> = localPreferences(existingLocal) + portablePreferences(imported) +
        (C.SETTINGS_VERSION to schemaVersion)

    fun legacySettingsSchemaVersion(values: Map<String, *>): Int = when (val marker = values["settings_version"]) {
        null -> 0
        is Int -> marker.also { require(it >= 0) { "Legacy backup settings version is invalid" } }
        else -> throw IllegalArgumentException("Legacy backup settings version is invalid")
    }

    private fun encodeSettings(values: Map<String, *>, schemaVersion: Int): File {
        val file = File.createTempFile("xtra-settings-", ".json")
        file.writeText(typedPreferencesJson(values, schemaVersion).toString())
        return file
    }

    private fun typedPreferencesJson(values: Map<String, *>, schemaVersion: Int, includeExcluded: Boolean = false): JSONObject = JSONObject().apply {
            put("schemaVersion", schemaVersion)
            put("values", JSONObject().apply {
                (if (includeExcluded) values else portablePreferences(values)).toSortedMap().forEach { (key, value) ->
                    val encodedValue: Pair<String, Any> = when (value) {
                        is Boolean -> "boolean" to value
                        is Int -> "int" to value
                        is Long -> "long" to value
                        is Float -> {
                            require(value.isFinite()) { "A preference value is not finite" }
                            "float" to value.toDouble()
                        }
                        is String -> "string" to value
                        is Set<*> -> {
                            val strings = value.map {
                                it as? String ?: throw IllegalArgumentException("A preference string set contains a non-string value")
                            }.sorted()
                            "string_set" to JSONArray(strings)
                        }
                        else -> throw IllegalArgumentException("A preference has an unsupported value type")
                    }
                    put(key, JSONObject().apply {
                        put("type", encodedValue.first)
                        put("value", encodedValue.second)
                    })
                }
            })
        }

    fun validateProxyConfiguration(file: File) {
        val json = JSONObject(file.readText())
        json.keys().forEach { key ->
            require(key in setOf("enabled", "allowDirectFallback", "host", "port", "user")) {
                "Unexpected proxy configuration key $key"
            }
        }
        json.optString("host", "").takeIf { it.isNotBlank() }?.let {
            require(it.length <= 2048) { "Proxy host is too long" }
        }
        json.optString("port", "").takeIf { it.isNotBlank() }?.let {
            require(it.toIntOrNull() in 1..65535) { "Proxy port is invalid" }
        }
        json.optString("user", "").let { require(it.length <= 2048) { "Proxy username is too long" } }
        if (json.has("enabled")) require(json.get("enabled") is Boolean) { "Proxy enabled flag is invalid" }
        if (json.has("allowDirectFallback")) require(json.get("allowDirectFallback") is Boolean) {
            "Proxy fallback flag is invalid"
        }
    }

    fun copyLimited(input: InputStream, output: OutputStream, maxBytes: Long) {
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        var total = 0L
        while (true) {
            val count = input.read(buffer)
            if (count < 0) return
            total += count
            require(total <= maxBytes) { "Backup input is too large" }
            output.write(buffer, 0, count)
        }
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
}
