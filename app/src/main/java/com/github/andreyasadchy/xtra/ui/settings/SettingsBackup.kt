package com.github.andreyasadchy.xtra.ui.settings

import org.json.JSONArray
import org.json.JSONObject
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
    const val DATABASE_ENTRY = "database.sqlite3"
    const val PROXY_ENTRY = "proxy.json"
    private const val MANIFEST_ENTRY = "manifest.json"
    private const val FORMAT = "xtra-settings-backup"
    private const val VERSION = 1
    private const val MAX_MANIFEST_BYTES = 64L * 1024L
    private const val MAX_PROXY_BYTES = 64L * 1024L
    private const val MAX_ENTRY_BYTES = 1024L * 1024L * 1024L
    private const val MAX_ARCHIVE_BYTES = MAX_ENTRY_BYTES * 2

    enum class FileType { ARCHIVE, PREFERENCES, DATABASE, UNKNOWN }

    data class Contents(val preferences: File?, val database: File?, val proxy: File? = null)

    fun writeArchive(output: OutputStream, preferences: File, database: File, proxy: File? = null) {
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
            put("version", VERSION)
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
        require(manifest.optInt("version") == VERSION) { "Unsupported backup version" }
        val expectedFiles = manifest.getJSONArray("files")
        require(expectedFiles.length() in 2..3) { "Backup manifest has an unsupported file count" }
        val expectedNames = buildSet {
            for (index in 0 until expectedFiles.length()) add(expectedFiles.getJSONObject(index).getString("name"))
        }
        require(
            expectedNames == setOf(PREFERENCES_ENTRY, DATABASE_ENTRY) ||
                expectedNames == setOf(PREFERENCES_ENTRY, DATABASE_ENTRY, PROXY_ENTRY),
        ) { "Backup manifest is incomplete" }
        require(extracted.keys - MANIFEST_ENTRY == expectedNames) { "Backup entries do not match the manifest" }
        for (index in 0 until expectedFiles.length()) {
            val expected = expectedFiles.getJSONObject(index)
            val name = expected.getString("name")
            require(name == PREFERENCES_ENTRY || name == DATABASE_ENTRY || name == PROXY_ENTRY) {
                "Unexpected backup entry $name"
            }
            val file = requireNotNull(extracted[name]) { "Backup entry $name is missing" }
            require(file.length() == expected.getLong("bytes")) { "Backup entry $name has the wrong size" }
            require(sha256(file).equals(expected.getString("sha256"), ignoreCase = true)) {
                "Backup entry $name failed its checksum"
            }
        }
        return Contents(extracted[PREFERENCES_ENTRY], extracted[DATABASE_ENTRY], extracted[PROXY_ENTRY])
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
